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

    public BorderRenderer(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    // === 常量定义 ===
    private static final float HAMMER_R = 1.0f;
    private static final float HAMMER_G = 0.2f;
    private static final float HAMMER_B = 0.0f;

    // RenderTypes 保持不变 ...
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

    // 【修改】WallPos 增加 worldY 字段，以及上下邻居检测
    private static class WallPos {
        int worldX, worldY, worldZ;
        double distSq;
        boolean n, s, w, e, u, d; // 增加 Up, Down 邻居状态

        public WallPos(int worldX, int worldY, int worldZ, double distSq, boolean n, boolean s, boolean w, boolean e, boolean u, boolean d) {
            this.worldX = worldX; this.worldY = worldY; this.worldZ = worldZ;
            this.distSq = distSq;
            this.n = n; this.s = s; this.w = w; this.e = e; this.u = u; this.d = d;
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
            maxRange = 64.0; // 锤子模式看远一点
        } else {
            data = ClientPosCache.ACTIVE_ENTRY;
            maxRange = 16.0; // 游玩模式范围适中 (为了覆盖垂直高度，稍微调大一点)
        }

        if (data == null) return;

        // === 1. 准备基础数据 ===
        int startX = (int) data.startX;
        int startZ = (int) data.startZ;
        // startY 暂定为 0，如果支持垂直偏移需修改此处
        int startY = 0;

        int width = data.width;
        int depth = data.depth;
        int height = data.height; // 【新增】获取高度

        double pX = mc.player.getX();
        double pY = mc.player.getY();
        double pZ = mc.player.getZ();
        double maxRangeSq = maxRange * maxRange;

        // === 2. 计算相对坐标的循环范围 (3D) ===
        // 玩家相对于墙起点的坐标
        int relPlayerX = (int)(pX - startX);
        int relPlayerY = (int)(pY - startY);
        int relPlayerZ = (int)(pZ - startZ);

        // X 和 Z 的范围
        int loopMinRx = Math.max(0, relPlayerX - (int)maxRange - 1);
        int loopMaxRx = Math.min(width, relPlayerX + (int)maxRange + 1);

        int loopMinRz = Math.max(0, relPlayerZ - (int)maxRange - 1);
        int loopMaxRz = Math.min(depth, relPlayerZ + (int)maxRange + 1);

        // 【新增】Y 的范围 (如果是锤子模式，可能希望看到更高，但为了性能也需要裁剪)
        // 锤子模式可以适当放宽 Y 轴限制，或者完全不限制 Y (看性能)
        // 这里为了安全，限制在玩家上下一定范围内
        double yRange = isHammer ? 64.0 : 16.0;
        int loopMinRy = Math.max(0, relPlayerY - (int)yRange - 1);
        int loopMaxRy = Math.min(height, relPlayerY + (int)yRange + 1);

        RENDER_QUEUE.clear();

        // === 3. 开始循环 (3D 遍历) ===
        for (int rx = loopMinRx; rx < loopMaxRx; rx++) {
            for (int rz = loopMinRz; rz < loopMaxRz; rz++) {
                // 【关键】增加 Y 轴循环
                for (int ry = loopMinRy; ry < loopMaxRy; ry++) {

                    // 3D 查表
                    if (!data.isWall(rx, rz, ry)) continue;

                    // 还原成世界坐标
                    int worldX = startX + rx;
                    int worldY = startY + ry;
                    int worldZ = startZ + rz;

                    // 距离判断 (3D 距离)
                    double dx = (worldX + 0.5) - pX;
                    double dy = (worldY + 0.5) - pY;
                    double dz = (worldZ + 0.5) - pZ;
                    double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq > maxRangeSq) continue;

                    // 邻居判断 (6个方向)
                    boolean n = isWallSafeRelative(data, rx, rz - 1, ry, width, depth, height); // 北
                    boolean s = isWallSafeRelative(data, rx, rz + 1, ry, width, depth, height); // 南
                    boolean w = isWallSafeRelative(data, rx - 1, rz, ry, width, depth, height); // 西
                    boolean e = isWallSafeRelative(data, rx + 1, rz, ry, width, depth, height); // 东
                    boolean u = isWallSafeRelative(data, rx, rz, ry + 1, width, depth, height); // 上
                    boolean d = isWallSafeRelative(data, rx, rz, ry - 1, width, depth, height); // 下

                    // 如果被完全包围 (6面都是墙)，则是内部方块，剔除
                    if (n && s && w && e && u && d) continue;

                    RENDER_QUEUE.add(new WallPos(worldX, worldY, worldZ, distSq, n, s, w, e, u, d));
                }
            }
        }

        // 4. 排序 (由远及近，保证透明渲染正确)
        RENDER_QUEUE.sort(Comparator.comparingDouble(obj -> -obj.distSq)); // 修正：半透明通常需要从远到近画，或者 RenderType 里的 sortOnUpload 会处理

        // 5. 绘制
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer depthBuilder = bufferSource.getBuffer(DEPTH_PASS);
        drawQueue(depthBuilder, matrix, false, isHammer, mc.player);

        VertexConsumer colorBuilder = bufferSource.getBuffer(COLOR_PASS);
        drawQueue(colorBuilder, matrix, true, isHammer, mc.player);

        if (bufferSource instanceof MultiBufferSource.BufferSource) {
            MultiBufferSource.BufferSource bs = (MultiBufferSource.BufferSource) bufferSource;
            bs.endBatch(DEPTH_PASS);
            bs.endBatch(COLOR_PASS);
        }
    }

    // 【修改】支持 3D 坐标检测
    private static boolean isWallSafeRelative(BorderData.BorderEntry data, int rx, int rz, int ry, int width, int depth, int height) {
        if (rx < 0 || rx >= width || rz < 0 || rz >= depth || ry < 0 || ry >= height) {
            return false;
        }
        return data.isWall(rx, rz, ry);
    }

    private static void drawQueue(VertexConsumer builder, Matrix4f matrix, boolean withColor, boolean isHammer, net.minecraft.world.entity.player.Player player) {

        // === 模式 A: 锤子模式 (上帝视角，显示具体体素) ===
        if (isHammer) {
            float r = HAMMER_R;
            float g = HAMMER_G;
            float b = HAMMER_B;
            float a = 0.4f;

            for (WallPos wall : RENDER_QUEUE) {
                int x = wall.worldX;
                int y = wall.worldY; // 【修改】使用具体的 Y
                int z = wall.worldZ;

                // 绘制 1x1x1 的方块表面
                if (!wall.n) addQuad(builder, matrix, x + 1, y+1, z, x, y+1, z, x, y, z, x + 1, y, z, r, g, b, a, withColor); // 北面
                if (!wall.s) addQuad(builder, matrix, x + 1, y, z + 1, x + 1, y+1, z + 1, x, y+1, z + 1, x, y, z + 1, r, g, b, a, withColor); // 南面
                if (!wall.w) addQuad(builder, matrix, x, y, z + 1, x, y+1, z + 1, x, y+1, z, x, y, z, r, g, b, a, withColor); // 西面
                if (!wall.e) addQuad(builder, matrix, x + 1, y, z, x + 1, y+1, z, x + 1, y+1, z + 1, x + 1, y, z + 1, r, g, b, a, withColor); // 东面

                // 锤子模式下也画上下底面，方便看出立体感
                if (!wall.u) addQuad(builder, matrix, x, y+1, z, x+1, y+1, z, x+1, y+1, z+1, x, y+1, z+1, r, g, b, a, withColor); // 上面
                if (!wall.d) addQuad(builder, matrix, x, y, z+1, x+1, y, z+1, x+1, y, z, x, y, z, r, g, b, a, withColor); // 下面
            }
            return;
        }

        // === 模式 B: 游玩模式 (高科技力场) ===
        double pX = player.getX();
        double pY = player.getEyeY();
        double pZ = player.getZ();

        int sub = 2; // 细分度
        double step = 1.0 / sub;

        for (WallPos wall : RENDER_QUEUE) {
            int wx = wall.worldX;
            int wy = wall.worldY; // 【修改】获取当前方块的 Y
            int wz = wall.worldZ;

            // 【关键修改】不再遍历 Y 轴大循环，而是针对当前这一个方块进行细分绘制
            // y 从 wy 开始，到 wy+1 结束 (高度为1)

            for (int i = 0; i < sub; i++) {
                double localY = wy + (i * step);
                double localY2 = localY + step;
                double centerY = localY + (step / 2.0);

                double dy = centerY - pY;
                double dySq = dy * dy;

                for (int j = 0; j < sub; j++) {
                    double offset1 = j * step;
                    double offset2 = offset1 + step;
                    double centerOffset = offset1 + (step / 2.0);

                    // 这里的逻辑和原来一样，只是 y 坐标被限制在了当前方块范围内
                    // 注意：游玩模式下，为了美观，我们通常只渲染侧面墙壁。
                    // 如果你需要显示天花板和地板的力场效果，需要额外写 drawSubQuad 逻辑来处理水平面的光斑计算
                    // 目前代码保持原样，只渲染四周墙壁，这对于迷宫/跑酷通常足够。

                    if (!wall.n) {
                        double centerX = wx + centerOffset;
                        double centerZ = wz;
                        double dx = centerX - pX;
                        double spreadSq = dx*dx + dySq;
                        drawSubQuad(builder, matrix,
                                wx + offset2, localY2, wz, wx + offset1, localY2, wz,
                                wx + offset1, localY, wz, wx + offset2, localY, wz,
                                centerX, centerY, centerZ, pX, pY, pZ, spreadSq, withColor);
                    }

                    if (!wall.s) {
                        double centerX = wx + centerOffset;
                        double centerZ = wz + 1.0;
                        double dx = centerX - pX;
                        double spreadSq = dx*dx + dySq;
                        drawSubQuad(builder, matrix,
                                wx + offset2, localY, wz + 1, wx + offset2, localY2, wz + 1,
                                wx + offset1, localY2, wz + 1, wx + offset1, localY, wz + 1,
                                centerX, centerY, centerZ, pX, pY, pZ, spreadSq, withColor);
                    }

                    if (!wall.w) {
                        double centerX = wx;
                        double centerZ = wz + centerOffset;
                        double dz = centerZ - pZ;
                        double spreadSq = dz*dz + dySq;
                        drawSubQuad(builder, matrix,
                                wx, localY, wz + offset2, wx, localY2, wz + offset2,
                                wx, localY2, wz + offset1, wx, localY, wz + offset1,
                                centerX, centerY, centerZ, pX, pY, pZ, spreadSq, withColor);
                    }

                    if (!wall.e) {
                        double centerX = wx + 1.0;
                        double centerZ = wz + centerOffset;
                        double dz = centerZ - pZ;
                        double spreadSq = dz*dz + dySq;
                        drawSubQuad(builder, matrix,
                                wx + 1, localY, wz + offset1, wx + 1, localY2, wz + offset1,
                                wx + 1, localY2, wz + offset2, wx + 1, localY, wz + offset2,
                                centerX, centerY, centerZ, pX, pY, pZ, spreadSq, withColor);
                    }
                }
            }
        }
    }

    // drawSubQuad 和 addQuad 保持原样 ...
    // (请复制你之前提供的 drawSubQuad 和 addQuad 代码，完全不需要改动)

    // ... 省略 ...
    private static void drawSubQuad(VertexConsumer builder, Matrix4f matrix,
                                    double x1, double y1, double z1,
                                    double x2, double y2, double z2,
                                    double x3, double y3, double z3,
                                    double x4, double y4, double z4,
                                    double cx, double cy, double cz,
                                    double px, double py, double pz,
                                    double spreadSq,
                                    boolean withColor) {
        // 这里直接粘贴你原来代码里的内容，逻辑是通用的
        // ...
        // 1. 全局距离计算
        double dx = cx - px;
        double dy = cy - py;
        double dz = cz - pz;
        double totalDistSq = dx * dx + dy * dy + dz * dz;

        if (totalDistSq >= 144.0) return;

        double outerRadius = 3.5;
        double outerRadiusSq = outerRadius * outerRadius;
        double innerRadius = 2.2;
        double innerRadiusSq = innerRadius * innerRadius;

        if (spreadSq >= outerRadiusSq) return;

        double totalDist = Math.sqrt(totalDistSq);
        double spreadDist = Math.sqrt(spreadSq);

        float baseAlpha = 0.0f;
        double outerSpreadFactor = spreadDist / outerRadius;
        double outerShape = 1.0 - Math.pow(outerSpreadFactor, 2.0);
        baseAlpha = (float) (0.35f * outerShape);

        float abyssAlpha = 0.0f;
        if (totalDist < 5.0 && spreadSq < innerRadiusSq) {
            double depthFactor = totalDist / 5.0;
            float depthStrength = (float) (0.9 * (1.0 - Math.pow(depthFactor, 3.5)));
            double innerSpreadFactor = spreadDist / innerRadius;
            double innerShape = 1.0 - Math.pow(innerSpreadFactor, 3.0);
            abyssAlpha = (float) (depthStrength * innerShape);
        }

        float finalAlpha = Math.max(baseAlpha, abyssAlpha);

        if (totalDist > 10.0) {
            double fade = (12.0 - totalDist) / 2.0;
            finalAlpha *= fade;
        }

        if (finalAlpha <= 0.005f) return;

        float baseR = 0.0f;
        float baseG = 0.0f;
        float baseB = 0.05f;

        double mistFactor = Math.min(1.0, totalDist / 8.0);
        float r = baseR + (float)(mistFactor * 0.3);
        float g = baseG + (float)(mistFactor * 0.3);
        float b = baseB + (float)(mistFactor * 0.5);

        addQuad(builder, matrix, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, r, g, b, finalAlpha, withColor);
    }

    private static void addQuad(VertexConsumer builder, Matrix4f matrix,
                                double x1, double y1, double z1,
                                double x2, double y2, double z2,
                                double x3, double y3, double z3,
                                double x4, double y4, double z4,
                                float r, float g, float b, float a, boolean withColor) {
        if (withColor) {
            builder.vertex(matrix, (float)x1, (float)y1, (float)z1).color(r, g, b, a).endVertex();
            builder.vertex(matrix, (float)x2, (float)y2, (float)z2).color(r, g, b, a).endVertex();
            builder.vertex(matrix, (float)x3, (float)y3, (float)z3).color(r, g, b, a).endVertex();
            builder.vertex(matrix, (float)x4, (float)y4, (float)z4).color(r, g, b, a).endVertex();
        } else {
            builder.vertex(matrix, (float)x1, (float)y1, (float)z1).endVertex();
            builder.vertex(matrix, (float)x2, (float)y2, (float)z2).endVertex();
            builder.vertex(matrix, (float)x3, (float)y3, (float)z3).endVertex();
            builder.vertex(matrix, (float)x4, (float)y4, (float)z4).endVertex();
        }
    }
}
