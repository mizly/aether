package dev.aether.macro.farming

// farms alternating a/d rows
class ADFarmMacro : AbstractFarmingMacro() {
    private val rows = stateCycle(0.005, 2, State.LEFT, State.RIGHT)

    override fun defaultAngle(): DefaultAngle {
        return DefaultAngle(-3f, 0f)
    }
}
