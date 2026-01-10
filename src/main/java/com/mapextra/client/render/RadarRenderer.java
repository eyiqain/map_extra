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
import java.util.Random;

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

    // =========================================================
    // 时间节奏：1s 扩散完成 + 1s 保持 + 1s 衰减 = 3s 结束
    // =========================================================
    // 主扫描：1s扩散 + 2s停留 + 1s衰减 = 4s
    private static final long EXPAND_MS = 1000L;
    private static final long HOLD_MS   = 2000L;
    private static final long FADE_MS   = 1000L;
    private static final long TOTAL_MS  = EXPAND_MS + HOLD_MS + FADE_MS;

    // =========================================================
// 红色 pulse：半径 5
// 生命周期：0.5s 扩散 + 1.5s 停留 + 1.0s 衰减 = 3.0s
// =========================================================
    private static final double PULSE_RADIUS = 20.0;

    private static final long PULSE_EXPAND_MS = 500L;
    private static final long PULSE_HOLD_MS   = 1500L;
    private static final long PULSE_FADE_MS   = 1000L;
    private static final long PULSE_TOTAL_MS  = PULSE_EXPAND_MS + PULSE_HOLD_MS + PULSE_FADE_MS;

    // range 起始/结束
    private static final double RANGE_START_MAX_DIST = 5.0;
    private static final double RANGE_START_OUTER = 3.0;
    private static final double RANGE_START_INNER = 0.0;

    private static final double RANGE_END_MAX_DIST = 30.0;
    private static final double RANGE_END_OUTER = 30.0;
    private static final double RANGE_END_INNER = 25.0;

    // color 起始/结束（你的绿色系）
    private static final float COLOR_START_R = 0.1f;
    private static final float COLOR_START_G = 0.2f;
    private static final float COLOR_START_B = 0.1f;

    private static final float COLOR_END_R = 0.0f;
    private static final float COLOR_END_G = 0.05f;
    private static final float COLOR_END_B = 0.0f;

    // =========================================================
    // 空间数码粒子（绿色）
