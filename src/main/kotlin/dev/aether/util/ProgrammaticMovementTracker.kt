package dev.aether.util

import net.minecraft.client.KeyMapping
import java.util.Collections
import java.util.IdentityHashMap

class ProgrammaticMovementTracker private constructor() {
    companion object {
        private val KEY_STATES: MutableMap<KeyMapping, Boolean> =
            Collections.synchronizedMap(IdentityHashMap())

        @JvmStatic
        fun set(mapping: KeyMapping?, down: Boolean) {
            if (mapping == null) return
            if (down) {
                KEY_STATES[mapping] = true
                return
            }
            KEY_STATES.remove(mapping)
        }

        @JvmStatic
        fun isDown(mapping: KeyMapping?): Boolean {
            if (mapping == null) return false
            return java.lang.Boolean.TRUE == KEY_STATES[mapping]
        }

        @JvmStatic
        fun clear(mapping: KeyMapping?) {
            if (mapping == null) return
            KEY_STATES.remove(mapping)
        }
    }
}
