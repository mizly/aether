package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Discoless entry: fires a single etherwarp from wherever the plot TP left the
 * player, with no rotation, then hands off to the hold state.
 * <p>
 * The warp is always fired, but a raycast taken just before the click records the
 * expected landing block. Comparing the post-click position against that prediction
 * separates a real etherwarp from a dropped-sneak Instant Transmission, which would
 * otherwise read as a successful move.
 */
final class PestEtherwarpEntryManager {

    static final int MAX_ATTEMPTS = 2;

    private static final long POST_CLICK_GRACE_MS = 250L;
    private static final double LANDING_TOLERANCE = 1.5;
    private static final long PLOT_TP_COOLDOWN_GUARD_MS = 1200L;
    private static final long ENTRY_TIMEOUT_MS = 8000L;

    private PestEtherwarpEntryManager() {
    }

    static void begin(PestDestroyerRuntime runtime) {
        runtime.resetEtherwarpEntry();
        PestAotvManager.isSneakingForAotv = true;
    }

    static void update(Minecraft client, PestDestroyerRuntime runtime) {
        if (client.player == null || client.level == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (runtime.etherwarpEntryRetryAt != 0L) {
            if (now < runtime.etherwarpEntryRetryAt) {
                return;
            }
            beginPlotTpRetry(runtime);
            return;
        }

        if (now - runtime.stateEnteredAt > ENTRY_TIMEOUT_MS) {
            ClientUtils.sendDebugMessage("[Discoless] Etherwarp entry timed out. Falling back to standard destroyer.");
            abandon(client, runtime);
            return;
        }

        PestAotvManager.isSneakingForAotv = true;

        int aotvSlot = GearManager.findAspectOfTheVoidSlot(client);
        if (aotvSlot < 0 || aotvSlot >= 9) {
            ClientUtils.sendDebugMessage("[Discoless] No AOTV in hotbar. Falling back to standard destroyer.");
            abandon(client, runtime);
            return;
        }
        if (((AccessorInventory) client.player.getInventory()).getSelected() != aotvSlot) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, aotvSlot));
            return;
        }

        if (!runtime.etherwarpEntryClicked) {
            fireWhenDue(client, runtime, now);
            return;
        }

        if (now < runtime.etherwarpEntryGraceUntil) {
            return;
        }

        if (landedAtPrediction(client, runtime)) {
            ClientUtils.sendDebugMessage("[Discoless] Etherwarp landed at prediction. Holding position.");
            succeed(client, runtime);
            return;
        }

        runtime.etherwarpEntryAttempts++;
        String movedInfo = String.format("%.2f", movedDistance(client, runtime));
        if (runtime.etherwarpEntryAttempts >= MAX_ATTEMPTS) {
            ClientUtils.sendDebugMessage("[Discoless] Etherwarp failed after " + MAX_ATTEMPTS
                    + " attempts (moved " + movedInfo + "b, off prediction). Falling back to standard destroyer.");
            abandon(client, runtime);
            return;
        }

        ClientUtils.sendDebugMessage("[Discoless] Etherwarp did not land at prediction (moved " + movedInfo
                + "b). Re-teleporting to retry.");
        releaseSneak(client);
        runtime.resetEtherwarpEntry();
        runtime.etherwarpEntryRetryAt = now + PLOT_TP_COOLDOWN_GUARD_MS;
    }

    private static void fireWhenDue(Minecraft client, PestDestroyerRuntime runtime, long now) {
        if (runtime.etherwarpEntryClickAt == 0L) {
            runtime.etherwarpEntryClickAt = now + ConfigHelpers.getRandomizedDelay(
                    AetherConfig.PEST_AOTV_DELAY_MIN.get(),
                    AetherConfig.PEST_AOTV_DELAY_MAX.get());
            return;
        }
        if (now < runtime.etherwarpEntryClickAt) {
            return;
        }

        runtime.etherwarpEntryPredicted = predictLanding(client);
        runtime.etherwarpEntryPreX = client.player.getX();
        runtime.etherwarpEntryPreY = client.player.getY();
        runtime.etherwarpEntryPreZ = client.player.getZ();
        runtime.etherwarpEntryClicked = true;
        runtime.etherwarpEntryGraceUntil = now + POST_CLICK_GRACE_MS;

        ClientUtils.performUseClick();
        ClientUtils.sendDebugMessage("[Discoless] Etherwarp fired (attempt "
                + (runtime.etherwarpEntryAttempts + 1) + "/" + MAX_ATTEMPTS + "). Predicted landing: "
                + describe(runtime.etherwarpEntryPredicted));
    }

    /**
     * Raycast along the current look vector. No rotation is applied, so this is
     * simply whatever the plot TP left us pointing at.
     */
    private static Vec3 predictLanding(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(client.player.getLookAngle().scale(EtherwarpHelper.MAX_ETHERWARP_DISTANCE));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return Vec3.atBottomCenterOf(hit.getBlockPos().above());
    }

    private static boolean landedAtPrediction(Minecraft client, PestDestroyerRuntime runtime) {
        Vec3 predicted = runtime.etherwarpEntryPredicted;
        return predicted != null
                && client.player.position().distanceTo(predicted) <= LANDING_TOLERANCE;
    }

    private static double movedDistance(Minecraft client, PestDestroyerRuntime runtime) {
        if (Double.isNaN(runtime.etherwarpEntryPreX)) {
            return 0.0;
        }
        double dx = client.player.getX() - runtime.etherwarpEntryPreX;
        double dy = client.player.getY() - runtime.etherwarpEntryPreY;
        double dz = client.player.getZ() - runtime.etherwarpEntryPreZ;
        return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
    }

    private static void beginPlotTpRetry(PestDestroyerRuntime runtime) {
        runtime.resetEtherwarpEntry();
        runtime.navigation.plotTpSent = false;
        runtime.navigation.plotTpWindow = null;
        PestDestroyer.setState(PestDestroyer.State.TELEPORT_TO_PLOT);
    }

    private static void succeed(Minecraft client, PestDestroyerRuntime runtime) {
        releaseSneak(client);
        runtime.resetEtherwarpEntry();
        runtime.etherwarpEntryAttempts = 0;
        runtime.navigation.discoTargetReached = true;
        runtime.navigation.trustedPlot = PestDiscoDestinationManager.getConfiguredPlot();
        runtime.navigation.trustedPlotExpiresAt = System.currentTimeMillis() + 120_000;
        PestDestroyer.setState(PestDestroyer.State.DISCO_SPIN);
    }

    private static void abandon(Minecraft client, PestDestroyerRuntime runtime) {
        releaseSneak(client);
        runtime.resetEtherwarpEntry();
        runtime.etherwarpEntryAttempts = 0;
        runtime.holdDestinationAbandoned = true;
        runtime.navigation.discoTargetReached = false;
        PestDestroyer.setState(PestDestroyer.State.EQUIP_VACUUM);
    }

    private static void releaseSneak(Minecraft client) {
        PestAotvManager.isSneakingForAotv = false;
        if (client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
        }
    }

    private static String describe(Vec3 predicted) {
        return predicted == null
                ? "none (no block in view)"
                : String.format("%.1f, %.1f, %.1f", predicted.x, predicted.y, predicted.z);
    }
}
