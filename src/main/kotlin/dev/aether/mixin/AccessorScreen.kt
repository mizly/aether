package dev.aether.mixin

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.ClickEvent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

@Mixin(Screen::class)
interface AccessorScreen {
    @Invoker("defaultHandleGameClickEvent")
    fun `aether$defaultHandleGameClickEvent`(clickEvent: ClickEvent, minecraft: Minecraft, screen: Screen)
}
