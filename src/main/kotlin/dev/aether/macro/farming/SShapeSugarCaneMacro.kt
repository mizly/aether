package dev.aether.macro.farming

// farms alternating a/s rows
class SShapeSugarCaneMacro : AbstractFarmingMacro() {
    private val rows = stateCycle(0.005, 2, State.LEFT, State.BACKWARD)

    override fun defaultAngle(): DefaultAngle {
        return DefaultAngle(0f, 45f)
    }
}
