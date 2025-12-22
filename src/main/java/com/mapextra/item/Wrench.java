package com.mapextra.item;

import com.mapextra.client.ClientPosCache;
import com.mapextra.init.ModSounds;
import com.mapextra.world.PosSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;



// 1. 继承 Item 类
public class Wrench extends Item {

    // 2. 构造函数：必须匹配父类格式，把属性传给父类
    public Wrench(Properties properties) {
        super(properties);
    }
    public BlockPos getPlaceBlockPos(Player player, Level level) {
        // 步骤1：获取玩家视角的射线追踪结果（原版放置方块的射线逻辑）
        BlockHitResult hitResult = getPlayerPOVHitResult(level, player);

        // 步骤2：判断是否命中有效方块
        if (hitResult.getType() != BlockHitResult.Type.BLOCK) {
            // 未命中方块：返回玩家视线前方2格的坐标（兜底逻辑）
            return null;
        }

        // 步骤3：获取命中的方块位置 + 命中的方块面（方向）
        BlockPos hitBlockPos = hitResult.getBlockPos(); // 准星命中的方块坐标
        Direction hitFace = hitResult.getDirection();  // 命中的方块面（如北、南、上、下）

        // 步骤4：计算放置坐标（命中位置 + 方向偏移 = 原版放置位置）
        BlockPos placePos = hitBlockPos.relative(hitFace);

        // 1. 获取该维度的最小高度（通常 -64）
        int minY = level.getMinBuildHeight();
        // 2. 获取该维度的最大逻辑高度（通常 320）
        int maxY = level.getMaxBuildHeight();
        // 判断是否严格在区间 [minY, maxY - 1] 内
        if (placePos.getY() < minY || placePos.getY() >= maxY) {
            return hitBlockPos; // 越界兜底
        }
        return placePos;
    }
    /**
     * 复用原版玩家视角射线追踪逻辑（Item类中的原版方法，直接复制即可）
     * 作用：发射从玩家眼睛出发的射线，找到第一个碰撞的方块
     */
    private BlockHitResult getPlayerPOVHitResult(Level level, Player player) {
        // 玩家视角参数：俯仰角、偏航角、眼睛位置
        float xRot = player.getXRot();
        float yRot = player.getYRot();
        Vec3 eyePos = player.getEyePosition(1.0F); // 玩家眼睛位置（1.0F是tick插值）

        // 计算射线方向向量（基于玩家视角）
        float cosY = (float) Math.cos(-yRot * ((float) Math.PI / 180F) - (float) Math.PI);
        float sinY = (float) Math.sin(-yRot * ((float) Math.PI / 180F) - (float) Math.PI);
        float cosX = (float) -Math.cos(-xRot * ((float) Math.PI / 180F));
        float sinX = (float) Math.sin(-xRot * ((float) Math.PI / 180F));
        Vec3 lookVec = new Vec3((double)(sinY * cosX), (double)sinX, (double)(cosY * cosX));

        // 射线长度
        double reachDistance = 8;
        Vec3 rayEnd = eyePos.add(lookVec.x * reachDistance, lookVec.y * reachDistance, lookVec.z * reachDistance);

        // 发射射线：检测方块，忽略流体，使用原版碰撞规则
        return level.clip(new ClipContext(
                eyePos, rayEnd,
                ClipContext.Block.OUTLINE, // 检测方块轮廓（原版放置逻辑）
                ClipContext.Fluid.NONE,    // 忽略流体
                player                     // 射线发起者（用于碰撞忽略）
        ));
    }
    // ... 在 Wrench 类中 ...

    /**
     * 获取拆除目标坐标
     * 逻辑：检测视线路径上最近的【实体方块】或【已保存的虚拟点】
     */
    public BlockPos getRemoveBlockPos(Player player, Level level) {
        // 1. 基础参数准备
        double reachDistance = 8.0; // 与放置逻辑保持一致
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 viewVec = player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(viewVec.scale(reachDistance));

        // 2. 原版方块射线检测 (获取最近的实体方块命中点)
        BlockHitResult blockHit = level.clip(new ClipContext(
                eyePos, endPos,
                ClipContext.Block.OUTLINE, // 检测方块轮廓
                ClipContext.Fluid.NONE,
                player
        ));

        // 记录方块命中的距离（如果没有命中方块，距离设为无限远）
        double blockDistanceSq = Double.MAX_VALUE;
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            blockDistanceSq = blockHit.getLocation().distanceToSqr(eyePos);
        }

