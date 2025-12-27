package com.mapextra.command;

import com.mapextra.MapExtra;
import com.mapextra.item.Radar;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RadarCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 命令结构改为:
        // /radar set range <数值>
        // /radar set cooldown <数值>
        dispatcher.register(Commands.literal("radar")
                .then(Commands.literal("set")

                        // 分支 1：设置范围 (Range)
                        .then(Commands.literal("range")
                                .then(Commands.argument("value", IntegerArgumentType.integer(1, 500))
                                        .executes(context -> {
                                            int newRange = IntegerArgumentType.getInteger(context, "value");
                                            Radar.SEARCH_RANGE = newRange;
                                            context.getSource().sendSuccess(() -> Component.literal("§a✅雷达范围已设置为: " + newRange + "米"), true);
                                            return 1;
                                        })
                                )
                        )

                        // 分支 2：设置冷却 (Cooldown)
                        .then(Commands.literal("cooldown")
                                // 这里限制输入 0 到 1200 (即最长1分钟)，你可以根据需要改大
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 1200))
                                        .executes(context -> {
                                            int newCooldown = IntegerArgumentType.getInteger(context, "ticks");
                                            Radar.COOLDOWN_TICKS = newCooldown;
                                            double seconds = newCooldown / 20.0; // 换算成秒显示给玩家看
                                            context.getSource().sendSuccess(() -> Component.literal("§b⌚雷达冷却已设置为: " + newCooldown + " ticks (" + seconds + "秒)"), true);
                                            return 1;
                                        })
                                )
                        )
                )
        );
    }
}
