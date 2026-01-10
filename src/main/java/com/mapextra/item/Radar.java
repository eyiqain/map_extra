package com.mapextra.item;

import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketRadarScanRequest;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;

import java.util.List;

public class Radar extends Item {
    public static int SEARCH_RANGE = 50;
    public static int COOLDOWN_TICKS = 60;

    public Radar(Properties properties){
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand UsedHand){

        // ✅ 1. 客户端逻辑：读取缓存，打包发给服务端
//        if (level.isClientSide) {
//            // 获取单例中的面数
//            RADAR_RANGE.rebuild(player);
//            int faceCount = GeometryCache.getInstance().getQuadCount();
//            // 发送包到服务端 (让服务端去广播给所有人)
//            ModMessage.sendToServer(new PacketShareQuadCount(faceCount));
//        }
         // ✅ 1. 客户端逻辑：读取缓存，打包发给服务端
                if (level.isClientSide) {
                    ModMessage.sendToServer(new PacketRadarScanRequest());
                    ParticleEffects.spawnRisingAirflow();
                }

        // ✅ 2. 服务端逻辑：原有的搜人功能
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
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.3F, 1.0F);
                nearestTarget.displayClientMessage(
                        Component.literal("👁你已被抓捕者发现！").withStyle(style -> style.withColor(0xFF0000).withBold(true)),
                        true
                );
                player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

            } else {
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.4F, 1.2F);

                // 没搜到人也发到聊天栏，把 Action Bar 让给面数统计
                player.displayClientMessage(Component.literal("§c❌范围内没有其他玩家"), false);
                player.getCooldowns().addCooldown(this, 20);
            }
        }
        return InteractionResultHolder.success(player.getItemInHand(UsedHand));
    }
}
