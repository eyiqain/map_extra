package com.mapextra.net;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class RadarScanService {

    private static final double SCAN_RADIUS = 30.0;

    private RadarScanService() {}

    public static void broadcastScan(ServerPlayer sender) {
        double ox = sender.getX();
        double oy = sender.getY();
        double oz = sender.getZ();

        long startTick = sender.level().getGameTime();

        List<PacketRadarScanSync.Target> targets = new ArrayList<>();
        for (ServerPlayer p : sender.server.getPlayerList().getPlayers()) {
            if (p == sender) continue;

            double dx = p.getX() - ox;
            double dz = p.getZ() - oz;
            double r = Math.sqrt(dx*dx + dz*dz);

            if (r <= SCAN_RADIUS) {
                targets.add(new PacketRadarScanSync.Target(
                        p.getUUID(), p.getX(), p.getY(), p.getZ()
                ));
            }
        }

        PacketRadarScanSync pkt = new PacketRadarScanSync(ox, oy, oz, startTick, targets);
        ModMessage.sendToAll(pkt);
    }
}
