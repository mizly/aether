package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.bootstrap.AetherKeybindRegistry;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public final class MixinKeyboardHandler {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == 1) {
            AetherBootstrapHooks.onUserInput();
            Minecraft client = Minecraft.getInstance();
            if (client.options == null || client.screen != null) return;
            if (window != ((AccessorWindow) (Object) client.getWindow()).getHandle()
                    || AetherKeybindRegistry.getMacroToggleKey().matches(event)) return;
            var options = client.options;
            if (options.keyUp.matches(event) || options.keyDown.matches(event)
                    || options.keyLeft.matches(event) || options.keyRight.matches(event)
                    || options.keyJump.matches(event) || options.keyShift.matches(event)
                    || options.keySprint.matches(event) || options.keyAttack.matches(event)
                    || options.keyUse.matches(event) || options.keyInventory.matches(event)
                    || options.keyDrop.matches(event) || options.keySwapOffhand.matches(event)) {
                AetherBootstrapHooks.onGameplayInput();
                return;
            }
            for (var key : options.keyHotbarSlots) {
                if (key.matches(event)) {
                    AetherBootstrapHooks.onGameplayInput();
                    return;
                }
            }
        }
    }
}
