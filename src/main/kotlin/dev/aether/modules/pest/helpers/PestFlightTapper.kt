package dev.aether.modules.pest.helpers

import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

class PestFlightTapper private constructor() {
    companion object {
        private const val CYCLE_TICKS = 8
        private const val FIRST_PRESS_PHASE = 1
        private const val SECOND_PRESS_PHASE = 3
        const val TIMEOUT_TICKS = 60

        @JvmStatic
        fun tick(client: Minecraft, tickCount: Int) {
            val options = client.options ?: return
            val phase = Math.floorMod(tickCount, CYCLE_TICKS)
            val down = phase == FIRST_PRESS_PHASE || phase == SECOND_PRESS_PHASE
            ClientUtils.setKeyMappingState(options.keyJump, down)
        }

        @JvmStatic
        fun release(client: Minecraft) {
            val options = client.options ?: return
            ClientUtils.setKeyMappingState(options.keyJump, false)
        }
    }
}
