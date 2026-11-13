package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientLevel::class)
class MixinClientLevel {
    @Inject(method = ["sendBlockUpdated"], at = [At("HEAD")])
    private fun onSendBlockUpdated(pos: BlockPos, oldState: BlockState, newState: BlockState, flags: Int, ci: CallbackInfo) {
        AetherBootstrapHooks.onBlockChanged(Minecraft.getInstance(), pos, oldState, newState)
    }
}
