package dev.aether.mixin

import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(Minecraft::class)
interface MixinMinecraft {
    @Invoker("startAttack")
    fun `aether$startAttack`(): Boolean

    @Invoker("continueAttack")
    fun `aether$continueAttack`(attacking: Boolean)

    @Invoker("startUseItem")
    fun `aether$startUseItem`()

    @Invoker("pickBlockOrEntity")
    fun `aether$pickBlockOrEntity`()

    // vanilla grabMouse() slams missTime to 10000 and it only decrements 1/tick, so freelook has to clear it after grabbing or the macro's continuous attack never resets it and breaking stays suppressed for ~500s
    @Accessor("missTime")
    fun `aether$setMissTime`(missTime: Int)
}
