package dev.aether.renderer

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext

class FunRenderer private constructor() {
    companion object {
        @JvmStatic fun hasVisibleEffects(): Boolean = HatRenderer.hasVisibleEffect() || CosmeticWorldRenderer.hasVisibleEffects()
        @JvmStatic fun collectSubmits(ctx: LevelRenderContext) { if (HatRenderer.hasVisibleEffect()) HatRenderer.render(ctx) }
        @JvmStatic fun renderAfterMain() { if (CosmeticWorldRenderer.hasVisibleEffects()) CosmeticWorldRenderer.render() }
    }
}
