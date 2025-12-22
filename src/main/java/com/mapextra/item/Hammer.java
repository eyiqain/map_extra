package com.mapextra.item;

import com.mapextra.init.ModSounds;
import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketSyncBorder;
import com.mapextra.world.BorderData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

public class Hammer extends Item {
    public Hammer(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    // ============================================
    // 【修改 1】 动画效果优化
    // ============================================

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        // 改为 SPEAR (三叉戟姿势) 或者 NONE。
        // SPEAR 会让物品在手里稍微倒过来一点，更有工具感。
        // 主要是为了去掉 BOW 的放大拉近效果。
        return UseAnim.NONE;
    }
   // 2. 核心渲染逻辑：手动控制旋转
   @Override
   public void initializeClient(Consumer<IClientItemExtensions> consumer) {
       consumer.accept(new IClientItemExtensions() {
           @Override
           public boolean applyForgeHandTransform(PoseStack poseStack, LocalPlayer player, HumanoidArm arm, ItemStack itemInHand, float partialTick, float equipProcess, float swingProcess) {

               if (player.isUsingItem() && player.getUseItem() == itemInHand) {

                   // ==========================================
                   // 1. 获取“在这个周期内的时间”
                   // ==========================================

                   // 我们不使用 player.tickCount (全局时间)，因为那样你刚按右键时，动画可能正好在中间。
                   // 我们使用 "已经使用了多久" (local time)，这样每次按下右键，动画都从头开始。
                   float useDuration = itemInHand.getUseDuration();
                   float remaining = player.getUseItemRemainingTicks();

                   // currentUseTime = 从按下右键到现在经过的 ticks + 小数部分(平滑)
                   float currentUseTime = (useDuration - remaining) + partialTick;

                   // ==========================================
                   // 2. 定义动画参数 (在这里调整手感！)
                   // ==========================================

                   float period = 4.0f;      // 周期：20 tick (1秒) 转一次
                   float startAngle = -45.0f; // 起点角度：向后扬起 45度
                   float endAngle = 45.0f;    // 终点角度：向前砸下 45度

                   // ==========================================
                   // 3. 计算当前进度 (0.0 -> 1.0 -> 瞬移回 0.0)
                   // ==========================================

                   // 取余数运算：让时间限制在 0 ~ 4 之间循环
                   float timeInCycle = currentUseTime % period;

                   // 归一化：变成 0.0 ~ 1.0 的百分比
                   float progress = timeInCycle / period;
                   progress = 1-progress * progress;

                   // ==========================================
                   // 【新增】绕物品中心翻转 180 度 (剑头变剑柄)
                   // ==========================================

                   // ==========================================
                   // 4. 计算当前角度并应用
                   // ==========================================

                   // 线性插值：根据进度计算当前角度
                   // progress = 0 时，角度 = startAngle
                   // progress = 1 时，角度 = endAngle
                   float currentAngle = Mth.lerp(progress, startAngle, endAngle);

                   // 绕 X 轴旋转 (XP = X Positive)
                   // 效果：物体会在手里前后摆动 (点头动作)
                   poseStack.mulPose(Axis.XP.rotationDegrees(currentAngle));

                   // 如果你觉得它绕着手柄末端转很奇怪，想让它绕着锤头转，可以在旋转前后加位移：
                   // poseStack.translate(0, 0.5, 0); // 移到旋转中心
                   // poseStack.mulPose(Axis.XP.rotationDegrees(currentAngle));
                   // poseStack.translate(0, -0.5, 0); // 移回去
               }

               return false;
           }
       });
   }
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ServerPlayer player = (ServerPlayer) context.getPlayer();

        InteractionResult result = tryPlaceAnywhere((ServerLevel) level, player);
        player.startUsingItem(context.getHand());

        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(itemstack);

        tryPlaceAnywhere((ServerLevel) level, (ServerPlayer) player);
        player.startUsingItem(hand);

