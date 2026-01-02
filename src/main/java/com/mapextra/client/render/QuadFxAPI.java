package com.mapextra.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * QuadFxAPI（单倍数 detail 版）
 *
 * 目标：
 * - 外部只给一个 detail：
 *   detail=1 -> 1.0 方块格
 *   detail=2 -> 0.5 方块格
 *   detail=3 -> 0.25 方块格
 *   ... 每+1细一倍
 *
 * - 内部保证：无论墙切片还是地面网格，最终都是“方格”而不是长方形
 * - 外部负责扩散/动画：shader 只负责一帧的着色
 */
public final class QuadFxAPI {

    private QuadFxAPI() {}

    // =========================================================
    // 基础类型
    // =========================================================
    public enum FaceDir { NORTH, SOUTH, WEST, EAST, UP, DOWN }

    public static final class Color {
        public float r, g, b, a;
        public Color(float r,float g,float b,float a){ this.r=r; this.g=g; this.b=b; this.a=a; }
        public static Color rgba(float r,float g,float b,float a){ return new Color(r,g,b,a); }
    }

    /** 任意四边形任务（API内部渲染的最终输入） */
    public static final class QuadJob {
        public final double x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4;
        public final double cx,cy,cz;     // 中心点（用于排序 + shader）
        public final double distSq;       // 排序用
        public final FaceDir dirHint;     // 给 shader 的提示（决定spread轴）；可为null

        public QuadJob(double x1,double y1,double z1,
                       double x2,double y2,double z2,
                       double x3,double y3,double z3,
                       double x4,double y4,double z4,
                       double cx,double cy,double cz,
                       double distSq,
                       FaceDir dirHint) {
            this.x1=x1; this.y1=y1; this.z1=z1;
            this.x2=x2; this.y2=y2; this.z2=z2;
            this.x3=x3; this.y3=y3; this.z3=z3;
            this.x4=x4; this.y4=y4; this.z4=z4;
            this.cx=cx; this.cy=cy; this.cz=cz;
            this.distSq=distSq;
            this.dirHint=dirHint;
        }
    }

    // =========================================================
    // 入口 A：普通绘制 flat()
    // =========================================================
    public static Flat flat() { return new Flat(); }

    public static final class Flat {
        private Color color = Color.rgba(1,1,1,0.4f);

        // 无限高墙侧面
        private boolean wallHeightEnabled = false;
        private float wallMinY = -64f;
        private float wallMaxY = 320f;

        private final List<QuadJob> quads = new ArrayList<>();

        private Flat() {}

        public Flat color(float r,float g,float b,float a){ this.color = Color.rgba(r,g,b,a); return this; }

        public Flat wallHeight(float minY, float maxY){
            this.wallHeightEnabled = true;
            this.wallMinY = minY;
            this.wallMaxY = maxY;
            return this;
        }

        public Flat clear(){ quads.clear(); return this; }

        public Flat quad(QuadJob q){ quads.add(q); return this; }

        public Flat face(int x,int y,int z, FaceDir dir, double eyeX,double eyeY,double eyeZ){
            QuadJob q = blockFaceToQuad(x,y,z,dir, eyeX,eyeY,eyeZ, wallHeightEnabled, wallMinY, wallMaxY);
            if (q != null) quads.add(q);
            return this;
        }

        public void render(VertexConsumer builder, Matrix4f matrix, boolean withColor){
            quads.sort(Comparator.comparingDouble(q -> q.distSq));
            for (QuadJob q : quads){
                addQuad(builder, matrix,
                        q.x1,q.y1,q.z1, q.x2,q.y2,q.z2, q.x3,q.y3,q.z3, q.x4,q.y4,q.z4,
                        color.r,color.g,color.b,color.a,
                        withColor);
            }
        }
    }

    // =========================================================
    // 入口 B：特效绘制 spot()
    // =========================================================
    public static Spot spot() { return new Spot(); }

    public interface Shader {
        Color shade(Sample s);
    }
    //针对楼梯等

    public static final class Sample {
        public final double x,y,z;            // 子面片中心
        public final double eyeX,eyeY,eyeZ;   // 视点
        public final double centerX,centerY,centerZ; // 特效中心（ring/吸入常用）
        public final FaceDir dirHint;
        public Sample(double x,double y,double z,
                      double eyeX,double eyeY,double eyeZ,
                      double centerX,double centerY,double centerZ,
                      FaceDir dirHint){
            this.x=x; this.y=y; this.z=z;
            this.eyeX=eyeX; this.eyeY=eyeY; this.eyeZ=eyeZ;
            this.centerX=centerX; this.centerY=centerY; this.centerZ=centerZ;
            this.dirHint=dirHint;
        }
    }

