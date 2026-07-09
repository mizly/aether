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
 * Farms rows using A and D (left/right strafe) without lane switching.
 *
 * <p>This macro alternates only between strafing left (A) and strafing right
 * (D). It treats sustained lack of horizontal movement as a row end, waits for
 * the configured lane-switch delay, then flips direction.
 */
public class ADFarmMacro extends AbstractMacro {
    private static final double PROGRESS_EPSILON_SQ = 0.005 * 0.005;
    private static final int ROW_STALL_THRESHOLD = 2;

    private int graceTicks = 0;
    private boolean isWaitingAtWall = false;
    private int rowStallTicks = 0;
    private double previousX = 0.0;
    private double previousZ = 0.0;
    private int lastDebugTick = Integer.MIN_VALUE;
    private State stateBeforeDrop = State.NONE;

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);

        if (mc.player != null) {
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            layerY = mc.player.blockPosition().getY();
        }

        graceTicks = 20;
        isWaitingAtWall = false;
        rowStallTicks = 0;
        stateBeforeDrop = State.NONE;

        AbstractMacro.State cached = FarmingMacroManager.getCachedDirection();
        if (cached == State.LEFT || cached == State.RIGHT) {
            previousState = cached;
        }

        if (!isPitchSet()) {
            setPitchDefault();
        }
        if (!isYawSet() && mc.player != null) {
            yaw = Optional.of(nearestCardinal(mc.player.getYRot()));
        }

        if (mc.player != null && (yaw.isPresent() || pitch.isPresent())) {
            float targetYaw = yaw.orElseGet(mc.player::getYRot);
            float targetPitch = pitch.orElseGet(mc.player::getXRot);
            RotationManager.rotateToYawPitch(mc, targetYaw, targetPitch,
                    AetherConfig.ROTATION_TIME.get());
            rotated = true;
        }
    }

    @Override
    public void updateState(Minecraft mc) {
        if (mc.player == null) return;

        if (currentState == State.NONE) {
            if (previousState == State.LEFT || previousState == State.RIGHT) {
                transitionLaneState(mc, previousState, "restore cached direction");
            } else {
                transitionLaneState(mc, State.LEFT, "default start direction");
            }
            FarmingMacroManager.saveDirection(currentState);
            graceTicks = 10;
        }

        if (currentState == State.DROPPING) {
            if (mc.player.onGround()) {
                double droppedY = Math.abs(layerY - mc.player.blockPosition().getY());
                rotateAfterDropIfConfigured(droppedY);
                layerY = mc.player.blockPosition().getY();
                State resumeState = stateBeforeDrop == State.LEFT || stateBeforeDrop == State.RIGHT
                        ? stateBeforeDrop
                        : previousState;
                if (resumeState == State.LEFT || resumeState == State.RIGHT) {
                    transitionLaneState(mc, resumeState, "drop finished");
                    FarmingMacroManager.saveDirection(resumeState);
                } else {
                    changeState(State.NONE);
                }
                stateBeforeDrop = State.NONE;
                isWaitingAtWall = false;
                rowStallTicks = 0;
                graceTicks = 10;
                previousX = mc.player.getX();
                previousZ = mc.player.getZ();
            }
            return;
        }

        if (!mc.player.onGround()
                && Math.abs(layerY - mc.player.getY()) > 1.5
                && mc.player.getY() < 80
                && !isDropDetectionSuppressed()) {
            if (currentState == State.LEFT || currentState == State.RIGHT) {
                stateBeforeDrop = currentState;
            }
            changeState(State.DROPPING);
            return;
        }

        if ((currentState == State.LEFT || currentState == State.RIGHT) && shouldSuppressLaneChangeForDirt(mc)) {
            isWaitingAtWall = false;
            rowStallTicks = 0;
            graceTicks = 0;
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            return;
        }

        if (graceTicks > 0) {
            graceTicks--;
            if (isWaitingAtWall && graceTicks == 0) {
                isWaitingAtWall = false;
                State next = currentState == State.LEFT ? State.RIGHT : State.LEFT;
                debugEvery(mc, 10, "Wait finished. Flipped " + currentState + " -> " + next);
                transitionLaneState(mc, next, "wall wait finished");
                FarmingMacroManager.saveDirection(next);
                graceTicks = 10;
            }
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            return;
        }

        double dx = mc.player.getX() - previousX;
        double dz = mc.player.getZ() - previousZ;
        double distSq = dx * dx + dz * dz;
        boolean fastLaneSwitch = FastLaneSwitchManager.shouldFastSwitch(mc, currentState);

        previousX = mc.player.getX();
        previousZ = mc.player.getZ();

        if (shouldIgnoreMovementStall(mc)) {
            graceTicks = Math.max(graceTicks, 2);
            rowStallTicks = 0;
            return;
        }

        if (distSq < PROGRESS_EPSILON_SQ || fastLaneSwitch) {
            rowStallTicks++;
            if (!fastLaneSwitch && rowStallTicks < ROW_STALL_THRESHOLD) {
                return;
            }

            rowStallTicks = 0;
            int totalMs = fastLaneSwitch ? 0 : getWallWaitMs();
            int ticks = (totalMs + 25) / 50;

            if (ticks > 0) {
                isWaitingAtWall = true;
                graceTicks = ticks;
                debugEvery(mc, 10, String.format("Row end reached. Waiting %d ticks (%dms)...", ticks, totalMs));
            } else {
                State next = currentState == State.LEFT ? State.RIGHT : State.LEFT;
                debugEvery(mc, 10, "Row end reached. Flipped " + currentState + " -> " + next);
                transitionLaneState(mc, next, "row end");
                FarmingMacroManager.saveDirection(next);
                graceTicks = fastLaneSwitch ? 0 : 10;
            }
        } else {
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
            case LEFT:
                holdKeys(mc, true, false, false, false, true, false, false);
                break;
            case RIGHT:
                holdKeys(mc, false, true, false, false, true, false, false);
                break;
            case DROPPING:
            case NONE:
            default:
                stopMovementKeepAttack(mc);
                break;
        }
    }

    private static int getWallWaitMs() {
        return ConfigHelpers.getRandomizedDelay(
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get());
    }

    private void debugEvery(Minecraft mc, int intervalTicks, String message) {
        if (mc.player == null) return;
        int currentTick = mc.player.tickCount;
        if (intervalTicks > 0 && currentTick - lastDebugTick < intervalTicks) return;
        lastDebugTick = currentTick;
        ClientUtils.sendDebugMessage(mc, "ADFarm: " + message);
    }

    private void transitionLaneState(Minecraft mc, State nextState, String reason) {
        State fromState = currentState;
        changeState(nextState);
        if ((fromState == State.LEFT || fromState == State.RIGHT || fromState == State.NONE)
                && (nextState == State.LEFT || nextState == State.RIGHT)
                && fromState != nextState) {
            ClientUtils.sendDebugMessage(mc,
                    "ADFarm: lane change " + fromState + " -> " + nextState + " (" + reason + ")");
        }
    }

    private static float nearestCardinal(float yaw) {
        float wrapped = Mth.wrapDegrees(yaw);
        float nearest = Math.round(wrapped / 90f) * 90f;
        return Mth.wrapDegrees(nearest);
    }

    protected void setPitchDefault() {
        pitch = Optional.of(-3f);
    }
}
