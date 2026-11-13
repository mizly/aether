package dev.aether.mixin

import net.minecraft.client.MouseHandler
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(MouseHandler::class)
interface AccessorMouseHandler {
    @Accessor("accumulatedDX")
    fun `aether$setAccumulatedDX`(value: Double)

    @Accessor("accumulatedDY")
    fun `aether$setAccumulatedDY`(value: Double)

    @Accessor("ignoreFirstMove")
    fun `aether$setIgnoreFirstMove`(value: Boolean)
}
