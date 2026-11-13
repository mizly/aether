package dev.aether.mixin

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(AbstractContainerScreen::class)
interface AccessorAbstractContainerScreen {
    @Accessor("leftPos")
    fun getLeftPos(): Int

    @Accessor("topPos")
    fun getTopPos(): Int

    @Invoker("slotClicked")
    fun invokeSlotClicked(slot: Slot, slotId: Int, mouseButton: Int, type: ContainerInput)
}
