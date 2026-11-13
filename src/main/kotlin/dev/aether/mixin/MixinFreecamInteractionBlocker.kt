package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

// freecam picks from the detached camera, so a player interaction sends a break aimed where the camera points while rotation packets say otherwise, and the server kicks for rotationBreak/farbreak
// only the human's input is suppressed: the checks read the key mappings, never raw glfw state, so the macro keeps farming in freecam
@Mixin(Minecraft::class)
class MixinFreecamInteractionBlocker {
    @Shadow private var missTime: Int = 0
    @Unique private var `aether$lastAttackDebug`: String = ""

    // pick() ray-traces from getCameraEntity(), which is the detached freecam; feed the real player so interactions stay anchored to it wherever the camera roams
    @Redirect(
        method = ["pick"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getCameraEntity()Lnet/minecraft/world/entity/Entity;")]
    )
    private fun `aether$freecamPickFromPlayer`(self: Minecraft): Entity? {
        if (AetherBootstrapHooks.isFreecamEnabled() && self.player != null) {
            return self.player
        }
        return self.cameraEntity
    }

    @Redirect(
        method = ["handleKeybinds"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startAttack()Z")]
    )
    private fun `aether$freecamStartAttack`(self: Minecraft): Boolean {
        if (aetherBlockManualAttack(self)) {
            if (AetherBootstrapHooks.isFreecamEnabled()) {
                ClientUtils.sendDebugMessage("[FC] startAttack BLOCKED missTime=$missTime")
            }
            return false
        }
        val result = (self as MixinMinecraft).`aether$startAttack`()
        if (AetherBootstrapHooks.isFreecamEnabled()) {
            ClientUtils.sendDebugMessage("[FC] startAttack ran result=$result missTime=$missTime")
        }
        return result
    }

    @Redirect(
        method = ["handleKeybinds"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V")]
    )
    private fun `aether$freecamContinueAttack`(self: Minecraft, attacking: Boolean) {
        val blocked = aetherBlockManualAttack(self)
        if (AetherBootstrapHooks.isFreecamEnabled()) {
            val state = "blocked=$blocked arg=$attacking isDown=${self.options.keyAttack.isDown()} missTime=$missTime"
            if (state != `aether$lastAttackDebug`) {
                `aether$lastAttackDebug` = state
                ClientUtils.sendDebugMessage("[FC] continueAttack $state")
            }
        }
        (self as MixinMinecraft).`aether$continueAttack`(!blocked && attacking)
    }

    @Redirect(
        method = ["handleKeybinds"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startUseItem()V")]
    )
    private fun `aether$freecamStartUseItem`(self: Minecraft) {
        if (AetherBootstrapHooks.isFreecamEnabled() && !self.options.keyUse.isDown()) {
            return
        }
        (self as MixinMinecraft).`aether$startUseItem`()
    }

    @Redirect(
        method = ["handleKeybinds"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pickBlockOrEntity()V")]
    )
    private fun `aether$freecamPickBlock`(self: Minecraft) {
        if (AetherBootstrapHooks.isFreecamEnabled()) {
            return
        }
        (self as MixinMinecraft).`aether$pickBlockOrEntity`()
    }

    private fun aetherBlockManualAttack(self: Minecraft): Boolean {
        return AetherBootstrapHooks.isFreecamEnabled() && !self.options.keyAttack.isDown()
    }
}
