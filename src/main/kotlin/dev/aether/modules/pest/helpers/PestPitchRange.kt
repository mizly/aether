package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig

internal data class PestPitchRange(val minimum: Float, val maximum: Float) {
    fun contains(pitch: Float): Boolean = pitch >= minimum && pitch <= maximum
    fun clamp(pitch: Float): Float = Math.max(minimum, Math.min(maximum, pitch))
    fun bucketFor(seed: Int): Float {
        val width = maximum - minimum
        val bucket = Math.floorMod(seed, width.toInt() + 1)
        return maximum - bucket
    }

    companion object {
        @JvmStatic fun configured(): PestPitchRange {
            val first = AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN.get()
            val second = AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX.get()
            return PestPitchRange(Math.min(first, second), Math.max(first, second))
        }
    }
}
