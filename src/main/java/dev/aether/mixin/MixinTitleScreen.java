package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.proxy.AetherProxyManager;
import dev.aether.proxy.AetherProxyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// cancelling init means vanilla buttons/panorama never get set up, and setting the screen directly rather than via execute() avoids a one-frame TitleScreen flash
@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {
    private static final int AETHER_BUTTON_WIDTH = 200;
    private static final int AETHER_BUTTON_HEIGHT = 20;
    private static final int AETHER_BUTTON_GAP = 4;
    private static final String HYPIXEL_ADDRESS = "mc.hypixel.net";

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
    private void aether$addButtons(CallbackInfo ci) {
        int buttonX = this.width / 2 - AETHER_BUTTON_WIDTH / 2;
        int buttonY = aether$nextInjectedButtonY();
        this.addRenderableWidget(Button.builder(
                        Component.literal(AetherProxyManager.selectedStatus()),
                        button -> Minecraft.getInstance().setScreen(new AetherProxyScreen(this)))
                .bounds(buttonX, buttonY, AETHER_BUTTON_WIDTH, AETHER_BUTTON_HEIGHT)
                .build());
    }

    @Inject(method = "createNormalMenuOptions", at = @At("RETURN"), cancellable = true)
    private void aether$addHypixelButton(int y, int spacing, CallbackInfoReturnable<Integer> cir) {
        Button multiplayerButton = null;
        for (var listener : this.children()) {
            if (listener instanceof Button button
                    && button.getMessage().equals(Component.translatable("menu.multiplayer"))) {
                multiplayerButton = button;
                break;
            }
        }
        if (multiplayerButton == null) {
            return;
        }

        int hypixelY = cir.getReturnValue() + spacing;
        Button hypixelButton = Button.builder(
                        Component.literal("Hypixel"),
                        button -> aether$connectToHypixel())
                .bounds(multiplayerButton.getX(), hypixelY,
                        multiplayerButton.getWidth(), multiplayerButton.getHeight())
                .build();
        hypixelButton.active = multiplayerButton.active;
        this.addRenderableWidget(hypixelButton);
        cir.setReturnValue(hypixelY);
    }

    private void aether$connectToHypixel() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData serverData = new ServerData("Hypixel", HYPIXEL_ADDRESS, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
                this,
                minecraft,
                ServerAddress.parseString(HYPIXEL_ADDRESS),
                serverData,
                false,
                null);
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