    public static final class Spot {
        // --- 核心：一个倍数 detail 控制所有切面尺度 ---
        // detail=1 -> 1.0
        // detail=2 -> 0.5
        // detail=3 -> 0.25
        private int detail = 2;

        private double maxDist = 12.0;
        private float alphaEps = 0.005f;
        private final double fadeStartFactor = 0.8;

        // 参考点
        private boolean eyeSet = false;
        private double eyeX,eyeY,eyeZ;

        private boolean centerSet = false;
        private double centerX,centerY,centerZ;

        // shader 不默认 new，避免类名不存在就炸
        private Shader shader = null;

        private final List<QuadJob> quads = new ArrayList<>();

        private Spot(){}

        // ---------- 链式配置 ----------
        public Spot eye(double x,double y,double z){ this.eyeX=x; this.eyeY=y; this.eyeZ=z; this.eyeSet=true; return this; }
        public Spot center(double x,double y,double z){ this.centerX=x; this.centerY=y; this.centerZ=z; this.centerSet=true; return this; }

        /** detail=2 => 0.5格；detail=3 => 0.25格 */
        public Spot detail(int d){ this.detail = Math.max(1, d); return this; }

        public Spot maxDist(double d){ this.maxDist = d; return this; }
        public Spot alphaEpsilon(float eps){ this.alphaEps = eps; return this; }

        public Spot shader(Shader shader){ this.shader = shader; return this; }

        public Spot clear(){ quads.clear(); return this; }

        public Spot quad(QuadJob q){ quads.add(q); return this; }

        public Spot face(int x,int y,int z, FaceDir dir){
            ensureEye();
            QuadJob q = blockFaceToQuad(x,y,z,dir, eyeX,eyeY,eyeZ, false,0,0);
            if (q != null) quads.add(q);
            return this;
        }

        // =========================================================
// 新增：精确绘制任意大小的水平面 (用于楼梯台阶)
// =========================================================
        public Spot rect(double x1, double z1, double x2, double z2, double y, FaceDir dir) {
            ensureEye();
            // 构造 QuadJob
            double cx = (x1 + x2) / 2.0;
            double cz = (z1 + z2) / 2.0;
            double dx = cx - eyeX; double dy = y - eyeY; double dz = cz - eyeZ;
            double distSq = dx*dx + dy*dy + dz*dz;

            // 顶点顺序遵循逆时针 (UP朝向)
            // (x1, z1) -> (x2, z1) -> (x2, z2) -> (x1, z2)
            quads.add(new QuadJob(
                    x1, y, z1,
                    x2, y, z1,
                    x2, y, z2,
                    x1, y, z2,
                    cx, y, cz, distSq, dir
            ));
            return this;
        }

        // =========================================================
// 新增：精确绘制任意大小的垂直墙面 (用于楼梯侧缝)
// =========================================================
        public Spot wallRect(double x1, double z1, double x2, double z2, double yBottom, double yTop, FaceDir dir) {
            ensureEye();
            double cx = (x1 + x2) / 2.0;
            double cz = (z1 + z2) / 2.0;
            double cy = (yBottom + yTop) / 2.0;
            double distSq = (cx-eyeX)*(cx-eyeX) + (cy-eyeY)*(cy-eyeY) + (cz-eyeZ)*(cz-eyeZ);

            // 垂直墙面顶点构造 (简化版，假设轴对齐)
            // 注意：这里为了简单，假设是直墙。对于楼梯内部侧面，通常是固定 x 或固定 z
            quads.add(new QuadJob(
                    x1, yBottom, z1,
                    x2, yBottom, z2,
                    x2, yTop,    z2,
                    x1, yTop,    z1,
                    cx, cy, cz, distSq, dir
            ));
            return this;
        }

