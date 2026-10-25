package dev.aether.renderer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

public final class FunRenderer {
    private FunRenderer() {}

    public static boolean hasVisibleEffects() {
        return HatRenderer.hasVisibleEffect() || CosmeticWorldRenderer.hasVisibleEffects();
    }

    public static void collectSubmits(LevelRenderContext ctx) {
        if (HatRenderer.hasVisibleEffect()) HatRenderer.render(ctx);
    }

    // wings draw straight into the framebuffer with raw gl, so they must run after the main pass
    public static void renderAfterMain() {
        if (CosmeticWorldRenderer.hasVisibleEffects()) CosmeticWorldRenderer.render();
    }
}
