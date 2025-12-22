package com.mapextra.client.render;

import com.mapextra.client.ClientPosCache;
import com.mapextra.client.ModRenderTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;

public class BeaconRenderer {
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource) {
        if (ClientPosCache.BEACONS.isEmpty()) return;

        VertexConsumer builder = bufferSource.getBuffer(ModRenderTypes.OVERLAY_LINES);

        // 自定义样式
        float scale = 0.4f;
        float red = 0.2f;
        float green = 1.0f;
        float blue = 0.2f;
        float alpha = 0.3f;

        double center = 0.5;
        double halfSize = 0.5 * scale;

        for (BlockPos pos : ClientPosCache.BEACONS) {
            double minX = pos.getX() + center - halfSize;
            double minY = pos.getY() + center - halfSize;
            double minZ = pos.getZ() + center - halfSize;
            double maxX = pos.getX() + center + halfSize;
            double maxZ = pos.getZ() + center + halfSize;

            LevelRenderer.renderLineBox(
                    poseStack, builder,
                    minX, minY, minZ,
                    maxX, 320, maxZ, // 高度写死或动态
                    red, green, blue,
                    alpha
            );
        }
        // 注意：endBatch 由调用者统一管理，或者这里不调用，交给 bufferSource 自动处理
    }
}