        public Spot wallColumn(int x, int z, FaceDir dir, double yMin, double yMax){
            ensureEye();
            if (dir == FaceDir.UP || dir == FaceDir.DOWN) return this;

            // ❌ 错误：不要在这里用 cell()，否则会被切两次
            // double cell = cell();

            // ✅以此修正：这里固定按 1.0 (标准方块高度) 切片
            // 剩下的 detail 细分工作交给 render() 里的循环去做
            double unit = 1.0;

            double start = Math.min(yMin, yMax);
            double end   = Math.max(yMin, yMax);

            // 对齐到整数网格 (1.0)
            double y = Math.floor(start);

            // 如果起点不在整数位，第一片可能会切多一点，但通常建议 yMin/yMax 也是对齐的
            // 或者直接用 floorToGrid(start, unit)

            for (; y < end; y += unit){
                // 确保不要切出范围，也不要切超过 1.0 高度
                double sliceBottom = Math.max(start, y);
                double sliceTop = Math.min(end, y + unit);

                // 如果这一段完全在范围外（比如 floor向下取整导致的），跳过
                if (sliceBottom >= sliceTop) continue;

                QuadJob q = wallSliceToQuad(x, z, dir, sliceBottom, sliceTop, eyeX,eyeY,eyeZ);
                if (q != null) quads.add(q);
            }
            return this;
        }

        // =========================================================
        // Mesh helper 2：ringMesh（水平光圈网格）
        // - radialStep 不再外部传，内部自动用 cell()
        // =========================================================
        public Spot ringMesh(double yPlane, double rMin, double rMax, int segments){
            ensureEye();
            ensureCenter();

            double cell = cell();
            if (segments < 8) segments = 8;

            double startR = Math.max(0.0, Math.min(rMin, rMax));
            double endR   = Math.max(rMin, rMax);

            // 对齐到 cell 网格
            double r = floorToGrid(startR, cell);
            double dTheta = (Math.PI * 2.0) / segments;

            for (; r < endR; r += cell){
                double r2 = Math.min(endR, r + cell);

                for (int i=0; i<segments; i++){
                    double a1 = i * dTheta;
                    double a2 = (i+1) * dTheta;

                    double x1 = centerX + Math.cos(a1) * r2;
                    double z1 = centerZ + Math.sin(a1) * r2;

                    double x2 = centerX + Math.cos(a2) * r2;
                    double z2 = centerZ + Math.sin(a2) * r2;

                    double x3 = centerX + Math.cos(a2) * r;
                    double z3 = centerZ + Math.sin(a2) * r;

                    double x4 = centerX + Math.cos(a1) * r;
                    double z4 = centerZ + Math.sin(a1) * r;

                    double cx = (x1+x2+x3+x4) * 0.25;
                    double cz = (z1+z2+z3+z4) * 0.25;
                    double cy = yPlane;

                    double dx = cx - eyeX, dy = cy - eyeY, dz = cz - eyeZ;
                    double distSq = dx*dx + dy*dy + dz*dz;

                    quads.add(new QuadJob(
                            x1,yPlane,z1,
                            x2,yPlane,z2,
                            x3,yPlane,z3,
                            x4,yPlane,z4,
                            cx,cy,cz,
                            distSq,
                            FaceDir.UP
                    ));
                }
            }
            return this;
        }
        // =========================================================
// Mesh helper 3：floorGrid（水平方格网格）
// 专门用于把地板当墙面画，保持 MC 的方块网格感
// =========================================================
        public Spot floorGrid(int xMin, int xMax, int zMin, int zMax, double yPlane, FaceDir dir) {
            ensureEye();
            // 只允许 UP (地板) 或 DOWN (天花板)
            if (dir != FaceDir.UP && dir != FaceDir.DOWN) return this;

            // 确保 min < max
            int x1 = Math.min(xMin, xMax);
            int x2 = Math.max(xMin, xMax);
            int z1 = Math.min(zMin, zMax);
            int z2 = Math.max(zMin, zMax);

            // 双重循环：模拟铺地砖，一块一块铺
            // 每次生成一个 1x1 的标准方块面，后续交给 render() 去做 detail 细分
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {

                    // 构造 1x1 的 QuadJob
                    // 顶点顺序需要符合逆时针或 API 标准
                    double qx1, qz1, qx2, qz2, qx3, qz3, qx4, qz4;

                    // 下面坐标对应 blockFaceToQuad 的 UP 逻辑：
                    // (x, z) -> (x+1, z) -> (x+1, z+1) -> (x, z+1)
                    // 这样纹理方向才和原本的方块一致
                    qx1 = x;     qz1 = z;
                    qx2 = x + 1; qz2 = z;
                    qx3 = x + 1; qz3 = z + 1;
                    qx4 = x;     qz4 = z + 1;

                    // 中心点
                    double cx = x + 0.5;
                    double cz = z + 0.5;

                    // 计算距离用于排序
                    double dx = cx - eyeX;
                    double dy = yPlane - eyeY;
                    double dz = cz - eyeZ;
                    double distSq = dx*dx + dy*dy + dz*dz;

                    quads.add(new QuadJob(
                            qx1, yPlane, qz1,
                            qx2, yPlane, qz2,
                            qx3, yPlane, qz3,
                            qx4, yPlane, qz4,
                            cx, yPlane, cz,
                            distSq,
                            dir // 传入 UP 或 DOWN，Shader 里会用到
                    ));
                }
            }
            return this;
        }

