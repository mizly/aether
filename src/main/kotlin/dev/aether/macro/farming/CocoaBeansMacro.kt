package dev.aether.macro.farming

// forward row, right lane, backward row, right lane
class CocoaBeansMacro : AbstractFarmingMacro() {
    private val states = stateCycle(0.02, 2, State.FORWARD, State.SWITCHING_LANE, State.BACKWARD, State.SWITCHING_LANE)

    override fun defaultAngle(): DefaultAngle {
        return DefaultAngle(-75f, 0f)
    }

    override fun stateKeys(): Map<State, StateKeys> {
        return mapOf(State.SWITCHING_LANE to StateKeys(false, true, false, false, true, false, false))
    }
}
