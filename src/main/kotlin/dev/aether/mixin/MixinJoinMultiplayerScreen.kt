package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.proxy.AetherProxyManager
import dev.aether.proxy.AetherProxyScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(JoinMultiplayerScreen::class)
abstract class MixinJoinMultiplayerScreen(title: Component) : Screen(title) {
    @Shadow private lateinit var lastScreen: Screen
    @Shadow protected var serverSelectionList: ServerSelectionList? = null

    @Inject(method = ["init"], at = [At("HEAD")], cancellable = true)
    private fun `aether$redirectInit`(ci: CallbackInfo) {
        val replacement = `aether$getReplacement`() ?: return
        ci.cancel()
        Minecraft.getInstance().gui.setScreen(replacement)
    }

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun `aether$addProxyButton`(ci: CallbackInfo) {
        if (serverSelectionList == null) return
        addRenderableWidget(
            Button.builder(Component.literal(AetherProxyManager.selectedStatus())) {
                Minecraft.getInstance().gui.setScreen(AetherProxyScreen(this))
            }.bounds(width - 185, 6, 180, 20).build()
        )
    }

    @Inject(method = ["removed"], at = [At("HEAD")], cancellable = true)
    private fun `aether$guardRemoved`(ci: CallbackInfo) {
        if (serverSelectionList == null) ci.cancel()
    }

    @Inject(method = ["tick"], at = [At("HEAD")], cancellable = true)
    private fun `aether$guardTick`(ci: CallbackInfo) {
        val replacement = `aether$getReplacement`()
        if (replacement != null) {
            ci.cancel()
            Minecraft.getInstance().gui.setScreen(replacement)
            return
        }
        if (serverSelectionList == null) ci.cancel()
    }

    private fun `aether$getReplacement`(): Screen? {
        val replacement = AetherBootstrapHooks.maybeCreateMultiplayerScreen(lastScreen)
        return if (replacement === this) null else replacement
    }
}
