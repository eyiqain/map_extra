package com.mapextra.client.render;

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

import java.util.Iterator;

public class RadarRenderer extends RenderType {

    public RadarRenderer(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                         boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    private static final RenderType RADAR_PASS = create(
            "mapextra_radar_pass",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false)
    );

    // 扫描节奏：1s扩散 + 1s保持 + 1s衰减
    private static final long EXPAND_MS = 1000L;
    private static final long HOLD_MS   = 1000L;
    private static final long FADE_MS   = 1000L;
    private static final long TOTAL_MS  = EXPAND_MS + HOLD_MS + FADE_MS;

    // ✅ 红色波：持续时间（建议 600~900ms，不要太明显）
    private static final long PULSE_MS = 800L;
    // ✅ 红色波：半径（你要的 5）
    private static final double PULSE_RADIUS = 10.0;

    // 扫描主色参数（你现在的）
    private static final double RANGE_START_MAX_DIST = 5.0;
    private static final double RANGE_START_OUTER = 3.0;
    private static final double RANGE_START_INNER = 0.0;

    private static final double RANGE_END_MAX_DIST = 30.0;
    private static final double RANGE_END_OUTER = 30.0;
    private static final double RANGE_END_INNER = 25.0;

    private static final float COLOR_START_R = 0.1f;
    private static final float COLOR_START_G = 0.2f;
    private static final float COLOR_START_B = 0.1f;

