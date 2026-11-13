package dev.aether.mixin

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import net.minecraft.client.multiplayer.ClientSuggestionProvider
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.concurrent.CompletableFuture

@Mixin(ClientSuggestionProvider::class)
class MixinClientSuggestionProvider {
    @Inject(method = ["customSuggestion"], at = [At("HEAD")], cancellable = true)
    private fun `aether$blockCommandSuggestionRequest`(context: CommandContext<*>, cir: CallbackInfoReturnable<CompletableFuture<Suggestions>>) {
        cir.returnValue = Suggestions.empty()
    }
}
