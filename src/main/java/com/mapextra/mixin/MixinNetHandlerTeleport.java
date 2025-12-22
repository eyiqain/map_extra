package com.mapextra.mixin;

import com.mapextra.math.WallIntercept;
import com.mapextra.world.BorderData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinNetHandlerTeleport {

    @Shadow public ServerPlayer player;

    // Shadow 方法同样需要匹配签名，Set<RelativeMovement> 即使不写泛型也可以，或者直接用 Object 为了避嫌
    // 但这里既然已经 import 了 RelativeMovement，这样写没问题
    @Shadow public abstract void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relativeSet);

    @Unique private static final double BOUNCE = 0.5D;
    @Unique private boolean mapextra$isIntercepting = false;

    /**
     * 修复点：使用 JVM 描述符格式
     * (DDDFFLjava/util/Set;)V
     * D=double, F=float, Ljava/util/Set;=Set对象, V=void返回
     */
    @Inject(
            method = "teleport(DDDFFLjava/util/Set;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void mapextra$onNetworkTeleport(
            double x, double y, double z,
            float yaw, float pitch,
            Set<RelativeMovement> relativeSet,
            CallbackInfo ci
    ) {
        // 1. 递归锁
        if (this.mapextra$isIntercepting) return;

        // 2. 基础检查
        if (this.player == null) return;

        // 确保 ServerLevel 不为 null (极少数边缘情况)
        if (this.player.serverLevel() == null) return;

        BorderData.BorderEntry entry = BorderData.get(this.player.serverLevel()).getActiveEntry();
        if (entry == null) return;

        Vec3 start = this.player.position();

        // 3. 计算绝对终点
        Vec3 end = mapextra$calculateTarget(start, x, y, z, relativeSet);

        // 4. 碰撞检测
        WallIntercept hit = mapextra$detectWallIntercept2D(start, end, entry);
        if (hit == null) return;

        // 5. 拦截原传送
        ci.cancel();

        // 6. 计算并执行安全回弹
        Vec3 safe = mapextra$applyBounce(hit);

        this.mapextra$isIntercepting = true;
        try {
            // 给玩家发消息，方便调试
            //this.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[MapExtra] 传送被墙体阻挡 (NetHandler)"));

            // 调用 Shadow 方法执行安全传送
            // 注意：此时相对集合传空 Set.of()，因为 safe 已经是绝对坐标
            this.teleport(safe.x, safe.y, safe.z, yaw, pitch, Set.of());

        } finally {
            this.mapextra$isIntercepting = false;
        }
    }

    // ==========================================================
    // 辅助方法 (保持不变)
    // ==========================================================

    @Unique
    private Vec3 mapextra$calculateTarget(Vec3 currentPos, double x, double y, double z, Set<RelativeMovement> relatives) {
        double targetX = x;
        double targetY = y;
        double targetZ = z;

        if (relatives != null && !relatives.isEmpty()) {
            if (relatives.contains(RelativeMovement.X)) targetX += currentPos.x;
            if (relatives.contains(RelativeMovement.Y)) targetY += currentPos.y;
            if (relatives.contains(RelativeMovement.Z)) targetZ += currentPos.z;
        }
        return new Vec3(targetX, targetY, targetZ);
    }

    @Unique
    private static WallIntercept mapextra$detectWallIntercept2D(Vec3 start, Vec3 end, BorderData.BorderEntry entry) {
        double x0 = start.x - entry.startX;
        double z0 = start.z - entry.startZ;
        double x1 = end.x   - entry.startX;
        double z1 = end.z   - entry.startZ;

        double dx = x1 - x0;
        double dz = z1 - z0;
        if (Math.abs(dx) + Math.abs(dz) < 1e-6) return null;

        int cellX = Mth.floor(x0);
        int cellZ = Mth.floor(z0);
        int endCellX = Mth.floor(x1);
        int endCellZ = Mth.floor(z1);

        int stepX = (dx > 0) ? 1 : -1;
        int stepZ = (dz > 0) ? 1 : -1;

        double invDx = (Math.abs(dx) < 1e-12) ? Double.POSITIVE_INFINITY : 1.0 / dx;
        double invDz = (Math.abs(dz) < 1e-12) ? Double.POSITIVE_INFINITY : 1.0 / dz;

        double tDeltaX = Math.abs(invDx);
        double tDeltaZ = Math.abs(invDz);

        double nextGridX = (stepX > 0) ? (cellX + 1) : cellX;
        double nextGridZ = (stepZ > 0) ? (cellZ + 1) : cellZ;

        double tMaxX = (invDx == Double.POSITIVE_INFINITY) ? Double.POSITIVE_INFINITY : (nextGridX - x0) * invDx;
        double tMaxZ = (invDz == Double.POSITIVE_INFINITY) ? Double.POSITIVE_INFINITY : (nextGridZ - z0) * invDz;

        int maxSteps = Math.abs(endCellX - cellX) + Math.abs(endCellZ - cellZ) + 8;

        double tEnter = 0.0;
        int lastAxis = -1;

        for (int i = 0; i < maxSteps; i++) {
            if (cellX == endCellX && cellZ == endCellZ) break;

            if (tMaxX < tMaxZ) {
                tEnter = tMaxX;
                if (tEnter > 1.0) break;
                cellX += stepX;
                tMaxX += tDeltaX;
                lastAxis = 0;
            } else {
                tEnter = tMaxZ;
                if (tEnter > 1.0) break;
                cellZ += stepZ;
                tMaxZ += tDeltaZ;
                lastAxis = 1;
            }

            if (cellX >= 0 && cellX < entry.width && cellZ >= 0 && cellZ < entry.depth) {
                if (entry.isWall(cellX, cellZ)) {
                    double hitWorldX = entry.startX + (x0 + dx * tEnter);
                    double hitWorldZ = entry.startZ + (z0 + dz * tEnter);
                    double hitY = start.y + (end.y - start.y) * Mth.clamp(tEnter, 0.0, 1.0);

                    int nx = 0, nz = 0;
                    if (lastAxis == 0) nx = (stepX > 0) ? -1 : 1;
                    else if (lastAxis == 1) nz = (stepZ > 0) ? -1 : 1;

                    return new WallIntercept(new Vec3(hitWorldX, hitY, hitWorldZ), nx, nz, tEnter);
                }
            }
        }
        return null;
    }

    @Unique
    private static Vec3 mapextra$applyBounce(WallIntercept hit) {
        return new Vec3(hit.point.x + hit.nx * BOUNCE, hit.point.y, hit.point.z + hit.nz * BOUNCE);
    }
}
