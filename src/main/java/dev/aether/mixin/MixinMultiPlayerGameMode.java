package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        AetherBootstrapHooks.onBlockBreak(pos);
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void onStartDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        AetherBootstrapHooks.onBlockBreakClick(Minecraft.getInstance(), pos);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"))
    private void onContinueDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        AetherBootstrapHooks.onBlockBreakClick(Minecraft.getInstance(), pos);
    }
}

