package com.mapextra.net;

import com.mapextra.client.render.GeometryCache;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server -> Client：同步一次雷达扫描事件（中心点 + startTick + 目标列表）
 */
public class PacketRadarScanSync {

    public final double ox, oy, oz;
    public final long startTick;

    public final List<Target> targets;

    public static final class Target {
        public final UUID uuid;
        public final double x, y, z;

        public Target(UUID uuid, double x, double y, double z) {
            this.uuid = uuid;
            this.x = x; this.y = y; this.z = z;
        }
    }

    public PacketRadarScanSync(double ox, double oy, double oz, long startTick, List<Target> targets) {
        this.ox = ox; this.oy = oy; this.oz = oz;
        this.startTick = startTick;
        this.targets = targets;
    }

    public static void encode(PacketRadarScanSync msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.ox);
        buf.writeDouble(msg.oy);
        buf.writeDouble(msg.oz);
        buf.writeLong(msg.startTick);

        buf.writeInt(msg.targets.size());
        for (Target t : msg.targets) {
            buf.writeUUID(t.uuid);
            buf.writeDouble(t.x);
            buf.writeDouble(t.y);
            buf.writeDouble(t.z);
        }
    }

    public static PacketRadarScanSync decode(FriendlyByteBuf buf) {
        double ox = buf.readDouble();
        double oy = buf.readDouble();
        double oz = buf.readDouble();
        long startTick = buf.readLong();

        int n = buf.readInt();
        List<Target> targets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            targets.add(new Target(id, x, y, z));
        }

        return new PacketRadarScanSync(ox, oy, oz, startTick, targets);
    }

    public static void handle(PacketRadarScanSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            GeometryCache.getInstance().offerServerScan(mc.level, msg.ox, msg.oy, msg.oz, msg.targets);
        });
        ctx.get().setPacketHandled(true);
    }
}
