package dev.aether.macro.impl;

import java.util.Optional;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.AbstractMacro;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Farms rows using W and S (forward/backward) pattern with a configurable left yaw offset.
 *
 * <p>This macro alternates ONLY between Moving Forward (W) and Moving Backward (S).
 * It detects "row end" by checking if the player has stopped moving in BOTH the X and Z
 * directions (3D distance check), ensuring it works correctly at its angled orientation.
 */
public class WSFarmMacro extends AbstractMacro {
    private static final float FLOWER_LEFT_OFFSET_DEGREES = 16.83f;

    /** Squared distance threshold for detecting movement stops. */
    private static final double PROGRESS_EPSILON_SQ = 0.005 * 0.005;

    private final float leftOffsetDegrees;
    private final String debugPrefix;
    private int graceTicks = 0;
    private boolean isWaitingAtWall = false;
    /** Number of consecutive ticks with negligible movement before treating as row end. */
    private int rowStallTicks = 0;
    private static final int ROW_STALL_THRESHOLD = 2;
    private double previousX = 0.0;
    private double previousZ = 0.0;
    private int lastDebugTick = Integer.MIN_VALUE;

    public WSFarmMacro() {
        this(FLOWER_LEFT_OFFSET_DEGREES, "[WSFlower]");
    }

