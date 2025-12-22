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

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModCommands {

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TAGS = (context, builder) -> {
        PosSavedData data = PosSavedData.get(context.getSource().getLevel());
        return SharedSuggestionProvider.suggest(data.getAllTags(), builder);
    };

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


        // --- 2. 注册主指令树 (Point) ---
        dispatcher.register(
                Commands.literal("point")
                        .requires(source -> source.hasPermission(2))

                        // help
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
                        .then(Commands.literal("beacon")
                                // 1. Clear
                                .then(Commands.literal("clear")
                                        .executes(context -> clearBeacons(context))
                                )
                                // 2. Add (带坐标)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(context -> addBeacon(context, BlockPosArgument.getLoadedBlockPos(context, "pos")))
                                        )
                                        // 默认 Add (当前位置)
                                        .executes(context -> addBeacon(context, BlockPos.containing(context.getSource().getPosition())))
                                )
                                // 3. Null (默认行为 -> Add 当前位置)
                                .executes(context -> addBeacon(context, BlockPos.containing(context.getSource().getPosition())))
                        )
        );

        // --- 3. 注册主指令树 (Borders) ---
        dispatcher.register(
                Commands.literal("borders")
                        .requires(source -> source.hasPermission(2))

                        // help
                        .then(Commands.literal("help")
                                .executes(ModCommands::helpBorders)
                        )

                        // 1. add <tagName> x z w d [h]
                        // 【修改】增加了 h (高度) 参数
                        .then(Commands.literal("add")
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("w", IntegerArgumentType.integer(1, 10000))
                                                                .then(Commands.argument("d", IntegerArgumentType.integer(1, 10000))
                                                                        // 新增 h 参数
                                                                        .then(Commands.argument("h", IntegerArgumentType.integer(1, 1024))
                                                                                .executes(ModCommands::addBorder)
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )

                        // 2. delete / clear
                        .then(Commands.literal("delete")
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_BORDERS)
                                        .executes(context -> deleteBorderByName(context, StringArgumentType.getString(context, "tagName")))
                                )
                        )
                        .then(Commands.literal("clear")
                                .then(Commands.literal("all")
                                        .executes(ModCommands::clearAllBorders)
                                )
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_BORDERS)
                                        .executes(context -> deleteBorderByName(context, StringArgumentType.getString(context, "tagName")))
                                )
                                .executes(ModCommands::clearFocusedBorder)
                        )

                        // 3. focus
                        .then(Commands.literal("focus")
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_BORDERS)
                                        .executes(ModCommands::focusBorder)
                                )
                        )

                        // 4. start
                        .then(Commands.literal("start")
                                .then(Commands.argument("tagName", StringArgumentType.word())
                                        .suggests(SUGGEST_BORDERS)
                                        .executes(context -> startBorder(context, StringArgumentType.getString(context, "tagName")))
                                )
                                .executes(context -> startBorder(context, null))
                        )

                        // 5. stop
                        .then(Commands.literal("stop")
                                .executes(ModCommands::stopBorder)
                        )

                        // 6. setblock
                        // 【修改】参数改为 lx ly lz state (支持 3D)
                        .then(Commands.literal("setblock")
                                .then(Commands.argument("lx", IntegerArgumentType.integer())
                                        .then(Commands.argument("ly", IntegerArgumentType.integer()) // 新增 LY
                                                .then(Commands.argument("lz", IntegerArgumentType.integer())
                                                        .then(Commands.argument("state", IntegerArgumentType.integer(0, 1))
                                                                .executes(ModCommands::setBorderBlock)
                                                        )
                                                )
                                        )
                                )
                        )

                        // 7. setline (保持 2D 逻辑，方便快速立墙)
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


    // ================= 逻辑实现：Help =================

    private static int helpCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("=== MapExtra 指令帮助 ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        sendHelpLine(source, "/point help", "显示此帮助信息");
        sendHelpLine(source, "/point create <组名>", "创建并关注一个新的标签组");
        sendHelpLine(source, "/point focus [组名]", "查看当前关注点，或切换到指定组");
        sendHelpLine(source, "/point list [组名]", "列出所有组，或列出指定组下的所有坐标");
        sendHelpLine(source, "/point random [组名]", "随机抽取一个坐标并生成传送指令");
        sendHelpLine(source, "/point random [组名] run <指令>", "在随机出的坐标处执行指令");
        sendHelpLine(source, "/point clear [组名|all]", "清空指定组或所有组的坐标");
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

    // ================= 逻辑实现：常规管理 (Point) =================

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

    private static int executeRandomTp(CommandContext<CommandSourceStack> context, String explicitTag) throws CommandSyntaxException {
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
        BeaconGlobalData globalData = BeaconGlobalData.get(level);
        globalData.addBeacon(pos);
        ModMessage.sendToAll(new PacketSyncBeacon(globalData.getBeacons()));
        context.getSource().sendSuccess(() -> Component.literal("§b[信标] §f已添加全局透视点: " + pos.toShortString()), true);
        return 1;
    }

    private static int clearBeacons(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BeaconGlobalData globalData = BeaconGlobalData.get(level);
        globalData.clearBeacons();
        ModMessage.sendToAll(new PacketSyncBeacon(globalData.getBeacons()));
        context.getSource().sendSuccess(() -> Component.literal("§b[信标] §f已清空所有全局点 (含 'Beacon' 标签组)"), true);
        return 1;
    }

    // ================= 逻辑实现：Border =================

    // 【修改】addBorder 支持 height 参数
    private static int addBorder(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "tagName");
        double x = DoubleArgumentType.getDouble(context, "x");
        double z = DoubleArgumentType.getDouble(context, "z");
        int w = IntegerArgumentType.getInteger(context, "w");
        int d = IntegerArgumentType.getInteger(context, "d");
        // 【新增】读取 height
        int h = IntegerArgumentType.getInteger(context, "h");

        ServerLevel level = context.getSource().getLevel();
        BorderData data = BorderData.get(level);

        data.addBorder(name, x, z, w, d, h);

        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            data.setPlayerFocus(player.getUUID(), name);
        }

        context.getSource().sendSuccess(() -> Component.literal("已创建 3D 边界 [" + name + "] (" + w + "x" + h + "x" + d + ") 并设为焦点。").withStyle(ChatFormatting.GREEN), true);
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

        if (targetName == null && context.getSource().getEntity() instanceof ServerPlayer player) {
            targetName = data.getPlayerFocus(player.getUUID());
        }

        if (targetName == null || data.getEntry(targetName) == null) {
            context.getSource().sendFailure(Component.literal("未指定有效边界，或当前无焦点。"));
            return 0;
        }

        data.setActiveBorder(targetName);
        syncActiveBorderToAll(level, data);

        String finalName = targetName;
        context.getSource().sendSuccess(() -> Component.literal("已激活边界 [" + finalName + "]").withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private static int stopBorder(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BorderData data = BorderData.get(level);
        data.setActiveBorder(null);
        syncActiveBorderToAll(level, data);
        context.getSource().sendSuccess(() -> Component.literal("已关闭地图边界。").withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    // 【修改】setBorderBlock 支持 ly
    private static int setBorderBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return modifyBorder(context, (data, name) -> {
            int x = IntegerArgumentType.getInteger(context, "lx");
            int y = IntegerArgumentType.getInteger(c