        // =========================================================
        // 渲染：排序 -> 内部统一细分到 cell 尺度 -> shader -> 写顶点
        // =========================================================
        public void render(VertexConsumer builder, Matrix4f matrix, boolean withColor){
            ensureEye();
            if (shader == null) throw new IllegalStateException("Spot requires a Shader.");

            quads.sort(Comparator.comparingDouble(q -> q.distSq));

            double maxDistSq = maxDist * maxDist;
            double fadeStart = maxDist * fadeStartFactor;

            // 子细分：保证每个1x1面切成 (2^(detail-1)) x (2^(detail-1))
            // detail=2 -> div=2 -> 0.5
            // detail=3 -> div=4 -> 0.25
            int div = div();
            double step = 1.0 / div;

            for (QuadJob q : quads){
                double dx0=q.cx-eyeX, dy0=q.cy-eyeY, dz0=q.cz-eyeZ;
                double d0=dx0*dx0+dy0*dy0+dz0*dz0;
                if (d0 >= maxDistSq) continue;

                for (int iy=0; iy<div; iy++){
                    double ty1=iy*step, ty2=(iy+1)*step;
                    for (int ix=0; ix<div; ix++){
                        double tx1=ix*step, tx2=(ix+1)*step;

                        double[] p1 = bilerpPoint(q, tx2, ty2);
                        double[] p2 = bilerpPoint(q, tx1, ty2);
                        double[] p3 = bilerpPoint(q, tx1, ty1);
                        double[] p4 = bilerpPoint(q, tx2, ty1);

                        double cx = (p1[0]+p2[0]+p3[0]+p4[0]) * 0.25;
                        double cy = (p1[1]+p2[1]+p3[1]+p4[1]) * 0.25;
                        double cz = (p1[2]+p2[2]+p3[2]+p4[2]) * 0.25;

                        double dist = Math.sqrt((cx-eyeX)*(cx-eyeX) + (cy-eyeY)*(cy-eyeY) + (cz-eyeZ)*(cz-eyeZ));
                        if (dist >= maxDist) continue;

                        float fadeMul = 1.0f;
                        if (dist > fadeStart){
                            double t = (maxDist - dist) / (maxDist - fadeStart);
                            if (t <= 0) continue;
                            fadeMul = (float)t;
                        }

                        Color c = shader.shade(new Sample(
                                cx,cy,cz,
                                eyeX,eyeY,eyeZ,
                                centerSet?centerX:cx, centerSet?centerY:cy, centerSet?centerZ:cz,
                                q.dirHint
                        ));
                        if (c == null) continue;

                        float a = c.a * fadeMul;
                        if (a <= alphaEps) continue;

                        addQuad(builder, matrix,
                                p1[0],p1[1],p1[2],
                                p2[0],p2[1],p2[2],
                                p3[0],p3[1],p3[2],
                                p4[0],p4[1],p4[2],
                                c.r,c.g,c.b,a,
                                withColor);
                    }
                }
            }
        }

        private void ensureEye(){
            if (!eyeSet) throw new IllegalStateException("spot().eye(x,y,z) is required.");
        }
        private void ensureCenter(){
            if (!centerSet) throw new IllegalStateException("ringMesh requires spot().center(x,y,z).");
        }

        private int div() {
            // detail=1 -> 1
            // detail=2 -> 2
            // detail=3 -> 4
            return 1 << (detail - 1);
        }

        private double cell() {
            return 1.0 / div();
        }

