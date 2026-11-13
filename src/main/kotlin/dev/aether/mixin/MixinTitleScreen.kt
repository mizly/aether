package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.proxy.AetherProxyManager
import dev.aether.proxy.AetherProxyScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

// cancelling init means vanilla buttons/panorama never get set up, and setting the screen directly rather than via execute() avoids a one-frame TitleScreen flash
@Mixin(TitleScreen::class)
abstract class MixinTitleScreen : Screen(TITLE_TEXT) {
    @Inject(method = ["init"], at = [At("HEAD")], cancellable = true)
    private fun `aether$redirectInit`(ci: CallbackInfo) {
        val replacement = `aether$getReplacement`() ?: return
        ci.cancel()
        Minecraft.getInstance().gui.setScreen(replacement)
    }

    @Inject(method = ["extractRenderState"], at = [At("HEAD")], cancellable = true)
    private fun `aether$redirectRender`(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float, ci: CallbackInfo) {
        val replacement = `aether$getReplacement`() ?: return
        ci.cancel()
        Minecraft.getInstance().gui.setScreen(replacement)
    }

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun `aether$addButtons`(ci: CallbackInfo) {
        val buttonX = width / 2 - AETHER_BUTTON_WIDTH / 2
        val buttonY = `aether$nextInjectedButtonY`()
        addRenderableWidget(
            Button.builder(Component.literal(AetherProxyManager.selectedStatus())) {
                Minecraft.getInstance().gui.setScreen(AetherProxyScreen(this))
            }.bounds(buttonX, buttonY, AETHER_BUTTON_WIDTH, AETHER_BUTTON_HEIGHT).build()
        )
    }

    @Inject(method = ["createNormalMenuOptions"], at = [At("RETURN")], cancellable = true)
    private fun `aether$addHypixelButton`(y: Int, spacing: Int, cir: CallbackInfoReturnable<Int>) {
        var multiplayerButton: Button? = null
        for (listener in this.children()) {
            if (listener is Button && listener.getMessage() == Component.translatable("menu.multiplayer")) {
                multiplayerButton = listener
                break
            }
        }
        val targetButton = multiplayerButton ?: return
        val hypixelY = cir.returnValue + spacing
        val hypixelButton = Button.builder(Component.literal("Hypixel")) {
            `aether$connectToHypixel`()
        }.bounds(targetButton.getX(), hypixelY, targetButton.getWidth(), targetButton.getHeight()).build()
        hypixelButton.active = targetButton.active
        addRenderableWidget(hypixelButton)
        cir.returnValue = hypixelY
    }

    private fun `aether$connectToHypixel`() {
        val minecraft = Minecraft.getInstance()
        val serverData = ServerData("Hypixel", HYPIXEL_ADDRESS, ServerData.Type.OTHER)
        ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(HYPIXEL_ADDRESS), serverData, false, null)
    }

    private fun `aether$nextInjectedButtonY`(): Int {
        val minX = width / 2 - AETHER_BUTTON_WIDTH / 2 - 4
        val maxX = width / 2 + AETHER_BUTTON_WIDTH / 2 + 4
        var maxBottom = height / 4 + 96
        for (listener in this.children()) {
            if (listener !is Button) continue
            if (listener.getX() > maxX || listener.getX() + listener.getWidth() < minX) continue
            if (listener.getWidth() < 98 || listener.getY() < height / 4 - 8) continue
            maxBottom = Math.max(maxBottom, listener.getY() + listener.getHeight())
        }
        return maxBottom + AETHER_BUTTON_GAP
    }

    private fun `aether$getReplacement`(): Screen? {
        val replacement = AetherBootstrapHooks.maybeCreateTitleScreen()
        return if (replacement === this) null else replacement
    }

    private companion object {
        private val TITLE_TEXT: Component = Component.empty()
        private const val AETHER_BUTTON_WIDTH = 200
        private const val AETHER_BUTTON_HEIGHT = 20
        private const val AETHER_BUTTON_GAP = 4
        private const val HYPIXEL_ADDRESS = "mc.hypixel.net"
    }
}
