package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;

@Mixin(ConfirmScreen.class)
public class MixinConfirmScreen {

    @Unique private BooleanConsumer aether$callback;
    @Unique private Component         aether$title;
    @Unique private Component         aether$message;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void aether$captureArgs(BooleanConsumer callback, Component title,
                                    Component message, CallbackInfo ci) {
        aether$callback = callback;
        aether$title    = title;
        aether$message  = message;
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void aether$redirectInit(CallbackInfo ci) {
        var replacement = AetherBootstrapHooks.maybeCreateConfirmScreen(aether$callback, aether$title, aether$message);
        if (replacement == null) {
            return;
        }
        ci.cancel();
        Minecraft.getInstance().setScreen(replacement);
    }

}

