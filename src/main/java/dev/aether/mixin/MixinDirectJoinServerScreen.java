package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;

@Mixin(DirectJoinServerScreen.class)
public class MixinDirectJoinServerScreen {

    @Shadow private EditBox ipEdit;

    @Unique private Screen          aether$lastScreen;
    @Unique private BooleanConsumer aether$callback;
    @Unique private ServerData      aether$serverData;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void aether$captureArgs(Screen lastScreen, BooleanConsumer callback,
                                    ServerData serverData, CallbackInfo ci) {
        aether$lastScreen = lastScreen;
        aether$callback   = callback;
        aether$serverData = serverData;
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void aether$redirectInit(CallbackInfo ci) {
        var replacement = AetherBootstrapHooks.maybeCreateDirectJoinScreen(aether$lastScreen, aether$callback, aether$serverData);
        if (replacement == null) {
            return;
        }
        ci.cancel();
        Minecraft.getInstance().setScreen(replacement);
    }

    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void aether$guardRemoved(CallbackInfo ci) {
        if (ipEdit == null) ci.cancel();
    }

    @Inject(method = "setInitialFocus", at = @At("HEAD"), cancellable = true)
    private void aether$guardSetInitialFocus(CallbackInfo ci) {
        if (ipEdit == null) ci.cancel();
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void aether$guardRender(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float pt, CallbackInfo ci) {
        if (ipEdit == null) ci.cancel();
    }
}

