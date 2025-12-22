package com.mapextra.event;

import com.mapextra.MapExtra;
import com.mapextra.math.BorderCollisionUtils;
import com.mapextra.world.BorderData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WallCollisionHandler {

    // 移除了 getSafePos 方法，因为核心回退逻辑已经移入 BorderCollisionUtils.detectCollision 内部

    // ==========================================
    // 场景 A & B: 拦截常规移动 (Tick)
    // ==========================================
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) return;

        ServerPlayer player = (ServerPlayer) event.player;
        BorderData.BorderEntry activeEntry = BorderData.get(player.serverLevel()).getActiveEntry();
        if (activeEntry == null) return;

        Vec3 start = new Vec3(player.xo, player.yo, player.zo);
        Vec3 end = player.position();

        if (start.distanceToSqr(end) < 1e-8) return;

        // 1. DDA 射线检测 (处理快速移动/穿墙)
        // 这里的 intercept 已经是计算过 "0.35 安全回退" 后的坐标了
        Vec3 intercept = BorderCollisionUtils.detectCollision(start, end, activeEntry);

        if (intercept != null) {
            // 发现穿墙，直接传送到安全点
            teleportRoot(player, intercept.x, end.y, intercept.z);
        }


    }


    // 辅助方法：统一处理传送和速度清零
    private static void teleportRoot(ServerPlayer player, double x, double y, double z) {
        Entity entityToMove = player.getRootVehicle();
        entityToMove.teleportTo(x, y, z);

        //  速度清零：防止惯性再次导致穿墙
        entityToMove.setDeltaMovement(0, entityToMove.getDeltaMovement().y, 0);
        player.hasImpulse = true; // 强制客户端同步
    }
}
