package com.mapextra.world;

import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketSyncPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;
import java.util.stream.Collectors;

public class PosSavedData extends SavedData {
    private static final String DATA_NAME = "mapextra_positions";
    public static final String DEFAULT_TAG = "default";

    // 数据存储
    private final Map<String, List<BlockPos>> tagMap = new HashMap<>();
    private final Map<String, List<BlockPos>> recycleBin = new HashMap<>();
    private final Map<UUID, String> playerFocus = new HashMap<>();
    private final RandomSource random = RandomSource.create();

    public PosSavedData() {
        // 无论如何，初始化必须保证 default 标签存在
        tagMap.put(DEFAULT_TAG, new ArrayList<>());
    }

    // ================= 核心业务逻辑 =================

    public String getFocus(UUID playerUUID) {
        return playerFocus.getOrDefault(playerUUID, DEFAULT_TAG);
    }

    public boolean setFocus(UUID playerUUID, String tagName, ServerPlayer player) {
        if (!tagName.equals(DEFAULT_TAG) && !tagMap.containsKey(tagName)) {
            return false;
        }
        playerFocus.put(playerUUID, tagName);
        setDirty();
        if (player != null) syncToPlayer(player);
        return true;
    }

    // 【修改后】增加 ServerPlayer 参数
    public boolean createTag(String tagName, ServerPlayer player) {
        if (tagMap.containsKey(tagName)) return false;

        // 1. 创建新标签
        tagMap.put(tagName, new ArrayList<>());
        recycleBin.remove(tagName);
        setDirty();

        // 2. 如果是玩家操作，直接让该玩家关注这个新标签
        if (player != null) {
            // setFocus 内部会自动调用 syncToPlayer，所以客户端会立即刷新：
            // 1. 看到新标签出现在 HUD 上 (数量0)
            // 2. 看到这个新标签变成了金色高亮
            setFocus(player.getUUID(), tagName, player);
        }

        return true;
    }

