package dev.aether.mixin;

import dev.aether.util.AetherFallbackPackLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftFallbackPack {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"
            ),
            index = 0
    )
    private RepositorySource[] aether$addFallbackPackSource(RepositorySource[] repositorySources) {
        RepositorySource[] sources = Arrays.copyOf(repositorySources, repositorySources.length + 1);
        sources[repositorySources.length] = new AetherFallbackPackLoader.FallbackPackRepositorySource();
        return sources;
    }
}
