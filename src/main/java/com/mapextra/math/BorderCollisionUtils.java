package com.mapextra.math; // 确保包名正确

import com.mapextra.world.BorderData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

public class BorderCollisionUtils {

    // ==========================================
    // 1. 物理引擎碰撞箱生成 (3D 升级版)
    // ==========================================
    public static void addWallCollisions(AABB entityBox, BorderData.BorderEntry entry, List<VoxelShape> shapes) {
        if (entry == null) return;

        // 1. 计算 X 和 Z 的相对范围（相对于边界起点）
        // 使用 -1 和 +1 是为了稍微扩大搜索范围，防止浮点数精度问题导致漏判
        int minX = (int) Math.floor(entityBox.minX - entry.startX) - 1;
        int maxX = (int) Math.ceil(entityBox.maxX - entry.startX) + 1;

        int minZ = (int) Math.floor(entityBox.minZ - entry.startZ) - 1;
        int maxZ = (int) Math.ceil(entityBox.maxZ - entry.startZ) + 1;

        // 2. 【新增】计算 Y 轴的绝对范围 (直接基于世界坐标)
        // 既然已经是 3D 边界，我们也需要只检查实体附近的 Y 高度
        int minY = (int) Math.floor(entityBox.minY) - 1;
        int maxY = (int) Math.ceil(entityBox.maxY) + 1;

        // 3. 三重循环遍历 (X, Y, Z)
        for (int x = minX; x <= maxX; x++) { // x 是相对坐标
            for (int z = minZ; z <= maxZ; z++) { // z 是相对坐标
                for (int y = minY; y <= maxY; y++) { // y 是绝对坐标 (假设 BorderEntry 内部处理了偏移或直接对应)

                    // 【关键修改】调用新的 3D isWall 方法
                    // 注意参数顺序，我们在上一步定义的是 (localX, localZ, localY)
                    if (entry.isWall(x, z, y)) {

                        // 还原回世界坐标用于生成碰撞箱
                        double wallX = entry.startX + x;
                        double wallZ = entry.startZ + z;
                        double wallY = y;

                        // 【关键修改】不再是无限高的柱子 (-64 ~ 320)，而是当前这一个方块 (y ~ y+1)
                        VoxelShape wallShape = Shapes.box(wallX, wallY, wallZ, wallX + 1.0, wallY + 1.0, wallZ + 1.0);

                        // 精确检测：只有当实体真的碰到这个 1x1x1 的方块时，才加入碰撞列表
                        if (Shapes.joinIsNotEmpty(wallShape, Shapes.create(entityBox), BooleanOp.AND)) {
                            shapes.add(wallShape);
                        }
                    }
                }
            }
        }
    }





// ==========================================
    // 2. 锤子射线检测 (用于拆除墙壁)
    // ==========================================
    public record WallHit(int localX, int localZ, Direction enterFace, double t) {
    }

    /**
     * 【带物理兜底的 DDA 检测】
     * 逻辑顺序：
     * 1. 标准 DDA 检测：寻找视线上的虚拟墙壁。
     * 2. 兜底检测：如果 DDA 未命中或被遮挡，检查准星实际命中的物理方块是否就是墙壁本身。
     */
    public static WallHit raycastWallCellDDA(ServerLevel level,
                                             ServerPlayer player,
                                             BorderData.BorderEntry entry,
                                             double reach) {

        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();

        // 起点微调 (防止眼睛刚好卡在格子边缘)
        Vec3 eye2 = eye.add(dir.scale(0.01));

        double dirX = dir.x;
        double dirZ = dir.z;

        // 垂直视线无法进行水平 DDA，直接返回 null (兜底逻辑也依赖水平坐标，所以这里直接退没问题)
        if (Math.abs(dirX) < 1e-8 && Math.abs(dirZ) < 1e-8) return null;

        // ---- 1. 获取物理射线结果 (用于遮挡判断 + 兜底数据) ----
        Vec3 end = eye2.add(dir.scale(reach));
        BlockHitResult blockHit = level.clip(new ClipContext(
                eye2, end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));

        double tBlock = Double.POSITIVE_INFINITY;
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            tBlock = blockHit.getLocation().distanceTo(eye2);
        }

        // ---- 2. 执行标准 DDA ----
        WallHit ddaHit = runDDA(eye2, dirX, dirZ, entry, reach, tBlock);

        // 如果 DDA 直接命中了有效墙壁，优先返回 DDA 结果
        if (ddaHit != null) {
            return ddaHit;
        }

        // ---- 3. 【兜底逻辑】 DDA 没结果？检查是否直接点到了"是墙的方块" ----

