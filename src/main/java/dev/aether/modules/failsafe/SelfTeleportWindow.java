package dev.aether.modules.failsafe;

final class SelfTeleportWindow {
    private static final long RESPONSE_TIMEOUT_MS = 10_000L;
    private long requestedAt = Long.MIN_VALUE;
    private long receivedAt = Long.MIN_VALUE;

    synchronized void reset() {
        requestedAt = Long.MIN_VALUE;
        receivedAt = Long.MIN_VALUE;
    }

    synchronized void request(long now) {
        requestedAt = now;
        receivedAt = Long.MIN_VALUE;
    }

    synchronized boolean onPositionPacket(long now, long settleMs) {
        if (TeleportDetection.isSettling(now, requestedAt, RESPONSE_TIMEOUT_MS)) {
            requestedAt = Long.MIN_VALUE;
            receivedAt = now;
            return true;
        }
        return TeleportDetection.isSettling(now, receivedAt, settleMs);
    }
}
