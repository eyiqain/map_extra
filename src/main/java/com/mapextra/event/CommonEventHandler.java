package com.mapextra.event;

import com.mapextra.MapExtra;
import com.mapextra.client.particles.ParticleRenderRegistry;
import com.mapextra.world.BeaconGlobalData;
import com.mapextra.world.PosSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CommonEventHandler {

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        syncToPlayer(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        syncToPlayer(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        syncToPlayer(event.getEntity());
    }

    // 封装好的同步方法
    private static void syncToPlayer(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            PosSavedData.get(serverPlayer.level()).syncToPlayer(serverPlayer);
            BeaconGlobalData.get((ServerLevel) serverPlayer.level()).syncToPlayer(serverPlayer);
        }
    }

    @Mod.EventBusSubscriber(modid = MapExtra.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public final class ClientModEvents {

        @SubscribeEvent
        public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
            ParticleRenderRegistry.onRegisterProviders(event);
        }
    }

}
