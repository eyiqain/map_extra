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

    private static final float NORMAL_R = 0.3f;
    private static final float NORMAL_G = 0.3f;
    private static final float NORMAL_B = 0.3f;

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
        int worldX, worldZ; // 这里存世界坐标，方便画图
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

        // === 1. 准备基础数据 ===
        int startX = (int) data.startX;
        int startZ = (int) data.startZ;
        int width = (int) data.width;
        int depth = (int) data.depth;

        double pX = mc.player.getX();
        double pZ = mc.player.getZ();
        double maxRangeSq = maxRange * maxRange;

        // === 2. 计算相对坐标的循环范围 (Relative Loop Range) ===
        // 我们想让 rx 从 0 循环到 width。
        // 但是为了性能，我们需要把循环卡在玩家周围 maxRange 的范围内。

        // 玩家相对于墙起点的坐标
        int relPlayerX = (int)(pX - startX);
        int relPlayerZ = (int)(pZ - startZ);

        // 算出“相对坐标”下的循环起点和终点
        int loopMinRx = Math.max(0, relPlayerX - (int)maxRange - 1);
        int loopMaxRx = Math.min(width, relPlayerX + (int)maxRange + 1);
        int loopMinRz = Math.max(0, relPlayerZ - (int)maxRange - 1);
        int loopMaxRz = Math.min(depth, relPlayerZ + (int)maxRange + 1);

        RENDER_QUEUE.clear();

        // === 3. 开始循环 (全部使用相对坐标 rx, rz) ===
        // rx = 0 代表墙的最左边，rx = width 代表墙的最右边
        // 这样 isWall(rx, rz) 永远不会越界，也不会错位！
        for (int rx = loopMinRx; rx < loopMaxRx; rx++) {
            for (int rz = loopMinRz; rz < loopMaxRz; rz++) {

                // 直接用相对坐标查表，绝对准确
                if (!data.isWall(rx, rz)) continue;

                // 还原成世界坐标，用于计算距离和最终渲染
                int worldX = startX + rx;
                int worldZ = startZ + rz;

                // 距离判断 (用世界坐标算)
                double dx = (worldX + 0.5) - pX;
                double dz = (worldZ + 0.5) - pZ;
                double distSq = dx * dx + dz * dz;
                if (distSq > maxRangeSq) continue;

                // 邻居判断 (传入相对坐标，非常清晰)
                boolean n = isWallSafeRelative(data, rx, rz - 1, width, depth); // 北
                boolean s = isWallSafeRelative(data, rx, rz + 1, width, depth); // 南
                boolean w = isWallSafeRelative(data, rx - 1, rz, width, depth); // 西
                boolean e = isWallSafeRelative(data, rx + 1, rz, width, depth); // 东

                // 如果被包围，剔除
                if (n && s && w && e) continue;

                // 加入队列，存的是世界坐标(方便画图)
                RENDER_QUEUE.add(new WallPos(worldX, worldZ, distSq, n, s, w, e));
            }
        }

        // 4. 排序
        RENDER_QUEUE.sort(Comparator.comparingDouble(obj -> obj.distSq));

        // 5. 绘制
        Matrix4f matrix = poseStack.last().pose();

        // 获取 RenderType buffer
        VertexConsumer depthBuilder = bufferSource.getBuffer(DEPTH_PASS);
        // 【修改】传入 mc.player
        drawQueue(depthBuilder, matrix, false, isHammer, mc.player);

        VertexConsumer colorBuilder = bufferSource.getBuffer(COLOR_PASS);
        // 【修改】传入 mc.player
        drawQueue(colorBuilder, matrix, true, isHammer, mc.player);

        // 6. 强制刷新 (不用变)
        if (bufferSource instanceof MultiBufferSource.BufferSource) {
            MultiBufferSource.BufferSource bs = (MultiBufferSource.BufferSource) bufferSource;
            bs.endBatch(DEPTH_PASS);
            bs.endBatch(COLOR_PASS);
        }
    }

    /**
     * 【重写的 helper】
     * 专门处理相对坐标的邻居检测
     * rx, rz: 要检查的相对坐标
     * width, depth: 数组的边界
     */
    private static boolean isWallSafeRelative(BorderData.BorderEntry data, int rx, int rz, int width, int depth) {
        // 如果相对坐标超出了数组范围 (比如 -1 或 >= width)
        // 说明这里是“边界外面”，也就是空气，返回 false 让它画出墙面
        if (rx < 0 || rx >= width || rz < 0 || rz >= depth) {
            return false;
        }
        // 在范围内，安全查表
        return data.isWall(rx, rz);
    }

    // 【修改】参数增加了 Player player
    private static void drawQueue(VertexConsumer builder, Matrix4f matrix, boolean withColor, boolean isHammer, net.minecraft.world.entity.player.Player player) {

        // === 模式 A: 锤子模式 (上帝视角，无限高墙) ===
        // (这部分代码完全保持不变)
        if (isHammer) {
            float r = HAMMER_R;
            float g = HAMMER_G;
            float b = HAMMER_B;
            float a = 0.4f;
            float minY = -64.0f;
            float maxY = 320.0f;

            for (WallPos wall : RENDER_QUEUE) {
                int x = wall.worldX;
                int z = wall.worldZ;
                if (!wall.n) addQuad(builder, matrix, x + 1, maxY, z, x, maxY, z, x, minY, z, x + 1, minY, z, r, g, b, a, withColor);
                if (!wall.s) addQuad(builder, matrix, x + 1, minY, z + 1, x + 1, maxY, z + 1, x, maxY, z + 1, x, minY, z + 1, r, g, b, a, withColor);
                if (!wall.w) addQuad(builder, matrix, x, minY, z + 1, x, maxY, z + 1, x, maxY, z, x, minY, z, r, g, b, a, withColor);
                if (!wall.e) addQuad(builder, matrix, x + 1, minY, z, x + 1, maxY, z, x + 1, maxY, z + 1, x + 1, minY, z + 1, r, g, b, a, withColor);
            }
            return;
        }

        // === 模式 B: 游玩模式 ===
        double pX = player.getX();
        double pY = player.getEyeY();
        double pZ = player.getZ();

        // 垂直范围 ±12 (虽然光斑小，但为了防止你抬头低头时光斑被切断，范围还是要大)
        int startY = (int) Math.floor(pY - 12.0);
        int endY = (int) Math.ceil(pY + 12.0);

        int sub = 2;
        double step = 1.0 / sub;

        for (WallPos wall : RENDER_QUEUE) {
            int wx = wall.worldX;
            int wz = wall.worldZ;

            for (int y = startY; y <= endY; y++) {
                for (int i = 0; i < sub; i++) {
                    double localY = y + (i * step);
                    double localY2 = localY + step;
                    double centerY = localY + (step / 2.0);

                    // 预先计算垂直方向的扩散 (Vertical Spread)
                    // 即：墙面上的点的高度 - 玩家眼睛高度
                    double dy = centerY - pY;
                    double dySq = dy * dy;

                    for (int j = 0; j < sub; j++) {
                        double offset1 = j * step;
                        double offset2 = offset1 + step;
                        double centerOffset = offset1 + (step / 2.0);

                        // 对于每一个朝向，我们需要计算水平扩散 (Horizontal Spread)
                        // SpreadSq = 水平偏差平方 + 垂直偏差平方

                        if (!wall.n) {
                            double centerX = wx + centerOffset;
                            double centerZ = wz;
                            // 北墙: 水平偏差是 X 轴差距
                            double dx = centerX - pX;
                            double spreadSq = dx*dx + dySq; // 【计算扩散】

                            drawSubQuad(builder, matrix,
                                    wx + offset2, localY2, wz, wx + offset1, localY2, wz,
                                    wx + offset1, localY, wz, wx + offset2, localY, wz,
                                    centerX, centerY, centerZ, pX, pY, pZ,
                                    spreadSq, // 【传入】
                                    withColor);
                        }

                        if (!wall.s) {
                            double centerX = wx + centerOffset;
                            double centerZ = wz + 1.0;
                            // 南墙: 水平偏差是 X 轴差距
                            double dx = centerX - pX;
                            double spreadSq = dx*dx + dySq;

                            drawSubQuad(builder, matrix,
                                    wx + offset2, localY, wz + 1, wx + offset2, localY2, wz + 1,
                                    wx + offset1, localY2, wz + 1, wx + offset1, localY, wz + 1,
                                    centerX, centerY, centerZ, pX, pY, pZ,
                                    spreadSq,
                                    withColor);
                        }

                        if (!wall.w) {
                            double centerX = wx;
                            double centerZ = wz + centerOffset;
                            // 西墙: 水平偏差是 Z 轴差距 (因为墙面沿Z轴延伸)
                            double dz = centerZ - pZ;
                            double spreadSq = dz*dz + dySq;

                            drawSubQuad(builder, matrix,
                                    wx, localY, wz + offset2, wx, localY2, wz + offset2,
                                    wx, localY2, wz + offset1, wx, localY, wz + offset1,
                                    centerX, centerY, centerZ, pX, pY, pZ,
                                    spreadSq,
                                    withColor);
                        }

                        if (!wall.e) {
                            double centerX = wx + 1.0;
                            double centerZ = wz + centerOffset;
                            // 东墙: 水平偏差是 Z 轴差距
                            double dz = centerZ - pZ;
                            double spreadSq = dz*dz + dySq;

                            drawSubQuad(builder, matrix,
                                    wx + 1, localY, wz + offset1, wx + 1, localY2, wz + offset1,
                                    wx + 1, localY2, wz + offset2, wx + 1, localY, wz + offset2,
                                    centerX, centerY, centerZ, pX, pY, pZ,
                                    spreadSq,
                                    withColor);
                        }
                    }
                }
            }
        }
    }

    /**
     * 处理子网格的距离计算和颜色生成
     */
    /**
     * @param spreadSq 扩散距离的平方 (光斑在墙面上的大小)
     */
    private static void drawSubQuad(VertexConsumer builder, Matrix4f matrix,
                                    double x1, double y1, double z1,
                                    double x2, double y2, double z2,
                                    double x3, double y3, double z3,
                                    double x4, double y4, double z4,
                                    double cx, double cy, double cz,
                                    double px, double py, double pz,
                                    double spreadSq, // 扩散距离平方
                                    boolean withColor) {

        // 1. 全局距离计算
        double dx = cx - px;
        double dy = cy - py;
        double dz = cz - pz;
        double totalDistSq = dx * dx + dy * dy + dz * dz;

        // 深度剔除：超过 12 米完全不渲染
        if (totalDistSq >= 144.0) return;

        // === 2. 独立定义两个光斑的半径 ===

        // 外层半径 (提示层): 设为 3.5 米 (比较大，容易看见)
        double outerRadius = 3.5;
        double outerRadiusSq = outerRadius * outerRadius; // 12.25

        // 内层半径 (深渊层): 设为 2.2 米 (比较小，聚焦感强)
        double innerRadius = 2.2;
        double innerRadiusSq = innerRadius * innerRadius; // 4.84

        // 总体剔除：如果超出了最大的那个圆(外层)，就直接不画了
        if (spreadSq >= outerRadiusSq) return;

        double totalDist = Math.sqrt(totalDistSq);
        double spreadDist = Math.sqrt(spreadSq);

        // === 3. 计算外层 Alpha (大光环) ===
        float baseAlpha = 0.0f;

        // 只有在 spreadSq < outerRadiusSq 时才计算 (上面已经剔除了，这里肯定满足)
        // 归一化: 0.0(中心) -> 1.0(外层边缘)
        double outerSpreadFactor = spreadDist / outerRadius;
        // 形状曲线: 2.0 次方 (比较柔和的圆)
        double outerShape = 1.0 - Math.pow(outerSpreadFactor, 2.0);

        // 基础透明度 0.15
        baseAlpha = (float) (0.35f * outerShape);

        // === 4. 计算内层 Alpha (小黑核) ===
        float abyssAlpha = 0.0f;

        // 只有满足两个条件才渲染内层：
        // A. 深度足够近 (< 5米)
        // B. 扩散足够小 (< 2.2米，即在内层圆圈里)
        if (totalDist < 5.0 && spreadSq < innerRadiusSq) {

            // 深度因子 (控制吸入感)
            double depthFactor = totalDist / 5.0;
            float depthStrength = (float) (0.9 * (1.0 - Math.pow(depthFactor, 3.5)));

            // 扩散因子 (控制内层圆的形状)
            double innerSpreadFactor = spreadDist / innerRadius;
            // 形状曲线: 3.0 次方 (边缘稍微锐利一点，和外层区分开)
            double innerShape = 1.0 - Math.pow(innerSpreadFactor, 3.0);

            // 最终内层透明度 = 深度强度 * 圆圈形状
            abyssAlpha = (float) (depthStrength * innerShape);
        }

        // === 5. 混合 (取最大值) ===
        // 效果：中心区域取 abyssAlpha (深色)，边缘区域取 baseAlpha (淡色光环)
        float finalAlpha = Math.max(baseAlpha, abyssAlpha);

        // === 6. 远距离消隐 (10-12米) ===
        if (totalDist > 10.0) {
            double fade = (12.0 - totalDist) / 2.0;
            finalAlpha *= fade;
        }

        if (finalAlpha <= 0.005f) return;

        // === 7. 颜色逻辑 ===
        float baseR = 0.0f;
        float baseG = 0.0f;
        float baseB = 0.05f;

        // 远处的雾效颜色
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
