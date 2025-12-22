package com.mapextra.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

public class ModRenderTypes extends RenderType {

    public ModRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                          boolean affectsCrumbling, boolean sortOnUpload,
                          Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    public static final RenderType OVERLAY_LINES = create(
            "overlay_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.of(16.0D))) // 线条宽度
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)

                    // 核心：穿墙
                    .setDepthTestState(NO_DEPTH_TEST)

                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)

                    // 【关键修复】尝试去掉 Layering，或者用 NO_LAYERING
                    // VIEW_OFFSET_Z_LAYERING 有时会干扰透视渲染
                    .setLayeringState(NO_LAYERING)

                    .setOutputState(MAIN_TARGET)
                    .createCompositeState(false)
    );
    public static final RenderType NORMAL_LINES = create(
            "normal_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.of(8.0D)))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setOutputState(MAIN_TARGET)
                    .createCompositeState(false)
    );
}