        if (blockHit.getType() == HitResult.Type.BLOCK) {

            // 获取被点击方块的世界坐标
            int blockX = blockHit.getBlockPos().getX();
            int blockZ = blockHit.getBlockPos().getZ();

            // 转换为边界的相对坐标
            // 原理：(方块坐标 - 边界起点) 即为相对坐标
            // 使用 Math.floor 确保处理负数坐标时的正确性 (虽然 blockPos 是 int，但 entry.startX 可能是 double)
            int localX = Mth.floor(blockX - entry.startX);
            int localZ = Mth.floor(blockZ - entry.startZ);

            // 检查1: 是否在边界范围内？
            if (localX >= 0 && localX < entry.width && localZ >= 0 && localZ < entry.depth) {

                return new WallHit(localX, localZ, blockHit.getDirection(), tBlock);
            }
        } else {
            // 这里可以选择性提示调试信息："点击方块不在边界范围内"
            // 但作为 Utils 类，通常只返回 null 让调用者处理
        }


        return null;
    }

    /**
     * 纯净的 DDA 算法核心，剥离出来使逻辑更清晰
     * @param tBlockLimit 如果 t > tBlockLimit，则视为被遮挡，返回 null
     */
    private static WallHit runDDA(Vec3 start, double dirX, double dirZ,
                                  BorderData.BorderEntry entry, double reach, double tBlockLimit) {

        double gx = start.x - entry.startX;
        double gz = start.z - entry.startZ;

        int x = Mth.floor(gx);
        int z = Mth.floor(gz);

        int stepX = dirX > 0 ? 1 : -1;
        int stepZ = dirZ > 0 ? 1 : -1;

        double nextGridX = (stepX > 0) ? (x + 1) : x;
        double nextGridZ = (stepZ > 0) ? (z + 1) : z;

        double tMaxX = (Math.abs(dirX) < 1e-8) ? Double.POSITIVE_INFINITY : (nextGridX - gx) / dirX;
        double tMaxZ = (Math.abs(dirZ) < 1e-8) ? Double.POSITIVE_INFINITY : (nextGridZ - gz) / dirZ;

        double tDeltaX = (Math.abs(dirX) < 1e-8) ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dirX);
        double tDeltaZ = (Math.abs(dirZ) < 1e-8) ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dirZ);

        double t = 0.0;

        for (int iter = 0; iter < 100; iter++) {
            if (t > reach) return null;

            // 严格遮挡判定：一旦超过方块距离，视为被遮挡
            // (因为如果正好重叠，会有下方的兜底逻辑接管，所以这里严格判断也没关系)
            if (t > tBlockLimit) return null;

            if (x >= 0 && x < entry.width && z >= 0 && z < entry.depth) {
                if (entry.isWall(x, z)) {
                    return new WallHit(x, z, null, t);
                }
            }

            if (tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }



    // ==========================================
    // 3. 【新增】移动路径拦截检测 (用于反作弊/防穿墙)
    // ==========================================

    /**
     * 检测 [start -> end] 这段位移是否穿过了墙壁
     * @return 如果撞墙，返回修正后的安全位置(Vec3)；如果没有，返回 null
     */
    public static Vec3 detectCollision(Vec3 start, Vec3 end, BorderData.BorderEntry entry) {
        double startX = start.x;
        double startZ = start.z;
        double endX = end.x;
        double endZ = end.z;

        double p0x = startX - entry.startX;
        double p0z = startZ - entry.startZ;
        double p1x = endX - entry.startX;
        double p1z = endZ - entry.startZ;

        double dx = p1x - p0x;
        double dz = p1z - p0z;

        // 如果位移极小，认为没穿墙
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) return null;

        // DDA 初始化
        int x = Mth.floor(p0x);
        int z = Mth.floor(p0z);
        int endGridX = Mth.floor(p1x);
        int endGridZ = Mth.floor(p1z);

        int stepX = (dx > 0) ? 1 : -1;
        int stepZ = (dz > 0) ? 1 : -1;

        double tMaxX, tMaxZ;
        double tDeltaX, tDeltaZ;

        // X轴参数
        if (Math.abs(dx) < 1e-9) {
            tMaxX = Double.POSITIVE_INFINITY;
            tDeltaX = Double.POSITIVE_INFINITY;
        } else {
            double nextBoundaryX = (stepX > 0) ? (x + 1) : x;
            tMaxX = (nextBoundaryX - p0x) / dx;
            tDeltaX = Math.abs(1.0 / dx);
        }

        // Z轴参数
        if (Math.abs(dz) < 1e-9) {
            tMaxZ = Double.POSITIVE_INFINITY;
            tDeltaZ = Double.POSITIVE_INFINITY;
        } else {
            double nextBoundaryZ = (stepZ > 0) ? (z + 1) : z;
            tMaxZ = (nextBoundaryZ - p0z) / dz;
            tDeltaZ = Math.abs(1.0 / dz);
        }

        // DDA 循环：这里的 t 是 0.0 ~ 1.0 的比例参数
        // 我们不使用累加的 t 变量，而是依赖 tMaxX/Z 是否 > 1.0 来判断是否走完了全程
        for (int i = 0; i < 200; i++) {
            // 检查当前格是否是墙
            // 注意：不检查起点格子(x,z)，否则玩家一出生在墙里就永远动不了
            // 或者：如果起点就在墙里，应该允许走出来，不允许走进去？
            // 这里简单处理：只要碰到墙就算撞
            if (x >= 0 && x < entry.width && z >= 0 && z < entry.depth) {
                if (entry.isWall(x, z)) {
                    // 撞墙！计算并返回“撞击点”
                    return calculateIntercept(start, end, x + entry.startX, z + entry.startZ, dx, dz, stepX, stepZ);
                }
            }

            // 如果到达终点格子，结束
            if (x == endGridX && z == endGridZ) break;

            // 步进
            if (tMaxX < tMaxZ) {
                if (tMaxX > 1.0) break; // 超过终点了
                x += stepX;
                tMaxX += tDeltaX;
            } else {
                if (tMaxZ > 1.0) break; // 超过终点了
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }

        return null;
    }

    /**
     * 计算射线与墙体格子的交点，并返回一个稍微回退的位置
     */
    // 在 BorderCollisionUtils 类中修改

    /**
     * 计算拦截点，并应用"垂直于墙面"的安全回退
     */
    private static Vec3 calculateIntercept(Vec3 start, Vec3 end, double wallWorldX, double wallWorldZ,
                                           double dirX, double dirZ, int stepX, int stepZ) {

        // 墙体边界
        double wallMinX = wallWorldX;
        double wallMaxX = wallWorldX + 1.0;
        double wallMinZ = wallWorldZ;
        double wallMaxZ = wallWorldZ + 1.0;

        // 🟢 关键修改：直接使用 0.35 (玩家半径0.3 + 0.05缓冲)
        // 这样不管速度多少、角度多少，保证贴不到墙
        double padding = 0.35;

        double hitX = end.x;
        double hitZ = end.z;
        double tX = Double.NEGATIVE_INFINITY;
        double tZ = Double.NEGATIVE_INFINITY;

        // 计算进入时间 t
        if (Math.abs(dirX) > 1e-9) {
            if (stepX > 0) tX = (wallMinX - start.x) / dirX;
            else           tX = (wallMaxX - start.x) / dirX;
        }
        if (Math.abs(dirZ) > 1e-9) {
            if (stepZ > 0) tZ = (wallMinZ - start.z) / dirZ;
            else           tZ = (wallMaxZ - start.z) / dirZ;
        }

        // 判断撞击面并垂直推离
        if (tX > tZ) {
            // 撞到了 X 面 (East/West)
            if (stepX > 0) hitX = wallMinX - padding; // 从左往右撞，停在墙左边 0.35
            else           hitX = wallMaxX + padding; // 从右往左撞，停在墙右边 0.35

            // Z 轴依然按照原轨迹投影 (保留侧滑的移动分量)
            hitZ = start.z + (dirZ * tX);
        } else {
            // 撞到了 Z 面 (North/South)
            if (stepZ > 0) hitZ = wallMinZ - padding;
            else           hitZ = wallMaxZ + padding;

            // X 轴按照原轨迹投影
            hitX = start.x + (dirX * tZ);
        }

        // Y 轴保持原样，交给外部插值或保持
        return new Vec3(hitX, end.y, hitZ);
    }
    // ==========================================
// 4. 【高性能】传送/坐标设定穿墙拦截 (三线厚线DDA)
// ==========================================

    public static Vec3 detectCollisionThick3(Vec3 start, Vec3 end, BorderData.BorderEntry entry, double r) {
        // 中心线
        Vec3 c = detectCollision(start, end, entry);
        if (c != null) return c;

        // 沿移动方向法线偏移两条线
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-8) return null;

        // 法线 ( -dz, dx )
        double nx = -dz / len;
        double nz =  dx / len;

        Vec3 sL = new Vec3(start.x + nx * r, start.y, start.z + nz * r);
        Vec3 eL = new Vec3(end.x   + nx * r, end.y,   end.z   + nz * r);
        Vec3 left = detectCollision(sL, eL, entry);
        if (left != null) return left;

        Vec3 sR = new Vec3(start.x - nx * r, start.y, start.z - nz * r);
        Vec3 eR = new Vec3(end.x   - nx * r, end.y,   end.z   - nz * r);
        return detectCollision(sR, eR, entry);
    }

}
