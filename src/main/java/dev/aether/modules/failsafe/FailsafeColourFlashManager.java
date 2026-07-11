package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.renderer.FailsafeColourFlashRenderer;

public final class FailsafeColourFlashManager {
    private static volatile long triggeredAtNanos = Long.MIN_VALUE;
    private static volatile boolean active;

    private FailsafeColourFlashManager() {
    }

    public static void trigger() {
        if (AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get()) {
            triggeredAtNanos = System.nanoTime();
            active = true;
        }
    }

    public static void dismiss() {
        active = false;
    }

    public static void render() {
        if (!AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get()) {
            active = false;
            return;
        }

        if (!active) {
            return;
        }

        long elapsedNanos = Math.max(0L, System.nanoTime() - triggeredAtNanos);
        long swapDelayNanos = getSwapDelayNanos();
        boolean useSecondColour = (elapsedNanos / swapDelayNanos & 1L) != 0L;
        int colour = useSecondColour
                ? AetherConfig.FAILSAFE_COLOUR_FLASH_SECOND.get()
                : AetherConfig.FAILSAFE_COLOUR_FLASH_FIRST.get();
        FailsafeColourFlashRenderer.render(colour, AetherConfig.FAILSAFE_COLOUR_FLASH_OPACITY.get());
    }

    private static long getSwapDelayNanos() {
        float delaySeconds = AetherConfig.FAILSAFE_COLOUR_FLASH_SWAP_DELAY_SECONDS.get();
        return Math.max(1L, Math.round(delaySeconds * 1_000_000_000.0));
    }
}
