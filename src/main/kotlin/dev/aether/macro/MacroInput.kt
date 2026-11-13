package dev.aether.macro

import dev.aether.util.ClientUtils
import dev.aether.util.ProgrammaticAttackTracker
import dev.aether.util.ProgrammaticMovementTracker
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

// keeps macro trackers and raw key state in sync
class MacroInput private constructor() {
    companion object {
        @JvmStatic
        fun set(mapping: KeyMapping, held: Boolean) {
            ProgrammaticMovementTracker.set(mapping, held)
            ClientUtils.setKeyMappingState(mapping, held)
        }

        @JvmStatic
        fun setAttack(attackKey: KeyMapping, held: Boolean) {
            ProgrammaticAttackTracker.setHeld(attackKey, held)
            ClientUtils.setKeyMappingState(attackKey, held)
        }

        @JvmStatic
        fun releaseMovement(mc: Minecraft) {
            val opts = mc.options
            ProgrammaticMovementTracker.set(opts.keyLeft, false)
            ProgrammaticMovementTracker.set(opts.keyRight, false)
            ProgrammaticMovementTracker.set(opts.keyUp, false)
            ProgrammaticMovementTracker.set(opts.keyDown, false)
            ProgrammaticMovementTracker.set(opts.keyShift, false)
            ProgrammaticMovementTracker.set(opts.keySprint, false)
            ProgrammaticMovementTracker.set(opts.keyJump, false)
            ClientUtils.forceReleaseMovementKeys()
        }
    }
}
