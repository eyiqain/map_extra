package com.mapextra.client.render;

import com.mapextra.client.render.QuadFxAPI.Color;
import com.mapextra.client.render.QuadFxAPI.FaceDir;
import com.mapextra.client.render.QuadFxAPI.Sample;
import com.mapextra.client.render.QuadFxAPI.Shader;

/**
 * 雷达扫描特效 Shader
 * 逻辑源自 OriginalBorderSpotShader，专用于雷达显示
 */
// =========================================================
// 新增：能量衰减版 RaderEnergyShader（不影响原 RaderEnergyShader）
// 特点：
// - 形状/范围逻辑保持一致
// - 额外引入“能量 energy(0..1)”对 alpha 和颜色同时衰减
// - 可用 life01(外部传入) 或 ageMs/lifeMs(内部计时) 两种方式驱动
// =========================================================
public final class RaderEnergyShader implements Shader {

    // ---- 基础颜色参数（同原版）----
    private float red = 0.0f, green = 0.0f, blue = 0.05f;

    // ---- 范围参数（同原版）----
    private double maxDistSq = 144.0;
    private double outerRad = 3.5;
    private double innerRad = 2.2;

    private boolean useCenterForShading = true;
    private double abyssRadius = 5.0;

    // =========================================================
    // 能量衰减参数（新增）
    // =========================================================

    // 方式 A：外部直接给 energy/life（推荐：你在 RadarRenderer 算好）
    private float energy01 = 1.0f; // 1=满能量, 0=没了
    // 方式 B：shader 内部计时（省改外部）
    private long birthMs = -1L;
    private long lifeMs = 4000L; // 总寿命（用于衰减曲线）
    private boolean useInternalClock = false;

    // 衰减曲线强度：越大越“快衰”
    private double decayPow = 2.2;     // alpha 衰减指数
    private double colorDecayPow = 1.6; // 颜色衰减指数

    // 颜色衰减目标色（能量耗尽时会向它靠拢）
    private float deadR = 0.0f, deadG = 0.0f, deadB = 0.0f;

    // 能量对 alpha 的额外权重（保留原本的 alpha 结构，再乘能量）
    private float alphaScale = 1.0f;

    // =========================================================
    // 链式配置：基础参数（保持习惯一致）
    // =========================================================
    public RaderEnergyShader color(float r, float g, float b) {
        this.red = r; this.green = g; this.blue = b;
        return this;
    }

    public RaderEnergyShader range(double maxDist, double outer, double inner) {
        this.maxDistSq = maxDist * maxDist;
        this.outerRad = outer;
        this.innerRad = inner;
        return this;
    }

    public RaderEnergyShader outerRadius(double outerRad) { this.outerRad = outerRad; return this; }
    public RaderEnergyShader innerRadius(double innerRad) { this.innerRad = innerRad; return this; }

    public RaderEnergyShader useCenterForShading(boolean useCenter) {
        this.useCenterForShading = useCenter;
        return this;
    }

    public RaderEnergyShader abyssRadius(double abyssRadius) {
        this.abyssRadius = abyssRadius;
        return this;
    }

    // =========================================================
    // 链式配置：能量衰减（新增）
    // =========================================================

    /** 外部直接给能量（1=满, 0=无），推荐每帧更新一次 */
    public RaderEnergyShader energy(float e01) {
        this.energy01 = clamp01f(e01);
        this.useInternalClock = false;
        return this;
    }

    /** 内部计时：设置出生时间 + 寿命（ms） */
    public RaderEnergyShader lifetimeMs(long lifeMs) {
        this.lifeMs = Math.max(1L, lifeMs);
        this.useInternalClock = true;
        if (this.birthMs < 0) this.birthMs = System.currentTimeMillis();
        return this;
    }

    /** 手动重置出生时间（比如 entry 复用同一个 shader 实例时） */
    public RaderEnergyShader resetBirth() {
        this.birthMs = System.currentTimeMillis();
        this.useInternalClock = true;
        return this;
    }

    /** alpha 总乘子（额外控制整体透明） */
    public RaderEnergyShader alphaScale(float s) {
        this.alphaScale = s;
        return this;
    }

    /** 衰减曲线强度：pow 越大越快变暗/变透明 */
    public RaderEnergyShader decayPow(double alphaPow, double colorPow) {
        this.decayPow = Math.max(0.1, alphaPow);
        this.colorDecayPow = Math.max(0.1, colorPow);
        return this;
    }

    /** 能量耗尽时的目标颜色（默认黑），可以设成深蓝更“能量散去” */
    public RaderEnergyShader deadColor(float r, float g, float b) {
        this.deadR = r; this.deadG = g; this.deadB = b;
        return this;
    }

