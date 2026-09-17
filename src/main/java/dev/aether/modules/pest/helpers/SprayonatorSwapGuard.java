package dev.aether.modules.pest.helpers;

final class SprayonatorSwapGuard {
    private static final int RELEASE_TICKS = 3;
    private int releasedAtTick = -1;

    boolean readyToSwap(int tick, boolean attacking) {
        if (releasedAtTick < 0 || tick < releasedAtTick || attacking) {
            releasedAtTick = tick;
            return false;
        }
        return tick - releasedAtTick >= RELEASE_TICKS;
    }
}
