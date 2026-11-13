package dev.aether.modules.pest.helpers

internal class SprayonatorSwapGuard {
    private var releasedAtTick = -1

    fun readyToSwap(tick: Int, attacking: Boolean): Boolean {
        if (releasedAtTick < 0 || tick < releasedAtTick || attacking) {
            releasedAtTick = tick
            return false
        }
        return tick - releasedAtTick >= RELEASE_TICKS
    }

    companion object {
        private const val RELEASE_TICKS = 3
    }
}
