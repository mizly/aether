package dev.aether.mixin

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Player::class)
class MixinPlayer {

    @Inject(method = ["getDisplayName"], at = [At("RETURN")], cancellable = true)
    private fun onGetDisplayName(cir: CallbackInfoReturnable<Component>) {
        // Handled by MixinStringDecomposer for consistent visual replacement while preserving formatting.
    }
}
