package dev.aether.macro.farming

import dev.aether.config.AetherConfig

// standard s-shape, optionally holding forward through each row
class SShapeCropMacro : AbstractFarmingMacro() {
    private val states = if (AetherConfig.MACRO_HOLD_W_WHILE_FARMING.get()) {
        stateCycle(0.02, 2, State.LEFT, State.RIGHT)
    } else {
        stateCycle(0.02, 2, State.LEFT, State.SWITCHING_LANE, State.RIGHT, State.SWITCHING_LANE)
    }

    override fun defaultAngle(): DefaultAngle {
        return DefaultAngle(5f, 0f)
    }

    override fun stateKeys(): Map<State, StateKeys> {
        val holdForward = AetherConfig.MACRO_HOLD_W_WHILE_FARMING.get()
        return mapOf(State.LEFT to StateKeys(true, false, holdForward, false, true, false, false),
                State.RIGHT to StateKeys(false, true, holdForward, false, true, false, false))
    }
}
