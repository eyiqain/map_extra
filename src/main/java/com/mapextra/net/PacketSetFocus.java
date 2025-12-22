package com.mapextra.net;

import com.mapextra.world.PosSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketSetFocus {
    public final String tagName;

    public PacketSetFocus(String tagName) {
        this.tagName = tagName;
    }

    // 【新增】静态解码方法，符合你的喜好
    public static PacketSetFocus decode(FriendlyByteBuf buf) {
        return new PacketSetFocus(buf.readUtf());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(tagName);
    }

    public static void handle(PacketSetFocus msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                // 修改服务端的 Focus 数据
                PosSavedData.get(player.level()).setFocus(player.getUUID(), msg.tagName, player);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
