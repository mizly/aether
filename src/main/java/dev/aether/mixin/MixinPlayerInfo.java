package dev.aether.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
public abstract class MixinPlayerInfo {

    @Shadow public abstract GameProfile getProfile();

    @WrapMethod(method = "getSkin")
    private PlayerSkin aether$getSkin(Operation<PlayerSkin> original) {
        if (AetherBootstrapHooks.shouldHidePlayerSkin(getProfile())) {
            if (getProfile().name().equals(Minecraft.getInstance().getUser().getName())) {
                return DefaultPlayerSkin.get(getProfile());
            }
        }
        return original.call();
    }

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void aether$onGetTabListDisplayName(CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        if (original != null) {
            Component transformed = AetherBootstrapHooks.transformDisplayComponent(original);
            if (transformed != null && transformed != original) {
                cir.setReturnValue(transformed);
            }
        }
    }
}

