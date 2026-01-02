package com.mapextra.net;

import com.mapextra.client.render.GeometryCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class PacketRadarScanSync {

    public final double ox, oy, oz;
    public final List<Target> targets;

    public static final class Target {
        public final UUID uuid;
        public final double x, y, z;

        public Target(UUID uuid, double x, double y, double z) {
            this.uuid = uuid;
            this.x = x; this.y = y; this.z = z;
        }
    }

    public PacketRadarScanSync(double ox, double oy, double oz, List<Target> targets) {
        this.ox = ox; this.oy = oy; this.oz = oz;
        this.targets = targets;
    }

    public static void encode(PacketRadarScanSync msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.ox);
        buf.writeDouble(msg.oy);
        buf.writeDouble(msg.oz);

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

        int n = buf.readInt();
        List<Target> targets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            targets.add(new Target(id, x, y, z));
        }
        return new PacketRadarScanSync(ox, oy, oz, targets);
    }

    /** 客户端收到：往本地 GeometryCache 队列塞一个新的 entry */
    public static void handle(PacketRadarScanSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;

            // ✅ 把服务端给的 targets 转成 GeometryCache.ScanTarget（并算 triggerMs）
            GeometryCache.getInstance().offerServerScan(mc.level, msg.ox, msg.oy, msg.oz, msg.targets);
        });
        ctx.get().setPacketHandled(true);
    }
}
