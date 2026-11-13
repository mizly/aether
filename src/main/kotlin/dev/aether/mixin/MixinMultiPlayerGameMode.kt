package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(MultiPlayerGameMode::class)
class MixinMultiPlayerGameMode {
    @Inject(method = ["destroyBlock"], at = [At("HEAD")])
    private fun onDestroyBlock(pos: BlockPos, cir: CallbackInfoReturnable<Boolean>) {
        AetherBootstrapHooks.onBlockBreak(pos)
    }

    @Inject(method = ["startDestroyBlock"], at = [At("HEAD")])
    private fun onStartDestroyBlock(pos: BlockPos, direction: Direction, cir: CallbackInfoReturnable<Boolean>) {
        AetherBootstrapHooks.onBlockBreakClick(Minecraft.getInstance(), pos)
    }

    @Inject(method = ["continueDestroyBlock"], at = [At("HEAD")])
    private fun onContinueDestroyBlock(pos: BlockPos, direction: Direction, cir: CallbackInfoReturnable<Boolean>) {
        AetherBootstrapHooks.onBlockBreakClick(Minecraft.getInstance(), pos)
    }
}