    private static final float COLOR_END_R = 0.0f;
    private static final float COLOR_END_G = 0.05f;
    private static final float COLOR_END_B = 0.0f;

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource) {
        GeometryCache cache = GeometryCache.getInstance();
        if (cache.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        double eyeX = mc.player.getX();
        double eyeY = mc.player.getEyeY();
        double eyeZ = mc.player.getZ();

        long now = System.currentTimeMillis();

        for (GeometryCache.CacheEntry entry : cache.getCacheQueue()) {
            long elapsed = now - entry.createTime;
            if (elapsed < 0) elapsed = 0;
            if (elapsed >= TOTAL_MS) continue;

            // 1) 扩散进度（只用 1 秒扩散完）
            double tExpand = clamp01((double) elapsed / (double) EXPAND_MS);
            tExpand = smoothstep(tExpand);

            // 2) 扫描主层能量：前 2 秒 1，最后 1 秒衰减
            float energyMain;
            if (elapsed <= EXPAND_MS + HOLD_MS) {
                energyMain = 1.0f;
            } else {
                double tFade = (double) (elapsed - (EXPAND_MS + HOLD_MS)) / (double) FADE_MS;
                tFade = clamp01(tFade);
                energyMain = (float) (1.0 - smoothstep(tFade));
            }

            // 3) 主层 range/color 插值（不倒放）
            double interpolatedMaxDist = lerp(RANGE_START_MAX_DIST, RANGE_END_MAX_DIST, tExpand);
            double interpolatedOuterRad = lerp(RANGE_START_OUTER, RANGE_END_OUTER, tExpand);
            double interpolatedInnerRad = lerp(RANGE_START_INNER, RANGE_END_INNER, tExpand);

            float interpolatedR = lerpFloat(COLOR_START_R, COLOR_END_R, (float) tExpand);
            float interpolatedG = lerpFloat(COLOR_START_G, COLOR_END_G, (float) tExpand);
            float interpolatedB = lerpFloat(COLOR_START_B, COLOR_END_B, (float) tExpand);

            // =========================================================
            // ✅ A) 到点触发：当 now >= target.triggerMs -> 在该玩家点生成 Pulse
            // =========================================================
            if (entry.targets != null && !entry.targets.isEmpty()) {
                for (GeometryCache.ScanTarget t : entry.targets) {
                    if (!t.triggered && now >= t.triggerMs) {
                        t.triggered = true;
                        // 触发一个红色波（开始时间用 triggerMs 更“对拍”）
                        entry.pulses.add(new GeometryCache.Pulse(t.x, t.y, t.z, t.triggerMs));
                    }
                }
            }

            // 先画主层扫描（绿色/你这层）
            var terrainShader = new RaderEnergyShader()
                    .range(interpolatedMaxDist, interpolatedOuterRad, interpolatedInnerRad)
                    .color(interpolatedR, interpolatedG, interpolatedB)
                    .deadColor(0.0f, 0.0f, 0.0f)
                    .decayPow(2.4, 1.8)
                    .energy(energyMain)
                    .useCenterForShading(true)
                    .abyssRadius(interpolatedInnerRad);

            var spot = QuadFxAPI.spot()
                    .eye(eyeX, eyeY, eyeZ)
                    .center(entry.originX, entry.originY, entry.originZ)
                    .shader(terrainShader)
                    .maxDist(512.0)
                    .detail(1)
                    .clear();

            cache.renderCache(spot, 32.0, entry);

            VertexConsumer builder = bufferSource.getBuffer(RADAR_PASS);
            Matrix4f matrix = poseStack.last().pose();
            spot.render(builder, matrix, true);

            // =========================================================
            // ✅ B) 叠加渲染：红色波（半径 5），能量衰减，不明显
            // =========================================================
            if (entry.pulses != null && !entry.pulses.isEmpty()) {
                Iterator<GeometryCache.Pulse> it = entry.pulses.iterator();
                while (it.hasNext()) {
                    GeometryCache.Pulse p = it.next();
                    long age = now - p.startMs;
                    if (age < 0) age = 0;

                    // 过期清理
                    if (age >= PULSE_MS) {
                        it.remove();
                        continue;
                    }

                    // pulse 能量：1 -> 0（更柔）
                    double tp = clamp01((double) age / (double) PULSE_MS);
                    float energyPulse = (float)(1.0 - smoothstep(tp));

                    // 做一个“很淡”的红波：不明显但能看出扫到了
                    // 颜色建议：偏红但不要满红，alpha 也交给 shader energy
                    var pulseShader = new RaderEnergyShader()
                            .range(PULSE_RADIUS, PULSE_RADIUS, 0.0)     // maxDist/outer=5
                            .color(0.35f, 0.05f, 0.05f)                // ✅ 淡红
                            .deadColor(0.0f, 0.0f, 0.0f)
                            .decayPow(2.8, 2.0)                        // 衰减快一点更“回波”
                            .energy(energyPulse)
                            .useCenterForShading(true)
                            .abyssRadius(0.0);

                    var pulseSpot = QuadFxAPI.spot()
                            .eye(eyeX, eyeY, eyeZ)
                            .center(p.x, p.y, p.z)     // ✅ 红波从玩家点发出
                            .shader(pulseShader)
                            .maxDist(512)
                            .detail(1)
                            .clear();

                    // 复用同一套地形 quads：用 renderCache 的“中心距离筛选”即可，
                    // 但中心要换成 pulseSpot 的 center，所以这里直接手动筛一遍更简单：
                    // ——最小侵入：暂时用 entry.quads 全部丢给 pulseSpot，再让 shader 自己裁剪（会多一点开销）
                    // ——更省开销：加一个 renderCacheAt(centerX,centerY,centerZ) 的重载（推荐）
                    // 我这里给你“省改结构”的做法：加一个小范围 limit 过滤（5~7格）
                    double limit = PULSE_RADIUS + 1.5;
                    double maxSq = limit * limit;
                    for (QuadFxAPI.QuadJob job : entry.quads) {
                        double dx = job.cx - p.x;
                        double dy = job.cy - p.y;
                        double dz = job.cz - p.z;
                        if (dx*dx + dy*dy + dz*dz <= maxSq) {
                            pulseSpot.quad(job);
                        }
                    }

                    pulseSpot.render(builder, matrix, true);
                }
            }
        }

        if (bufferSource instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch(RADAR_PASS);
        }
    }

    private static double lerp(double start, double end, double t) {
        return start + (end - start) * t;
    }

    private static float lerpFloat(float start, float end, float t) {
        return start + (end - start) * t;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static double smoothstep(double t) {
        t = clamp01(t);
        return t * t * (3.0 - 2.0 * t);
    }
}
