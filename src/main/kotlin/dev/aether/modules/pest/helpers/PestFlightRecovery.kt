package dev.aether.modules.pest.helpers

import net.minecraft.world.phys.Vec3

class PestFlightRecovery {
    enum class Action { CONTINUE, WAIT, RETRY, GIVE_UP }

    private var progressPosition: Vec3? = null
    private var retries = 0
    private var retryAt = 0L

    fun reset() {
        progressPosition = null
        retries = 0
        retryAt = 0L
    }

    fun update(navigating: Boolean, position: Vec3, now: Long, enteredAt: Long, timeoutMs: Long): Action {
        if (now - enteredAt > timeoutMs) return Action.GIVE_UP
        val progress = progressPosition
        if (progress == null || position.distanceToSqr(progress) >= PROGRESS_DISTANCE_SQ) {
            progressPosition = position
            retries = 0
            retryAt = 0L
        }
        if (navigating) return Action.CONTINUE
        if (now < retryAt) return Action.WAIT
        if (retries >= MAX_RETRIES) return Action.GIVE_UP
        retryAt = now + (RETRY_DELAY_MS shl retries)
        retries++
        return Action.RETRY
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 300L
        private const val PROGRESS_DISTANCE_SQ = 4.0
    }
}
