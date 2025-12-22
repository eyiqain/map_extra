package com.mapextra.client;

import com.mapextra.math.BorderCollisionUtils;
import com.mapextra.net.PacketSyncBorder;
import com.mapextra.world.BorderData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

public class ClientPosCache {
    // 1. 核心数据：当前红框渲染用的坐标列表
    public static final List<BlockPos> currentPositions = new ArrayList<>();

    // 2. HUD 数据：当前关注名
    public static String currentFocus = "default";

    // 3. HUD 数据：轮盘统计信息
    public static final Map<String, Integer> ALL_TAG_STATS = new LinkedHashMap<>();
    public static final List<String> SORTED_TAG_NAMES = new ArrayList<>();

    // === 全局信标列表 ===
    public static Set<BlockPos> BEACONS = new HashSet<>();

    // === 边界列表 (HUD用) ===
    public static List<String> SORTED_BORDER_NAMES = new ArrayList<>();
    public static String currentBorderFocus = "";

    // === 【核心数据】分开存储 激活边界 和 焦点边界 ===

    // 1. 激活的边界 (Active): 用于碰撞检测，以及没有拿锤子时的渲染 (灰色)
    public static BorderData.BorderEntry ACTIVE_ENTRY = null;
    public static String activeBorderName = null;

    // 2. 焦点的边界 (Focus): 仅用于手持锤子时的渲染 (橘色)
    public static BorderData.BorderEntry FOCUS_ENTRY = null;

    // =======================================================

    public static void updateBeacons(Set<BlockPos> newBeacons) {
        BEACONS = new HashSet<>(newBeacons);
    }

    /**
     * 网络包收到数据后调用此方法更新 (Point Tag 相关)
     */
    public static void update(String focus, List<BlockPos> posList, Map<String, Integer> stats) {
        currentFocus = focus;
        currentPositions.clear();
        currentPositions.addAll(posList);
        ALL_TAG_STATS.clear();
        ALL_TAG_STATS.putAll(stats);
        SORTED_TAG_NAMES.clear();
        SORTED_TAG_NAMES.addAll(stats.keySet());
        Collections.sort(SORTED_TAG_NAMES);
    }

    // === 【核心修改】处理网络包同步边界数据 ===
    // 这是最主要的数据入口，由 PacketSyncBorder.handle 调用
    public static void handleBorderSync(PacketSyncBorder msg) {
        if (msg.isFocusSync) {
            // 更新“编辑中”的边界 (FOCUS)
            FOCUS_ENTRY = msg.entry;
            // 只有当名字不为空时才更新本地的焦点名字，防止被空包重置
            if (msg.name != null && !msg.name.isEmpty()) {
                currentBorderFocus = msg.name;
            }
        } else {
            // 更新“生效中”的边界 (ACTIVE)
            ACTIVE_ENTRY = msg.entry;
            activeBorderName = msg.name;
        }
    }

    // 辅助方法：更新边界名字列表 (HUD用)
    public static void updateBorderNames(List<String> names) {
        SORTED_BORDER_NAMES.clear();
        SORTED_BORDER_NAMES.addAll(names);
        Collections.sort(SORTED_BORDER_NAMES);
    }

    // === 【核心修改】计算碰撞 ===
    // 只针对 ACTIVE_ENTRY 生效
    public static void addBorderCollisions(AABB entityBox, List<VoxelShape> shapes) {
        // 如果没有激活的边界，直接返回，不要生成空气墙
        if (ACTIVE_ENTRY == null) {
            return;
        }
        // 调用算法生成碰撞盒
        BorderCollisionUtils.addWallCollisions(entityBox, ACTIVE_ENTRY, shapes);
    }

    // 【已修复】这个方法是为了兼容旧代码保留的，但加入了空指针检查
    // 如果你要手动更新部分属性(例如修改大小)，建议通过网络包重新发整个 Entry，
    // 或者确保这里的逻辑是安全的。
    public static void updateBorder(double x, double z, int width, int depth, boolean isActive) {
        BorderData.BorderEntry target = isActive ? ACTIVE_ENTRY : FOCUS_ENTRY;

        if (target != null) {
            target.setBorder(x, z, width, depth);
        } else {
            // 如果目标是空的，创建一个新的
            target = new BorderData.BorderEntry(x, z, width, depth);
            if (isActive) ACTIVE_ENTRY = target;
            else FOCUS_ENTRY = target;
        }
    }
}
