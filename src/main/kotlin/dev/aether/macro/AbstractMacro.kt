package dev.aether.macro

import net.minecraft.client.Minecraft

abstract class AbstractMacro {
    abstract fun onEnable(mc: Minecraft)
    abstract fun onDisable(mc: Minecraft)
    abstract fun onTick(mc: Minecraft)
}
