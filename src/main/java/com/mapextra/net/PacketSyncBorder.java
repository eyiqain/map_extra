package com.mapextra.net;

import com.mapextra.world.BorderData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/**
 * 世界边界同步网络包（完整3D版本）
 * 核心修改：新增高度(h)、三维网格数据的序列化/反序列化
 */
public class PacketSyncBorder {
    private final String borderName;
    private final BorderData.BorderEntry borderEntry;
    private final boolean isFocusSync;

    // 构造函数（兼容原有调用，新增高度参数）
    public PacketSyncBorder(String borderName, BorderData.BorderEntry borderEntry, boolean isFocusSync) {
        this.borderName = borderName;
        this.borderEntry = borderEntry;
        this.isFocusSync = isFocusSync;
    }

    // ==================== 序列化（Encode）====================
    public void encode(FriendlyByteBuf buf) {
        // 1. 写入边界名称
        buf.writeUtf(this.borderName);
        // 2. 写入是否为焦点同步标记
        buf.writeBoolean(this.isFocusSync);
        // 3. 写入是否有边界数据（核心修改：新增3D字段）
        buf.writeBoolean(this.borderEntry != null);
        if (this.borderEntry != null) {
            // 原有2D字段
            buf.writeDouble(this.borderEntry.startX);
            buf.writeDouble(this.borderEntry.startZ);
            buf.writeInt(this.borderEntry.width);
            buf.writeInt(this.borderEntry.depth);

            // ========== 3D新增字段 ==========
            buf.writeInt(this.borderEntry.height); // 写入高度
            // 写入三维网格数据（byte数组）
            buf.writeByteArray(this.borderEntry.grid);
        }
    }

    // ==================== 反序列化（Decode）====================
    public static PacketSyncBorder decode(FriendlyByteBuf buf) {
        // 1. 读取边界名称
        String name = buf.readUtf();
        // 2. 读取焦点同步标记
        boolean isFocusSync = buf.readBoolean();
        // 3. 读取是否有边界数据（核心修改：读取3D字段）
        boolean hasEntry = buf.readBoolean();

        BorderData.BorderEntry entry = null;
        if (hasEntry) {
            // 原有2D字段
            double startX = buf.readDouble();
            double startZ = buf.readDouble();
            int width = buf.readInt();
            int depth = buf.readInt();

            // ========== 3D新增字段 ==========
            int height = buf.readInt(); // 读取高度
            byte[] grid = buf.readByteArray(); // 读取三维网格

            // 使用3D构造函数创建Entry（需确保BorderEntry已支持height参数）
            entry = new BorderData.BorderEntry(startX, startZ, width, depth, height);
            entry.grid = grid; // 赋值三维网格
        }
        return new PacketSyncBorder(name, entry, isFocusSync);
    }

    // ==================== 处理逻辑（Handle）====================
    public boolean handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 服务端→客户端：同步边界数据（逻辑不变，仅数据结构为3D）
            if (context.getDirection().getReceptionSide().isClient()) {
                BorderData.syncBorderFromServer(this.borderName, this.borderEntry, this.isFocusSync);
            }
        });
        context.setPacketHandled(true);
        return true;
    }

    // ==================== Getter（兼容原有代码）====================
    public String getBorderName() {
        return borderName;
    }

    public BorderData.BorderEntry getBorderEntry() {
        return borderEntry;
    }

    public boolean isFocusSync() {
        return isFocusSync;
    }
}