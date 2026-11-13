package dev.aether.util

import dev.aether.macro.MacroStateManager
import dev.aether.mixin.AccessorMouseHandler
import net.minecraft.client.Minecraft

// drops the mouse delta that piles up while the window is unfocused
class RefocusInputGuard private constructor() {
    companion object {
        private var wasFocused = true

        @JvmStatic
        fun reset() {
            wasFocused = true
        }

        @JvmStatic
        fun tick(client: Minecraft?) {
            if (client == null || client.getWindow() == null) return
            val focused = client.getWindow().isFocused()
            val regained = focused && !wasFocused
            wasFocused = focused
            if (!regained || !MacroStateManager.isMacroRunning()) return
            // Vanilla only discards the stale delta inside grabMouse, which the macro
            // suppresses while the cursor is ungrabbed - so it lands as a view snap.
            val handler = client.mouseHandler as AccessorMouseHandler
            handler.`aether$setAccumulatedDX`(0.0)
            handler.`aether$setAccumulatedDY`(0.0)
            handler.`aether$setIgnoreFirstMove`(true)
        }
    }
}
