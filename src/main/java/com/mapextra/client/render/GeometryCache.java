package com.mapextra.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class GeometryCache {

    public static final GeometryCache RADAR_RANGE = new GeometryCache();
    public static GeometryCache getInstance() { return RADAR_RANGE; }

    // 自动过期时间：3秒（3000毫秒）
    private static final long EXPIRE_TIME = 3000L;
    // 扫描半径
    private static final int SCAN_RADIUS = 30;

    // ✅ 扫描扩散期（与你 RadarRenderer 保持一致）
    private static final long EXPAND_MS = 1000L;
    // ✅ 扩散速度 v：30格 / 1秒 = 30 blocks/s
    private static final double WAVE_SPEED = SCAN_RADIUS / (EXPAND_MS / 1000.0); // 30.0

    // =========================================================
    // ✅ 新增：扫描目标（玩家点）
    // =========================================================
    public static final class ScanTarget {
        public final UUID uuid;
        public final double x, y, z;
        public final double r;       // 该目标到中心的水平距离（也可以换成3D）
        public final long triggerMs; // 何时触发红色脉冲
        public boolean triggered = false;

        public ScanTarget(UUID uuid, double x, double y, double z, double r, long triggerMs) {
            this.uuid = uuid;
            this.x = x; this.y = y; this.z = z;
            this.r = r;
            this.triggerMs = triggerMs;
        }
    }

    // =========================================================
    // ✅ 新增：红色脉冲（真正渲染的对象）
    // =========================================================
    public static final class Pulse {
        public final double x, y, z;
        public final long startMs;
        public Pulse(double x, double y, double z, long startMs) {
            this.x=x; this.y=y; this.z=z;
            this.startMs=startMs;
        }
    }

    // ✅ 队列元素：中心坐标 + cachedQuads + 目标列表 + 脉冲列表
    public static class CacheEntry {
        public double originX, originY, originZ;
        public List<QuadFxAPI.QuadJob> quads;
        public long createTime;

        // ✅ 新增：本次扫描圈里有哪些玩家点（可能为空）
        public final List<ScanTarget> targets;

        // ✅ 新增：命中后生成的红色波（渲染时会自动清理过期）
        public final List<Pulse> pulses = new ArrayList<>();

        public CacheEntry(double originX, double originY, double originZ,
                          List<QuadFxAPI.QuadJob> quads,
                          List<ScanTarget> targets) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.quads = quads;
            this.targets = (targets != null) ? targets : new ArrayList<>();
            this.createTime = System.currentTimeMillis();
        }
    }

    private final Deque<CacheEntry> cacheQueue = new LinkedList<>();

    public void offerEntry(CacheEntry entry) {
        removeExpiredEntries();
        cacheQueue.offerLast(entry);
    }

    private void removeExpiredEntries() {
        long currentTime = System.currentTimeMillis();
        while (!cacheQueue.isEmpty()) {
            CacheEntry firstEntry = cacheQueue.peekFirst();
            if (currentTime - firstEntry.createTime > EXPIRE_TIME) {
                cacheQueue.pollFirst();
            } else break;
        }
    }

    public int getQuadCount() {
        removeExpiredEntries();
        int total = 0;
        for (CacheEntry e : cacheQueue) total += e.quads.size();
        return total;
    }

    // =========================================================
    // ✅ rebuild：除了构建地形 quads，同时抓取范围内玩家 -> targets
    // 说明：如果你最终由服务端给 r，这里 targets 也可以先为空，
    //      但你现在说“客户端这里要多读取范围内所有玩家位置”，所以就在这里读。
    // =========================================================
    public void rebuild(Player player) {
        List<QuadFxAPI.QuadJob> tempQuads = new LinkedList<>();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();

        Level level = player.level();
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        int px = (int) Math.floor(playerX);
        int py = (int) Math.floor(playerY);
        int pz = (int) Math.floor(playerZ);

        // 1) 采集地形 quads（你原逻辑不动）
        for (int x = px - SCAN_RADIUS; x <= px + SCAN_RADIUS; x++) {
            for (int z = pz - SCAN_RADIUS; z <= pz + SCAN_RADIUS; z++) {
                if ((x - px)*(x - px) + (z - pz)*(z - pz) > SCAN_RADIUS * SCAN_RADIUS) continue;

                for (int y = py - 2; y <= py + 3; y++) {
                    mPos.set(x, y, z);
                    BlockState state = level.getBlockState(mPos);
                    if (state.isAir()) continue;
                    ModelGeometryUtil.extractHybrid(level, mPos, state, tempQuads::add);
                }
            }
        }

        // 2) ✅ 采集范围内玩家作为 targets（r 可能不存在 -> targets 可能为空）
        //    这里用水平距离 r（更像雷达），你要 3D 也可以改成 dy 一起算。
        long now = System.currentTimeMillis();
        List<ScanTarget> targets = new ArrayList<>();

        for (Player p : level.players()) {
            if (p == player) continue;

            double dx = p.getX() - playerX;
            double dz = p.getZ() - playerZ;
            double r = Math.sqrt(dx*dx + dz*dz);

            if (r <= SCAN_RADIUS) {
                // 触发时间：扫描波前到达该半径的时刻
                long triggerMs = now + (long)((r / WAVE_SPEED) * 1000.0);

                targets.add(new ScanTarget(
                        p.getUUID(),
                        p.getX(), p.getY(), p.getZ(),
                        r,
                        triggerMs
                ));
            }
        }

        CacheEntry newEntry = new CacheEntry(playerX, playerY, playerZ, tempQuads, targets);
        this.offerEntry(newEntry);
    }

    public void renderCache(QuadFxAPI.Spot realSpot, double limitRadius, CacheEntry entry) {
        if (entry.quads.isEmpty()) return;

        double maxSq = limitRadius * limitRadius;
        double centerX = entry.originX;
        double centerY = entry.originY;
        double centerZ = entry.originZ;

        for (QuadFxAPI.QuadJob job : entry.quads) {
            double dx = job.cx - centerX;
            double dy = job.cy - centerY;
            double dz = job.cz - centerZ;
            double distSq = dx*dx + dy*dy + dz*dz;
            if (distSq <= maxSq) realSpot.quad(job);
        }
    }

    public boolean isEmpty() {
        removeExpiredEntries();
        return cacheQueue.isEmpty();
    }

    public Deque<CacheEntry> getCacheQueue() {
        removeExpiredEntries();
        return cacheQueue;
    }
}
