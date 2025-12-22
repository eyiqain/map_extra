package com.mapextra.net;

import com.mapextra.client.ClientPosCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class PacketSyncPos {
    public final String currentFocus;
    public final List<BlockPos> positions;
    public final Map<String, Integer> allTagStats;

    // --- 构造函数 (服务端发包用) ---
    public PacketSyncPos(String currentFocus, List<BlockPos> positions, Map<String, Integer> allTagStats) {
        this.currentFocus = currentFocus != null ? currentFocus : "default";
        this.positions = positions != null ? positions : new ArrayList<>();
        this.allTagStats = allTagStats != null ? allTagStats : new HashMap<>();
    }

    // --- 解码构造函数 (客户端收包用) ---
    public PacketSyncPos(FriendlyByteBuf buf) {
        // 1. 读当前关注名
        this.currentFocus = buf.readUtf();

        // 2. 读坐标列表
        int posSize = buf.readInt();
        // 你的防御性检查很好，保留它
        if (posSize > 10000 || posSize < 0) posSize = 0;

        this.positions = new ArrayList<>(posSize);
        for (int i = 0; i < posSize; i++) {
            this.positions.add(buf.readBlockPos());
        }

        // 3. 读统计 Map (String -> Int)
        int statSize = buf.readInt();
        if (statSize > 1000 || statSize < 0) statSize = 0; // 同样加个防御限制

        this.allTagStats = new HashMap<>(statSize);
        for (int i = 0; i < statSize; i++) {
            String tagName = buf.readUtf();
            int count = buf.readInt();
            this.allTagStats.put(tagName, count);
        }
    }

    // --- 编码方法 (写入 ByteBuf) ---
    public void encode(FriendlyByteBuf buf) {
        // 1. 写当前关注名
        buf.writeUtf(currentFocus);

        // 2. 写坐标列表
        buf.writeInt(positions.size());
        for (BlockPos pos : positions) {
            buf.writeBlockPos(pos);
        }

        // 3. 写统计 Map
        buf.writeInt(allTagStats.size());
        allTagStats.forEach((name, count) -> {
            buf.writeUtf(name);
            buf.writeInt(count);
        });
    }

    // --- 解码辅助 ---
    public static PacketSyncPos decode(FriendlyByteBuf buf) {
        return new PacketSyncPos(buf);
    }

    // --- 客户端处理逻辑 ---
    public static void handle(PacketSyncPos msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 调用我们刚写好的 ClientPosCache.update，一次性更新所有数据
            // 这样 渲染器(Render) 和 界面(HUD) 都能拿到最新的
            ClientPosCache.update(msg.currentFocus, msg.positions, msg.allTagStats);
        });
        ctx.get().setPacketHandled(true);
    }
}
