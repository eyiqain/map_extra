package com.mapextra.world;

import com.mapextra.math.BorderCollisionUtils;
import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketSyncBorder;
import com.mapextra.net.PacketSyncBorderNames; // 确保这个包已经按上一步创建好
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

public class BorderData extends SavedData {
    private static final String DATA_NAME = "mapextra_borders";

    // 内部类：定义单个边界的所有属性
    public static class BorderEntry {
        public double startX;
        public double startZ;
        public int width;
        public int depth;
        public byte[] grid; // 使用一维数组存储二维数据

        public BorderEntry(double x, double z, int w, int d) {
            this.startX = x;
            this.startZ = z;
            this.width = w;
            this.depth = d;
            this.grid = new byte[w * d];
        }

        public void setBorder(double x, double z, int newWidth, int newDepth) {
            this.startX = x;
            this.startZ = z;

            // 如果尺寸变了，尝试保留旧数据
            if (newWidth != this.width || newDepth != this.depth) {
                byte[] newGrid = new byte[newWidth * newDepth];

                // 只有当想保留旧画作时才取消注释下面这段：
                // int copyW = Math.min(this.width, newWidth);
                // int copyD = Math.min(this.depth, newDepth);
                // for(int i=0; i<copyW; i++) {
                //     for(int j=0; j<copyD; j++) {
                //         newGrid[i + j * newWidth] = this.grid[i + j * this.width];
                //     }
                // }

                this.grid = newGrid; // 目前你直接覆盖是清空，也算一种逻辑
                this.width = newWidth;
                this.depth = newDepth;
            }
        }


        public boolean isWall(int localX, int localZ) {
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth) return false;
            return grid[localX + localZ * width] == 1;
        }

        public void setWall(int localX, int localZ, boolean isWall) {
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth) return;
            grid[localX + localZ * width] = (byte) (isWall ? 1 : 0);
        }

        public void addBorderCollisions(AABB entityBox, List<VoxelShape> shapes) {
            BorderCollisionUtils.addWallCollisions(entityBox, this, shapes);
        }
    }

    // 存储所有命名的边界配置
    private final Map<String, BorderEntry> borders = new HashMap<>();

    // 全局当前激活的边界名称 (用于碰撞和渲染)
    private String activeBorderName = null;

    // 玩家当前的编辑焦点 (UUID -> TagName)
    private final Map<UUID, String> playerEditFocus = new HashMap<>();

    public BorderData() {}

    public static BorderData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(BorderData::load, BorderData::new, DATA_NAME);
    }

    // ================== 管理逻辑 ==================

    public void addBorder(String name, double x, double z, int w, int d) {
        borders.put(name, new BorderEntry(x, z, w, d));
        setDirty();
        // 【关键】边界列表变动，同步给所有人
        syncNamesToAll();
    }

    public boolean removeBorder(String name) {
        if (borders.remove(name) != null) {
            if (name.equals(activeBorderName)) {
                activeBorderName = null; // 如果删除了正在开启的边界，强制关闭
            }
            setDirty();
            // 【关键】边界列表变动，同步给所有人
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

    // 【新增】获取列表形式的名字，用于网络传输
    public List<String> getAllNamesList() {
        return new ArrayList<>(borders.keySet());
    }

    // 【新增】发送同步名字包的辅助方法:列表变动，有人新建内容
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

    /**
     * 获取当前激活的边界实体 (用于网络同步和碰撞)
     */
    public BorderEntry getActiveEntry() {
        if (activeBorderName == null) return null;
        return borders.get(activeBorderName);
    }

    // ================== 绘图逻辑 (SetBlock / SetLine) ==================

    public boolean setBlock(String name, int localX, int localZ, boolean isWall) {
        BorderEntry entry = borders.get(name);
        if (entry == null) return false;

        entry.setWall(localX, localZ, isWall);
        setDirty();
        return true;
    }

    // Bresenham 画线算法
    public boolean setLine(String name, int x1, int z1, int x2, int z2, boolean isWall) {
        BorderEntry entry = borders.get(name);
        if (entry == null) return false;

        int dx = Math.abs(x2 - x1);
        int dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;
        int err = dx - dz;

        while (true) {
            entry.setWall(x1, z1, isWall);

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

    public boolean isWall(int worldX, int worldZ) {
        BorderEntry active = getActiveEntry();
        if (active == null) return false; // 没有激活边界，全是空气

        int localX = (int)(worldX - active.startX);
        int localZ = (int)(worldZ - active.startZ);

        if (localX < 0 || localX >= active.width || localZ < 0 || localZ >= active.depth) {
            return false;
        }
        return active.isWall(localX, localZ);
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
            entryTag.putByteArray("grid", entry.grid);
            bordersTag.put(name, entryTag);
        });
        tag.put("borders", bordersTag);

        if (activeBorderName != null) {
            tag.putString("active", activeBorderName);
        }

        // 保存编辑焦点
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
                byte[] grid = entryTag.getByteArray("grid");

                BorderEntry entry = new BorderEntry(x, z, w, d);
                // 简单的防错
                if (grid.length == w * d) {
                    entry.grid = grid;
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
//// 【核心方法】同步给单个玩家
//// 逻辑：
//// 1. 告诉他现在服务器有哪些墙的名字（用于更新列表 UI）
//// 2. 检查他正在关注哪个墙，把那个墙的详细网格数据发给他
//public void syncToPlayer(ServerPlayer player) {
//    if (player == null) return;
//
//    // 1. 同步名字列表 (轻量级)
//    // 这样玩家打开 GUI 列表时，能看到最新的所有墙
//    ModMessage.sendToPlayer(new PacketSyncBorderNames(getAllNamesList()), player);
//
//    // 2. 同步焦点数据 (重量级)
//    String focusName = getPlayerFocus(player.getUUID());
//
//    // 如果玩家还没关注任何墙，尝试自动给他分配一个默认的 (模仿 PosSavedData 的体验)
//    if (focusName == null || !borders.containsKey(focusName)) {
//        if (!borders.isEmpty()) {
//            focusName = borders.keySet().iterator().next();
//            setPlayerFocus(player.getUUID(), focusName); // 记住这个自动分配
//        }
//    }
//    // 如果确定了关注对象，发送详细网格数据
//    if (focusName != null && borders.containsKey(focusName)) {
//        BorderEntry entry = borders.get(focusName);
//        // 发送数据包：告诉客户端 "你现在的 Focus 是 focusName，它的数据是 entry"
//        ModMessage.sendToPlayer(new PacketSyncBorder(focusName, entry,true), player);
//    } else {
//        // 如果真的没有墙，或者被删光了，发个空包或者清除指令给客户端
//        // ModMessage.sendToPlayer(new PacketClearBorderCache(), player);
//    }
//}
//// 【核心方法】同步给全服所有玩家
//// 这里的精髓在于：虽然是“同步给所有人”，但每个人的 syncToPlayer
//// 会根据他们各自的 Focus 拿到不同的数据！
//// 张三 Focus "Wall_A" -> 收到 "Wall_A" 的数据
//// 李四 Focus "Wall_B" -> 收到 "Wall_B" 的数据
//public void syncToAll(ServerLevel level) {
//    if (level == null) return;
//
//    // 遍历每一个在线玩家，单独为他定制数据包
//    for (ServerPlayer player : level.players()) {
//        syncToPlayer(player);
//    }
//}