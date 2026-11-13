package dev.aether.mixin

import dev.aether.util.AetherFallbackPackLoader
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.repository.RepositorySource
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyArg
import java.util.Arrays

@Mixin(Minecraft::class)
abstract class MixinMinecraftFallbackPack {
    @ModifyArg(
        method = ["<init>"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"
        )],
        index = 0
    )
    private fun `aether$addFallbackPackSource`(repositorySources: Array<RepositorySource>): Array<RepositorySource> {
        val sources = Arrays.copyOf(repositorySources, repositorySources.size + 1)
        sources[repositorySources.size] = AetherFallbackPackLoader.FallbackPackRepositorySource()
        return sources
    }
}
