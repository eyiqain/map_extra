package com.mapextra.init;

import com.mapextra.MapExtra;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MapExtra.MODID);

    // 注册添加声音
    public static final RegistryObject<SoundEvent> WRENCH_ADD = SOUNDS.register("wrench_add",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MapExtra.MODID, "wrench_add")));

    // 注册移除声音
    public static final RegistryObject<SoundEvent> WRENCH_REMOVE = SOUNDS.register("wrench_remove",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MapExtra.MODID, "wrench_remove")));

    //注册雷达声音
    public static final RegistryObject<SoundEvent> RADAR = SOUNDS.register("radar",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MapExtra.MODID, "radar")));

    public static void register(IEventBus eventBus) {
        SOUNDS.register(eventBus);
    }
}