// - 仅扩散前 800ms 出现
// - 数量随扩散增加
// - 只生成在空气里
// =========================================================
    // 新增粒子配置常量（可直接放在RadarRenderer类中，与原有常量并列）
    private static final long DIGI_MS = 1000L;
    private static final int DIGI_TRY_MULT = 4;
    private static final float DIGI_SIZE = 0.25f; // 1/4方块大小（mc中方块为1单位）
    private static final long DIGI_LIFE_MS = 800L; // 粒子200ms内逐渐透明消失
    private static final double DIGI_FALL_SPEED = 2.5; // 粒子下坠速度（每秒2.5格，视觉更明显）
    private static final int DIGI_TOTAL_QUADS = 3800; // 提高总粒子数，让生成更多
    private static final int DIGI_MAX_QUADS_PER_FRAME = 300; // 提高每帧上限，避免数量被限制
    // 粒子出现的空间半径：跟主扫描一致（30格）或你想要的值
    private static final double DIGI_RADIUS_MAX = 25.0;


    // 生成尝试次数倍率：空气过滤会丢一些，乘个系数补回


    public static void render(PoseStack poseStack, MultiBufferSource bufferSource) {
        GeometryCache cache = GeometryCache.getInstance();
        if (cache.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        double eyeX = mc.player.getX();
        double eyeY = mc.player.getEyeY();
        double eyeZ = mc.player.getZ();

        long now = System.currentTimeMillis();

        VertexConsumer builder = bufferSource.getBuffer(RADAR_PASS);
        Matrix4f matrix = poseStack.last().pose();

        for (GeometryCache.CacheEntry entry : cache.getCacheQueue()) {
            long elapsed = now - entry.createTime;
            if (elapsed < 0) elapsed = 0;
            if (elapsed >= TOTAL_MS) continue;

            // 1) 扩散进度（0..1）只在前 1s 完成
            double tExpand = clamp01((double) elapsed / (double) EXPAND_MS);
            tExpand = smoothstep(tExpand);

            // 2) 主能量：前 2s=1，最后 1s 衰减到 0
            float energyMain;
            if (elapsed <= EXPAND_MS + HOLD_MS) {
                energyMain = 1.0f; // 前 3 秒保持满能量
            } else {
                double tFade = (double) (elapsed - (EXPAND_MS + HOLD_MS)) / (double) FADE_MS; // 最后 1 秒 1->0
                tFade = clamp01(tFade);
                energyMain = (float) (1.0 - smoothstep(tFade));
            }


            // 3) range/color 插值（只跟随扩散）
            double maxDist = lerp(RANGE_START_MAX_DIST, RANGE_END_MAX_DIST, tExpand);
            double outerRad = lerp(RANGE_START_OUTER, RANGE_END_OUTER, tExpand);
            double innerRad = lerp(RANGE_START_INNER, RANGE_END_INNER, tExpand);

            float rr = lerpFloat(COLOR_START_R, COLOR_END_R, (float) tExpand);
            float rg = lerpFloat(COLOR_START_G, COLOR_END_G, (float) tExpand);
            float rb = lerpFloat(COLOR_START_B, COLOR_END_B, (float) tExpand);

            // =========================================================
            // ✅ A) 到点触发 pulse：now >= triggerMs 就在玩家点生成红波
            // =========================================================
            if (entry.targets != null && !entry.targets.isEmpty()) {
                for (GeometryCache.ScanTarget t : entry.targets) {
                    if (!t.triggered && now >= t.triggerMs) {
                        t.triggered = true;
                        entry.pulses.add(new GeometryCache.Pulse(t.x, t.y, t.z, t.triggerMs));
                    }
                }
            }

            // =========================================================
            // ✅ B) 画主扫描层（绿色）
            // =========================================================
            var mainShader = new RaderEnergyShader()
                    .range(maxDist, outerRad, innerRad)
                    .color(rr, rg, rb)
                    .deadColor(0.02f, 0.2f, 0.02f)
                    .decayPow(2.4, 1.8)
                    .energy(energyMain)
                    .useCenterForShading(true)
                    .abyssRadius(innerRad);

            var mainSpot = QuadFxAPI.spot()
                    .eye(eyeX, eyeY, eyeZ)
                    .center(entry.originX, entry.originY, entry.originZ)
                    .shader(mainShader)
                    .maxDist(1024.0)
                    .detail(1)
                    .clear();

            cache.renderCache(mainSpot, 32.0, entry);
            mainSpot.render(builder, matrix, true);
            //粒子
            //renderGreenDigiQuads(builder, matrix, mc, entry, now);

            // =========================================================
            // ✅ C) 画 pulses（淡红波半径5），叠加显示
            // =========================================================
            if (entry.pulses != null && !entry.pulses.isEmpty()) {
                Iterator<GeometryCache.Pulse> it = entry.pulses.iterator();
                while (it.hasNext()) {
                    GeometryCache.Pulse p = it.next();
                    long age = now - p.startMs;
                    if (age < 0) age = 0;

                    // 3秒生命周期：0.5s扩散 + 1.5s停留 + 1s衰减
                    if (age >= PULSE_TOTAL_MS) {
                        it.remove();
                        continue;
                    }

// -------------------------------------------------
// 1) 扩散进度：前 0.5 秒 0->1（决定“半径生长”）
// -------------------------------------------------
                    double tPulseExpand = clamp01((double) age / (double) PULSE_EXPAND_MS);
                    tPulseExpand = smoothstep(tPulseExpand);

            // -------------------------------------------------
            // 2) 能量：前 2 秒保持 1，最后 1 秒衰减 1->0
            //    （0.5+1.5=2.0s）
            // -------------------------------------------------
                    float energyPulse;
                    long pulseHoldEnd = PULSE_EXPAND_MS + PULSE_HOLD_MS;
                    if (age <= pulseHoldEnd) {
                        energyPulse = 1.0f;
                    } else {
                        double tFade = (double) (age - pulseHoldEnd) / (double) PULSE_FADE_MS;
                        tFade = clamp01(tFade);
                        energyPulse = (float) (1.0 - smoothstep(tFade));
                    }

                    // -------------------------------------------------
                    // 3) ✅ 让红波“长出来”：半径从小到 PULSE_RADIUS
                    //    这里外圈 outerRad 跟随扩散，innerRad 给一点点厚度
                    // -------------------------------------------------
                    double pulseOuter = lerp(0.6, PULSE_RADIUS, tPulseExpand);   // 起始别为0，避免几何过薄
                    double pulseInner = Math.max(0.0, pulseOuter - 1.2);         // 让红波有一点“环厚”

                    var pulseShader = new RaderEnergyShader()
                            .range(PULSE_RADIUS, pulseOuter, pulseInner) // ✅ maxDist=5 固定，outer/inner 随时间变大
                            .color(0.60f, 0.02f, 0.01f)                 // 更淡一点
                            .deadColor(0.02f, 0.10f, 0.02f)
                            .decayPow(2.6, 3.2)
                            .energy(energyPulse)
                            .useCenterForShading(true)
                            .abyssRadius(0.0);

                    var pulseSpot = QuadFxAPI.spot()
                            .eye(eyeX, eyeY, eyeZ)
                            .center(p.x, p.y, p.z)
                            .shader(pulseShader)
                            .maxDist(1024.0)
                            .detail(1)
                            .clear();

                    // 为了省改结构，这里直接小范围筛 job（半径5附近）
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
    // 修改后的粒子渲染核心函数
    private static void renderGreenDigiQuads(VertexConsumer builder, Matrix4f matrix,
                                             Minecraft mc, GeometryCache.CacheEntry entry, long now) {
        long elapsed = now - entry.createTime;
        if (elapsed < 0) elapsed = 0;

        // ✅ 修改 1：延长渲染时间窗口
        // 允许额外运行 DIGI_LIFE_MS 的时间，让最后一批粒子能自然淡出
        if (elapsed >= DIGI_MS + DIGI_LIFE_MS) return;

        // 0..1 扩散进度（越晚球壳越大）
        // 这里会自动 clamp 到 1.0，所以在 2000ms~2800ms 期间，球壳半径会保持最大，不会继续扩大，符合物理直觉
        double t = clamp01((double) elapsed / (double) DIGI_MS);

        // 当前球壳半径
        double rNow = DIGI_RADIUS_MAX * t;

        // 本帧生成数量
        int quadsThisFrame = estimateQuadsThisFrame(t);
        if (quadsThisFrame <= 0) return;

        // 时间桶逻辑
        long bucket = now / DIGI_LIFE_MS;
        long ageInBucket = now - bucket * DIGI_LIFE_MS; // 0..799ms

        // ✅ 修改 2：检查粒子“出生时间”
        // 粒子的绝对出生时间 = 当前时间 - 它的存活时长(ageInBucket)
        long birthTimeAbs = now - ageInBucket;
        // 生成停止的绝对时间 = 雷达创建时间 + 2000ms
        long stopGenTimeAbs = entry.createTime + DIGI_MS;

        // 如果这个粒子的计算出生时间晚于停止生成的时间，说明它是在“加时赛”阶段凭空产生的，
        // 我们不应该渲染它。我们只渲染那些在 stopGenTimeAbs 之前就已经存在的粒子。
        if (birthTimeAbs > stopGenTimeAbs) return;

        // 随机种子：保证不同雷达/不同帧粒子分布不一致
        long seed = bucket ^ (entry.createTime * 31L)
                ^ Double.doubleToLongBits(entry.originX * 0.13)
                ^ Double.doubleToLongBits(entry.originZ * 0.37);
        Random rnd = new Random(seed);

        // 初始深绿色，随存活时间衰减透明度
        float rCol = 0.01f;
        float gCol = 0.2f;
        float bCol = 0.01f;
        double lifeT = clamp01((double) ageInBucket / (double) DIGI_LIFE_MS);
        float lifeEnergy = (float) (1.0 - smoothstep(lifeT));
        float aCol = 1.0f * lifeEnergy; // ✅ 这里会正确地从 1 淡出到 0

        // ... 后面的代码保持不变 ...
        int needed = quadsThisFrame;
        int tries = quadsThisFrame * DIGI_TRY_MULT;

        for (int i = 0; i < tries && needed > 0; i++) {
            // ... 原有的生成逻辑 ...
            double shell = 1.5 + 0.1 * rNow;
            double rr = Math.max(0.5, rNow - shell + rnd.nextDouble() * (shell * 2.0));

            double u = rnd.nextDouble();
            double v = rnd.nextDouble();
            double theta = u * Math.PI * 2.0;
            double phi = Math.acos(2.0 * v - 1.0);

            double ox = rr * Math.sin(phi) * Math.cos(theta);
            double oy = rr * Math.cos(phi) * 0.85;
            double oz = rr * Math.sin(phi) * Math.sin(theta);

            double x = entry.originX + ox;
            double y = entry.originY + 1.0 + oy;
            double z = entry.originZ + oz;

            double fallTimeSec = ageInBucket / 1000.0;
            double fallDist = DIGI_FALL_SPEED * fallTimeSec;
            y -= fallDist;

            net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(x, y, z);
            if (!mc.level.getBlockState(bp).isAir()) continue;

            addBillboardTowardPoint(builder, matrix,
                    x, y, z,
                    entry.originX, entry.originY + 1.0, entry.originZ,
                    DIGI_SIZE, rCol, gCol, bCol, aCol);

            needed--;
        }
    }

    // 修改后的每帧粒子数量估算：随球壳扩大（t增大）生成数量显著增多
    private static int estimateQuadsThisFrame(double t) {
        // 提高基础生成量，让粒子更多
        double base = (double) DIGI_TOTAL_QUADS / (double) (DIGI_MS / 12.0); // 分母减小，基础量提高
        // 调整系数：t^2分布，后期（球壳大）生成数量远多于前期
        double k = 0.6 + 2.8 * (t * t); // 系数提高，数量增长更明显
        int q = (int) Math.round(base * k);

        // 边界限制
        if (q < 3) q = 3;
        if (q > DIGI_MAX_QUADS_PER_FRAME) q = DIGI_MAX_QUADS_PER_FRAME;
        return q;
    }
    private static void addBillboardTowardPoint(VertexConsumer b, Matrix4f m,
                                                double x, double y, double z,
                                                double tx, double ty, double tz,
                                                float size,
                                                float r, float g, float bl, float a) {
        // forward = 指向目标（中心）
        double fx = tx - x;
        double fy = ty - y;
        double fz = tz - z;

        double fl = Math.sqrt(fx*fx + fy*fy + fz*fz);
        if (fl < 1e-4) return;
        fx /= fl; fy /= fl; fz /= fl;

        // up 选世界上方向，避免接近竖直时退化
        double ux = 0, uy = 1, uz = 0;
        double dot = fx*ux + fy*uy + fz*uz;
        if (Math.abs(dot) > 0.92) { // 太平行就换一个 up
            ux = 1; uy = 0; uz = 0;
        }

        // right = normalize(cross(forward, up))
        double rx = fy*uz - fz*uy;
        double ry = fz*ux - fx*uz;
        double rz = fx*uy - fy*ux;
        double rl = Math.sqrt(rx*rx + ry*ry + rz*rz);
        if (rl < 1e-4) return;
        rx = (rx / rl) * size;
        ry = (ry / rl) * size;
        rz = (rz / rl) * size;

        // up2 = cross(right, forward)
        double ux2 = ry*fz - rz*fy;
        double uy2 = rz*fx - rx*fz;
        double uz2 = rx*fy - ry*fx;
        double ul = Math.sqrt(ux2*ux2 + uy2*uy2 + uz2*uz2);
        if (ul < 1e-4) return;
        ux2 = (ux2 / ul) * size;
        uy2 = (uy2 / ul) * size;
        uz2 = (uz2 / ul) * size;

        // quad 四角
        double x1 = x - rx - ux2, y1 = y - ry - uy2, z1 = z - rz - uz2;
        double x2 = x + rx - ux2, y2 = y + ry - uy2, z2 = z + rz - uz2;
        double x3 = x + rx + ux2, y3 = y + ry + uy2, z3 = z + rz + uz2;
        double x4 = x - rx + ux2, y4 = y - ry + uy2, z4 = z - rz + uz2;

        // 写顶点（你 RadarRenderer 里需要有 addQuad()，没有就复制一份 QuadFxAPI 的）
        addQuad(b, m, x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4, r,g,bl,a);
    }
    private static void addQuad(VertexConsumer builder, Matrix4f matrix,
                                double x1,double y1,double z1,
                                double x2,double y2,double z2,
                                double x3,double y3,double z3,
                                double x4,double y4,double z4,
                                float r,float g,float b,float a) {
        builder.vertex(matrix, (float)x1,(float)y1,(float)z1).color(r,g,b,a).endVertex();
        builder.vertex(matrix, (float)x2,(float)y2,(float)z2).color(r,g,b,a).endVertex();
        builder.vertex(matrix, (float)x3,(float)y3,(float)z3).color(r,g,b,a).endVertex();
        builder.vertex(matrix, (float)x4,(float)y4,(float)z4).color(r,g,b,a).endVertex();
    }

}
