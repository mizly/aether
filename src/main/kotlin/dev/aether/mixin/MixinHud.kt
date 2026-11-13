package dev.aether.mixin

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Hud
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.Objective
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyVariable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

// 26.2 moved hud drawing, the scoreboard sidebar and the actionbar from Gui to Hud
// hides the vanilla hud while the nanovg screens are open; the hud editor redraws the elements itself inside the nvg frame
@Mixin(Hud::class)
class MixinHud {
    @WrapMethod(method = ["displayScoreboardSidebar"])
    private fun `aether$extractScoreboardSidebar`(graphics: GuiGraphicsExtractor, objective: Objective, original: Operation<Void>) {
        AetherBootstrapHooks.extractScoreboardSidebar(graphics) { target -> original.call(target, objective) }
    }

    @Inject(method = ["extractRenderState"], at = [At("HEAD")], cancellable = true)
    private fun suppressHudForNvgScreens(guiGraphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker, ci: CallbackInfo) {
        val screen = Minecraft.getInstance().gui.screen()
        if (AetherBootstrapHooks.shouldSuppressVanillaHud(screen)) ci.cancel()
    }

    @ModifyVariable(method = ["setOverlayMessage"], at = [At("HEAD")], argsOnly = true)
    private fun `aether$modifyOverlayMessage`(component: Component): Component =
        AetherBootstrapHooks.transformOverlayMessage(component)
}
