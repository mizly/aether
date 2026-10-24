package dev.aether.renderer;

import net.minecraft.client.Minecraft;

public final class FailsafeColourFlashRenderer {
    private FailsafeColourFlashRenderer() {
    }

    public static void render(int argb, float opacity) {
        if (opacity <= 0.0f || NanoVGManager.isDrawing()) {
            return;
        }
        var window = Minecraft.getInstance().getWindow();
        float width = window.getGuiScaledWidth();
        float height = window.getGuiScaledHeight();
        if (width <= 0 || height <= 0) return;

        int alpha = Math.round(Math.max(0.0f, Math.min(1.0f, opacity)) * ((argb >>> 24) & 0xFF));
        NanoVGManager.ensureInitialized();
        NanoVGManager.beginFrame(width, height);
        try {
            NanoVGManager.getRenderer().rect(0, 0, width, height, (alpha << 24) | (argb & 0x00FFFFFF));
        } finally {
            NanoVGManager.endFrame();
        }
    }
}
