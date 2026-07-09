package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.proxy.AetherProxyManager;
import dev.aether.proxy.AetherProxyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MixinJoinMultiplayerScreen extends Screen {

    protected MixinJoinMultiplayerScreen(Component title) {
        super(title);
    }

    @Shadow private Screen lastScreen;
    @Shadow protected ServerSelectionList serverSelectionList;

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void aether$redirectInit(CallbackInfo ci) {
        var replacement = aether$getReplacement();
        if (replacement == null) {
            return;
        }
        ci.cancel();
        Minecraft.getInstance().setScreen(replacement);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void aether$addProxyButton(CallbackInfo ci) {
        if (serverSelectionList == null) {
            return;
        }

        this.addRenderableWidget(Button.builder(
                        Component.literal(AetherProxyManager.selectedStatus()),
                        button -> Minecraft.getInstance().setScreen(new AetherProxyScreen(this)))
                .bounds(this.width - 185, 6, 180, 20)
                .build());
    }

    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void aether$guardRemoved(CallbackInfo ci) {
        if (serverSelectionList == null) ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void aether$guardTick(CallbackInfo ci) {
        var replacement = aether$getReplacement();
        if (replacement != null) {
            ci.cancel();
            Minecraft.getInstance().setScreen(replacement);
            return;
        }
        if (serverSelectionList == null) ci.cancel();
    }

    private Screen aether$getReplacement() {
        Screen replacement = AetherBootstrapHooks.maybeCreateMultiplayerScreen(lastScreen);
        return replacement == this ? null : replacement;
    }
}

