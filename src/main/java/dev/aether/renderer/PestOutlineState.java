package dev.aether.renderer;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

public record PestOutlineState(int color, int originalColor) {
    private static final RenderStateDataKey<PestOutlineState> KEY = RenderStateDataKey.create();

    public static void extract(LivingEntityRenderState state, int color) {
        if (color != 0) {
            state.setData(KEY, new PestOutlineState(color, state.outlineColor));
            state.outlineColor = color;
        } else if (state.getData(KEY) != null) {
            state.setData(KEY, null);
        }
    }

    public static void submitBody(LivingEntityRenderState state, Runnable submit) {
        PestOutlineState outline = state.getData(KEY);
        submitWithColor(state, outline == null ? state.outlineColor : outline.originalColor, submit);
    }

    public static void submitHead(LivingEntityRenderState state, Runnable submit) {
        PestOutlineState outline = state.getData(KEY);
        submitWithColor(state, outline == null ? state.outlineColor : outline.color, submit);
    }

    private static void submitWithColor(LivingEntityRenderState state, int color, Runnable submit) {
        int previous = state.outlineColor;
        state.outlineColor = color;
        try {
            submit.run();
        } finally {
            state.outlineColor = previous;
        }
    }
}
