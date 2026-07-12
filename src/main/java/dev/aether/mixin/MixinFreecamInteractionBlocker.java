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

/**
 * While freecam is active the camera entity is a detached {@code RemotePlayer}, so
 * {@link net.minecraft.client.Minecraft#pick} ray-traces from the freecam viewpoint.
 * If the <em>player</em> then interacts (attack / use / pick), the resulting break /
 * interact packet is aimed wherever the freecam points while the real player's rotation
 * packets say otherwise, tripping the server's rotation / reach checks ("rotationBreak" /
 * "farbreak") and getting the user kicked.
 *
 * <p>We only want to suppress the <em>human's</em> physical input, not the macro. Physical
 * mouse buttons are swallowed at the source by {@code MixinMouseHandler#onMouseClick} while
 * freecam is active, so during freecam the attack/use mappings can only be down
 * programmatically (macro). The checks below therefore consult only the mapping state -
 * never raw GLFW button state, which the human's mouse still flips even with events
 * swallowed. So the farming loop keeps working in freecam while manual clicks do nothing.
 */
@Mixin(Minecraft.class)
public class MixinFreecamInteractionBlocker {
    @Shadow private int missTime;
    @Unique private static String aether$lastAttackDebug = "";

    /**
     * {@link net.minecraft.client.Minecraft#pick} computes {@code hitResult} by
     * ray-tracing from {@code getCameraEntity()} - the detached freecam camera while
     * freecam is active. That makes the macro break whatever the freecam is aimed at
     * instead of the block in front of the real player. Feed the real player as the ray
     * source so interactions stay anchored to the player, wherever the camera roams.
     */
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

    /** {@code true} when freecam is on and the attack mapping is not macro-held. */
    private static boolean aether$blockManualAttack(Minecraft self) {
        return AetherBootstrapHooks.isFreecamEnabled() && !self.options.keyAttack.isDown();
    }
}
