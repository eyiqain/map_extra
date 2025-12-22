package com.mapextra.net;

import com.mapextra.client.ClientPosCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class PacketSyncBeacon {
    public final Set<BlockPos> beacons;

    // 构造器（保持原有逻辑）
    public PacketSyncBeacon(Set<BlockPos> beacons) {
        this.beacons = beacons;
    }

    // ==================== 修复1：解码方法改为静态（匹配Forge规范） ====================
    public static PacketSyncBeacon decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Set<BlockPos> posSet = new HashSet<>();
        for (int i = 0; i < size; i++) {
            posSet.add(buf.readBlockPos());
        }
        return new PacketSyncBeacon(posSet);
    }

    // ==================== 修复2：编码方法改名（匹配ModMessage中的encoder引用） ====================
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(beacons.size());
        for (BlockPos pos : beacons) {
            buf.writeBlockPos(pos);
        }
    }

    // ==================== 修复3：完善处理逻辑（标记包已处理） ====================
    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        // 确保在客户端主线程执行
        ctx.enqueueWork(() -> {
            // 原有客户端缓存更新逻辑不变
            ClientPosCache.updateBeacons(beacons);
        });
        // 标记包已处理，避免网络异常
        ctx.setPacketHandled(true);
        return true;
    }
}