package com.mapextra.event;

import com.mapextra.MapExtra;
import com.mapextra.client.ClientPosCache;
import com.mapextra.init.ModItemRegister;
import com.mapextra.net.ModMessage;
import com.mapextra.net.PacketSetBorderFocus; // 【新增】你需要创建这个包
import com.mapextra.net.PacketSetFocus;
import com.mapextra.net.PacketSyncBorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, value = Dist.CLIENT)
public class ClientInputHandler {

    private static long lastScrollTime = 0;
    private static final long SCROLL_COOLDOWN = 100;

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        ItemStack mainHand = player.getMainHandItem();
        boolean isWrench = mainHand.is(ModItemRegister.WRENCH.get());
        boolean isHammer = mainHand.is(ModItemRegister.HAMMER.get()); // 【新增】检查锤子

        // 只有手持 扳手 或 锤子 且按住 Alt 时触发
        if ((isWrench || isHammer) && Screen.hasAltDown()) {

            event.setCanceled(true); // 取消原版事件

            long now = System.currentTimeMillis();
            if (now - lastScrollTime < SCROLL_COOLDOWN) {
                return;
            }
            lastScrollTime = now;

            double delta = event.getScrollDelta();
            int change = (delta > 0) ? -1 : 1; // 滚轮方向

            // === 分支逻辑 ===
            if (isWrench) {
                // 切换 Point Tag
                handleScroll(ClientPosCache.SORTED_TAG_NAMES, ClientPosCache.currentFocus, change, (newTag) -> {
                    ClientPosCache.currentFocus = newTag;
                    ModMessage.sendToServer(new PacketSetFocus(newTag));
                });
            }
            else if (isHammer) {
                // 切换 Border Name
                // 【注意】你需要创建 PacketSetBorderFocus 类，或者用其他方式通知服务端
                handleScroll(ClientPosCache.SORTED_BORDER_NAMES, ClientPosCache.currentBorderFocus, change, (newBorder) -> {
                    ClientPosCache.currentBorderFocus = newBorder;
                    ModMessage.sendToServer(new PacketSetBorderFocus(newBorder));
                });





            }
        }
    }

    // 提取通用的滚动计算逻辑
    private static void handleScroll(List<String> list, String currentItem, int change, java.util.function.Consumer<String> onConfirm) {
        if (list.isEmpty()) return;

        int currentIndex = list.indexOf(currentItem);
        if (currentIndex == -1) currentIndex = 0;

        int newIndex = currentIndex + change;
        // 循环取模
        newIndex = (newIndex % list.size() + list.size()) % list.size();

        String newItem = list.get(newIndex);

        if (!newItem.equals(currentItem)) {
            onConfirm.accept(newItem);
        }
    }
}
