package com.mapextra.client.render;

import com.mapextra.client.ClientPosCache;
import com.mapextra.init.ModItemRegister;
import com.mapextra.world.BorderData;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BorderRenderer extends RenderType {

    public BorderRenderer(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                          boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    // === 常量定义 ===
    private static final float HAMMER_R = 1.0f;
    private static final float HAMMER_G = 0.2f;
    private static final float HAMMER_B = 0.0f;

    private static final RenderType DEPTH_PASS = create(
            "mapextra_border_depth",
            DefaultVertexFormat.POSITION,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionShader))
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setWriteMaskState(DEPTH_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false)
    );

    private static final RenderType COLOR_PASS = create(
            "mapextra_border_color",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false)
    );

    private static final List<WallPos> RENDER_QUEUE = new ArrayList<>();

    private static class WallPos {
        int worldX, worldZ;
        double distSq;
        boolean n, s, w, e;

        public WallPos(int worldX, int worldZ, double distSq, boolean n, boolean s, boolean w, boolean e) {
            this.worldX = worldX; this.worldZ = worldZ; this.distSq = distSq;
            this.n = n; this.s = s; this.w = w; this.e = e;
        }
    }

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean isHammer = mc.player.getMainHandItem().is(ModItemRegister.HAMMER.get());
        BorderData.BorderEntry data;
        double maxRange;

        if (isHammer) {
            data = ClientPosCache.FOCUS_ENTRY;
            maxRange = 64.0;
        } else {
            data = ClientPosCache.ACTIVE_ENTRY;
            maxRange = 12.0;
        }

        if (data == null) return;

        int startX = (int) data.startX;
        int startZ = (int) data.startZ;
        int width  = (int) data.width;
        int depth  = (int) data.depth;

        double pX = mc.player.getX();
        double pZ = mc.player.getZ();
        double maxRangeSq = maxRange * maxRange;

        int relPlayerX = (int)(pX - startX);
        int relPlayerZ = (int)(pZ - startZ);

        int loopMinRx = Math.max(0, relPlayerX - (int)maxRange - 1);
        int loopMaxRx = Math.min(width, relPlayerX + (int)maxRange + 1);
        int loopMinRz = Math.max(0, relPlayerZ - (int)maxRange - 1);
        int loopMaxRz = Math.min(depth, relPlayerZ + (int)maxRange + 1);

        RENDER_QUEUE.clear();

        for (int rx = loopMinRx; rx < loopMaxRx; rx++) {
            for (int rz = loopMinRz; rz < loopMaxRz; rz++) {

                if (!data.isWall(rx, rz)) continue;

                int worldX = startX + rx;
                int worldZ = startZ + rz;

                double dx = (worldX + 0.5) - pX;
                double dz = (worldZ + 0.5) - pZ;
                double distSq = dx * dx + dz * dz;
                if (distSq > maxRangeSq) continue;

                boolean n = isWallSafeRelative(data, rx, rz - 1, width, depth);
                boolean s = isWallSafeRelative(data, rx, rz + 1, width, depth);
                boolean w = isWallSafeRelative(data, rx - 1, rz, width, depth);
                boolean e = isWallSafeRelative(data, rx + 1, rz, width, depth);

                if (n && s && w && e) continue;

                RENDER_QUEUE.add(new WallPos(worldX, worldZ, distSq, n, s, w, e));
            }
        }

        RENDER_QUEUE.sort(Comparator.comparingDouble(obj -> obj.distSq));

        Matrix4f matrix = poseStack.last().pose();

        VertexConsumer depthBuilder = bufferSource.getBuffer(DEPTH_PASS);
        renderWithAPI(depthBuilder, matrix, false, isHammer, mc.player);

        VertexConsumer colorBuilder = bufferSource.getBuffer(COLOR_PASS);
        renderWithAPI(colorBuilder, matrix, true, isHammer, mc.player);

        if (bufferSource instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch(DEPTH_PASS);
            bs.endBatch(COLOR_PASS);
        }
    }

    private static boolean isWallSafeRelative(BorderData.BorderEntry data, int rx, int rz, int width, int depth) {
        if (rx < 0 || rx >= width || rz < 0 || rz >= depth) return false;
        return data.isWall(rx, rz);
    }

    /**
     * 用 QuadFxAPI 统一渲染：
     * - 锤子：flat 无限高墙 +（调试）紫色半径3地面网格渐变
     * - 普通：spot + 原版算法shader（等价你 drawSubQuad）
     */
    private static void renderWithAPI(VertexConsumer builder, Matrix4f matrix, boolean withColor,
                                      boolean isHammer, net.minecraft.world.entity.player.Player player) {

        double eyeX = player.getX();
        double eyeY = player.getEyeY();
        double eyeZ = player.getZ();

        // ================================
        // 模式 A：锤子模式（无限高墙）
        // ================================
        if (isHammer) {
            var flat = QuadFxAPI.flat()
                    .color(HAMMER_R, HAMMER_G, HAMMER_B, 0.4f)
                    .wallHeight(-64f, 320f)
                    .clear();

            for (WallPos wall : RENDER_QUEUE) {
                int x = wall.worldX;
                int z = wall.worldZ;

                if (!wall.n) flat.face(x, 0, z, QuadFxAPI.FaceDir.NORTH, eyeX, eyeY, eyeZ);
                if (!wall.s) flat.face(x, 0, z, QuadFxAPI.FaceDir.SOUTH, eyeX, eyeY, eyeZ);
                if (!wall.w) flat.face(x, 0, z, QuadFxAPI.FaceDir.WEST,  eyeX, eyeY, eyeZ);
                if (!wall.e) flat.face(x, 0, z, QuadFxAPI.FaceDir.EAST,  eyeX, eyeY, eyeZ);
            }

            flat.render(builder, matrix, withColor);

            // 2. 画地形贴合网格 (ModelGeometryUtil 版)
            // 这一步完美解决了门、栅栏、楼梯的形状问题

            // 定义 Shader (紫色地形网格)
            var terrainShader = new QuadFxAPI.FalloffSpotShader()
                    .range(12.0, 10.0, 5.0)
                    .useCenterForShading(false);

            var spot = QuadFxAPI.spot()
                    .eye(eyeX, eyeY, eyeZ)
                    .center(player.getX(), player.getY(), player.getZ())
                    .shader(terrainShader)
                    .maxDist(16.0)
                    .detail(2)
                    .clear();

            net.minecraft.world.level.Level level = player.level();
            int radius = 10;
            int px = (int)Math.floor(player.getX());
            int py = (int)Math.floor(player.getY());
            int pz = (int)Math.floor(player.getZ());

            // 为了减少对象创建，复用 MutableBlockPos
            net.minecraft.core.BlockPos.MutableBlockPos mPos = new net.minecraft.core.BlockPos.MutableBlockPos();

            for (int x = px - radius; x <= px + radius; x++) {
                for (int z = pz - radius; z <= pz + radius; z++) {

                    // 简单的水平距离剔除，优化性能
                    double dx = (x + 0.5) - player.getX();
                    double dz = (z + 0.5) - player.getZ();
                    if (dx*dx + dz*dz > radius * radius) continue;

                    // 垂直扫描范围：玩家脚下 -2 到 +3
                    int yMin = py - 2;
                    int yMax = py + 3;

                    for (int y = yMin; y <= yMax; y++) {
                        mPos.set(x, y, z);
                        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(mPos);

                        // 1. 过滤空气
                        if (state.isAir()) continue;

                        // 2. 过滤掉完全透明或不想显示的方块（可选）
                        // if (!state.getMaterial().isSolid()) continue;

                        // 3. 【核心调用】直接提取模型几何体
                        // ✅ 使用混合模式：自动处理 门(Model) 和 床(Shape)
                        ModelGeometryUtil.extractHybrid(level, mPos, state, spot::quad);
                    }
                }
            }

            spot.render(builder, matrix, withColor);

            // ✅ 新增：每帧结束后调用结算
            ModelGeometryUtil.endFrame();

            return;
        }

        // ================================
        // 模式 B：游玩模式（原版光斑）
        // ================================
        double yMin = eyeY - 12.0;
        double yMax = eyeY + 12.0;

        // ✅ 原版算法 shader（等价你原 drawSubQuad 的计算）
        var shader = new QuadFxAPI.FalloffSpotShader().useCenterForShading(false);

        var spot = QuadFxAPI.spot()
                .eye(eyeX, eyeY, eyeZ)
                .shader(shader)
                .maxDist(12.0)
                .detail(2)               // ✅ detail=2 => 0.5×0.5（对应你原 sub=2 + step=0.5 的方格观感）
                .alphaEpsilon(0.005f)
                .clear();

        for (WallPos wall : RENDER_QUEUE) {
            int x = wall.worldX;
            int z = wall.worldZ;

            if (!wall.n) spot.wallColumn(x, z, QuadFxAPI.FaceDir.NORTH, yMin, yMax);
            if (!wall.s) spot.wallColumn(x, z, QuadFxAPI.FaceDir.SOUTH, yMin, yMax);
            if (!wall.w) spot.wallColumn(x, z, QuadFxAPI.FaceDir.WEST,  yMin, yMax);
            if (!wall.e) spot.wallColumn(x, z, QuadFxAPI.FaceDir.EAST,  yMin, yMax);
        }

        spot.render(builder, matrix, withColor);
    }

    /**
     * 原版 drawSubQuad() 逻辑搬进 Shader：
     * - spreadSq：由 API 根据 dirHint 计算（墙面 N/S 用 X+Y，E/W 用 Z+Y）
     * - totalDist：用 eye 的 3D 距离
     * - alpha：max(baseAlpha, abyssAlpha)
     * - 10~12 fade
     * - 雾化颜色
     */
    /**
     * 原版 drawSubQuad() 逻辑搬进 Shader
     * 现已增强：支持链式调用修改参数，以便 Hammer 模式复用
     */


}
