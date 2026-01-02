package com.mapextra.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.function.Consumer;

public final class ModelGeometryUtil {

    private ModelGeometryUtil() {}

    private static final RandomSource RANDOM = RandomSource.create();

    // 过滤阈值（按你需求调）
    private static final double MIN_DIAGONAL_SQ = 0.005;   // 兜底
    private static final double MIN_AREA = 0.0005;         // 更可靠的小面过滤（建议你从 0.0002~0.001 调）

    // 统计
    private static int capturedFaces = 0;
    private static int culledFaces = 0;
    private static int ignoredSmallFaces = 0;

    public static void endFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String msg = String.format(
                    "§e[渲染统计] §f捕获面: §b%d §f| 邻面剔除: §c%d §f| 小面忽略: §c%d",
                    capturedFaces, culledFaces, ignoredSmallFaces
            );
            mc.player.displayClientMessage(Component.literal(msg), true);
        }
        capturedFaces = 0;
        culledFaces = 0;
        ignoredSmallFaces = 0;
    }

    public static void extractHybrid(Level level, BlockPos pos, BlockState state, Consumer<QuadFxAPI.QuadJob> collector) {
        boolean hasModel = extractBakedModel(level, pos, state, collector);

        RenderShape renderShape = state.getRenderShape();
        if (!hasModel || renderShape == RenderShape.ENTITYBLOCK_ANIMATED) {
            extractVoxelShape(level, pos, state, collector);
        }
    }

    // ---------------------------------------------------------
    // 核心：用 Minecraft 自己的 shouldRenderFace 做邻面剔除
    // ---------------------------------------------------------
    private static boolean shouldRenderFace(Level level, BlockState state, BlockPos pos, Direction dir) {
        BlockPos neighborPos = pos.relative(dir);
        return Block.shouldRenderFace(state, level, pos, dir, neighborPos);
    }

    private static boolean extractBakedModel(Level level, BlockPos pos, BlockState state, Consumer<QuadFxAPI.QuadJob> collector) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) return false;

        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        if (model == null) return false;

        long seed = state.getSeed(pos);
        boolean addedAny = false;

        // 定向面：先剔除再 getQuads（省大头）
        for (Direction dir : Direction.values()) {
            if (!shouldRenderFace(level, state, pos, dir)) {
                culledFaces++; // 这里按“面方向”计数（比你之前靠谱）
                continue;
            }

            RANDOM.setSeed(seed);
            List<BakedQuad> quads = model.getQuads(state, dir, RANDOM);
            if (processBakedQuads(quads, pos, collector, dir)) addedAny = true;
        }

        // 无方向 quad：不做邻面剔除，只做小面过滤
        RANDOM.setSeed(seed);
        List<BakedQuad> quads = model.getQuads(state, null, RANDOM);
        if (processBakedQuads(quads, pos, collector, null)) addedAny = true;

        return addedAny;
    }

    private static boolean processBakedQuads(List<BakedQuad> quads, BlockPos pos,
                                             Consumer<QuadFxAPI.QuadJob> collector, Direction dirHint) {
        if (quads.isEmpty()) return false;
        boolean added = false;

        final double bx = pos.getX();
        final double by = pos.getY();
        final double bz = pos.getZ();

        for (BakedQuad quad : quads) {
            int[] data = quad.getVertices();

            double x1 = Float.intBitsToFloat(data[0])  + bx;
            double y1 = Float.intBitsToFloat(data[1])  + by;
            double z1 = Float.intBitsToFloat(data[2])  + bz;

            double x2 = Float.intBitsToFloat(data[8])  + bx;
            double y2 = Float.intBitsToFloat(data[9])  + by;
            double z2 = Float.intBitsToFloat(data[10]) + bz;

            double x3 = Float.intBitsToFloat(data[16]) + bx;
            double y3 = Float.intBitsToFloat(data[17]) + by;
            double z3 = Float.intBitsToFloat(data[18]) + bz;

            double x4 = Float.intBitsToFloat(data[24]) + bx;
            double y4 = Float.intBitsToFloat(data[25]) + by;
            double z4 = Float.intBitsToFloat(data[26]) + bz;

            // 兜底：对角线过小
            double dx = x1 - x3, dy = y1 - y3, dz = z1 - z3;
            double diagSq = dx*dx + dy*dy + dz*dz;
            if (diagSq < MIN_DIAGONAL_SQ) {
                ignoredSmallFaces++;
                continue;
            }

            // 更稳：面积过滤
            double area = quadArea(x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4);
            if (area < MIN_AREA) {
                ignoredSmallFaces++;
                continue;
            }

            double cx = (x1+x2+x3+x4) * 0.25;
            double cy = (y1+y2+y3+y4) * 0.25;
            double cz = (z1+z2+z3+z4) * 0.25;

            Direction useDir = (dirHint != null) ? dirHint : quad.getDirection();

            collector.accept(new QuadFxAPI.QuadJob(
                    x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4,
                    cx,cy,cz, 0.0, convertDir(useDir)
            ));
            capturedFaces++;
            added = true;
        }

        return added;
    }

    private static void extractVoxelShape(Level level, BlockPos pos, BlockState state, Consumer<QuadFxAPI.QuadJob> collector) {
        VoxelShape shape = state.getShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) return;

        final double x = pos.getX();
        final double y = pos.getY();
        final double z = pos.getZ();

        // VoxelShape 每个方向：先算一次 shouldRenderFace，避免盒子循环里重复查邻块
        final boolean up    = shouldRenderFace(level, state, pos, Direction.UP);
        final boolean down  = shouldRenderFace(level, state, pos, Direction.DOWN);
        final boolean north = shouldRenderFace(level, state, pos, Direction.NORTH);
        final boolean south = shouldRenderFace(level, state, pos, Direction.SOUTH);
        final boolean west  = shouldRenderFace(level, state, pos, Direction.WEST);
        final boolean east  = shouldRenderFace(level, state, pos, Direction.EAST);

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double wx1 = x + minX, wx2 = x + maxX;
            double wy1 = y + minY, wy2 = y + maxY;
            double wz1 = z + minZ, wz2 = z + maxZ;

            // 每个面都可以做面积过滤（盒子面是矩形，面积很好算）
            if (up)    addVoxelFaceIfBigEnough(collector, wx1,wy2,wz1, wx1,wy2,wz2, wx2,wy2,wz2, wx2,wy2,wz1, QuadFxAPI.FaceDir.UP);
            if (down)  addVoxelFaceIfBigEnough(collector, wx1,wy1,wz2, wx1,wy1,wz1, wx2,wy1,wz1, wx2,wy1,wz2, QuadFxAPI.FaceDir.DOWN);
            if (north) addVoxelFaceIfBigEnough(collector, wx2,wy2,wz1, wx1,wy2,wz1, wx1,wy1,wz1, wx2,wy1,wz1, QuadFxAPI.FaceDir.NORTH);
            if (south) addVoxelFaceIfBigEnough(collector, wx1,wy2,wz2, wx2,wy2,wz2, wx2,wy1,wz2, wx1,wy1,wz2, QuadFxAPI.FaceDir.SOUTH);
            if (west)  addVoxelFaceIfBigEnough(collector, wx1,wy2,wz1, wx1,wy2,wz2, wx1,wy1,wz2, wx1,wy1,wz1, QuadFxAPI.FaceDir.WEST);
            if (east)  addVoxelFaceIfBigEnough(collector, wx2,wy2,wz2, wx2,wy2,wz1, wx2,wy1,wz1, wx2,wy1,wz2, QuadFxAPI.FaceDir.EAST);

            // 如果某方向不可渲染，你可以把它当“剔除面方向”统计（可选）
            // 这里不再累加 culledFaces，避免盒子多时统计爆炸误导
        });
    }

    private static void addVoxelFaceIfBigEnough(Consumer<QuadFxAPI.QuadJob> collector,
                                                double x1,double y1,double z1,
                                                double x2,double y2,double z2,
                                                double x3,double y3,double z3,
                                                double x4,double y4,double z4,
                                                QuadFxAPI.FaceDir dir) {
        double area = quadArea(x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4);
        if (area < MIN_AREA) {
            ignoredSmallFaces++;
            return;
        }
        double cx = (x1+x2+x3+x4) * 0.25;
        double cy = (y1+y2+y3+y4) * 0.25;
        double cz = (z1+z2+z3+z4) * 0.25;
        collector.accept(new QuadFxAPI.QuadJob(
                x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4,
                cx,cy,cz, 0.0, dir
        ));
        capturedFaces++;
    }

    private static double quadArea(
            double x1,double y1,double z1,
            double x2,double y2,double z2,
            double x3,double y3,double z3,
            double x4,double y4,double z4
    ) {
        return triArea(x1,y1,z1, x2,y2,z2, x3,y3,z3) + triArea(x1,y1,z1, x3,y3,z3, x4,y4,z4);
    }

    private static double triArea(
            double ax,double ay,double az,
            double bx,double by,double bz,
            double cx,double cy,double cz
    ) {
        double abx = bx-ax, aby = by-ay, abz = bz-az;
        double acx = cx-ax, acy = cy-ay, acz = cz-az;
        double cxp = aby*acz - abz*acy;
        double cyp = abz*acx - abx*acz;
        double czp = abx*acy - aby*acx;
        return 0.5 * Math.sqrt(cxp*cxp + cyp*cyp + czp*czp);
    }

    private static QuadFxAPI.FaceDir convertDir(Direction mcDir) {
        return switch (mcDir) {
            case NORTH -> QuadFxAPI.FaceDir.NORTH;
            case SOUTH -> QuadFxAPI.FaceDir.SOUTH;
            case WEST  -> QuadFxAPI.FaceDir.WEST;
            case EAST  -> QuadFxAPI.FaceDir.EAST;
            case UP    -> QuadFxAPI.FaceDir.UP;
            case DOWN  -> QuadFxAPI.FaceDir.DOWN;
        };
    }
}
