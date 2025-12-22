package com.mapextra.world;

import com.mapextra.math.BorderCollisionUtils;
import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketSyncBorderNames;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

public class BorderData extends SavedData {
    private static final String DATA_NAME = "mapextra_borders";
    // 默认高度，用于旧数据迁移或未指定高度时 (覆盖 -64 到 320 的范围)
    public static final int DEFAULT_HEIGHT = 384;

    // 内部类：定义单个边界的所有属性
    public static class BorderEntry {
        public double startX;
        public double startZ;
        public int width;   // X轴长度
        public int depth;   // Z轴长度
        public int height;  // 【新增】Y轴高度
        public byte[] grid; // 使用一维数组存储三维数据 (X, Z, Y)

        // 【新增】全参构造函数
        public BorderEntry(double x, double z, int w, int d, int h) {
            this.startX = x;
            this.startZ = z;
            this.width = w;
            this.depth = d;
            this.height = h;
            this.grid = new byte[w * d * h];
        }

        // 【兼容】旧的构造函数，默认高度
        public BorderEntry(double x, double z, int w, int d) {
            this(x, z, w, d, DEFAULT_HEIGHT);
        }

        public void setBorder(double x, double z, int newWidth, int newDepth, int newHeight) {
            this.startX = x;
            this.startZ = z;

            // 如果尺寸变了，重置网格
            if (newWidth != this.width || newDepth != this.depth || newHeight != this.height) {
                // 直接覆盖清空，如果需要保留数据可在此处实现 3D 数组拷贝
                this.grid = new byte[newWidth * newDepth * newHeight];
                this.width = newWidth;
                this.depth = newDepth;
                this.height = newHeight;
            }
        }

        // 获取数组索引的辅助方法
        private int getIndex(int x, int z, int y) {
            return x + z * width + y * width * depth;
        }

        // 【升级】支持 3D 检查
        public boolean isWall(int localX, int localZ, int localY) {
            if (localX < 0 || localX >= width ||
                    localZ < 0 || localZ >= depth ||
                    localY < 0 || localY >= height) return false;
            return grid[getIndex(localX, localZ, localY)] == 1;
        }

        // 【兼容】旧的 2D 检查：只要这一列有任何一个方块是墙，就视为墙 (或者你可以改为检查 y=0)
        // 这里为了保险，默认检查 y=64 (海平面) 或者返回 false，但物理碰撞现在应该主要用 addBorderCollisions
        public boolean isWall2D(int localX, int localZ) {
            // 简单的逻辑：检查该列所有高度，只要有一个是墙，2D视角就认为是墙
            for (int y = 0; y < height; y++) {
                if (grid[getIndex(localX, localZ, y)] == 1) return true;
            }
            return false;
        }

        // 【升级】设置单个 3D 块
        public void setWall(int localX, int localZ, int localY, boolean isWall) {
            if (localX < 0 || localX >= width ||
                    localZ < 0 || localZ >= depth ||
                    localY < 0 || localY >= height) return;
            grid[getIndex(localX, localZ, localY)] = (byte) (isWall ? 1 : 0);
        }

        // 【兼容】设置整列 (对应原来的 2D 设置逻辑)
        public void setWallColumn(int localX, int localZ, boolean isWall) {
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth) return;
            byte val = (byte) (isWall ? 1 : 0);
            for (int y = 0; y < height; y++) {
                grid[getIndex(localX, localZ, y)] = val;
            }
        }

