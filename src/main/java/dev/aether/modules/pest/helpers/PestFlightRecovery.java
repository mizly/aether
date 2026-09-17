package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;

final class PestFlightRecovery {
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 300L;
    private static final double PROGRESS_DISTANCE_SQ = 4.0;

    enum Action {
        CONTINUE, WAIT, RETRY, GIVE_UP
    }

    private Vec3 progressPosition;
    private int retries;
    private long retryAt;

    void reset() {
        progressPosition = null;
        retries = 0;
        retryAt = 0;
    }

    Action update(boolean navigating, Vec3 position, long now, long enteredAt, long timeoutMs) {
        if (now - enteredAt > timeoutMs) return Action.GIVE_UP;
        if (progressPosition == null || position.distanceToSqr(progressPosition) >= PROGRESS_DISTANCE_SQ) {
            progressPosition = position;
            retries = 0;
            retryAt = 0;
        }
        if (navigating) return Action.CONTINUE;
        if (now < retryAt) return Action.WAIT;
        if (retries >= MAX_RETRIES) return Action.GIVE_UP;
        retryAt = now + (RETRY_DELAY_MS << retries);
        retries++;
        return Action.RETRY;
    }
}