    // =========================================================
    // 核心：着色逻辑（主体同原版，最后叠加能量衰减）
    // =========================================================
    @Override
    public QuadFxAPI.Color shade(QuadFxAPI.Sample s) {

        // ---- 0) 计算 energy（0..1）----
        float e = useInternalClock ? computeInternalEnergy() : energy01;
        if (e <= 0.001f) return null;

        // 1) 坐标偏移（同原版）
        double dx, dy, dz;
        if (useCenterForShading) {
            dx = s.x - s.centerX;
            dy = s.y - s.centerY;
            dz = s.z - s.centerZ;
        } else {
            dx = s.x - s.eyeX;
            dy = s.y - s.eyeY;
            dz = s.z - s.eyeZ;
        }

        // 2) 总距离校验
        double totalDistSq = dx*dx + dy*dy + dz*dz;
        if (totalDistSq >= maxDistSq) return null;
        double totalDist = Math.sqrt(totalDistSq);

        // 3) spreadSq（同原版）
        double spreadSq;
        if (s.dirHint == QuadFxAPI.FaceDir.NORTH || s.dirHint == QuadFxAPI.FaceDir.SOUTH) {
            spreadSq = useCenterForShading ?
                    (s.x - s.centerX)*(s.x - s.centerX) + (s.y - s.centerY)*(s.y - s.centerY) :
                    (s.x - s.eyeX)*(s.x - s.eyeX) + (s.y - s.eyeY)*(s.y - s.eyeY);
        } else if (s.dirHint == QuadFxAPI.FaceDir.EAST || s.dirHint == QuadFxAPI.FaceDir.WEST) {
            spreadSq = useCenterForShading ?
                    (s.z - s.centerZ)*(s.z - s.centerZ) + (s.y - s.centerY)*(s.y - s.centerY) :
                    (s.z - s.eyeZ)*(s.z - s.eyeZ) + (s.y - s.eyeY)*(s.y - s.eyeY);
        } else {
            spreadSq = useCenterForShading ?
                    (s.x - s.centerX)*(s.x - s.centerX) + (s.z - s.centerZ)*(s.z - s.centerZ) :
                    (s.x - s.eyeX)*(s.x - s.eyeX) + (s.z - s.eyeZ)*(s.z - s.eyeZ);
        }

        // 4) 外圈形状校验
        double outerRadiusSq = outerRad * outerRad;
        double innerRadiusSq = innerRad * innerRad;
        if (spreadSq >= outerRadiusSq) return null;
        double spreadDist = Math.sqrt(spreadSq);

        // 5) 外圈渐变强度（同原版）
        double outerSpreadFactor = spreadDist / outerRad;
        double outerShape = 1.0 - Math.pow(outerSpreadFactor, 2.0);
        float baseAlpha = (float) (0.35f * outerShape);

        // 6) 黑色核心区强度（同原版）
        float abyssAlpha = 0.0f;
        if (totalDist < abyssRadius && spreadSq < innerRadiusSq) {
            double depthFactor = totalDist / abyssRadius;
            float depthStrength = (float) (0.9 * (1.0 - Math.pow(depthFactor, 3.5)));

            double innerSpreadFactor = spreadDist / innerRad;
            double innerShape = 1.0 - Math.pow(innerSpreadFactor, 3.0);

            abyssAlpha = (float) (depthStrength * innerShape);
        }

        // 7) 最终透明度（原版）
        float finalAlpha = Math.max(baseAlpha, abyssAlpha);

        // 8) 距离淡出（原版）
        double maxDist = Math.sqrt(maxDistSq);
        double fadeStart = maxDist - 2.0;
        if (totalDist > fadeStart) {
            double fade = (maxDist - totalDist) / (maxDist - fadeStart);
            finalAlpha *= (float) fade;
        }

        // =========================================================
        // 9) ✅ 能量衰减：只在这里乘，不改变形状/半径（不会倒放）
        //    alpha 衰减：e^decayPow（更像能量耗散）
        // =========================================================
        float eAlpha = (float)Math.pow(e, decayPow);
        finalAlpha *= (eAlpha * alphaScale);

        if (finalAlpha <= 0.005f) return null;

        // 10) 雾化提亮（原版）
        double mistFactor = Math.min(1.0, totalDist / (maxDist * 0.6));
        float r = red + (float)(mistFactor * 0.3);
        float g = green + (float)(mistFactor * 0.3);
        float b = blue + (float)(mistFactor * 0.5);

        // =========================================================
        // 11) ✅ 颜色能量衰减：颜色向 deadColor 收敛（更“能量散去”）
        // =========================================================
        float eCol = (float)Math.pow(e, colorDecayPow);
        r = lerp(deadR, r, eCol);
        g = lerp(deadG, g, eCol);
        b = lerp(deadB, b, eCol);

        return QuadFxAPI.Color.rgba(r, g, b, finalAlpha);
    }

    // ---- internal energy curve ----
    private float computeInternalEnergy() {
        if (birthMs < 0) birthMs = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        long age = now - birthMs;
        if (age <= 0) return 1.0f;
        if (age >= lifeMs) return 0.0f;

        // 更像“能量衰减”的曲线：先慢后快
        // t: 0..1
        double t = (double)age / (double)lifeMs;
        // energy: 1 -> 0
        double e = 1.0 - smoothstep(t);
        return (float) e;
    }

    private static double smoothstep(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    private static float clamp01f(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}