package dev.aether.mixin

import com.mojang.blaze3d.platform.Window
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(Window::class)
interface AccessorWindow {
    @Accessor("handle")
    fun getHandle(): Long
}