        return InteractionResultHolder.consume(itemstack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int count) {

        // =========================================================
        // 【新增】给予速度药水效果
        // =========================================================
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            //希望有个小加速
            int duration = getUseDuration(stack) - count;
            if (duration > 7) {
                // 放置逻辑频率 (这里设为每 2 tick 放置一次，即每秒 10 次)
                if (duration % 2 == 0) {
                    tryPlaceAnywhere((ServerLevel) level, player);

                    // 2. 【新增】仅当成功放置墙壁时，才给予加速效果

                }
            }
        }

    }


    private InteractionResult tryPlaceAnywhere(ServerLevel level, ServerPlayer player) {
        BorderData data = BorderData.get(level);
        String targetName = data.getPlayerFocus(player.getUUID());
        if (targetName == null) targetName = data.getActiveBorderName();

        if (targetName == null || data.getEntry(targetName) == null) {
            player.sendSystemMessage(Component.literal("未选择边界或未创建！").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        InteractionResult wallResult = tryPlaceOnWall(level, player);
        if (wallResult == InteractionResult.SUCCESS || wallResult == InteractionResult.CONSUME) {
            return wallResult;
        }

        InteractionResult blockResult = tryPlaceOnPhysicalBlock(level, player);
        if (blockResult == InteractionResult.SUCCESS || blockResult == InteractionResult.CONSUME) {
            return blockResult;
        }

        return InteractionResult.PASS;
    }

    // ============================================
    // 【修改 3】 射线检测加入物理方块阻挡逻辑
    // ============================================

    private InteractionResult tryPlaceOnWall(ServerLevel level, ServerPlayer player) {
        BorderData data = BorderData.get(level);
        String targetName = data.getPlayerFocus(player.getUUID());
        if (targetName == null) targetName = data.getActiveBorderName();
        if (targetName == null) return InteractionResult.PASS;

        BorderData.BorderEntry entry = data.getEntry(targetName);
        if (entry == null) return InteractionResult.PASS;

        double reachDistance = 10.0;
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getLookAngle();

        BlockPos hitPos = null;
        Direction hitFace = null;
        double step = 0.1;
        int steps = (int) (reachDistance / step);

        for (int i = 0; i < steps; i++) {
            Vec3 curr = eyePos.add(lookVec.scale(i * step));

            // 1. 【新增】检查物理世界是否有方块阻挡
            // 如果射线碰到了实体方块，说明视线被挡住了，不能再打到后面的空气墙了
            BlockPos currentWorldPos = BlockPos.containing(curr);
            BlockState state = level.getBlockState(currentWorldPos);

            // 如果方块有碰撞体积（不是空气、水、草等），则中断射线
            if (!state.getCollisionShape(level, currentWorldPos).isEmpty()) {
                // 这里 break 很关键！意味着射线撞墙了，不再继续往后找空气墙
                // 返回 PASS，这样 tryPlaceAnywhere 就会接着调用 tryPlaceOnPhysicalBlock
                // 从而让你能在这个物理方块表面放墙
                return InteractionResult.PASS;
            }

            // 2. 检查虚拟空气墙
            int localX = (int) Math.floor(curr.x - entry.startX);
            int localZ = (int) Math.floor(curr.z - entry.startZ);

            if (localX >= 0 && localX < entry.width && localZ >= 0 && localZ < entry.depth) {
                if (entry.isWall(localX, localZ)) {
                    // 击中虚拟墙
                    Vec3 prev = eyePos.add(lookVec.scale((i - 1) * step));
                    int prevX = (int) Math.floor(prev.x - entry.startX);
                    int prevZ = (int) Math.floor(prev.z - entry.startZ);

                    if (prevX < localX) hitFace = Direction.WEST;
                    else if (prevX > localX) hitFace = Direction.EAST;
                    else if (prevZ < localZ) hitFace = Direction.NORTH;
                    else if (prevZ > localZ) hitFace = Direction.SOUTH;
                    else {
                        if (lookVec.y < 0) hitFace = Direction.UP;
                        else hitFace = Direction.DOWN;
                    }
                    hitPos = new BlockPos(localX, 0, localZ);
                    break; // 找到目标，跳出循环
                }
            }
        }

        if (hitPos == null || hitFace == null) return InteractionResult.PASS;

        int newX = hitPos.getX() + hitFace.getStepX();
        int newZ = hitPos.getZ() + hitFace.getStepZ();

        return placeWallLogic(level, player, data, entry, targetName, newX, newZ);
    }

    private InteractionResult tryPlaceOnPhysicalBlock(ServerLevel level, ServerPlayer player) {
        BlockHitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hitResult.getBlockPos();
            BorderData data = BorderData.get(level);
            String targetName = data.getPlayerFocus(player.getUUID());
            if (targetName == null) targetName = data.getActiveBorderName();
            if (targetName == null) return InteractionResult.PASS;

            BorderData.BorderEntry entry = data.getEntry(targetName);
            if (entry == null) return InteractionResult.PASS;

            int localX = (int) (pos.getX() - entry.startX);
            int localZ = (int) (pos.getZ() - entry.startZ);

            return placeWallLogic(level, player, data, entry, targetName, localX, localZ);
        }
        return InteractionResult.PASS;
    }

    private InteractionResult placeWallLogic(ServerLevel level, ServerPlayer player, BorderData data, BorderData.BorderEntry entry, String targetName, int localX, int localZ) {
        if (localX >= 0 && localX < entry.width && localZ >= 0 && localZ < entry.depth) {
            if (!entry.isWall(localX, localZ)) {
                entry.setWall(localX, localZ, true);
                data.setDirty();

                ModMessage.sendToPlayer(new PacketSyncBorder(targetName, entry, true), player);
                if (targetName.equals(data.getActiveBorderName())) {
                    ModMessage.sendToAll(new PacketSyncBorder(targetName, entry, false));
                }

                playRandomPitchSound(level, player, player.blockPosition(), ModSounds.WRENCH_ADD.get());
                player.sendSystemMessage(Component.literal("已添加 [" + localX + ", " + localZ + "]").withStyle(ChatFormatting.GREEN), true);
                return InteractionResult.SUCCESS;
            } else {
                player.sendSystemMessage(Component.literal("这里已经是墙了").withStyle(ChatFormatting.YELLOW), true);
                return InteractionResult.CONSUME;
            }
        } else {
            player.sendSystemMessage(Component.literal("不在范围内 [" + localX + ", " + localZ + "]").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
    }

    public void playRandomPitchSound(Level level, Player player, BlockPos pos, SoundEvent sound) {
        float randomCents = (level.random.nextFloat() * 500f) - 250f;
        float pitch = (float) Math.pow(2, randomCents / 1200.0);
        Player target = level.isClientSide ? player : null;
        level.playSound(target, pos.getX(), pos.getY(), pos.getZ(), sound, SoundSource.PLAYERS, 1.0f, pitch);
    }
}
