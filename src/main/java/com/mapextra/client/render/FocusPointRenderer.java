package com.mapextra.client.render;

import com.mapextra.client.ClientPosCache;
import com.mapextra.client.ModRenderTypes;
import com.mapextra.init.ModItemRegister; // 确保引用正确
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.*;

public class FocusPointRenderer {
    // 状态管理依然保留在这里，因为它是渲染专用的状态
    private static final Map<BlockPos, Long> ANIMATION_STATES = new HashMap<>();
    private static final Map<BlockPos, Long> REMOVAL_STATES = new HashMap<>();
    private static final long APPEAR_DURATION = 250;
    private static final long DISAPPEAR_DURATION = 100;

    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // 只有手持扳手才渲染
        if (!mc.player.getMainHandItem().is(ModItemRegister.WRENCH.get())) return;

        List<BlockPos> renderList = new ArrayList<>(ClientPosCache.currentPositions);

        if (renderList.isEmpty() && REMOVAL_STATES.isEmpty() && ANIMATION_STATES.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        updateStates(renderList, now);

        // 渲染活跃点
        for (BlockPos pos : renderList) {
            long startTime = ANIMATION_STATES.getOrDefault(pos, now);
            float progress = Mth.clamp((float) (now - startTime) / APPEAR_DURATION, 0f, 1f);
            float animVal = easeOutExpo(progress);
            float entryScale = (float) (0.5 + 0.5 * animVal);
            renderBlockBox(poseStack, bufferSource, pos, animVal, 1.0f, entryScale);
        }

        // 渲染消失点
        for (Map.Entry<BlockPos, Long> entry : REMOVAL_STATES.entrySet()) {
            BlockPos pos = entry.getKey();
            float progress = Mth.clamp((float) (now - entry.getValue()) / DISAPPEAR_DURATION, 0f, 1f);
            float fadeFactor = 1.0f - progress;
            renderBlockBox(poseStack, bufferSource, pos, 1.0f, fadeFactor, fadeFactor);
        }
    }

    private static void updateStates(List<BlockPos> renderList, long now) {
        Iterator<Map.Entry<BlockPos, Long>> it = ANIMATION_STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            if (!renderList.contains(entry.getKey())) {
                REMOVAL_STATES.put(entry.getKey(), now);
                it.remove();
            }
        }
        REMOVAL_STATES.entrySet().removeIf(entry -> (now - entry.getValue()) > DISAPPEAR_DURATION);
        for (BlockPos pos : renderList) {
            REMOVAL_STATES.remove(pos);
            ANIMATION_STATES.putIfAbsent(pos, now);
        }
    }

    private static void renderBlockBox(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                       BlockPos pos, float animVal, float alphaMul, float scale) {
        double yOffset = -0.8 * (1.0f - animVal);
        double center = 0.475; // (0.05+0.9)/2
        double halfSize = 0.425 * scale;
        double min = center - halfSize;
        double max = center + halfSize;

        // Overlay
        LevelRenderer.renderLineBox(poseStack, bufferSource.getBuffer(ModRenderTypes.OVERLAY_LINES),
                pos.getX() + min, pos.getY() + min + yOffset, pos.getZ() + min,
                pos.getX() + max, pos.getY() + max + yOffset, pos.getZ() + max,
                0.5F, 0F, 0F, 0.35F * alphaMul);

        // Normal
        LevelRenderer.renderLineBox(poseStack, bufferSource.getBuffer(ModRenderTypes.NORMAL_LINES),
                pos.getX() + min, pos.getY() + min + yOffset, pos.getZ() + min,
                pos.getX() + max, pos.getY() + max + yOffset, pos.getZ() + max,
                1F, 0.3F, 0F, 0.9F * alphaMul);
    }

    private static float easeOutExpo(float x) {
        return x == 1 ? 1 : 1 - (float) Math.pow(2, -10 * x);
    }
}
