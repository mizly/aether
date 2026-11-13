package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ConfirmScreen::class)
class MixinConfirmScreen {
    @Unique private lateinit var `aether$callback`: BooleanConsumer
    @Unique private lateinit var `aether$title`: Component
    @Unique private lateinit var `aether$message`: Component

    @Inject(method = ["<init>"], at = [At("TAIL")])
    private fun `aether$captureArgs`(callback: BooleanConsumer, title: Component, message: Component, ci: CallbackInfo) {
        `aether$callback` = callback
        `aether$title` = title
        `aether$message` = message
    }

    @Inject(method = ["init"], at = [At("HEAD")], cancellable = true)
    private fun `aether$redirectInit`(ci: CallbackInfo) {
        val replacement = AetherBootstrapHooks.maybeCreateConfirmScreen(`aether$callback`, `aether$title`, `aether$message`)
        if (replacement == null) {
            return
        }
        ci.cancel()
        Minecraft.getInstance().gui.setScreen(replacement)
    }
}
