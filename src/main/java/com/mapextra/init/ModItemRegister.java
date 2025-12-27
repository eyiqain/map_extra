package com.mapextra.init;

import com.mapextra.item.Hammer;
import com.mapextra.item.Radar;
import com.mapextra.item.Wrench;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import static com.mapextra.MapExtra.MODID;

public class ModItemRegister {
    // 物品注册器（延迟注册模式）
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    // 注册扳手物品
    public static final RegistryObject<Item> WRENCH =
            ITEMS.register("wrench", () -> new Wrench(new Item.Properties()));
    // 注册锤子物品
    public static final RegistryObject<Item> HAMMER =
            ITEMS.register("hammer", () -> new Hammer(new Item.Properties()));
    // 注册搜索雷达物品（躲猫猫道具）
    public static final RegistryObject<Item> RADAR =
            ITEMS.register("radar", () -> new Radar(new Item.Properties()));

    // 创造模式标签页注册器
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // 新标签页
    public static final RegistryObject<CreativeModeTab> MAGIC_EXTRA_TAB =
            CREATIVE_MODE_TABS.register("mapextra",
                    () -> CreativeModeTab.builder()
                            .icon(() -> new ItemStack(WRENCH.get())) // 设置标签页图标
                            .title(Component.translatable("item.group.mapextra")) // 本地化标题
                            .displayItems((params, output) -> {          // 添加物品到标签页
                                // 自动添加所有已注册物品
                                ITEMS.getEntries().forEach(entry ->
                                        output.accept(entry.get())
                                );
                            })
                            .build()
            );

    // 注册方法（需在主类调用）
    public static void init(IEventBus bus) {
        ITEMS.register(bus);          // 注册物品到总线[1](@ref)
        CREATIVE_MODE_TABS.register(bus); // 注册标签页到总线[1,5](@ref)
    }
}