        private static double floorToGrid(double v, double grid){
            return Math.floor(v / grid) * grid;
        }
    }

    // =========================================================
    // Shader：墙光斑 + 地面光斑 (全向通用版)
    // =========================================================
    // =========================================================
    // 核心：完整可配置的 FalloffSpotShader
    // =========================================================
    public static final class FalloffSpotShader implements Shader {
        // 基础颜色参数
        private float red = 0.0f, green = 0.0f, blue = 0.05f;

        // 范围参数
        private double maxDistSq = 144.0; // 12^2（默认最大渲染范围）
        private double outerRad = 3.5;    // 外圈渐变半径（默认）
        private double innerRad = 2.2;    // 内圈形状半径（默认）

        // 坐标切换开关：true=中心坐标，false=眼睛坐标（默认中心）
        private boolean useCenterForShading = true;

        // 黑色核心区半径（替换原硬编码的5.0，默认保留5.0）
        private double abyssRadius = 5.0;

        // =========================================================
        // 链式配置方法（全部可外部调用）
        // =========================================================
        /** 配置基础颜色 */
        public FalloffSpotShader color(float r, float g, float b) {
            this.red = r;
            this.green = g;
            this.blue = b;
            return this;
        }

        /** 批量配置：最大距离 + 外圈半径 + 内圈半径 */
        public FalloffSpotShader range(double maxDist, double outer, double inner) {
            this.maxDistSq = maxDist * maxDist;
            this.outerRad = outer;
            this.innerRad = inner;
            return this;
        }

        /** 独立配置外圈半径 */
        public FalloffSpotShader outerRadius(double outerRad) {
            this.outerRad = outerRad;
            return this;
        }

        /** 独立配置内圈半径 */
        public FalloffSpotShader innerRadius(double innerRad) {
            this.innerRad = innerRad;
            return this;
        }

        /** 切换坐标计算方式：true=中心坐标，false=眼睛坐标 */
        public FalloffSpotShader useCenterForShading(boolean useCenter) {
            this.useCenterForShading = useCenter;
            return this;
        }

        /** 配置黑色核心区半径（替换原5.0硬编码） */
        public FalloffSpotShader abyssRadius(double abyssRadius) {
            this.abyssRadius = abyssRadius;
            return this;
        }

        // =========================================================
        // 核心着色逻辑（完整修改版）
        // =========================================================
        @Override
        public QuadFxAPI.Color shade(QuadFxAPI.Sample s) {
            // 1. 根据开关切换坐标计算偏移量
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

            // 2. 总距离校验（超过maxDist则不渲染）
            double totalDistSq = dx*dx + dy*dy + dz*dz;
            if (totalDistSq >= maxDistSq) return null;
            double totalDist = Math.sqrt(totalDistSq);

            // 3. 计算spreadSq（光圈形状，跟随坐标开关）
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

            // 4. 外圈形状校验（超过outerRad则不渲染）
            double outerRadiusSq = outerRad * outerRad;
            double innerRadiusSq = innerRad * innerRad;
            if (spreadSq >= outerRadiusSq) return null;
            double spreadDist = Math.sqrt(spreadSq);

            // 5. 外圈渐变强度
            double outerSpreadFactor = spreadDist / outerRad;
            double outerShape = 1.0 - Math.pow(outerSpreadFactor, 2.0);
            float baseAlpha = (float) (0.35f * outerShape);

            // 6. 黑色核心区强度（使用可配置的abyssRadius，替换原5.0）
            float abyssAlpha = 0.0f;
            if (totalDist < abyssRadius && spreadSq < innerRadiusSq) {
                // 黑色强度随距离渐变（0→abyssRadius，强度从1→0）
                double depthFactor = totalDist / abyssRadius;
                float depthStrength = (float) (0.9 * (1.0 - Math.pow(depthFactor, 3.5)));

                double innerSpreadFactor = spreadDist / innerRad;
                double innerShape = 1.0 - Math.pow(innerSpreadFactor, 3.0);

                abyssAlpha = (float) (depthStrength * innerShape);
            }

            // 7. 最终透明度（取外圈和核心区的最大值）
            float finalAlpha = Math.max(baseAlpha, abyssAlpha);

            // 8. 距离淡出（跟随maxDist）
            double maxDist = Math.sqrt(maxDistSq);
            double fadeStart = maxDist - 2.0;
            if (totalDist > fadeStart) {
                double fade = (maxDist - totalDist) / (maxDist - fadeStart);
                finalAlpha *= (float) fade;
            }

            // 9. 透明度阈值过滤
            if (finalAlpha <= 0.005f) return null;

            // 10. 雾化提亮（跟随坐标开关的总距离）
            double mistFactor = Math.min(1.0, totalDist / (maxDist * 0.6));
            float r = red + (float)(mistFactor * 0.3);
            float g = green + (float)(mistFactor * 0.3);
            float b = blue + (float)(mistFactor * 0.5);

            // 返回最终颜色
            return QuadFxAPI.Color.rgba(r, g, b, finalAlpha);
        }
    }


    // =========================================================
    // Shader：扩散波前光圈 (全向通用版)
    // - 放在地上：向四周扩散
    // - 放在墙上：像靶心一样扩散
    // =========================================================
    public static final class FrameRingWaveShader implements Shader {
        public double waveRadius = 0.0;
        public double ridgeWidth = 0.6;

        public double outerFadeRadius = 12.0;
        public double pow = 2.0;

        public float baseR=0.0f, baseG=0.0f, baseB=0.05f;
        public float darkR=0.0f, darkG=0.0f, darkB=0.0f;

        public float backgroundStrength = 0.20f;
        public float ridgeStrength = 0.85f;

        public boolean mist = true;
        public float mistR=0.3f, mistG=0.3f, mistB=0.5f;
        public double mistDist=8.0;

        public FrameRingWaveShader waveRadius(double r){ this.waveRadius=r; return this; }
        public FrameRingWaveShader ridgeWidth(double w){ this.ridgeWidth=w; return this; }
        public FrameRingWaveShader fadeRadius(double r){ this.outerFadeRadius=r; return this; }
        public FrameRingWaveShader pow(double p){ this.pow=p; return this; }

        public FrameRingWaveShader colors(float br,float bg,float bb, float dr,float dg,float db){
            this.baseR=br; this.baseG=bg; this.baseB=bb;
            this.darkR=dr; this.darkG=dg; this.darkB=db;
            return this;
        }
        public FrameRingWaveShader strength(float bgStrength, float ridgeStrength){
            this.backgroundStrength=bgStrength; this.ridgeStrength=ridgeStrength;
            return this;
        }
        public FrameRingWaveShader mist(boolean enable){ this.mist=enable; return this; }

        @Override
        public Color shade(Sample s) {
            // 计算相对于特效中心(centerX/Y/Z)的偏移
            double dx = s.x - s.centerX;
            double dy = s.y - s.centerY;
            double dz = s.z - s.centerZ;

            // ✅ 核心修改：根据朝向计算“半径 r”
            double r;
            if (s.dirHint == FaceDir.NORTH || s.dirHint == FaceDir.SOUTH) {
                // 墙面：在垂直面上画圆
                r = Math.sqrt(dx*dx + dy*dy);
            } else if (s.dirHint == FaceDir.EAST || s.dirHint == FaceDir.WEST) {
                // 墙面：在垂直面上画圆
                r = Math.sqrt(dz*dz + dy*dy);
            } else {
                // 地面/天花板：在水平面上画圆 (原版逻辑)
                r = Math.sqrt(dx*dx + dz*dz);
            }

            double t = outerFadeRadius > 0 ? clamp01(r / outerFadeRadius) : 1.0;
            double bg = falloff(t, pow);
            float bgA = backgroundStrength * (float)bg;

            double ridge = gaussianPeak(r, waveRadius, ridgeWidth);
            float ridgeA = ridgeStrength * (float)ridge;

            float a = bgA + ridgeA;
            if (a <= 0.0001f) return null;

            float mix = clamp01f((float)ridge);
            float rCol = lerp(baseR, darkR, mix);
            float gCol = lerp(baseG, darkG, mix);
            float bCol = lerp(baseB, darkB, mix);

            if (mist) {
                // 雾效永远计算到玩家眼睛的真实 3D 距离
                double ex = s.x - s.eyeX;
                double ey = s.y - s.eyeY;
                double ez = s.z - s.eyeZ;
                double dist = Math.sqrt(ex*ex + ey*ey + ez*ez);
                double mf = Math.min(1.0, dist / mistDist);
                rCol = rCol + (float)(mf * mistR);
                gCol = gCol + (float)(mf * mistG);
                bCol = bCol + (float)(mf * mistB);
            }

            return Color.rgba(rCol, gCol, bCol, a);
        }

        private static double gaussianPeak(double x, double mu, double sigma){
            if (sigma <= 0) return 0;
            double d = (x - mu) / sigma;
            return Math.exp(-(d*d));
        }
    }

    // =========================================================
    // 几何工具：方块面 / 墙切片 -> Quad
    // =========================================================
    private static QuadJob blockFaceToQuad(int x,int y,int z, FaceDir dir,
                                           double eyeX,double eyeY,double eyeZ,
                                           boolean wallHeightEnabled,
                                           float wallMinY, float wallMaxY) {
        double x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4;
        double cx,cy,cz;

        double y0 = wallHeightEnabled ? wallMinY : y;
        double yTop = wallHeightEnabled ? wallMaxY : (y + 1);

        switch (dir) {
            case NORTH -> { x1=x+1; y1=yTop; z1=z;   x2=x; y2=yTop; z2=z;   x3=x; y3=y0; z3=z;   x4=x+1; y4=y0; z4=z;   cx=x+0.5; cy=(y0+yTop)*0.5; cz=z; }
            case SOUTH -> { x1=x+1; y1=y0;  z1=z+1; x2=x+1; y2=yTop; z2=z+1; x3=x; y3=yTop; z3=z+1; x4=x; y4=y0; z4=z+1; cx=x+0.5; cy=(y0+yTop)*0.5; cz=z+1; }
            case WEST  -> { x1=x;   y1=y0;  z1=z+1; x2=x;   y2=yTop; z2=z+1; x3=x; y3=yTop; z3=z;   x4=x; y4=y0; z4=z;   cx=x;   cy=(y0+yTop)*0.5; cz=z+0.5; }
            case EAST  -> { x1=x+1; y1=y0;  z1=z;   x2=x+1; y2=yTop; z2=z;   x3=x+1; y3=yTop; z3=z+1; x4=x+1; y4=y0; z4=z+1; cx=x+1; cy=(y0+yTop)*0.5; cz=z+0.5; }
            case UP    -> { x1=x;   y1=y+1; z1=z;   x2=x+1; y2=y+1; z2=z;   x3=x+1; y3=y+1; z3=z+1; x4=x; y4=y+1; z4=z+1; cx=x+0.5; cy=y+1; cz=z+0.5; }
            case DOWN  -> { x1=x;   y1=y;   z1=z+1; x2=x+1; y2=y;   z2=z+1; x3=x+1; y3=y;   z3=z;   x4=x; y4=y;   z4=z;   cx=x+0.5; cy=y;   cz=z+0.5; }
            default -> { return null; }
        }

        double dx = cx-eyeX, dy = cy-eyeY, dz = cz-eyeZ;
        double distSq = dx*dx + dy*dy + dz*dz;

        return new QuadJob(
                x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4,
                cx,cy,cz,
                distSq,
                dir
        );
    }

    private static QuadJob wallSliceToQuad(int x, int z, FaceDir dir, double y1, double y2,
                                           double eyeX,double eyeY,double eyeZ) {
        double x1,yA,z1, x2,yB,z2, x3,yC,z3, x4,yD,z4;
        double cx,cy,cz;

        double minY = y1;
        double maxY = y2;
        cy = (minY + maxY) * 0.5;

        switch (dir) {
            case NORTH -> {
                x1=x+1; yA=maxY; z1=z;
                x2=x;   yB=maxY; z2=z;
                x3=x;   yC=minY; z3=z;
                x4=x+1; yD=minY; z4=z;
                cx=x+0.5; cz=z;
            }
            case SOUTH -> {
                x1=x+1; yA=minY; z1=z+1;
                x2=x+1; yB=maxY; z2=z+1;
                x3=x;   yC=maxY; z3=z+1;
                x4=x;   yD=minY; z4=z+1;
                cx=x+0.5; cz=z+1;
            }
            case WEST -> {
                x1=x; yA=minY; z1=z+1;
                x2=x; yB=maxY; z2=z+1;
                x3=x; yC=maxY; z3=z;
                x4=x; yD=minY; z4=z;
                cx=x; cz=z+0.5;
            }
            case EAST -> {
                x1=x+1; yA=minY; z1=z;
                x2=x+1; yB=maxY; z2=z;
                x3=x+1; yC=maxY; z3=z+1;
                x4=x+1; yD=minY; z4=z+1;
                cx=x+1; cz=z+0.5;
            }
            default -> { return null; }
        }

        double dx = cx-eyeX, dy = cy-eyeY, dz = cz-eyeZ;
        double distSq = dx*dx + dy*dy + dz*dz;

        return new QuadJob(
                x1,yA,z1, x2,yB,z2, x3,yC,z3, x4,yD,z4,
                cx,cy,cz,
                distSq,
                dir
        );
    }

    // =========================================================
    // Quad 子细分：双线性插值
    // =========================================================
    private static double[] bilerpPoint(QuadJob q, double tx, double ty) {
        double xt = lerp(q.x2, q.x1, tx);
        double yt = lerp(q.y2, q.y1, tx);
        double zt = lerp(q.z2, q.z1, tx);

        double xb = lerp(q.x3, q.x4, tx);
        double yb = lerp(q.y3, q.y4, tx);
        double zb = lerp(q.z3, q.z4, tx);

        return new double[] { lerp(xb, xt, ty), lerp(yb, yt, ty), lerp(zb, zt, ty) };
    }

    private static double lerp(double a,double b,double t){ return a + (b-a)*t; }

    // =========================================================
    // 写顶点
    // =========================================================
    private static void addQuad(VertexConsumer builder, Matrix4f matrix,
                                double x1,double y1,double z1,
                                double x2,double y2,double z2,
                                double x3,double y3,double z3,
                                double x4,double y4,double z4,
                                float r,float g,float b,float a,
                                boolean withColor) {
        if (withColor) {
            builder.vertex(matrix, (float)x1,(float)y1,(float)z1).color(r,g,b,a).endVertex();
            builder.vertex(matrix, (float)x2,(float)y2,(float)z2).color(r,g,b,a).endVertex();
            builder.vertex(matrix, (float)x3,(float)y3,(float)z3).color(r,g,b,a).endVertex();
            builder.vertex(matrix, (float)x4,(float)y4,(float)z4).color(r,g,b,a).endVertex();
        } else {
            builder.vertex(matrix, (float)x1,(float)y1,(float)z1).endVertex();
            builder.vertex(matrix, (float)x2,(float)y2,(float)z2).endVertex();
            builder.vertex(matrix, (float)x3,(float)y3,(float)z3).endVertex();
            builder.vertex(matrix, (float)x4,(float)y4,(float)z4).endVertex();
        }
    }

    // =========================================================
    // 小工具
    // =========================================================
    private static double clamp01(double v){ return v<0?0:(v>1?1:v); }
    private static float clamp01f(float v){ return v<0?0:(v>1?1:v); }
    private static double falloff(double t,double pow){ return 1.0 - Math.pow(t, pow); }
    private static float lerp(float a,float b,float t){ return a + (b-a)*t; }
}
//案例：
//var shader = new QuadFxAPI.FalloffSpotShader()
//        .radii(3.5, 2.2)
//        .pow(2.0)
//        .strength(0.35f, 0.90f)
//        .baseColor(0.0f, 0.0f, 0.05f)
//        .mist(true);
//
//QuadFxAPI.spot()
//    .eye(eyeX, eyeY, eyeZ)
//    .shader(shader)
//    .maxDist(12.0)
//    .detail(2)                 // ✅ 0.5×0.5（墙、地面都统一）
//    .clear()
//// yMin/yMax 外部仍然可控；切片大小由 detail 决定，不再传 sliceY
//    .wallColumn(worldX, worldZ, QuadFxAPI.FaceDir.NORTH, eyeY-12, eyeY+12)
//    .render(builder, matrix, true);
///地面网格
//double seconds = (mc.level.getGameTime() + partialTick) / 20.0;
//double waveRadius = seconds * 2.0;
//
//var ringShader = new QuadFxAPI.FrameRingWaveShader()
//        .waveRadius(waveRadius)
//        .fadeRadius(24.0)
//        .pow(2.0)
//        .mist(true);
//
//QuadFxAPI.spot()
//    .eye(eyeX, eyeY, eyeZ)
//    .center(player.getX(), player.getY(), player.getZ())
//    .shader(ringShader)
//    .maxDist(24.0)
//    .detail(2)              // ✅ ring 的径向切片也统一为 0.5
//    .clear()
//    .ringMesh(player.getY(), 0, 24, 96)
//    .render(builder, matrix, true);
//// 1. 定义 Shader（长什么样？）
//var myShader = new MyCoolShader().color(Red).radius(5.0);
//
/// / 2. 配置 Pipeline
//QuadFxAPI.spot()
//    .eye(playerX, playerY, playerZ)  // 设定观察点
//    .center(targetX, targetY, targetZ) // 设定特效中心
//    .shader(myShader)                // 挂载 Shader
//    .detail(2)                       // 设定精度：0.5格精度
//    .maxDist(12.0)                   // 设定视距剔除
//    .clear()                         // 清空上一帧队列
//
//// 3. 提交几何体任务（在哪里？）
//    .wallColumn(x, z, NORTH, 0, 256) // 添加一根柱子
//    .ringMesh(64, 0, 5, 32)          // 添加一个光环
//
//// 4. 执行渲染
//    .render(builder, matrix, true);  // 排序 -> 细分 -> 着色 -> 写入显存