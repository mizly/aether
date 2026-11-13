package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.DirectJoinServerScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(DirectJoinServerScreen::class)
class MixinDirectJoinServerScreen {
    @Shadow private var ipEdit: EditBox? = null

    @Unique private var `aether$lastScreen`: Screen? = null
    @Unique private lateinit var `aether$callback`: BooleanConsumer
    @Unique private lateinit var `aether$serverData`: ServerData

    @Inject(method = ["<init>"], at = [At("TAIL")])
    private fun `aether$captureArgs`(lastScreen: Screen, callback: BooleanConsumer, serverData: ServerData, ci: CallbackInfo) {
        `aether$lastScreen` = lastScreen
        `aether$callback` = callback
        `aether$serverData` = serverData
    }

    @Inject(method = ["init"], at = [At("HEAD")], cancellable = true)
    private fun `aether$redirectInit`(ci: CallbackInfo) {
        val replacement = AetherBootstrapHooks.maybeCreateDirectJoinScreen(`aether$lastScreen`, `aether$callback`, `aether$serverData`)
        if (replacement == null) return
        ci.cancel()
        Minecraft.getInstance().gui.setScreen(replacement)
    }

    @Inject(method = ["removed"], at = [At("HEAD")], cancellable = true)
    private fun `aether$guardRemoved`(ci: CallbackInfo) {
        if (ipEdit == null) ci.cancel()
    }

    @Inject(method = ["setInitialFocus"], at = [At("HEAD")], cancellable = true)
    private fun `aether$guardSetInitialFocus`(ci: CallbackInfo) {
        if (ipEdit == null) ci.cancel()
    }

    @Inject(method = ["extractRenderState"], at = [At("HEAD")], cancellable = true)
    private fun `aether$guardRender`(g: GuiGraphicsExtractor, mx: Int, my: Int, pt: Float, ci: CallbackInfo) {
        if (ipEdit == null) ci.cancel()
    }
}
