package com.mapextra.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 作用：客户端告诉服务端 "我扫描了多少个面"，
 * 服务端收到后，广播给所有玩家显示在物品栏上方。
 */
public class PacketShareQuadCount {
    private final int count;

    public PacketShareQuadCount(int count) {
        this.count = count;
    }

    // 编码：写入整数
    public static void encode(PacketShareQuadCount msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.count);
    }

    // 解码：读取整数
    public static PacketShareQuadCount decode(FriendlyByteBuf buf) {
        return new PacketShareQuadCount(buf.readInt());
    }

    // 处理：服务端逻辑
    public static void handle(PacketShareQuadCount msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) {
                // 构建消息：显示是谁扫描的，以及面数
                String text = "§e[📡雷达广播] §f玩家 §b" + sender.getName().getString() + " §f当前捕获面数: §a" + msg.count;
                Component component = Component.literal(text);

                // ✅ 广播给服务器里的【所有】玩家，显示在 Action Bar (物品栏上方)
                for (ServerPlayer player : sender.server.getPlayerList().getPlayers()) {
                    player.displayClientMessage(component, true);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
