package com.mapextra.command;

import com.mapextra.MapExtra;
import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketSyncBeacon;
import com.mapextra.net.PacketSyncBorder;
import com.mapextra.world.BeaconGlobalData;
import com.mapextra.world.BorderData;
import com.mapextra.world.PosSavedData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModCommands {

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TAGS = (context, builder) -> {
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());
        return SharedSuggestionProvider.suggest(data.getAllTags(), builder);
    };
    // 【新增】BORDER 名称提示
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_BORDERS = (context, builder) -> {
        BorderData data = BorderData.get(context.getSource().getLevel());
        return SharedSuggestionProvider.suggest(data.getAllNames(), builder);
    };


    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // --- 1. 构建 Random 分支 ---
        LiteralArgumentBuilder<CommandSourceStack> randomNode = Commands.literal("random");

        // A. /point random run ... (保持原有功能：在随机点执行指令)
        randomNode.then(Commands.literal("run")
                .fork(dispatcher.getRoot(), context -> resolveRandomSource(context, null))
        );

        // B. /point random <tagName> ...
        ArgumentBuilder<CommandSourceStack, ?> tagArgForRandom = Commands.argument("tagName", StringArgumentType.word())
                .suggests(SUGGEST_TAGS);

        // B1. /point random <tagName> run ...
        tagArgForRandom.then(Commands.literal("run")
                .fork(dispatcher.getRoot(), context -> resolveRandomSource(context, StringArgumentType.getString(context, "tagName")))
        );

        // B2. /point random <tagName> (只显示坐标)
        tagArgForRandom.executes(context -> executeRandomTp(context, StringArgumentType.getString(context, "tagName")));

        // A2. /point random (只显示坐标)
        randomNode.executes(context -> executeRandomTp(context, null));

        // 组合 Random 节点
        randomNode.then(tagArgForRandom);


        // --- 2. 注册主指令树 ---
        dispatcher.register(
                Commands.literal("point")
                        .requires(source -> source.hasPermission(2))

                        // help 【新增】
                        .then(Commands.literal("help")
                                .executes(ModCommands::helpCommand))

                        // create
                        .then(Commands.literal("create")
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .executes(ModCommands::createTag))
                        )

                        // focus
                        .then(Commands.literal("focus")
                                .executes(ModCommands::showCurrentFocus)
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_TAGS)
                                        .executes(ModCommands::focusTag))
                        )

                        // list
                        .then(Commands.literal("list")
                                .executes(ModCommands::listAllTags)
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_TAGS)
                                        .executes(ModCommands::listSpecificTag))
                        )

                        // clear (含 all)
                        .then(Commands.literal("clear")
                                .then(Commands.literal("all")
                                        .executes(ModCommands::clearAllTags)
                                )
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_TAGS)
                                        .executes(context -> clearTag(context, StringArgumentType.getString(context, "tagName")))
                                )
                                .executes(context -> clearTag(context, null))
                        )

                        // undo (含 all)
                        .then(Commands.literal("undo")
                                .then(Commands.literal("all")
                                        .executes(ModCommands::undoAllTags)
                                )
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_TAGS)
                                        .executes(ModCommands::undoClear)
                                )
                        )

                        // random
                        .then(randomNode)
                        // === 新增：信标 (Beacon) 指令 ===
                        .then(Commands.literal("beacon") // 对应 "信标"
                                // 1. Clear
                                .then(Commands.literal("clear")
                                        .executes(context -> clearBeacons(context))
                                )
                                // 2. Add (带坐标)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(context -> addBeacon(context, BlockPosArgument.getLoadedBlockPos(context, "pos")))
                                        )
                                        // 默认 Add (当前位置) -> 修复点：使用 BlockPos.containing()
                                        .executes(context -> addBeacon(context, BlockPos.containing(context.getSource().getPosition())))
                                )
                                // 3. Null (默认行为 -> Add 当前位置) -> 修复点：使用 BlockPos.containing()
                                .executes(context -> addBeacon(context, BlockPos.containing(context.getSource().getPosition())))
                        )
        );
        // --- 2. 注册主指令树 (Borders) ---
        dispatcher.register(
                Commands.literal("borders")
                        .requires(source -> source.hasPermission(2))

                        // === 新增 0: 帮助信息 ===
                        .then(Commands.literal("help")
                                .executes(ModCommands::helpBorders)
                        )

                        .then(Commands.literal("add")

                                // 原来的：/borders add <name> <x> <z> <w> <d>
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("w", IntegerArgumentType.integer(1, 10000))
                                                                .then(Commands.argument("d", IntegerArgumentType.integer(1, 10000))
                                                                        .executes(ModCommands::addBorder)
                                                                )
                                                        )
                                                )
                                        )
                                )

                                // 新增：/borders add center <name> <radius>
                                .then(Commands.literal("center")
                                        .then(Commands.argument("tagName", StringArgumentType.word())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 5000))
                                                        .executes(ModCommands::addBorderCenterRing)
                                                )
                                        )
                                )
                        )

                        // === 新增 2: delete / clear ===
                        // 2.1 delete <name>
                        .then(Commands.literal("delete")
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_BORDERS) // 使用新的提示器
                                        .executes(context -> deleteBorderByName(context, StringArgumentType.getString(context, "tagName")))
                                )
                        )
                        // 2.2 clear (all | name | default)
                        .then(Commands.literal("clear")
                                // clear all
                                .then(Commands.literal("all")
                                        .executes(ModCommands::clearAllBorders)
                                )
                                // clear <tagName>
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_BORDERS)
                                        .executes(context -> deleteBorderByName(context, StringArgumentType.getString(context, "tagName")))
                                )
                                // clear (默认删除当前 Focus 的)
                                .executes(ModCommands::clearFocusedBorder)
                        )

                        // 3. focus <tagName>
                        .then(Commands.literal("focus")
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_BORDERS) // 添加提示
                                        .executes(ModCommands::focusBorder)
                                )
                        )

                        // 4. start [tagName]
                        .then(Commands.literal("start")
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_BORDERS) // 添加提示
                                        .executes(context -> startBorder(context, StringArgumentType.getString(context, "tagName")))
                                )
                                .executes(context -> startBorder(context, null))
                        )

                        // 5. stop
                        .then(Commands.literal("stop")
                                .executes(ModCommands::stopBorder)
                        )

                        // 6. setblock
                        .then(Commands.literal("setblock")
                                .then(Commands.argument("lx", IntegerArgumentType.integer())
                                        .then(Commands.argument("lz", IntegerArgumentType.integer())
                                                .then(Commands.argument("state", IntegerArgumentType.integer(0, 1))
                                                        .executes(ModCommands::setBorderBlock)
                                                )
                                        )
                                )
                        )

                        // 7. setline
                        .then(Commands.literal("setline")
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                .then(Commands.argument("state", IntegerArgumentType.integer(0, 1))
                                                                        .executes(ModCommands::setBorderLine)
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
        );
    }


    // ================= 逻辑实现：Help 【新增】 =================

    private static int helpCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        source.sendSuccess(() -> Component.literal("=== MapExtra 指令帮助 ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        // 辅助方法：发送一行帮助
        sendHelpLine(source, "/point help", "显示此帮助信息");
        sendHelpLine(source, "/point create <组名>", "创建并关注一个新的标签组");
        sendHelpLine(source, "/point focus [组名]", "查看当前关注点，或切换到指定组");
        sendHelpLine(source, "/point list [组名]", "列出所有组，或列出指定组下的所有坐标");
        sendHelpLine(source, "/point random [组名]", "随机抽取一个坐标并生成传送指令");
        sendHelpLine(source, "/point random [组名] run <指令>", "在随机出的坐标处执行指令");
        sendHelpLine(source, "/point clear [组名|all]", "清空指定组或所有组的坐标 (放入回收站)");
        sendHelpLine(source, "/point undo [组名|all]", "从回收站恢复被清空的数据");
        sendHelpLine(source, "/point beacon ~ ~ ~", "创造一个全新信标点");
        source.sendSuccess(() -> Component.literal("提示：手持扳手可显示HUD，Alt+滚轮可快速切换关注组").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);

        return 1;
    }

    private static void sendHelpLine(CommandSourceStack source, String cmd, String desc) {
        source.sendSuccess(() -> Component.literal(cmd)
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(desc).withStyle(ChatFormatting.WHITE)), false);
    }

    // ================= 逻辑实现：常规管理 =================

    private static int createTag(CommandContext<CommandSourceStack> context) {
        String tagName = StringArgumentType.getString(context, "tagName");
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());
        ServerPlayer player = context.getSource().getPlayer();
        if (data.createTag(tagName,player)) {
            context.getSource().sendSuccess(() -> Component.literal(" 已创建新标签组: " + tagName).withStyle(ChatFormatting.GREEN), true);
        } else {
            context.getSource().sendFailure(Component.literal(" 标签组 " + tagName + " 已存在！"));
        }
        return 1;
    }

    private static int focusTag(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String tagName = StringArgumentType.getString(context, "tagName");
        ServerPlayer player = context.getSource().getPlayerOrException();
        PosSavedData data = PosSavedData.get(player.level());

        if (data.setFocus(player.getUUID(), tagName, player)) {
            context.getSource().sendSuccess(() -> Component.literal("👁 关注点切换至: " + tagName).withStyle(ChatFormatting.GOLD), true);
        } else {
            context.getSource().sendFailure(Component.literal(" 标签组不存在: " + tagName));
        }
        return 1;
    }

    private static int listAllTags(CommandContext<CommandSourceStack> context) {
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());
        Set<String> tags = data.getAllTags();
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("=== 所有标签组 ===").withStyle(ChatFormatting.GOLD), false);
        for (String tag : tags) {
            source.sendSuccess(() -> Component.literal(" - " + tag + ": " + data.getPositions(tag).size() + " 个坐标"), false);
        }
        return 1;
    }

    private static int listSpecificTag(CommandContext<CommandSourceStack> context) {
        String tagName = StringArgumentType.getString(context, "tagName");
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());
        List<BlockPos> list = data.getPositions(tagName);
        context.getSource().sendSuccess(() -> Component.literal("=== [" + tagName + "] 坐标列表 (" + list.size() + ") ===").withStyle(ChatFormatting.AQUA), false);
        for (BlockPos p : list) {
            context.getSource().sendSuccess(() -> Component.literal(" - " + p.toShortString()), false);
        }
        return 1;
    }

    // ================= 逻辑实现：Clear / Undo =================

    private static int clearTag(CommandContext<CommandSourceStack> context, String tagName) throws CommandSyntaxException {
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());

        if (tagName == null) {
            if (context.getSource().getEntity() instanceof Player p) {
                tagName = data.getFocus(p.getUUID());
            } else {
                tagName = PosSavedData.DEFAULT_TAG;
            }
        }

        int count = data.clearTag(tagName);

        if (count > 0) {
            if (context.getSource().getEntity() instanceof ServerPlayer player) {
                if (data.getFocus(player.getUUID()).equals(tagName)) {
                    data.syncToPlayer(player);
                }
            }

            String finalTagName = tagName;
            context.getSource().sendSuccess(() -> Component.literal(" 已清空 [" + finalTagName + "] (" + count + " 个坐标)")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" [点此撤销]").withStyle(style -> style
                            .withColor(ChatFormatting.RED).withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/point undo " + finalTagName))
                    )), true);
        } else {
            context.getSource().sendFailure(Component.literal("该标签组已经是空的了。"));
        }
        return 1;
    }

    private static int clearAllTags(CommandContext<CommandSourceStack> context) {
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());
        int total = data.clearAll();

        if (total > 0) {
            if (context.getSource().getEntity() instanceof ServerPlayer player) {
                data.syncToPlayer(player);
            }

            context.getSource().sendSuccess(() -> Component.literal(" 已清空所有标签组，共移除 " + total + " 个坐标。")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" [撤销全部]").withStyle(style -> style
                            .withColor(ChatFormatting.GOLD).withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/point undo all"))
                    )), true);
        } else {
            context.getSource().sendFailure(Component.literal("没有任何数据可清除。"));
        }
        return 1;
    }

    private static int undoClear(CommandContext<CommandSourceStack> context) {
        String tagName = StringArgumentType.getString(context, "tagName");
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());

        if (data.undoClear(tagName)) {
            if (context.getSource().getEntity() instanceof ServerPlayer player) {
                if (data.getFocus(player.getUUID()).equals(tagName)) {
                    data.syncToPlayer(player);
                }
            }
            context.getSource().sendSuccess(() -> Component.literal(" 成功恢复 [" + tagName + "] 的数据！").withStyle(ChatFormatting.GREEN), true);
        } else {
            context.getSource().sendFailure(Component.literal("没有可撤销的数据。"));
        }
        return 1;
    }

    private static int undoAllTags(CommandContext<CommandSourceStack> context) {
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());

        if (data.undoAll()) {
            if (context.getSource().getEntity() instanceof ServerPlayer player) {
                data.syncToPlayer(player);
            }
            context.getSource().sendSuccess(() -> Component.literal(" 已恢复所有回收站中的数据！").withStyle(ChatFormatting.GREEN), true);
        } else {
            context.getSource().sendFailure(Component.literal("回收站是空的，无法恢复。"));
        }
        return 1;
    }

    // ================= 逻辑实现：Random =================

    private static List<CommandSourceStack> resolveRandomSource(CommandContext<CommandSourceStack> context, String explicitTag) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        PosSavedData data = PosSavedData.get(level);

        String targetTag = explicitTag;
        if (targetTag == null) {
            if (source.getEntity() instanceof Player player) {
                targetTag = data.getFocus(player.getUUID());
            } else {
                targetTag = PosSavedData.DEFAULT_TAG;
            }
        }

        BlockPos pos = data.getRandomPos(targetTag, level);
        if (pos == null) {
            throw new SimpleCommandExceptionType(Component.literal(" 标签 [" + targetTag + "] 下没有可用坐标！").withStyle(ChatFormatting.RED)).create();
        }

        Vec3 newPos = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return Collections.singletonList(source.withPosition(newPos));
    }

    // 重点修改：不再传送，改为输出坐标
    private static int executeRandomTp(CommandContext<CommandSourceStack> context, String explicitTag) throws CommandSyntaxException {
        // 复用逻辑获取坐标
        List<CommandSourceStack> sources = resolveRandomSource(context, explicitTag);
        Vec3 pos = sources.get(0).getPosition();

        String coordString = String.format("%d %d %d", (int)pos.x, (int)pos.y, (int)pos.z);
        String tpCommand = "/tp @s " + coordString;

        context.getSource().sendSuccess(() -> Component.literal(" 随机结果: ")
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("[" + coordString + "]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withBold(true)
                                // 点击这里会将 /tp x y z 放入聊天框，方便玩家手动传送
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tpCommand))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("点击生成传送指令")))
                        )), false);
        return 1;
    }
    private static int showCurrentFocus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PosSavedData data = PosSavedData.get(player.level());

        String currentTag = data.getFocus(player.getUUID());

        context.getSource().sendSuccess(() -> Component.literal(" 当前关注的标签组: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("[" + currentTag + "]")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)), false);
        return 1;
    }

    private static int addBeacon(CommandContext<CommandSourceStack> context, BlockPos pos) {
        ServerLevel level = context.getSource().getLevel();

        // 1. 添加到全局透视 (BeaconGlobalData)
        BeaconGlobalData globalData = BeaconGlobalData.get(level);
        globalData.addBeacon(pos);

        // 【修改网络发包】
        ModMessage.sendToAll(new PacketSyncBeacon(globalData.getBeacons()));

//        // 2. 代码复用：同时添加到 "Beacon" 普通标签组，以便在 list 中显示
//        PosSavedData posData = PosSavedData.get(level);
//       String commonTagName = "Beacon";
//
//        List<BlockPos> list = posData.getPositions(commonTagName);
//        if (!list.contains(pos)) {
//            list.add(pos);
//            posData.setDirty();
//        }

        context.getSource().sendSuccess(() -> Component.literal("§b[信标] §f已添加全局透视点: " + pos.toShortString()), true);
        return 1;
    }

    private static int clearBeacons(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();

        // 1. 清空全局透视
        BeaconGlobalData globalData = BeaconGlobalData.get(level);
        globalData.clearBeacons();

        // 【修改网络发包】
        ModMessage.sendToAll(new PacketSyncBeacon(globalData.getBeacons()));

//        // 2. 同步清空 "Beacon" 普通标签组
//        PosSavedData posData = PosSavedData.get(level);
//        posData.clearTag("Beacon");

        context.getSource().sendSuccess(() -> Component.literal("§b[信标] §f已清空所有全局点 (含 'Beacon' 标签组)"), true);
        return 1;
    }
// ================= 逻辑实现：Border =================

private static int addBorder(CommandContext<CommandSourceStack> context) {
    String name = StringArgumentType.getString(context, "tagName");
    double x = DoubleArgumentType.getDouble(context, "x");
    double z = DoubleArgumentType.getDouble(context, "z");
    int w = IntegerArgumentType.getInteger(context, "w");
    int d = IntegerArgumentType.getInteger(context, "d");

    ServerLevel level = context.getSource().getLevel();
    BorderData data = BorderData.get(level);

    data.addBorder(name, x, z, w, d);

    // 自动将创建者的焦点切换到新边界
    if (context.getSource().getEntity() instanceof ServerPlayer player) {
        data.setPlayerFocus(player.getUUID(), name);
    }

    context.getSource().sendSuccess(() -> Component.literal("已创建地图边界 [" + name + "] 并设为编辑焦点。").withStyle(ChatFormatting.GREEN), true);
    return 1;
}

private static int focusBorder(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    String name = StringArgumentType.getString(context, "tagName");
    ServerPlayer player = context.getSource().getPlayerOrException();
    BorderData data = BorderData.get((ServerLevel) player.level());

    if (!data.getAllNames().contains(name)) {
        context.getSource().sendFailure(Component.literal("找不到边界配置: " + name));
        return 0;
    }

    data.setPlayerFocus(player.getUUID(), name);
    context.getSource().sendSuccess(() -> Component.literal("正在编辑边界: " + name).withStyle(ChatFormatting.GOLD), true);
    return 1;
}

private static int startBorder(CommandContext<CommandSourceStack> context, String explicitName) throws CommandSyntaxException {
    ServerLevel level = context.getSource().getLevel();
    BorderData data = BorderData.get(level);
    String targetName = explicitName;

    // 如果没指定名字，尝试获取玩家当前的焦点
    if (targetName == null && context.getSource().getEntity() instanceof ServerPlayer player) {
        targetName = data.getPlayerFocus(player.getUUID());
    }

    if (targetName == null || data.getEntry(targetName) == null) {
        context.getSource().sendFailure(Component.literal("未指定有效边界，或当前无焦点。"));
        return 0;
    }

    data.setActiveBorder(targetName);

    // 【关键同步】因为激活状态变了，必须通知全服所有玩家渲染新墙
    syncActiveBorderToAll(level, data);

    String finalName = targetName;
    context.getSource().sendSuccess(() -> Component.literal("已激活边界 [" + finalName + "]").withStyle(ChatFormatting.RED), true);
    return 1;
}

private static int stopBorder(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    BorderData data = BorderData.get(level);

    data.setActiveBorder(null);

    // 【关键同步】通知全服关闭渲染
    syncActiveBorderToAll(level, data);

    context.getSource().sendSuccess(() -> Component.literal("已关闭地图边界。").withStyle(ChatFormatting.YELLOW), true);
    return 1;
}

private static int setBorderBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    return modifyBorder(context, (data, name) -> {
        int x = IntegerArgumentType.getInteger(context, "lx");
        int z = IntegerArgumentType.getInteger(context, "lz");
        boolean state = IntegerArgumentType.getInteger(context, "state") == 1;
        return data.setBlock(name, x, z, state);
    });
}