        public void addBorderCollisions(AABB entityBox, List<VoxelShape> shapes) {
            // 注意：BorderCollisionUtils 需要更新以支持 height 和 3D 检查
            // 如果 Utils 还没改，这里传递 this 进去后，Utils 内部调用 this.isWall 可能会报错
            // 假设 Utils 已经适配，或者 Utils 只调用公共字段
            BorderCollisionUtils.addWallCollisions(entityBox, this, shapes);
        }
    }

    // 存储所有命名的边界配置
    private final Map<String, BorderEntry> borders = new HashMap<>();

    // 全局当前激活的边界名称
    private String activeBorderName = null;

    // 玩家当前的编辑焦点
    private final Map<UUID, String> playerEditFocus = new HashMap<>();

    public BorderData() {}

    public static BorderData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(BorderData::load, BorderData::new, DATA_NAME);
    }

    // ================== 管理逻辑 ==================

    // 【升级】支持自定义高度
    public void addBorder(String name, double x, double z, int w, int d, int h) {
        borders.put(name, new BorderEntry(x, z, w, d, h));
        setDirty();
        syncNamesToAll();
    }

    // 【兼容】旧接口，使用默认高度
    public void addBorder(String name, double x, double z, int w, int d) {
        addBorder(name, x, z, w, d, DEFAULT_HEIGHT);
    }

    public boolean removeBorder(String name) {
        if (borders.remove(name) != null) {
            if (name.equals(activeBorderName)) {
                activeBorderName = null;
            }
            setDirty();
            syncNamesToAll();
            return true;
        }
        return false;
    }

    public BorderEntry getEntry(String name) {
        return borders.get(name);
    }

    public Set<String> getAllNames() {
        return borders.keySet();
    }

    public List<String> getAllNamesList() {
        return new ArrayList<>(borders.keySet());
    }

    public void syncNamesToAll() {
        ModMessage.sendToAll(new PacketSyncBorderNames(getAllNamesList()));
    }

    // ================== 焦点与激活 ==================

    public void setPlayerFocus(UUID uuid, String name) {
        if (borders.containsKey(name)) {
            playerEditFocus.put(uuid, name);
            setDirty();
        }
    }

    public String getPlayerFocus(UUID uuid) {
        return playerEditFocus.get(uuid);
    }

    public void setActiveBorder(String name) {
        if (name == null || borders.containsKey(name)) {
            this.activeBorderName = name;
            setDirty();
        }
    }

    public String getActiveBorderName() {
        return activeBorderName;
    }

    public BorderEntry getActiveEntry() {
        if (activeBorderName == null) return null;
        return borders.get(activeBorderName);
    }

    // ================== 绘图逻辑 (SetBlock / SetLine) ==================

    // 【新增】3D 设置方块
    public boolean setBlock(String name, int localX, int localY, int localZ, boolean isWall) {
        BorderEntry entry = borders.get(name);
        if (entry == null) return false;
        entry.setWall(localX, localZ, localY, isWall);
        setDirty();
        return true;
    }

    // 【兼容】2D 设置方块 -> 变成设置整根柱子
    public boolean setBlock(String name, int localX, int localZ, boolean isWall) {
        BorderEntry entry = borders.get(name);
        if (entry == null) return false;
        // 旧逻辑：设置一个点 -> 新逻辑：设置这一列的所有高度 (实现"默认全1"的效果)
        entry.setWallColumn(localX, localZ, isWall);
        setDirty();
        return true;
    }

    // Bresenham 画线算法 (保持 2D 逻辑，但操作 3D 柱子)
    public boolean setLine(String name, int x1, int z1, int x2, int z2, boolean isWall) {
        BorderEntry entry = borders.get(name);
        if (entry == null) return false;

        int dx = Math.abs(x2 - x1);
        int dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;
        int err = dx - dz;

        while (true) {
            // 这里调用 setWallColumn，确保画出的线是一堵通天的墙
            entry.setWallColumn(x1, z1, isWall);

            if (x1 == x2 && z1 == z2) break;
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x1 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z1 += sz;
            }
        }
        setDirty();
        return true;
    }

    // ================== 碰撞逻辑 (只针对 Active) ==================

    // 【升级】3D 碰撞检测
    public boolean isWall(int worldX, int worldY, int worldZ) {
        BorderEntry active = getActiveEntry();
        if (active == null) return false;

        int localX = (int)(worldX - active.startX);
        int localY = worldY; // 假设 worldY 就是绝对高度，如果有关联偏移需减去 startY
        int localZ = (int)(worldZ - active.startZ);

        if (localX < 0 || localX >= active.width ||
                localZ < 0 || localZ >= active.depth ||
                localY < 0 || localY >= active.height) { // 检查 Y 边界
            return false;
        }
        return active.isWall(localX, localZ, localY);
    }

    // 【兼容】2D 碰撞检测 (如果还有旧代码在调用)
    public boolean isWall(int worldX, int worldZ) {
        // 默认检查大约脚部高度，或者检查整列
        // 这里假设检查 y=64
        return isWall(worldX, 64, worldZ);
    }

    // ================== NBT 读写 ==================

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag bordersTag = new CompoundTag();
        borders.forEach((name, entry) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putDouble("x", entry.startX);
            entryTag.putDouble("z", entry.startZ);
            entryTag.putInt("w", entry.width);
            entryTag.putInt("d", entry.depth);
            entryTag.putInt("h", entry.height); // 保存高度
            entryTag.putByteArray("grid", entry.grid);
            bordersTag.put(name, entryTag);
        });
        tag.put("borders", bordersTag);

        if (activeBorderName != null) {
            tag.putString("active", activeBorderName);
        }

        CompoundTag focusTag = new CompoundTag();
        playerEditFocus.forEach((uuid, name) -> {
            if (borders.containsKey(name)) {
                focusTag.putString(uuid.toString(), name);
            }
        });
        tag.put("focus", focusTag);

        return tag;
    }

    public static BorderData load(CompoundTag tag) {
        BorderData data = new BorderData();
        if (tag.contains("borders")) {
            CompoundTag bordersTag = tag.getCompound("borders");
            for (String name : bordersTag.getAllKeys()) {
                CompoundTag entryTag = bordersTag.getCompound(name);
                double x = entryTag.getDouble("x");
                double z = entryTag.getDouble("z");
                int w = entryTag.getInt("w");
                int d = entryTag.getInt("d");

                // 【关键】兼容性处理：检查是否存在高度数据
                boolean hasHeight = entryTag.contains("h");
                int h = hasHeight ? entryTag.getInt("h") : DEFAULT_HEIGHT;

                byte[] rawGrid = entryTag.getByteArray("grid");

                // 创建新的 Entry (自动分配 3D 数组)
                BorderEntry entry = new BorderEntry(x, z, w, d, h);

                if (hasHeight) {
                    // 情况 A: 已经是新版 3D 数据，直接加载
                    if (rawGrid.length == w * d * h) {
                        entry.grid = rawGrid;
                    }
                } else {
                    // 情况 B: 旧版 2D 数据 -> 迁移到 3D
                    // 要求：默认全1 (即把2D的墙延伸到所有高度)
                    if (rawGrid.length == w * d) {
                        for (int i = 0; i < w; i++) {
                            for (int j = 0; j < d; j++) {
                                // 检查旧网格是否有墙
                                byte isWall = rawGrid[i + j * w];
                                if (isWall == 1) {
                                    // 在新网格中，将这一列的所有高度都设为 1
                                    for (int k = 0; k < h; k++) {
                                        entry.grid[i + j * w + k * w * d] = 1;
                                    }
                                }
                            }
                        }
                    }
                }

                data.borders.put(name, entry);
            }
        }

        if (tag.contains("active")) {
            String active = tag.getString("active");
            if (data.borders.containsKey(active)) {
                data.activeBorderName = active;
            }
        }

        if (tag.contains("focus")) {
            CompoundTag focusTag = tag.getCompound("focus");
            for (String key : focusTag.getAllKeys()) {
                try {
                    data.playerEditFocus.put(UUID.fromString(key), focusTag.getString(key));
                } catch (Exception ignored) {}
            }
        }

        return data;
    }
}
