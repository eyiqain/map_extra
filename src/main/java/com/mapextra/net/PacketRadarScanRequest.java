package com.mapextra.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server：请求触发一次雷达扫描
 */
public class PacketRadarScanRequest {

    public PacketRadarScanRequest() {}

    public static void encode(PacketRadarScanRequest msg, FriendlyByteBuf buf) {
        // 无需字段
    }

    public static PacketRadarScanRequest decode(FriendlyByteBuf buf) {
        return new PacketRadarScanRequest();
    }

    public static void handle(PacketRadarScanRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            // ✅ 服务端权威生成扫描事件并广播给所有客户端
            RadarScanService.broadcastScan(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}