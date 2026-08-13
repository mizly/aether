package dev.aether.modules.pest.helpers;

import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

final class PestDestroyerInputController {
    private static final long KILL_USE_RETRY_HOLD_MS = 2_000L;
    private static final long KILL_USE_RETRY_RELEASE_MS = 150L;
    private static final long KILL_USE_RETRY_CLICK_HOLD_MS = 100L;

    private PestDestroyerInputController() {
    }

    static void update(
            Minecraft client,
            PestDestroyerRuntime runtime,
            boolean vacuumTemporarilyReleased
    ) {
        if (!runtime.active || client.player == null) {
            return;
        }
        if (runtime.state == PestDestroyer.State.AOTV_BETWEEN_PESTS
                || runtime.state == PestDestroyer.State.HUNT_PEST) {
            return;
        }
        if (runtime.vacuumSlot < 0) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            return;
        }

        int selected = ((AccessorInventory) client.player.getInventory()).getSelected();
        if (runtime.state == PestDestroyer.State.KILL_PEST) {
            boolean shouldUse = !vacuumTemporarilyReleased;
            if (shouldUse && selected != runtime.vacuumSlot) {
                client.execute(() -> FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot));
            }
            ClientUtils.setKeyMappingState(client.options.keyUse, shouldUse);
            return;
        }

        boolean shouldUse = selected == runtime.vacuumSlot
                && runtime.state != PestDestroyer.State.GET_LOCATION
                && !vacuumTemporarilyReleased;
        ClientUtils.setKeyMappingState(client.options.keyUse, shouldUse);
    }

    static boolean shouldTemporarilyReleaseKillVacuum(
            PestDestroyerRuntime runtime,
            boolean vacuumReady,
            boolean targetInRange) {
        long now = System.currentTimeMillis();
        if (runtime.state != PestDestroyer.State.KILL_PEST
                || !vacuumReady
                || !targetInRange) {
            runtime.resetKillVacuumRetry();
            return false;
        }
        if (runtime.killVacuumRetryPressAt != 0L) {
            return true;
        }
        if (runtime.killVacuumHoldStartedAt == 0L) {
            runtime.killVacuumHoldStartedAt = now;
            return false;
        }
        if (now - runtime.killVacuumHoldStartedAt < KILL_USE_RETRY_HOLD_MS) {
            return false;
        }

        runtime.killVacuumHoldStartedAt = 0L;
        runtime.killVacuumRetryPressAt = now + KILL_USE_RETRY_RELEASE_MS;
        runtime.killVacuumReleaseUntil =
                runtime.killVacuumRetryPressAt + KILL_USE_RETRY_CLICK_HOLD_MS;
        ClientUtils.sendDebugMessage("PestDestroyer: retrying vacuum use");
        return true;
    }

    static boolean isVacuumTemporarilyReleased(PestDestroyerRuntime runtime) {
        return runtime.killVacuumRetryPressAt != 0L;
    }

    static void updateVacuumRetryPulse(
            Minecraft client,
            PestDestroyerRuntime runtime) {
        if (runtime.killVacuumRetryPressAt == 0L || client.options == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < runtime.killVacuumRetryPressAt) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            return;
        }
        if (now < runtime.killVacuumReleaseUntil) {
            ClientUtils.setKeyMappingState(client.options.keyUse, true);
            return;
        }

        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        runtime.killVacuumRetryPressAt = 0L;
        runtime.killVacuumReleaseUntil = 0L;
    }
}
