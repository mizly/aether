package dev.aether.macro.farming

// farms alternating w/s rows
class WSFarmMacro : AbstractFarmingMacro() {
    private val rows = stateCycle(0.005, 2, State.FORWARD, State.BACKWARD)

    override fun defaultAngle(): DefaultAngle {
        return DefaultAngle(-3f, -16.83f)
    }
}
