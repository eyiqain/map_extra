package com.mapextra.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端告诉服务端：我触发了一次雷达扫描 */
public class PacketRadarScanRequest {

    public PacketRadarScanRequest() {}

    public static void encode(PacketRadarScanRequest msg, FriendlyByteBuf buf) {
        // 无内容
    }

    public static PacketRadarScanRequest decode(FriendlyByteBuf buf) {
        return new PacketRadarScanRequest();
    }

    public static void handle(PacketRadarScanRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            // ✅ 服务端计算 + 广播给所有人
            RadarScanService.broadcastScan(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}
