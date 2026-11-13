package dev.aether.macro.farming

import dev.aether.config.AetherConfig

// repeating a -> s -> s+d rows, or d -> s -> s+a when flipped
class SDSMushroomMacro : AbstractFarmingMacro() {
    private val reverseLane = AetherConfig.MACRO_SDS_MUSHROOM_REVERSE_LANE.get()
    private val rows = if (reverseLane) {
        stateCycle(0.005, 2, State.RIGHT, State.BACKWARD, State.LEFT)
    } else {
        stateCycle(0.005, 2, State.LEFT, State.BACKWARD, State.RIGHT)
    }

    override fun defaultAngle(): DefaultAngle {
        return DefaultAngle(5.5f, if (reverseLane) CARDINAL_OFFSET_DEGREES else -CARDINAL_OFFSET_DEGREES)
    }

    override fun stateKeys(): Map<State, StateKeys> {
        return mapOf(State.LEFT to StateKeys(true, false, false, reverseLane, true, false, false),
                State.RIGHT to StateKeys(false, true, false, !reverseLane, true, false, false))
    }

    companion object {
        private const val CARDINAL_OFFSET_DEGREES = 16f
    }
}
