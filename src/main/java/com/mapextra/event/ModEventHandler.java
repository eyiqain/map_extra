package com.mapextra.event;

import com.mapextra.MapExtra;
import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketSyncBorder;
import com.mapextra.net.PacketSyncBorderNames;
import com.mapextra.world.BorderData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MapExtra.MODID)
public class ModEventHandler {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ServerLevel level = player.serverLevel();
        BorderData data = BorderData.get(level);

        // ==================================================
        // 1. 基础同步：发名字列表 (必须先发，让客户端 GUI 知道有哪些选项)
        // ==================================================
        ModMessage.sendToPlayer(new PacketSyncBorderNames(data.getAllNamesList()), player);

        // ==================================================
        // 2. 全局同步：发当前服务器激活的墙 (Active)
        //    (用于碰撞检测和全局渲染)
        // ==================================================
        String activeName = data.getActiveBorderName();
        BorderData.BorderEntry activeEntry = data.getActiveEntry();

        // 就算 activeName 是 null (没开墙)，发一个 null 过去也是为了告诉客户端 "现在没墙"
        PacketSyncBorder activePacket = new PacketSyncBorder(activeName, activeEntry, false);
        ModMessage.sendToPlayer(activePacket, player);

        // ==================================================
        // 3. 【新增】个人同步：发玩家关注的墙 (Focus)
        //    (用于手持物品编辑时的目标)
        // ==================================================
        String focusName = data.getPlayerFocus(player.getUUID());

        // 逻辑优化：如果玩家是新来的，或者之前的 Focus 被删了
        // 尝试自动给他分配一个默认 Focus (如果有墙的话)
        if (focusName == null || data.getEntry(focusName) == null) {
            // 如果有激活的墙，默认关注激活的
            if (activeName != null && data.getEntry(activeName) != null) {
                focusName = activeName;
            }
            // 否则，随便找一个墙给他关注 (列表里的第一个)
            else if (!data.getAllNames().isEmpty()) {
                focusName = data.getAllNames().iterator().next();
            }

            // 如果找到了新的 Focus，要在服务端保存状态
            if (focusName != null) {
                data.setPlayerFocus(player.getUUID(), focusName);
            }
        }

        // 只要确定了 Focus，就发包同步
        if (focusName != null) {
            BorderData.BorderEntry focusEntry = data.getEntry(focusName);
            if (focusEntry != null) {
                // 注意：这里 isFocusSync = true
                PacketSyncBorder focusPacket = new PacketSyncBorder(focusName, focusEntry, true);
                ModMessage.sendToPlayer(focusPacket, player);
            }
        }
    }
}
