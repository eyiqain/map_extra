package com.mapextra.command; // 你的包名

import com.mapextra.item.Radar;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RadarCommand {

    // 注册指令的方法
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 定义指令结构: /radar set <范围>
        dispatcher.register(Commands.literal("radar") // 第一级：radar
                .then(Commands.literal("set")             // 第二级：set
                        .then(Commands.argument("range", IntegerArgumentType.integer(1, 500)) // 第三级：输入整数(1到500)
                                .executes(context -> {
                                    // === 指令执行的逻辑 ===

                                    // 1. 获取玩家输入的数字
                                    int newRange = IntegerArgumentType.getInteger(context, "range");

                                    // 2. 修改 Seeker 类里的那个变量
                                    Radar.SEARCH_RANGE = newRange;

                                    // 3. 给玩家发送反馈消息
                                    context.getSource().sendSuccess(() -> Component.literal("§a雷达范围已设置为: " + newRange + "米"), true);

                                    return 1; // 返回 1 表示执行成功
                                })
                        )
                )
        );
    }
}
