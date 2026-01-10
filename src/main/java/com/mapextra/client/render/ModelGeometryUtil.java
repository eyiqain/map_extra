package com.mapextra.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

public final class ModelGeometryUtil {

    private ModelGeometryUtil() {}

    // 统计数据（保留接口，但不再统计邻面剔除的具体细节，因为现在是逻辑剔除）
    private static int capturedFaces = 0;

    public static void endFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // 简化统计信息，因为现在没有"小面忽略"这种概念了
            String msg = String.format("§e[极速渲染] §f生成面数: §b%d", capturedFaces);
            mc.player.displayClientMessage(Component.literal(msg), true);
        }
        capturedFaces = 0;
    }

    /**
     * 极速版提取：强制将所有方块视为 1x1x1 正方体
     * 只要邻居不是空气，就剔除对应面。
     */
    public static void extractHybrid(Level level, BlockPos pos, BlockState state, Consumer<QuadFxAPI.QuadJob> collector) {
        // 如果方块本身是空气（虽然外层通常判断过了，这里做个双保险）
        if (state.isAir()) return;

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        // -------------------------------------------------------------
        // 手动遍历 6 个方向。
        // 逻辑：如果某方向的邻居是空气，说明那个面“露在外面”，需要渲染。
        //       如果邻居有方块（不管是什么方块），我们都视为"贴合"，不渲染该面。
        // -------------------------------------------------------------

        // UP (Y+1)
        if (level.getBlockState(pos.above()).isAir()) {
            addFullFace(collector, x, y, z, Direction.UP);
        }

        // DOWN (Y-1)
        if (level.getBlockState(pos.below()).isAir()) {
            addFullFace(collector, x, y, z, Direction.DOWN);
        }

        // NORTH (Z-1)
        if (level.getBlockState(pos.north()).isAir()) {
            addFullFace(collector, x, y, z, Direction.NORTH);
        }

        // SOUTH (Z+1)
        if (level.getBlockState(pos.south()).isAir()) {
            addFullFace(collector, x, y, z, Direction.SOUTH);
        }

        // WEST (X-1)
        if (level.getBlockState(pos.west()).isAir()) {
            addFullFace(collector, x, y, z, Direction.WEST);
        }

        // EAST (X+1)
        if (level.getBlockState(pos.east()).isAir()) {
            addFullFace(collector, x, y, z, Direction.EAST);
        }
    }

    /**
     * 硬编码添加一个 1x1 完整面
     * 没有任何模型读取，直接生成坐标。
     */
    private static void addFullFace(Consumer<QuadFxAPI.QuadJob> collector, int x, int y, int z, Direction dir) {
        // 预计算坐标，避免每次重新计算
        // 稍微偏移一点点 (0.0001) 防止深度冲突，或者直接用整数
        double x0 = x, x1 = x + 1.0;
        double y0 = y, y1 = y + 1.0;
        double z0 = z, z1 = z + 1.0;

        // 中心点
        double cx = x + 0.5;
        double cy = y + 0.5;
        double cz = z + 0.5;

        // 根据方向生成对应的 4 个顶点
        // 注意：顶点的顺序影响背面剔除（Counter-Clockwise），但在 Shader 里通常关了 Cull，所以这里只要点对就行
        switch (dir) {
            case UP -> // Y=1 面: (x0,z0) -> (x0,z1) -> (x1,z1) -> (x1,z0)
                    collector.accept(new QuadFxAPI.QuadJob(
                            x0, y1, z0,
                            x0, y1, z1,
                            x1, y1, z1,
                            x1, y1, z0,
                            cx, cy, cz, 0.0, QuadFxAPI.FaceDir.UP
                    ));

            case DOWN -> // Y=0 面
                    collector.accept(new QuadFxAPI.QuadJob(
                            x0, y0, z1,
                            x0, y0, z0,
                            x1, y0, z0,
                            x1, y0, z1,
                            cx, cy, cz, 0.0, QuadFxAPI.FaceDir.DOWN
                    ));

            case NORTH -> // Z=0 面 (Facing -Z)
                    collector.accept(new QuadFxAPI.QuadJob(
                            x1, y1, z0,
                            x1, y0, z0,
                            x0, y0, z0,
                            x0, y1, z0,
                            cx, cy, cz, 0.0, QuadFxAPI.FaceDir.NORTH
                    ));

            case SOUTH -> // Z=1 面 (Facing +Z)
                    collector.accept(new QuadFxAPI.QuadJob(
                            x0, y1, z1,
                            x0, y0, z1,
                            x1, y0, z1,
                            x1, y1, z1,
                            cx, cy, cz, 0.0, QuadFxAPI.FaceDir.SOUTH
                    ));

            case WEST -> // X=0 面 (Facing -X)
                    collector.accept(new QuadFxAPI.QuadJob(
                            x0, y1, z1,
                            x0, y0, z1,
                            x0, y0, z0,
                            x0, y1, z0,
                            cx, cy, cz, 0.0, QuadFxAPI.FaceDir.WEST
                    ));

            case EAST -> // X=1 面 (Facing +X)
                    collector.accept(new QuadFxAPI.QuadJob(
                            x1, y1, z0,
                            x1, y0, z0,
                            x1, y0, z1,
                            x1, y1, z1,
                            cx, cy, cz, 0.0, QuadFxAPI.FaceDir.EAST
                    ));
        }

        capturedFaces++;
    }
}