    public void syncToPlayer(ServerPlayer player) {
        if (player == null) return;
        try {
            String currentTag = getFocus(player.getUUID());
            List<BlockPos> currentList = getPositions(currentTag);

            Map<String, Integer> stats = new HashMap<>();

            // 【修改点】: 遍历所有 tagMap 中的键，不再判断 isEmpty()
            // 只要标签存在（哪怕是空的），都同步给客户端 HUD
            tagMap.forEach((k, v) -> {
                stats.put(k, v.size());
            });

            ModMessage.sendToPlayer(new PacketSyncPos(
                    currentTag,
                    currentList != null ? currentList : new ArrayList<>(),
                    stats
            ), player);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean addPos(Player player, BlockPos pos, ServerLevel world) {
        String tag = getFocus(player.getUUID());
        List<BlockPos> list = tagMap.computeIfAbsent(tag, k -> new ArrayList<>());
        if (!list.contains(pos)) {
            list.add(pos);
            setDirty();
            if (player instanceof ServerPlayer serverPlayer) syncToPlayer(serverPlayer);
            syncToAll(world);
            return true;
        }

        return false;

    }

    public boolean removePos(Player player, BlockPos pos, ServerLevel world) {
        String tagName = getFocus(player.getUUID());
        List<BlockPos> list = tagMap.get(tagName);
        if (list != null && list.contains(pos)) {
            list.remove(pos);
            setDirty();
            if (player instanceof ServerPlayer serverPlayer) syncToPlayer(serverPlayer);
            syncToAll(world);
            return true;
        }

        return false;
    }

    public int clearTag(String tagName) {
        if (!tagMap.containsKey(tagName)) return 0;
        List<BlockPos> currentList = tagMap.get(tagName);
        int size = currentList.size();
        if (size > 0) {
            recycleBin.put(tagName, new ArrayList<>(currentList));
            currentList.clear();
            setDirty();
        }
        return size;
    }

    public boolean undoClear(String tagName) {
        if (!recycleBin.containsKey(tagName)) return false;
        List<BlockPos> bin = recycleBin.get(tagName);
        List<BlockPos> current = tagMap.computeIfAbsent(tagName, k -> new ArrayList<>());
        for (BlockPos pos : bin) {
            if (!current.contains(pos)) current.add(pos);
        }
        recycleBin.remove(tagName);
        setDirty();
        return true;
    }
    // ================= 批量操作逻辑 =================

    /**
     * 清空所有标签的数据（全部移入回收站）
     */
    public int clearAll() {
        int totalCount = 0;
        // 遍历所有现有的标签名
        // 使用 new ArrayList 包装 keySet 防止并发修改异常（虽然 clearTag 只是清空 list 不删 key，但为了稳妥）
        for (String tagName : new ArrayList<>(tagMap.keySet())) {
            totalCount += clearTag(tagName); // 复用现有的单体清除逻辑
        }
        return totalCount;
    }

    /**
     * 撤销所有回收站的数据
     */
    public boolean undoAll() {
        if (recycleBin.isEmpty()) return false;

        boolean anyRestored = false;
        // 遍历回收站里的所有标签
        for (String tagName : new ArrayList<>(recycleBin.keySet())) {
            if (undoClear(tagName)) { // 复用现有的单体撤销逻辑
                anyRestored = true;
            }
        }
        return anyRestored;
    }
    public List<BlockPos> getPositions(String tagName) {
        return tagMap.getOrDefault(tagName, Collections.emptyList());
    }

    public Set<String> getAllTags() {
        return tagMap.keySet();
    }

    public BlockPos getRandomPos(String tagName, Level level) {
        List<BlockPos> list = tagMap.get(tagName);
        if (list == null || list.isEmpty()) return null;
        List<BlockPos> validPositions = list.stream()
                .filter(pos -> level.getWorldBorder().isWithinBounds(pos))
                .collect(Collectors.toList());
        if (validPositions.isEmpty()) return null;
        return validPositions.get(random.nextInt(validPositions.size()));
    }

    // ================= NBT 读写 (纯净版) =================

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag mapsTag = new CompoundTag();

        // 用来记录哪些标签最终被保留了下来
        Set<String> survivedTags = new HashSet<>();
        survivedTags.add(DEFAULT_TAG); // 默认组永远算作存活

        // 1. 保存坐标数据 (剔除空组)
        tagMap.forEach((key, list) -> {
            // 逻辑：如果是默认组，或者列表里有东西，才保存
            if (key.equals(DEFAULT_TAG) || !list.isEmpty()) {
                mapsTag.put(key, writeList(list));
                survivedTags.add(key); // 记录它活下来了
            }
        });
        tag.put("tag_map", mapsTag);

        // (回收站部分你既然注释掉了，这里就不写了)

        // 2. 保存玩家关注点 (自动重置检测)
        CompoundTag focusTag = new CompoundTag();
        playerFocus.forEach((uuid, tagName) -> {
            // 检查：玩家关注的这个标签，是否在刚才的保存列表中？
            if (survivedTags.contains(tagName)) {
                // 还在，正常保存
                focusTag.putString(uuid.toString(), tagName);
            } else {
                // 不在了（说明它是空的，且被删除了），强制把玩家重置回 default
                focusTag.putString(uuid.toString(), DEFAULT_TAG);
            }
        });
        tag.put("player_focus", focusTag);

        return tag;
    }

    /**
     * 读取方法：彻底放弃治疗旧数据
     * 任何错误都会导致返回一个干净的新对象
     */
    public static PosSavedData load(CompoundTag tag) {
        PosSavedData data = new PosSavedData(); // 默认为空

        // 只有当存在 tag_map 且格式正确时才读取
        if (tag.contains("tag_map")) {
            try {
                // 1. 读取坐标
                CompoundTag mapsTag = tag.getCompound("tag_map");
                for (String key : mapsTag.getAllKeys()) {
                    List<BlockPos> list = readList(mapsTag.getList(key, Tag.TAG_LONG));
                    data.tagMap.put(key, list);
                }

                // 2. 读取回收站
                if (tag.contains("recycle_bin")) {
                    CompoundTag binTag = tag.getCompound("recycle_bin");
                    for (String key : binTag.getAllKeys()) {
                        data.recycleBin.put(key, readList(binTag.getList(key, Tag.TAG_LONG)));
                    }
                }

                // 3. 读取 Focus
                if (tag.contains("player_focus")) {
                    CompoundTag focusTag = tag.getCompound("player_focus");
                    for (String key : focusTag.getAllKeys()) {
                        try {
                            data.playerFocus.put(UUID.fromString(key), focusTag.getString(key));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                // 捕获所有异常：如果读取出错，就打印个日志，然后继续使用上面那个空的 data
                // 这样绝对不会崩游戏，只会导致旧数据丢失
                System.err.println("MapExtra: 存档数据格式不匹配或已损坏，已重置为新存档。");
            }
        }

        // 必须保证默认组存在
        data.tagMap.putIfAbsent(DEFAULT_TAG, new ArrayList<>());
        return data;
    }

    private ListTag writeList(List<BlockPos> positions) {
        ListTag listTag = new ListTag();
        for (BlockPos pos : positions) {
            listTag.add(LongTag.valueOf(pos.asLong()));
        }
        return listTag;
    }

    private static List<BlockPos> readList(ListTag listTag) {
        List<BlockPos> list = new ArrayList<>();
        for (Tag t : listTag) {
            // 严格检查类型：只有 LongTag 才读，其他的一律无视
            if (t instanceof LongTag longTag) {
                list.add(BlockPos.of(longTag.getAsLong()));
            }
        }
        return list;
    }

    public static PosSavedData get(Level level) {
        if (level.isClientSide) {
            throw new RuntimeException("服务端专用");
        }
        return ((ServerLevel) level).getDataStorage().computeIfAbsent(
                PosSavedData::load,
                PosSavedData::new,
                DATA_NAME
        );
    }
    // ... 在 PosSavedData 类中 ...

    // 【新增】同步给全服所有玩家
    // 逻辑：遍历在线玩家，为每个人单独调用 syncToPlayer
    // 这样既能更新全局的数据(点/统计)，又不会打乱每个人各自的 Focus
    public void syncToAll(ServerLevel level) {
        if (level == null) return;
        for (ServerPlayer player : level.players()) {
            syncToPlayer(player);
        }
    }

    // 同时，建议修改 createTag, addPos (如果你的点是通过物品添加的) 等方法
    // 确保它们内部逻辑变动后，有机会被外部调用 syncToAll

}
