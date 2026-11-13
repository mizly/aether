package dev.aether.mixin

import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(PlayerTabOverlay::class)
interface PlayerTabOverlayAccessor {
    @Accessor("header")
    fun getHeader(): Component
}
