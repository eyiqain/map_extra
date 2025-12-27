package com.mapextra.net;

import com.mapextra.client.ClientPosCache;
import com.mapextra.world.BorderData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketSyncBorder {
    public final String name;
    public final BorderData.BorderEntry entry;
    public final boolean isFocusSync; // 新增字段：是否为焦点同步

    public PacketSyncBorder(String name, BorderData.BorderEntry entry, boolean isFocusSync) {
        this.name = name;
        this.entry = entry;
        this.isFocusSync = isFocusSync;
    }

    public static void encode(PacketSyncBorder msg, FriendlyByteBuf buf) {
        // 1. 写名字 (防止 null)
        buf.writeUtf(msg.name == null ? "" : msg.name);

        // 2. 【关键】写标记 (顺序必须和 decode 一致)
        buf.writeBoolean(msg.isFocusSync);

        // 3. 写 Entry 是否存在
        boolean hasEntry = (msg.entry != null);
        buf.writeBoolean(hasEntry);

        // 4. 写 Entry 详情
        if (hasEntry) {
            buf.writeDouble(msg.entry.startX);
            buf.writeDouble(msg.entry.startZ);
            buf.writeInt(msg.entry.width);
            buf.writeInt(msg.entry.depth);

            // 防止 grid 为 null 导致崩溃
            if (msg.entry.grid == null) {
                // 如果是新建的空墙，发一个全0的数组
                buf.writeByteArray(new byte[msg.entry.width * msg.entry.depth]);
            } else {
                buf.writeByteArray(msg.entry.grid);
            }
        }
    }

    public static PacketSyncBorder decode(FriendlyByteBuf buf) {
        // 1. 读名字
        String name = buf.readUtf();

        // 2. 【关键】读标记
        boolean isFocusSync = buf.readBoolean();

        // 3. 读 Entry 是否存在
        boolean hasEntry = buf.readBoolean();

        BorderData.BorderEntry entry = null;
        if (hasEntry) {
            double x = buf.readDouble();
            double z = buf.readDouble();
            int w = buf.readInt();
            int d = buf.readInt();
            byte[] grid = buf.readByteArray(); // 读 grid

            entry = new BorderData.BorderEntry(x, z, w, d);
            entry.grid = grid;
        }

        return new PacketSyncBorder(name, entry, isFocusSync);
    }

    public static void handle(PacketSyncBorder msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                // 调用 ClientPosCache 更新
                ClientPosCache.handleBorderSync(msg);
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
