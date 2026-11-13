package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Team
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(PlayerTeam::class)
class MixinPlayerTeam {

    private companion object {
        @JvmStatic
        @Inject(method = ["formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;"], at = [At("RETURN")], cancellable = true)
        private fun `aether$onFormatNameForTeam`(team: Team, name: Component, cir: CallbackInfoReturnable<MutableComponent>) {
            val original: MutableComponent? = cir.returnValue
            if (original != null) {
                val transformed = AetherBootstrapHooks.transformDisplayComponent(original)
                if (transformed is MutableComponent && transformed !== original) {
                    cir.returnValue = transformed
                }
            }
        }
    }
}
