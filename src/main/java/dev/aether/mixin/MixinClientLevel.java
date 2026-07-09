package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class MixinClientLevel {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void onSendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        AetherBootstrapHooks.onBlockChanged(Minecraft.getInstance(), pos, oldState, newState);
    }
}

