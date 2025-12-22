package com.mapextra.event;

import com.mapextra.MapExtra;
import com.mapextra.client.ClientPosCache;
import com.mapextra.init.ModItemRegister;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class HudRenderEventHandler {

    @SubscribeEvent
    public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        ItemStack mainHand = player.getMainHandItem();

        // === 分支判断：根据手持物品决定渲染内容 ===
        if (mainHand.is(ModItemRegister.WRENCH.get())) {
            // 渲染：点位标签 (Tags)
            renderList(event.getGuiGraphics(), mc,
                    ClientPosCache.SORTED_TAG_NAMES,
                    ClientPosCache.currentFocus,
                    true); // true = 显示数量
        }
        else if (mainHand.is(ModItemRegister.HAMMER.get())) {
            // 渲染：边界名称 (Borders)
            // 【注意】你需要确保 ClientPosCache 里有 SORTED_BORDER_NAMES 和 currentBorderFocus
            renderList(event.getGuiGraphics(), mc,
                    ClientPosCache.SORTED_BORDER_NAMES,
                    ClientPosCache.currentBorderFocus,
                    false); // false = 不显示数量
        }
    }

    // 提取出的通用渲染逻辑 (完美复刻原逻辑)
    private static void renderList(GuiGraphics guiGraphics, Minecraft mc, List<String> items, String currentFocus, boolean showCount) {
        int totalItems = items.size();
        if (totalItems == 0) return;

        int currentIndex = items.indexOf(currentFocus);
        if (currentIndex == -1) currentIndex = 0;

        // === 1. 屏幕定位 ===
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int baseX = 5;
        int baseY = screenHeight / 5;

        // === 2. 计算渲染行数 ===
        int maxLines = 5;
        int linesToRender = Math.min(maxLines, totalItems);

        // === 3. 计算焦点偏移 ===
        int focusLineOffset = (linesToRender == 1) ? 0 : 1;
        int lineHeight = 12;

        int startDataIndex = currentIndex - focusLineOffset;

        // === 4. 循环渲染 ===
        for (int i = 0; i < linesToRender; i++) {
            int rawIndex = startDataIndex + i;
            int actualIndex = (rawIndex % totalItems + totalItems) % totalItems;

            String name = items.get(actualIndex);
            boolean isFocused = (i == focusLineOffset);

            // 构造显示文本
            String displayText = name;
            if (showCount && isFocused) {
                // 仅针对 PointTag 显示数量逻辑
                int count;
                if (name.equals(ClientPosCache.currentFocus)) {
                    count = ClientPosCache.currentPositions.size();
                } else {
                    count = ClientPosCache.ALL_TAG_STATS.getOrDefault(name, 0);
                }
                displayText = name + " : " + count;
            }

            renderRow(guiGraphics, mc, displayText, baseX, baseY + (i * lineHeight), isFocused);
        }
    }

    private static void renderRow(GuiGraphics guiGraphics, Minecraft mc, String text, int x, int y, boolean isFocused) {
        int color;
        float scale;

        if (isFocused) {
            color = 0xFFFF55; // 金色
            scale = 1.2f;
        } else {
            color = 0xAAAAAA; // 灰色
            scale = 0.8f;
        }

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        poseStack.translate(x + (isFocused ? 5 : 0), y, 0);
        poseStack.scale(scale, scale, 1.0f);

        guiGraphics.drawString(mc.font, text, 0, 0, color, true);

        poseStack.popPose();
    }
}
