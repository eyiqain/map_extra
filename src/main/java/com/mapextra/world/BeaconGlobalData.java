package com.mapextra.world;

import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketSyncBeacon;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

public class BeaconGlobalData extends SavedData {
    private static final String DATA_NAME = "mapextra_global_beacons";

    // 使用 Set 防止重复
    private final Set<BlockPos> beacons = new HashSet<>();

    public static BeaconGlobalData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(BeaconGlobalData::load, BeaconGlobalData::new, DATA_NAME);
    }

    public Set<BlockPos> getBeacons() {
        return beacons;
    }

    public void addBeacon(BlockPos pos) {
        if (beacons.add(pos)) {
            setDirty();
        }
    }

    public void clearBeacons() {
        beacons.clear();
        setDirty();
    }

    // === NBT 读写逻辑 ===

    public BeaconGlobalData() {}

    public static BeaconGlobalData load(CompoundTag tag) {
        BeaconGlobalData data = new BeaconGlobalData();
        ListTag list = tag.getList("Beacons", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.beacons.add(NbtUtils.readBlockPos(list.getCompound(i)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (BlockPos pos : beacons) {
            list.add(NbtUtils.writeBlockPos(pos));
        }
        tag.put("Beacons", list);
        return tag;
    }

    public void syncToPlayer(ServerPlayer player) {
        if (player == null) return;

        try {
            // 1. 获取当前存储的所有信标点
            // 为了防止并发修改异常，建议这里传给包之前做一个浅拷贝，或者确保 getBeacons 返回的是安全的数据
            Set<BlockPos> currentBeacons = getBeacons();

            // 2. 构建数据包并发送
            // 使用我们之前封装好的 ModMessage 发送给单个玩家
            ModMessage.sendToPlayer(new PacketSyncBeacon(currentBeacons), player);

        } catch (Exception e) {
            e.printStackTrace(); // 打印错误日志，方便调试
        }
    }
}
