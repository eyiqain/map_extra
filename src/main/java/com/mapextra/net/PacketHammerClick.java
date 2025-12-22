package com.mapextra.net;

import com.mapextra.item.Hammer;
import com.mapextra.math.BorderCollisionUtils;
import com.mapextra.world.BorderData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

import static com.mapextra.math.BorderCollisionUtils.raycastWallCellDDA;

public class PacketHammerClick {


    public PacketHammerClick() {

    }

    // 解码器
    public static PacketHammerClick decode(FriendlyByteBuf buf) {
        return new PacketHammerClick();
    }

    // 编码器
    public void encode(FriendlyByteBuf buf) {

    }

    // 处理器 (服务端执行)
    public static void handle(PacketHammerClick msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ServerLevel level = player.serverLevel();
            BorderData data = BorderData.get(level);

            // 2. 确定目标边界 (优先 Focus，其次 Active)
            String targetName = data.getPlayerFocus(player.getUUID());
            if (targetName == null) {
                targetName = data.getActiveBorderName();
            }
            // 如果都没有，提示玩家
            if (targetName == null) {
                player.sendSystemMessage(Component.literal("未选择编辑目标！请先使用指令 /borders focus 或 start   ").withStyle(ChatFormatting.RED));
                return;
            }
            BorderData.BorderEntry entry = data.getEntry(targetName);
            if (entry == null) return;
            BorderCollisionUtils.WallHit hit = raycastWallCellDDA(level, player, entry, 10.0);
            if (hit == null) {
                player.sendSystemMessage(Component.literal("DDA miss (or blocked)").withStyle(ChatFormatting.DARK_GRAY), true);
                return;
            }

            player.sendSystemMessage(Component.literal("DDA hit: " + hit.localX() + "," + hit.localZ())
                    .withStyle(ChatFormatting.GRAY), true);

            int x = hit.localX();
            int z = hit.localZ();

            if (!entry.isWall(x, z)) {
                player.sendSystemMessage(Component.literal("Not a wall cell").withStyle(ChatFormatting.DARK_GRAY), true);
                return;
            }

            entry.setWall(x, z, false);
            data.setDirty();

            ModMessage.sendToPlayer(new PacketSyncBorder(targetName, entry, true), player);
            if (targetName.equals(data.getActiveBorderName())) {
                ModMessage.sendToAll(new PacketSyncBorder(targetName, entry, false));
            }


        });
        ctx.get().setPacketHandled(true);
    }


}
