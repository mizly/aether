package dev.aether.mixin

import net.minecraft.world.entity.player.Inventory
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(Inventory::class)
interface AccessorInventory {
    @Accessor("selected")
    fun setSelected(selected: Int)

    @Accessor("selected")
    fun getSelected(): Int
}
