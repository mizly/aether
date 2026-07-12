package dev.aether.config;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.util.ClientUtils;
import java.util.concurrent.ThreadLocalRandom;

public final class ConfigHelpers {
    private ConfigHelpers() {}

    public static int getRandomizedDelay(int baseDelay) {
        int jitter = Math.round(Math.abs(baseDelay) * 0.15f);
        return getRandomizedDelay(baseDelay - jitter, baseDelay + jitter);
    }

    public static int getRandomizedDelay(int minDelay, int maxDelay) {
        int lower = Math.max(0, Math.min(minDelay, maxDelay));
        int upper = Math.max(0, Math.max(minDelay, maxDelay));
        if (lower == upper) {
            return lower;
        }
        return ThreadLocalRandom.current().nextInt(lower, upper + 1);
    }

    public static void executePlotTpRewarp() {
        if (AetherConfig.ENABLE_PLOT_TP_REWARP.get()) {
            executePlotTpRewarp(AetherConfig.PLOT_TP_NUMBER.get());
        }
    }

    public static void executePlotTpRewarp(String plotNumber) {
        executeRewarpCommand(RewarpMode.PLOT_TP, plotNumber);
    }

    public static void executeRewarpCommand(RewarpMode mode, String plotNumber) {
        RewarpMode resolvedMode = mode == null ? RewarpMode.FLY : mode;
        if (!resolvedMode.usesCommand()) {
            return;
        }

        String sanitizedPlotNumber = plotNumber == null || plotNumber.isBlank() ? "0" : plotNumber.trim();
        AetherBootstrapHooks.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        if (resolvedMode == RewarpMode.WARP_GARDEN) {
            ClientUtils.sendCommand("/warp garden");
        } else {
            ClientUtils.sendCommand("/plottp " + sanitizedPlotNumber);
        }
    }

    public static UnflyMode getUnflyMode() {
        try { return UnflyMode.valueOf(AetherConfig.UNFLY_MODE.get()); }
        catch (IllegalArgumentException e) { return UnflyMode.DOUBLE_TAP_SPACE; }
    }

    public static FreelookMode getFreelookMode() {
        try { return FreelookMode.valueOf(AetherConfig.FREELOOK_MODE.get()); }
        catch (IllegalArgumentException e) { return FreelookMode.HOLD; }
    }
}