    protected WSFarmMacro(float leftOffsetDegrees, String debugPrefix) {
        this.leftOffsetDegrees = leftOffsetDegrees;
        this.debugPrefix = debugPrefix;
    }

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);
        
        // Initial state setup
        if (mc.player != null) {
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            layerY = mc.player.blockPosition().getY();
        }
        
        // Give the player time to start moving before checking for stalls
        graceTicks = 20;

        // Restore the last known direction (Forward or Backward)
        AbstractMacro.State cached = FarmingMacroManager.getCachedDirection();
        if (cached == State.FORWARD || cached == State.BACKWARD) {
            previousState = cached;
        }

        // Set default orientation
        if (!isPitchSet()) {
            setPitchDefault();
        }
        if (!isYawSet() && mc.player != null) {
            yaw = Optional.of(snapYaw(mc.player.getYRot()));
        }

        // Trigger starting rotation
        if (mc.player != null && (yaw.isPresent() || pitch.isPresent())) {
            float targetYaw   = yaw.orElseGet(mc.player::getYRot);
            float targetPitch = pitch.orElseGet(mc.player::getXRot);
            RotationManager.rotateToYawPitch(mc, targetYaw, targetPitch,
                    AetherConfig.ROTATION_TIME.get());
            rotated = true;
        }
    }

    @Override
    public void updateState(Minecraft mc) {
        if (mc.player == null) return;

        // Fallback to initial state
        if (currentState == State.NONE) {
            if (previousState == State.FORWARD || previousState == State.BACKWARD) {
                changeState(previousState);
            } else {
                changeState(State.FORWARD);
            }
            FarmingMacroManager.saveDirection(currentState);
            graceTicks = 10; // Hardcoded startup grace
        }

        // Handle dropping to lower layers
        if (currentState == State.DROPPING) {
            if (mc.player.onGround()) {
                double droppedY = Math.abs(layerY - mc.player.blockPosition().getY());
                rotateAfterDropIfConfigured(droppedY);
                layerY = mc.player.blockPosition().getY();
                changeState(State.NONE);
            }
            return;
        }

        // Fall detection
        if (!mc.player.onGround() && Math.abs(layerY - mc.player.getY()) > 1.5 && mc.player.getY() < 80) {
            changeState(State.DROPPING);
            return;
        }

        if ((currentState == State.FORWARD || currentState == State.BACKWARD) && shouldSuppressLaneChangeForDirt(mc)) {
            isWaitingAtWall = false;
            rowStallTicks = 0;
            graceTicks = 0;
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            return;
        }

        // Movement stall detection
        if (graceTicks > 0) {
            graceTicks--;
            if (isWaitingAtWall && graceTicks == 0) {
                // Done waiting at the wall. Flip direction and start moving.
                isWaitingAtWall = false;
                State next = (currentState == State.FORWARD) ? State.BACKWARD : State.FORWARD;
                debugEvery(mc, 10, "Wait finished. Flipped " + currentState + " -> " + next);
                changeState(next);
                FarmingMacroManager.saveDirection(next);
                graceTicks = 10; // Hardcoded acceleration grace
            }
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            return;
        }

        // Use total horizontal distance moved to check for row progress
        double dx = mc.player.getX() - previousX;
        double dz = mc.player.getZ() - previousZ;
        double distSq = dx * dx + dz * dz;
        boolean fastLaneSwitch = FastLaneSwitchManager.shouldFastSwitch(mc, currentState);
        
        previousX = mc.player.getX();
        previousZ = mc.player.getZ();

        if (shouldIgnoreMovementStall(mc)) {
            // Suppress stall detection for a short grace when freecam / other
            // systems are in effect.
            graceTicks = Math.max(graceTicks, 2);
            rowStallTicks = 0;
            return;
        }

        // Count consecutive ticks with negligible movement. This prevents
        // transient server jitter / rubberband corrections from masking a
        // true row end by requiring a small run of non-progress ticks.
        if (distSq < PROGRESS_EPSILON_SQ || fastLaneSwitch) {
            rowStallTicks++;
            if (!fastLaneSwitch && rowStallTicks < ROW_STALL_THRESHOLD) {
                // Not enough consecutive stalls yet; continue farming.
                return;
            }
            // Enough consecutive stalls: treat as row end and reset counter.
            rowStallTicks = 0;

            // Player has stopped moving (hit a wall).
            int totalMs = fastLaneSwitch ? 0 : ConfigHelpers.getRandomizedDelay(
                    AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                    AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get());
            int ticks = (totalMs + 25) / 50; // Convert ms to ticks with rounding

            if (ticks > 0) {
                isWaitingAtWall = true;
                graceTicks = ticks;
                debugEvery(mc, 10, String.format("Row end reached. Waiting %d ticks (%dms)...", ticks, totalMs));
            } else {
                // Instant flip if delay is 0
                State next = (currentState == State.FORWARD) ? State.BACKWARD : State.FORWARD;
                debugEvery(mc, 10, "Row end reached. Flipped " + currentState + " -> " + next);
                changeState(next);
                FarmingMacroManager.saveDirection(next);
                graceTicks = fastLaneSwitch ? 0 : 10; // Hardcoded acceleration grace
            }
        } else {
            // Movement observed; reset stall counter.
            rowStallTicks = 0;
        }
    }

    @Override
    public void invokeState(Minecraft mc) {
        if (mc.player == null) return;

        if (isWaitingAtWall) {
            stopMovementKeepAttack(mc);
            return;
        }

        switch (currentState) {
            case FORWARD:
                // Move Forward (W)
                holdKeys(mc, false, false, true, false, true, false, false);
                break;
            case BACKWARD:
                // Move Backward (S)
                holdKeys(mc, false, false, false, true, true, false, false);
                break;
            case DROPPING:
            case NONE:
            default:
                stopMovementKeepAttack(mc);
                break;
        }
    }

    private void debugEvery(Minecraft mc, int intervalTicks, String message) {
        if (mc.player == null) return;
        int currentTick = mc.player.tickCount;
        if (intervalTicks > 0 && currentTick - lastDebugTick < intervalTicks) return;
        lastDebugTick = currentTick;
        ClientUtils.sendDebugMessage(mc, debugPrefix + " " + message);
    }

    private float snapYaw(float yaw) {
        float wrapped = Mth.wrapDegrees(yaw);
        float nearest = Math.round(wrapped / 90f) * 90f;
        return Mth.wrapDegrees(nearest - leftOffsetDegrees);
    }

    protected void setPitchDefault() {
        pitch = Optional.of(-3f);
    }
}
