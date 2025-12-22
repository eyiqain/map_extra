package com.mapextra.net;

import com.mapextra.MapExtra;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModMessage {
    private static SimpleChannel NETWORK;

    private static int packetId = 0;

    private static int id() {
        return packetId++;
    }

    public static <MSG> void sendToAll(MSG message) {
        NETWORK.send(PacketDistributor.ALL.noArg(), message);
    }

    public static void register() {
        NETWORK = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(MapExtra.MODID, "main"),
                () -> "1.0",
                s -> true,
                s -> true);

        // 1. 删除点 (Client -> Server)
        NETWORK.messageBuilder(PacketDelPos.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(PacketDelPos::encode)
                .decoder(PacketDelPos::decode)
                .consumerMainThread(PacketDelPos::handle)
                .add();

        // 2. 同步数据 (Server -> Client)
        NETWORK.messageBuilder(PacketSyncPos.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PacketSyncPos::encode)
                .decoder(PacketSyncPos::decode)
                .consumerMainThread(PacketSyncPos::handle)
                .add();

        // 3. 设置关注 (Client -> Server)
        // ❌ 之前写错了方向导致掉线
        // ✅ 必须是 PLAY_TO_SERVER (客户端发给服务端)
        NETWORK.messageBuilder(PacketSetFocus.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(PacketSetFocus::encode)
                .decoder(PacketSetFocus::decode) // 这里用静态引用
                .consumerMainThread(PacketSetFocus::handle)
                .add();
        // ==================== 新增：注册PacketSyncBeacon（关键修复） ====================
        NETWORK.messageBuilder(PacketSyncBeacon.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PacketSyncBeacon::encode)    // 匹配改名后的encode方法
                .decoder(PacketSyncBeacon::decode)    // 匹配静态decode方法
                .consumerMainThread(PacketSyncBeacon::handle) // 匹配handle方法
                .add();
        NETWORK.messageBuilder(PacketSyncBorder.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PacketSyncBorder::encode)    // 匹配改名后的encode方法
                .decoder(PacketSyncBorder::decode)    // 匹配静态decode方法
                .consumerMainThread(PacketSyncBorder::handle) // 匹配handle方法
                .add();
        // === 【修正】 6. 锤子点击 (Client -> Server) ===
        // 这是一个动作请求包，必须是 PLAY_TO_SERVER
        NETWORK.messageBuilder(PacketHammerClick.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(PacketHammerClick::encode)
                .decoder(PacketHammerClick::decode)
                .consumerMainThread(PacketHammerClick::handle)
                .add();
        // === 【新增】 7. 设置边界关注 (Client -> Server) ===
        // 用于滚轮切换边界
        NETWORK.messageBuilder(PacketSetBorderFocus.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(PacketSetBorderFocus::encode)
                .decoder(PacketSetBorderFocus::decode)
                .consumerMainThread(PacketSetBorderFocus::handle)
                .add();

        // === 【新增】 8. 同步边界名称列表 (Server -> Client) ===
        // 用于 HUD 显示所有边界名字
        NETWORK.messageBuilder(PacketSyncBorderNames.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PacketSyncBorderNames::encode)
                .decoder(PacketSyncBorderNames::decode)
                .consumerMainThread(PacketSyncBorderNames::handle)
                .add();

    }


    public static <MSG> void sendToServer(MSG Message) {
        NETWORK.sendToServer(Message);
    }

    public static <MSG> void sendToPlayer(MSG Message, ServerPlayer player) {
        NETWORK.send(PacketDistributor.PLAYER.with(() -> player), Message);
    }

}
