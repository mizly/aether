package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTeam.class)
public class MixinPlayerTeam {

    @Inject(method = "formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;", at = @At("RETURN"), cancellable = true)
    private static void aether$onFormatNameForTeam(Team team, Component name, CallbackInfoReturnable<MutableComponent> cir) {
        MutableComponent original = cir.getReturnValue();
        if (original != null) {
            Component transformed = AetherBootstrapHooks.transformDisplayComponent(original);
            if (transformed instanceof MutableComponent mutable && transformed != original) {
                cir.setReturnValue(mutable);
            }
        }
    }
}

