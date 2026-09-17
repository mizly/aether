package dev.aether.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// hides the vanilla hud while the nanovg screens are open; the hud editor redraws the elements itself inside the nvg frame
@Mixin(Gui.class)
public class MixinGui {

    @WrapMethod(method = "displayScoreboardSidebar")
    private void aether$extractScoreboardSidebar(GuiGraphicsExtractor graphics, Objective objective, Operation<Void> original) {
        AetherBootstrapHooks.extractScoreboardSidebar(graphics, target -> original.call(target, objective));
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void suppressHudForNvgScreens(GuiGraphicsExtractor guiGraphics,
                                          DeltaTracker deltaTracker,
                                          CallbackInfo ci) {
        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
        if (AetherBootstrapHooks.shouldSuppressVanillaHud(screen)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private Component aether$modifyOverlayMessage(Component component) {
        return AetherBootstrapHooks.transformOverlayMessage(component);
    }

}
