package com.mapextra.net;

import com.mapextra.world.PosSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketDelPos {
    private final BlockPos pos;

    public PacketDelPos(BlockPos pos) {
        this.pos = pos;
    }

    // 编码：把 BlockPos 写成字节流
    public static void encode(PacketDelPos msg, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(msg.pos);
    }

    // 解码：从字节流读出 BlockPos
    public static PacketDelPos decode(FriendlyByteBuf buffer) {
        return new PacketDelPos(buffer.readBlockPos());
    }

    // 处理：服务端收到包后做什么
    public static void handle(PacketDelPos msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 这里的代码运行在服务端主线程
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                // 1. 获取服务端的 SavedData
                PosSavedData data = PosSavedData.get(player.level());
                // 2. 删除坐标 (addPos方法里我们稍后会加上同步逻辑)
                data.removePos(player,msg.pos,ctx.get().getSender().serverLevel());
                // 3. (可选) 给玩家发个提示
                // player.sendSystemMessage(Component.literal("服务端已接收并保存坐标: " + msg.pos));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
