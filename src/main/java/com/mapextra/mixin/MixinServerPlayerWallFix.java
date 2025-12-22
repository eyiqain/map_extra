package com.mapextra.mixin;

import com.mapextra.math.WallPenetrationFix;
import com.mapextra.world.BorderData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class MixinServerPlayerWallFix {

    /**
     * 移动结束后：如果玩家 AABB 已经和空气墙重叠，则做 MTV 推出。
     * 正常情况下不会触发，0 影响原版手感。
     */
    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("TAIL"))
    private void mapextra$afterMove(MoverType moverType, Vec3 vec, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof ServerPlayer player)) return;

        if (WallPenetrationFix.isReentry()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        BorderData.BorderEntry entry = BorderData.get(level).getActiveEntry();
        if (entry == null) return;

        // rootVehicle 一并修复（和你原先事件逻辑一致）
        Entity entityToFix = player.getRootVehicle();

        AABB box = entityToFix.getBoundingBox();

        // 正常路径：不在墙内 -> 记录 lastSafe 并退出
        if (!WallPenetrationFix.overlapsAnyWall(box, entry)) {
            WallPenetrationFix.updateLastSafe(player.getStringUUID(), entityToFix.position());
            return;
        }

        // 异常路径：已经在墙内 -> 尝试推出 / 回退
        WallPenetrationFix.withGuard(() -> applyFix(player, level, entry, entityToFix));

    }


    private static void applyFix(ServerPlayer player,
                                 ServerLevel level,
                                 BorderData.BorderEntry entry,
                                 Entity entityToFix) {
        AABB box = entityToFix.getBoundingBox();

        // 1) MTV 推出（只改 XZ）
        Vec3 push = WallPenetrationFix.resolvePenetrationXZ(box, entry);
        if (push != null) {
            double nx = entityToFix.getX() + push.x;
            double nz = entityToFix.getZ() + push.z;

            entityToFix.teleportTo(nx, entityToFix.getY(), nz);

            // 清除水平速度，避免连续“顶进墙”
            Vec3 vel = entityToFix.getDeltaMovement();
            entityToFix.setDeltaMovement(0.0, vel.y, 0.0);

            player.hasImpulse = true;

            // 推出后如果已经安全，更新 lastSafe
            if (!WallPenetrationFix.overlapsAnyWall(entityToFix.getBoundingBox(), entry)) {
                WallPenetrationFix.updateLastSafe(player.getStringUUID(), entityToFix.position());
            }
            return;
        }

        // 2) 推出失败 -> 回到最后安全点
        Vec3 safe = WallPenetrationFix.getLastSafe(player.getStringUUID());
        if (safe != null) {
            entityToFix.teleportTo(safe.x, safe.y, safe.z);

            Vec3 vel = entityToFix.getDeltaMovement();
            entityToFix.setDeltaMovement(0.0, vel.y, 0.0);

            player.hasImpulse = true;
        } else {
            // 没有 lastSafe：至少别让速度继续把他“挤进墙”
            Vec3 vel = entityToFix.getDeltaMovement();
            entityToFix.setDeltaMovement(0.0, vel.y, 0.0);
            player.hasImpulse = true;
        }
    }
}
