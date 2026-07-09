package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.proxy.AetherProxyManager;
import dev.aether.proxy.AetherProxyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces {@link TitleScreen} with {@link AetherTitleScreen} with zero frame delay.
 *
 * Cancelling {@code init} means vanilla buttons/panorama are never set up.
 * Calling {@code setScreen} directly (not via {@code execute()}) means
 * {@code mc.screen} is already {@link AetherTitleScreen} before the first render tick,
 * so there is no one-frame TitleScreen flash.
 */
@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {
    private static final int AETHER_BUTTON_WIDTH = 200;
    private static final int AETHER_BUTTON_HEIGHT = 20;
    private static final int AETHER_BUTTON_GAP = 4;

    protected MixinTitleScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void aether$redirectInit(CallbackInfo ci) {
        var replacement = aether$getReplacement();
        if (replacement == null) {
            return;
        }
        ci.cancel();
        Minecraft.getInstance().setScreen(replacement);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void aether$redirectRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        var replacement = aether$getReplacement();
        if (replacement == null) {
            return;
        }
        ci.cancel();
        Minecraft.getInstance().setScreen(replacement);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void aether$addProxyButton(CallbackInfo ci) {
        int buttonX = this.width / 2 - AETHER_BUTTON_WIDTH / 2;
        int buttonY = aether$nextInjectedButtonY();
        this.addRenderableWidget(Button.builder(
                        Component.literal(AetherProxyManager.selectedStatus()),
                        button -> Minecraft.getInstance().setScreen(new AetherProxyScreen(this)))
                .bounds(buttonX, buttonY, AETHER_BUTTON_WIDTH, AETHER_BUTTON_HEIGHT)
                .build());
    }

    private int aether$nextInjectedButtonY() {
        int minX = this.width / 2 - AETHER_BUTTON_WIDTH / 2 - 4;
        int maxX = this.width / 2 + AETHER_BUTTON_WIDTH / 2 + 4;
        int maxBottom = this.height / 4 + 96;
        for (var listener : this.children()) {
            if (!(listener instanceof Button button)) {
                continue;
            }
            if (button.getX() > maxX || button.getX() + button.getWidth() < minX) {
                continue;
            }
            if (button.getWidth() < 98 || button.getY() < this.height / 4 - 8) {
                continue;
            }
            maxBottom = Math.max(maxBottom, button.getY() + button.getHeight());
        }
        return maxBottom + AETHER_BUTTON_GAP;
    }

    private Screen aether$getReplacement() {
        Screen replacement = AetherBootstrapHooks.maybeCreateTitleScreen();
        return replacement == this ? null : replacement;
    }
}

