package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonInfo
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(MouseHandler::class)
class MixinMouseHandler {
    @Inject(method = ["turnPlayer"], at = [At("HEAD")], cancellable = true)
    private fun onTurnPlayer(frameTime: Double, ci: CallbackInfo) {
        if (AetherBootstrapHooks.shouldCancelMouseTurn()) {
            ci.cancel()
        }
    }

    // stops vanilla re-grabbing the cursor while the macro has released it
    @Inject(method = ["grabMouse"], at = [At("HEAD")], cancellable = true)
    private fun onGrabMouse(ci: CallbackInfo) {
        if (AetherBootstrapHooks.isMouseUngrabbed()) {
            ci.cancel()
        }
    }

    @Inject(method = ["onButton"], at = [At("HEAD")], cancellable = true)
    private fun onMouseClick(l: Long, mouseButtonInfo: MouseButtonInfo, i: Int, ci: CallbackInfo) {
        if (i == 1) {
            AetherBootstrapHooks.onUserInput()
        }
        if (AetherBootstrapHooks.isMouseUngrabbed()) {
            ci.cancel()
            return
        }
        // Swallow all physical mouse buttons while freecam is on (no screen open) so they
        // never touch key states or click counts - only the macro's programmatic input acts.
        if (AetherBootstrapHooks.isFreecamEnabled() && Minecraft.getInstance().gui.screen() == null) {
            dev.aether.util.ClientUtils.sendDebugMessage("[FC] swallowed button " + mouseButtonInfo.button() + " action=" + i)
            ci.cancel()
            return
        }
        if (mouseButtonInfo.button() == 0 && i == 1) {
            val mc = Minecraft.getInstance()
            val screen: Screen? = mc.gui.screen()
            if (screen != null && AetherBootstrapHooks.hasCustomScreenBackground(screen)) {
                val mouseX = mc.mouseHandler.xpos() * mc.getWindow().guiScaledWidth.toDouble() / mc.getWindow().width.toDouble()
                val mouseY = mc.mouseHandler.ypos() * mc.getWindow().guiScaledHeight.toDouble() / mc.getWindow().height.toDouble()
                AetherBootstrapHooks.onBackgroundLeftClick(mc, screen, mouseX, mouseY)
            }
        }
    }

    @Inject(method = ["onScroll"], at = [At("HEAD")], cancellable = true)
    private fun onMouseScroll(l: Long, d: Double, e: Double, ci: CallbackInfo) {
        AetherBootstrapHooks.onUserInput()
        if (AetherBootstrapHooks.isMouseUngrabbed()) {
            ci.cancel()
            return
        }
    }

    @Inject(method = ["onMove"], at = [At("HEAD")])
    private fun onMouseMove(window: Long, mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double, ci: CallbackInfo) {
        AetherBootstrapHooks.onUserInput()
    }
}
