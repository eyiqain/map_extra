package com.mapextra.event;

import com.mapextra.MapExtra;
import com.mapextra.init.ModSounds;
import com.mapextra.item.Hammer;
import com.mapextra.item.Wrench;
import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketDelPos;
import com.mapextra.net.PacketHammerClick;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ItemClickEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ItemClickEventHandler.class);

    // ==================== 左键检测 (方块) ====================
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();

        // 必须是客户端，因为我们要发包
        if (!level.isClientSide) return;

        Player player = event.getEntity();
        Item item = event.getItemStack().getItem();
        BlockPos clickedPos = event.getPos();

        // --- 分支 1: 扳手 (Wrench) 逻辑 ---
        if (item instanceof Wrench) {
            deletePos(level, player, item);
        }// --- 分支 2: 锤子 (Hammer) 逻辑 ---
        else if (item instanceof Hammer) {
            deleteWall(level,player,item);//

        }

    }

    // ==================== 左键检测 (空气) ====================
    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        Level level = event.getLevel();
        Player player = event.getEntity();
        Item item = event.getItemStack().getItem();

        // --- 分支 1: 扳手 (Wrench) 逻辑 ---
        if (item instanceof Wrench) {
            deletePos(level, player, item);
        }
        // --- 分支 2: 锤子 (Hammer) 逻辑 ---
        else if (item instanceof Hammer) {
            deleteWall(level,player,item);//

        }
    }

    // ==================== 逻辑实现：扳手 (删除点) ====================
    private static void deletePos(Level level, Player player, Item item) {
        // 这里不需要 instanceof 检查了，因为调用前已经检查过了，但为了安全保留强转
        Wrench wrench = (Wrench) item;
        BlockPos pos = wrench.getRemoveBlockPos(player, level);

        if(pos != null) {
            wrench.playRandomPitchSound(level, player, pos, ModSounds.WRENCH_REMOVE.get());
            ModMessage.sendToServer(new PacketDelPos(pos));
        }
    }

    // ==================== 逻辑实现：锤子 (删除墙) ====================
    private static void deleteWall(Level level, Player player, Item item) {
        Hammer hammer = (Hammer) item;
        hammer.playRandomPitchSound(level, player, player.getOnPos(), ModSounds.WRENCH_REMOVE.get());
        // 直接发空包，服务端计算
        ModMessage.sendToServer(new PacketHammerClick());

    }
}
