package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// freecam picks from the detached camera, so a player interaction sends a break aimed where the camera points while rotation packets say otherwise, and the server kicks for rotationBreak/farbreak
// only the human's input is suppressed: the checks read the key mappings, never raw glfw state, so the macro keeps farming in freecam
@Mixin(Minecraft.class)
public class MixinFreecamInteractionBlocker {
    @Shadow private int missTime;
    @Unique private static String aether$lastAttackDebug = "";

    // pick() ray-traces from getCameraEntity(), which is the detached freecam; feed the real player so interactions stay anchored to it wherever the camera roams
    @Redirect(
        method = "pick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getCameraEntity()Lnet/minecraft/world/entity/Entity;")
    )
    private Entity aether$freecamPickFromPlayer(Minecraft self) {
        if (AetherBootstrapHooks.isFreecamEnabled() && self.player != null) {
            return self.player;
        }
        return self.getCameraEntity();
    }

    @Redirect(
        method = "handleKeybinds",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startAttack()Z")
    )
    private boolean aether$freecamStartAttack(Minecraft self) {
        if (aether$blockManualAttack(self)) {
            if (AetherBootstrapHooks.isFreecamEnabled()) {
                ClientUtils.sendDebugMessage("[FC] startAttack BLOCKED missTime=" + missTime);
            }
            return false;
        }
        boolean result = ((MixinMinecraft) self).aether$startAttack();
        if (AetherBootstrapHooks.isFreecamEnabled()) {
            ClientUtils.sendDebugMessage("[FC] startAttack ran result=" + result + " missTime=" + missTime);
        }
        return result;
    }

    @Redirect(
        method = "handleKeybinds",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V")
    )
    private void aether$freecamContinueAttack(Minecraft self, boolean attacking) {
        boolean blocked = aether$blockManualAttack(self);
        if (AetherBootstrapHooks.isFreecamEnabled()) {
            String state = "blocked=" + blocked + " arg=" + attacking
                    + " isDown=" + self.options.keyAttack.isDown() + " missTime=" + missTime;
            if (!state.equals(aether$lastAttackDebug)) {
                aether$lastAttackDebug = state;
                ClientUtils.sendDebugMessage("[FC] continueAttack " + state);
            }
        }
        ((MixinMinecraft) self).aether$continueAttack(!blocked && attacking);
    }

    @Redirect(
        method = "handleKeybinds",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startUseItem()V")
    )
    private void aether$freecamStartUseItem(Minecraft self) {
        if (AetherBootstrapHooks.isFreecamEnabled() && !self.options.keyUse.isDown()) {
            return;
        }
        ((MixinMinecraft) self).aether$startUseItem();
    }

    @Redirect(
        method = "handleKeybinds",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pickBlockOrEntity()V")
    )
    private void aether$freecamPickBlock(Minecraft self) {
        if (AetherBootstrapHooks.isFreecamEnabled()) {
            return;
        }
        ((MixinMinecraft) self).aether$pickBlockOrEntity();
    }

    private static boolean aether$blockManualAttack(Minecraft self) {
        return AetherBootstrapHooks.isFreecamEnabled() && !self.options.keyAttack.isDown();
    }
}
