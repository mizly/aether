package dev.aether.modules.session;

import dev.aether.macro.MacroState;

final class RecoverySequence {
    enum Action {
        WAIT(null),
        LOBBY("/lobby"),
        SKYBLOCK("/skyblock"),
        GARDEN("/warp garden"),
        RESUME(null);

        final String command;

        Action(String command) {
            this.command = command;
        }
    }

    private static final long COMMAND_DELAY_MS = 3_000L;
    private static final long RETRY_DELAY_MS = 10_000L;
    private static final long GARDEN_STABLE_MS = 1_000L;

    private Object observedWorld;
    private long worldReadyAt;
    private long lastCommandAt;
    private Action lastCommand;
    private long gardenSince = -1L;
    private long limboSince = -1L;

    void reset(long now) {
        observedWorld = null;
        worldReadyAt = now + COMMAND_DELAY_MS;
        lastCommandAt = now;
        lastCommand = null;
        gardenSince = -1L;
        limboSince = -1L;
    }

    Action update(long now, Object world, MacroState.Location location, boolean ready) {
        if (world != observedWorld) {
            observedWorld = world;
            worldReadyAt = now + COMMAND_DELAY_MS;
            gardenSince = -1L;
            limboSince = -1L;
        }
        if (world == null || !ready) {
            gardenSince = -1L;
            limboSince = -1L;
            return Action.WAIT;
        }

        if (location == MacroState.Location.LIMBO) {
            if (limboSince < 0L) {
                limboSince = now;
            }
        } else {
            limboSince = -1L;
        }
        if (location == MacroState.Location.GARDEN) {
            if (gardenSince < 0L) {
                gardenSince = now;
            }
            return now >= worldReadyAt && now - lastCommandAt >= COMMAND_DELAY_MS
                    && now - gardenSince >= GARDEN_STABLE_MS ? Action.RESUME : Action.WAIT;
        }
        gardenSince = -1L;

        // A missing scoreboard during a transfer is not yet proof that we landed in Limbo.
        if (location == MacroState.Location.LIMBO && now - limboSince < COMMAND_DELAY_MS) {
            return Action.WAIT;
        }

        Action action = switch (location) {
            case LIMBO -> Action.LOBBY;
            case LOBBY -> Action.SKYBLOCK;
            case HUB, CRYSTAL_HOLLOWS -> Action.GARDEN;
            default -> Action.WAIT;
        };
        long delay = action == lastCommand ? RETRY_DELAY_MS : COMMAND_DELAY_MS;
        if (action == Action.WAIT || now < worldReadyAt || now - lastCommandAt < delay) {
            return Action.WAIT;
        }
        lastCommand = action;
        lastCommandAt = now;
        return action;
    }
}
