package com.mapextra.client;

import com.mapextra.MapExtra;
import com.mapextra.client.render.BeaconRenderer;
import com.mapextra.client.render.BorderRenderer;
import com.mapextra.client.render.FocusPointRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MapExtra.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientRenderHandler {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // 统一在这一层过滤，所有渲染器都不用再写这行了
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 准备 OpenGL 状态
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        // 这里必须拿到 BufferSource 实现类，因为我们的自定义 RenderType 需要 endBatch
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        // 全局应用摄像机位移
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // === 1. 调用信标渲染器 ===
        BeaconRenderer.render(poseStack, bufferSource);

        // === 2. 调用红框复活点渲染器 ===
        FocusPointRenderer.render(poseStack, bufferSource);

        // === 3. 调用世界边界渲染器 ===
        BorderRenderer.render(poseStack, bufferSource);

//        RadarRenderer.render(poseStack, bufferSource);

        // === 统一结束批处理 ===
        // 这里非常关键！如果不手动 endBatch，你的自定义 RenderType 可能不会立即绘制
        bufferSource.endBatch(ModRenderTypes.NORMAL_LINES);
        bufferSource.endBatch(ModRenderTypes.OVERLAY_LINES);
        // 原版 RenderType 通常不需要手动 endBatch，除非你想强制覆盖深度
        bufferSource.endBatch();

        poseStack.popPose();
    }
}
