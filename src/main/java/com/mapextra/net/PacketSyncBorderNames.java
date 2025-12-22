package com.mapextra.net;

import com.mapextra.client.ClientPosCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PacketSyncBorderNames {
    public final List<String> names;

    public PacketSyncBorderNames(List<String> names) {
        this.names = names;
    }

    public static void encode(PacketSyncBorderNames msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.names, FriendlyByteBuf::writeUtf);
    }

    public static PacketSyncBorderNames decode(FriendlyByteBuf buf) {
        return new PacketSyncBorderNames(buf.readCollection(ArrayList::new, FriendlyByteBuf::readUtf));
    }

    public static void handle(PacketSyncBorderNames msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 在客户端执行
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                // 需要你在 ClientPosCache 里加一个 updateBorderNames 方法
                ClientPosCache.updateBorderNames(msg.names);
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
