package dev.aether.util

import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

class ProgrammaticAttackTracker private constructor() {
    companion object {
        @Volatile
        private var attackHeld = false

        @JvmStatic
        fun setHeld(mapping: KeyMapping?, held: Boolean) {
            attackHeld = held && mapping != null
        }

        @JvmStatic
        fun isHeld(): Boolean = attackHeld

        @JvmStatic
        fun isHeld(mapping: KeyMapping?): Boolean = attackHeld && mapping != null

        @JvmStatic
        fun shouldTreatMouseAsGrabbed(client: Minecraft?): Boolean =
            isHeld(if (client == null || client.options == null) null else client.options.keyAttack) &&
                client != null && client.options != null && client.gui.screen() == null
    }
}
