package com.mapextra.net;

import com.mapextra.world.BorderData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketSetBorderFocus {
    public final String name;

    public PacketSetBorderFocus(String name) {
        this.name = name;
    }

    public static void encode(PacketSetBorderFocus msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name);
    }

    public static PacketSetBorderFocus decode(FriendlyByteBuf buf) {
        return new PacketSetBorderFocus(buf.readUtf());
    }

    public static void handle(PacketSetBorderFocus msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            BorderData data = BorderData.get(player.serverLevel());

            // 1. 在服务端记录玩家当前关注的是哪个墙
            data.setPlayerFocus(player.getUUID(), msg.name);

            // 2. 【核心修复】立刻查找该墙体数据，并发回给客户端！
            BorderData.BorderEntry entry = data.getEntry(msg.name);

            // 发送同步包：
            // 参数3 isFocusSync = true (表示这是为了更新橘色编辑框)
            // 如果 entry 是 null (比如切到了一个空名字)，也要发个 null 过去清空客户端缓存
            ModMessage.sendToPlayer(new PacketSyncBorder(msg.name, entry, true), player);
        });
        ctx.get().setPacketHandled(true);
    }
}
