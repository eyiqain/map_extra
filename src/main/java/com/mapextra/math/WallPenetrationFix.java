package com.mapextra.math;

import com.mapextra.world.BorderData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 防线三：仅处理“异常状态”——玩家 AABB 已经与空气墙重叠。
 * 正常情况下玩家不可能进入墙体，因此不会触发，不影响原版手感/碰撞逻辑。
 */
public final class WallPenetrationFix {

    private WallPenetrationFix() {}

    // 玩家最后一个“安全位置”(不在墙内)，用于推出失败时回退
    private static final ConcurrentHashMap<String, Vec3> LAST_SAFE_POS = new ConcurrentHashMap<>();

    // 递归保护：我们在 injection 里 teleportTo，会再次触发 injection
    private static final ThreadLocal<Boolean> REENTRY_GUARD = ThreadLocal.withInitial(() -> false);

    public static boolean isReentry() {
        return REENTRY_GUARD.get();
    }

    public static void withGuard(Runnable r) {
        if (REENTRY_GUARD.get()) return;
        REENTRY_GUARD.set(true);
        try {
            r.run();
        } finally {
            REENTRY_GUARD.set(false);
        }
    }

    public static void updateLastSafe(String playerKey, Vec3 pos) {
        LAST_SAFE_POS.put(playerKey, pos);
    }

    public static Vec3 getLastSafe(String playerKey) {
        return LAST_SAFE_POS.get(playerKey);
    }

    /**
     * 快速判断：box 是否与任意墙格的 AABB 重叠
     */
    public static boolean overlapsAnyWall(AABB box, BorderData.BorderEntry entry) {
        if (entry == null) return false;

        int minX = (int) Math.floor(box.minX - entry.startX) - 1;
        int maxX = (int) Math.floor(box.maxX - entry.startX) + 1;
        int minZ = (int) Math.floor(box.minZ - entry.startZ) - 1;
        int maxZ = (int) Math.floor(box.maxZ - entry.startZ) + 1;

        for (int lx = minX; lx <= maxX; lx++) {
            for (int lz = minZ; lz <= maxZ; lz++) {
                if (!entry.isWall(lx, lz)) continue;

                double wx = entry.startX + lx;
                double wz = entry.startZ + lz;

                // 你的墙是 1x1 且无限高；这里用 AABB intersects 即可（比 Voxel join 更快）
                AABB wall = new AABB(wx, -64, wz, wx + 1.0, 320, wz + 1.0);
                if (wall.intersects(box)) {
                    return true;
                }
            }
        }
        return false;
    }

    /*
     * MTV 推出：把已经进入墙内的 AABB 推出到最近不相交位置（只推 XZ）
     * @return 推出后的“位置偏移”(dx,dz)，若无法推出则返回 null
     */
    public static Vec3 resolvePenetrationXZ(AABB originalBox, BorderData.BorderEntry entry) {
        if (entry == null) return null;

        // epsilon：防浮点误差导致推出后仍 intersects
        final double eps = 0.003;
        // 最大迭代次数：处理角落/夹角同时与多面墙重叠
        final int maxIter = 4;

        AABB box = originalBox;
        double accX = 0.0;
        double accZ = 0.0;

        for (int iter = 0; iter < maxIter; iter++) {
            // 找到一个与 box 相交的墙（只要找到一个就先推出，迭代解决多墙）
            AABB hitWall = findFirstIntersectingWall(box, entry);
            if (hitWall == null) {
                // 已经不在墙内
                return new Vec3(accX, 0.0, accZ);
            }

            // 计算四个方向的推出量（把 box 推到 wall 外面）
            // 注意：这些量在“重叠”时一定能解除重叠（或至少变小）
            double pushLeft  = hitWall.minX - box.maxX - eps;  // 往 -X 推
            double pushRight = hitWall.maxX - box.minX + eps;  // 往 +X 推
            double pushFront = hitWall.minZ - box.maxZ - eps;  // 往 -Z 推
            double pushBack  = hitWall.maxZ - box.minZ + eps;  // 往 +Z 推

            // 选绝对值最小的推出方向（最小扰动）
            double bestDx = pushLeft;
            double bestDz = 0.0;
            double bestAbs = Math.abs(pushLeft);

            double absRight = Math.abs(pushRight);
            if (absRight < bestAbs) {
                bestAbs = absRight;
                bestDx = pushRight;
                bestDz = 0.0;
            }

            double absFront = Math.abs(pushFront);
            if (absFront < bestAbs) {
                bestAbs = absFront;
                bestDx = 0.0;
                bestDz = pushFront;
            }

            double absBack = Math.abs(pushBack);
            if (absBack < bestAbs) {
                bestAbs = absBack;
                bestDx = 0.0;
                bestDz = pushBack;
            }

            // 应用推出
            box = box.move(bestDx, 0.0, bestDz);
            accX += bestDx;
            accZ += bestDz;
        }

        // 迭代后仍在墙内：认为推出失败
        if (overlapsAnyWall(box, entry)) {
            return null;
        }
        return new Vec3(accX, 0.0, accZ);
    }

    private static AABB findFirstIntersectingWall(AABB box, BorderData.BorderEntry entry) {
        int minX = (int) Math.floor(box.minX - entry.startX) - 1;
        int maxX = (int) Math.floor(box.maxX - entry.startX) + 1;
        int minZ = (int) Math.floor(box.minZ - entry.startZ) - 1;
        int maxZ = (int) Math.floor(box.maxZ - entry.startZ) + 1;

        for (int lx = minX; lx <= maxX; lx++) {
            for (int lz = minZ; lz <= maxZ; lz++) {
                if (!entry.isWall(lx, lz)) continue;

                double wx = entry.startX + lx;
                double wz = entry.startZ + lz;

                AABB wall = new AABB(wx, -64, wz, wx + 1.0, 320, wz + 1.0);
                if (wall.intersects(box)) {
                    return wall;
                }
            }
        }
        return null;
    }
}
