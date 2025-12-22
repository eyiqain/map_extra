package com.mapextra.math;

import net.minecraft.world.phys.Vec3;

/**
 * 传送路径与墙体的交点信息
 */
public final class WallIntercept {
    public final Vec3 point;   // 靠近起点的交点（world 坐标）
    public final int nx;        // 进入面法线 (-1 / 0 / 1)
    public final int nz;
    public final double tEnter; // 0..1 参数（用于插值 Y）

    public WallIntercept(Vec3 point, int nx, int nz, double tEnter) {
        this.point = point;
        this.nx = nx;
        this.nz = nz;
        this.tEnter = tEnter;
    }
}
