package dev.aether.modules.session

import dev.aether.macro.MacroState

internal class RecoverySequence {
    enum class Action(val command: String?) {
        WAIT(null),
        LOBBY("/lobby"),
        SKYBLOCK("/skyblock"),
        GARDEN("/warp garden"),
        RESUME(null)
    }

    companion object {
        private const val COMMAND_DELAY_MS = 3_000L
        private const val RETRY_DELAY_MS = 10_000L
        private const val GARDEN_STABLE_MS = 1_000L
    }

    private var observedWorld: Any? = null
    private var worldReadyAt: Long = 0L
    private var lastCommandAt: Long = 0L
    private var lastCommand: Action? = null
    private var gardenSince: Long = -1L
    private var limboSince: Long = -1L

    fun reset(now: Long) {
        observedWorld = null
        worldReadyAt = now + COMMAND_DELAY_MS
        lastCommandAt = now
        lastCommand = null
        gardenSince = -1L
        limboSince = -1L
    }

    fun update(now: Long, world: Any?, location: MacroState.Location, ready: Boolean): Action {
        if (world !== observedWorld) {
            observedWorld = world
            worldReadyAt = now + COMMAND_DELAY_MS
            gardenSince = -1L
            limboSince = -1L
        }
        if (world == null || !ready) {
            gardenSince = -1L
            limboSince = -1L
            return Action.WAIT
        }

        if (location == MacroState.Location.LIMBO) {
            if (limboSince < 0L) {
                limboSince = now
            }
        } else {
            limboSince = -1L
        }
        if (location == MacroState.Location.GARDEN) {
            if (gardenSince < 0L) {
                gardenSince = now
            }
            return if (now >= worldReadyAt && now - lastCommandAt >= COMMAND_DELAY_MS
                && now - gardenSince >= GARDEN_STABLE_MS) Action.RESUME else Action.WAIT
        }
        gardenSince = -1L

        // A missing scoreboard during a transfer is not yet proof that we landed in Limbo.
        if (location == MacroState.Location.LIMBO && now - limboSince < COMMAND_DELAY_MS) {
            return Action.WAIT
        }

        val action = when (location) {
            MacroState.Location.LIMBO -> Action.LOBBY
            MacroState.Location.LOBBY -> Action.SKYBLOCK
            MacroState.Location.HUB, MacroState.Location.CRYSTAL_HOLLOWS -> Action.GARDEN
            else -> Action.WAIT
        }
        val delay = if (action == lastCommand) RETRY_DELAY_MS else COMMAND_DELAY_MS
        if (action == Action.WAIT || now < worldReadyAt || now - lastCommandAt < delay) {
            return Action.WAIT
        }
        lastCommand = action
        lastCommandAt = now
        return action
    }
}
