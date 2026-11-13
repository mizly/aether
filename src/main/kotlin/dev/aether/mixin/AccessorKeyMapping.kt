package dev.aether.mixin

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(KeyMapping::class)
interface AccessorKeyMapping {
    @Accessor("key")
    fun getKey(): InputConstants.Key
}
