package dev.aether.mixin

import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(Entity::class)
interface AccessorEntity {
    @Accessor("xRotO") fun setXRotO(xRotO: Float)
}