        // 3. 虚拟点射线检测 (遍历已保存的点，看有没有挡在方块前面的)
        BlockPos targetPos = null;
        double minDistanceSq = blockDistanceSq; // 初始最小距离设为方块命中的距离

        // 如果是服务端，直接读 SavedData；如果是客户端，应该读你的 CLIENT_POSITIONS_CACHE
        List<BlockPos> candidates;
        if (!level.isClientSide) {
            // 1. 获取数据实例
            PosSavedData data = PosSavedData.get(level);

            // 2. 获取玩家当前 Focus 的标签名 (比如 "default" 或 "arena")
            // 注意：你需要确保这里能拿到 player 对象
            String currentTag = data.getFocus(player.getUUID());

            // 3. 获取该标签下的坐标列表
            candidates = data.getPositions(currentTag);
        } else {
            candidates = new ArrayList<>(ClientPosCache.currentPositions);
        }

        // 优化：只遍历玩家附近的点 (简单的 AABB 筛选)
        // 这里为了简单直接遍历所有，如果点特别多需要做区块筛选
        for (BlockPos pos : candidates) {
            // 构造一个虚拟的 1x1x1 碰撞箱
            AABB aabb = new AABB(pos);

            // 检测射线是否穿过这个 AABB
            // clip 返回的是射线进入 AABB 的那个点 (Optional)
            Optional<Vec3> hit = aabb.clip(eyePos, endPos);

            if (hit.isPresent()) {
                double distSq = hit.get().distanceToSqr(eyePos);
                // 找最近的那个
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    targetPos = pos;
                }
            }
        }

        // 4. 决策返回
        if (targetPos != null) {
            // 命中虚拟点更近 -> 返回虚拟点坐标
            return targetPos;
        } else if (blockHit.getType() == HitResult.Type.BLOCK) {
            // 没有命中更近的虚拟点，但命中了实体方块 -> 返回实体方块坐标
            return blockHit.getBlockPos();
        }

        // 啥都没打中
        return null;
    }

    /**
     * 禁用物品攻击/破坏方块的能力（左键点击方块无任何反应）
     * @return false = 不允许攻击/破坏任何方块
     */
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false; // 关键：返回false，完全禁用挖掘触发
    }


    // =========================================================
    // 这里就是你要找的“重写方法”的区域
    // 你可以通过重写这些方法来改变物品的行为
    // =========================================================

    // 示例：重写右键使用物品的逻辑
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {

        if (!level.isClientSide) {
            // 服务端逻辑：给玩家发一条消息
            BlockPos pos = getPlaceBlockPos(player, level);
            if(pos != null) {
                PosSavedData data = PosSavedData.get(level);
                data.addPos(player,pos, (ServerLevel) level);
            }
            playRandomPitchSound(level, player ,pos, ModSounds.WRENCH_ADD.get());
            // 增加冷却时间
            //player.getCooldowns().addCooldown(this, 5);
        }
        // 返回成功，并把物品交还给玩家（如果返回 consume 会消耗物品）
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    // 示例：添加物品提示信息 (Tooltip) - 当鼠标悬停在物品上时显示
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag isAdvanced) {
        tooltipComponents.add(Component.translatable("左键删除右键添加").withStyle(style -> style.withColor(0xFFAA00)));
        tooltipComponents.add(Component.translatable("ALT+滚轮切换标签").withStyle(style -> style.withColor(0x008080)));
        super.appendHoverText(stack, level, tooltipComponents, isAdvanced);
    }

    /**
     * 播放带有随机音调的声音 (±250 音分)
     * @param level 世界
     * @param pos 播放源坐标
     * @param sound 声音事件
     */
    // 修改方法签名，增加 Player player 参数
    public void playRandomPitchSound(Level level, Player player, BlockPos pos, SoundEvent sound) {
        float randomCents = (level.random.nextFloat() * 500f) - 250f;
        float pitch = (float) Math.pow(2, randomCents / 1200.0);

        // 【关键修复】
        // 如果是客户端：必须传入 player，否则自己听不到。
        // 如果是服务端：传入 null 表示广播给所有人；如果传入 player 表示广播给"除了这个人以外的人" (防止双重声音)。
        // 这里为了简单，我们统一：客户端传 player，服务端传 null。
        Player target = level.isClientSide ? player : null;

        level.playSound(target, pos.getX(), pos.getY(), pos.getZ(),
                sound, SoundSource.PLAYERS, 1.0f, pitch);
    }
}