private static int setBorderLine(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    return modifyBorder(context, (data, name) -> {
        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");
        boolean state = IntegerArgumentType.getInteger(context, "state") == 1;
        return data.setLine(name, x1, z1, x2, z2, state);
    });
}

// 辅助方法：统一处理编辑逻辑
private static int modifyBorder(CommandContext<CommandSourceStack> context, java.util.function.BiFunction<BorderData, String, Boolean> action) throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    ServerLevel level = player.serverLevel().getLevel();
    BorderData data = BorderData.get(level);

    String focus = data.getPlayerFocus(player.getUUID());
    if (focus == null) {
        context.getSource().sendFailure(Component.literal("你当前没有关注任何边界，请先使用 /point borders focus <name>"));
        return 0;
    }

    if (action.apply(data, focus)) {
        // 【关键同步】如果正在编辑的正是当前激活显示的边界，需要立即同步给全服
        if (focus.equals(data.getActiveBorderName())) {
            syncActiveBorderToAll(level, data);
        }
        context.getSource().sendSuccess(() -> Component.literal("操作成功 (" + focus + ")"), false);
        return 1;
    } else {
        context.getSource().sendFailure(Component.literal("操作失败 (越界或配置不存在)"));
        return 0;
    }
}
    // ================= 逻辑实现：Border 新增方法 =================

    // 1. 帮助指令
    private static int helpBorders(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("=== 地图边界 (Borders) 指令帮助 ===").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);

        sendHelpLine(source, "/borders add <名> <x> <z> <w> <h>", "创建一个新的边界区域");
        sendHelpLine(source, "/borders start [名]", "激活并显示指定的边界 (若不填则使用当前Focus)");
        sendHelpLine(source, "/borders stop", "关闭当前显示的边界");
        sendHelpLine(source, "/borders focus <名>", "设置当前编辑的目标 (使用锤子修改时生效)");
        sendHelpLine(source, "/borders delete <名>", "永久删除一个边界配置");
        sendHelpLine(source, "/borders clear", "删除当前Focus的边界");
        sendHelpLine(source, "/borders clear all", "删除所有边界配置 [慎用]");

        source.sendSuccess(() -> Component.literal("提示：锤子左键=擦除墙体，右键=添加墙体").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        return 1;
    }

    // 2. 删除指定名称的边界 (delete <name> 和 clear <name> 共用)
    private static int deleteBorderByName(CommandContext<CommandSourceStack> context, String tagName) {
        ServerLevel level = context.getSource().getLevel();
        BorderData data = BorderData.get(level);

        // 检查是否存在
        if (data.getEntry(tagName) == null) {
            context.getSource().sendFailure(Component.literal("找不到名为 [" + tagName + "] 的边界配置。"));
            return 0;
        }

        // 检查是否正在运行，如果是，需要同步关闭
        boolean wasActive = tagName.equals(data.getActiveBorderName());

        // 执行删除
        if (data.removeBorder(tagName)) {
            // 如果删的是当前激活的，必须通知全服关闭渲染
            if (wasActive) {
                syncActiveBorderToAll(level, data);
            }
            context.getSource().sendSuccess(() -> Component.literal("已删除边界配置: " + tagName).withStyle(ChatFormatting.YELLOW), true);
            return 1;
        } else {
            return 0;
        }
    }

    // 3. 删除当前 Focus 的边界 (clear 无参)
    private static int clearFocusedBorder(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BorderData data = BorderData.get(player.serverLevel());

        String focus = data.getPlayerFocus(player.getUUID());
        if (focus == null) {
            context.getSource().sendFailure(Component.literal("你当前没有关注任何边界，无法执行快速删除。请使用 /borders clear <name>"));
            return 0;
        }

        return deleteBorderByName(context, focus);
    }

    // 4. 删除所有边界 (clear all)
    private static int clearAllBorders(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BorderData data = BorderData.get(level);

        Set<String> allNames = data.getAllNames();
        if (allNames.isEmpty()) {
            context.getSource().sendFailure(Component.literal("当前没有任何边界配置。"));
            return 0;
        }

        // 创建副本以避免并发修改异常
        int count = allNames.size();
        // 简单暴力：直接创建一个新列表遍历删除
        // 注意：这里需要确保 BorderData 的 removeBorder 逻辑正确处理了 active 状态
        // 为了安全起见，我们先强制关闭 Active
        if (data.getActiveBorderName() != null) {
            data.setActiveBorder(null);
            syncActiveBorderToAll(level, data);
        }

        // 这里的 removeBorder 需要支持从 map 中移除
        // 由于 BorderData.getAllNames() 返回的是 keySet，直接 clear map 比较快，但我们用 remove 保持逻辑一致
        List<String> namesToDelete = List.copyOf(allNames);
        for (String name : namesToDelete) {
            data.removeBorder(name);
        }

        context.getSource().sendSuccess(() -> Component.literal("已清空所有边界配置 (共删除 " + count + " 个)").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
        return 1;
    }
    // /borders add center <name> <radius>
// 以玩家当前位置 (~ ~ ~) 为中心，radius 为半径，自动算 w/d，并把外围一圈设为 1
    private static int addBorderCenterRing(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "tagName");
        int radius = IntegerArgumentType.getInteger(context, "radius");

        ServerLevel level = context.getSource().getLevel();
        BorderData data = BorderData.get(level);

        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos center = BlockPos.containing(player.position());

        // 自动算出宽高（方形）：边长 = 2*radius + 1
        int size = radius * 2 + 1;

        // startX/startZ 为方形左上角（最小 X/Z）
        double startX = center.getX() - radius;
        double startZ = center.getZ() - radius;

        // 1) 创建边界（会自动分配 grid）
        data.addBorder(name, startX, startZ, size, size);

        // 2) 外围一圈置 1
        BorderData.BorderEntry entry = data.getEntry(name);
        if (entry != null) {
            fillBorderOuterRing(entry);
        }

        // 3) 自动切换创建者编辑焦点
        data.setPlayerFocus(player.getUUID(), name);

        context.getSource().sendSuccess(
                () -> Component.literal("已创建中心边界圈 [" + name + "]，中心=" + center.getX() + "," + center.getZ()
                                + " 半径=" + radius + " (大小=" + size + "x" + size + "，外圈=1)")
                        .withStyle(ChatFormatting.GREEN),
                true
        );
        return 1;
    }

    // 工具：把 entry 的外围一圈全部置 1
    private static void fillBorderOuterRing(BorderData.BorderEntry entry) {
        int w = entry.width;
        int d = entry.depth;
        if (w <= 0 || d <= 0) return;

        // 顶边 & 底边
        for (int x = 0; x < w; x++) {
            entry.setWall(x, 0, true);
            entry.setWall(x, d - 1, true);
        }
        // 左边 & 右边
        for (int z = 0; z < d; z++) {
            entry.setWall(0, z, true);
            entry.setWall(w - 1, z, true);
        }
    }
// 辅助方法：发送同步包
private static void syncActiveBorderToAll(ServerLevel level, BorderData data) {
    // 1. 获取名字 (String)
    String name = data.getActiveBorderName();

    // 2. 获取实体 (Entry)
    BorderData.BorderEntry entry = data.getActiveEntry();

    // 3. 发包
    // 参数顺序: (String name, BorderEntry entry, boolean isFocusSync)
    // isFocusSync = false，表示这是"全局激活"的边界，不是"个人编辑"的焦点
    ModMessage.sendToAll(new PacketSyncBorder(name, entry, false));
    }
}
