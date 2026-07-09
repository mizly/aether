package dev.aether.renderer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

public final class FunRenderer {
    private FunRenderer() {}

    public static boolean hasVisibleEffects() {
        return HatRenderer.hasVisibleEffect();
    }

    public static void renderWorld(LevelRenderContext ctx) {
        HatRenderer.render(ctx);
    }
}
