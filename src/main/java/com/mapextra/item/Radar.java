package com.mapextra.item;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents; // ✅ 新增：导入原版声音事件
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;

import java.util.List;

public class Radar extends Item {
    // 默认50米
    public static int SEARCH_RANGE = 50;
    // 默认冷却3秒
    public static int COOLDOWN_TICKS = 60;

    public Radar(Properties properties){
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand UsedHand){
        if (!level.isClientSide){
            AABB searchArea = player.getBoundingBox().inflate((double)SEARCH_RANGE);
            List<Player> players = level.getEntitiesOfClass(Player.class, searchArea, p -> p != player && !p.isSpectator());

            Player nearestTarget = null;
            double minDistance = Double.MAX_VALUE;

            for (Player target : players){
                double distance = player.distanceToSqr(target);
                if(distance < minDistance){
                    minDistance = distance;
                    nearestTarget = target;
                }
            }

            if (nearestTarget != null){
                double actualDistance = Math.sqrt(minDistance);

                // ✅ 修改 1：成功音效 -> 经验球声
                // volume: 0.3F (更小声), pitch: 1.0F (正常音调，你可以改成 2.0F 会更尖锐像电子雷达)
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.3F, 1.0F);

                player.displayClientMessage(Component.literal("§e🔍发现目标: §f" + nearestTarget.getName().getString() +
                        " §7(距离: " + String.format("%.1f", actualDistance) + "m)"), true);

                nearestTarget.displayClientMessage(
                        Component.literal("👁你已被抓捕者发现！").withStyle(style -> style.withColor(0xFF0000).withBold(true)),
                        true
                );

                nearestTarget.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
                player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

            } else {
                // ✅ 修改 2：失败音效 -> 发射器空发声
                // volume: 0.4F (稍微小声), pitch: 1.2F (稍微高一点的咔哒声)
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.4F, 1.2F);

                player.displayClientMessage(Component.literal("§c❌范围内没有其他玩家"), true);
                player.getCooldowns().addCooldown(this, 20);
            }
        }
        return InteractionResultHolder.success(player.getItemInHand(UsedHand));
    }
}
