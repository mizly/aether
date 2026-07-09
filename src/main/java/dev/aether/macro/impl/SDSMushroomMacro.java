package dev.aether.macro.impl;

import dev.aether.config.AetherConfig;
import dev.aether.macro.AbstractMacro;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.Optional;

/**
 * Mushroom SDS macro.
 *
 * <p>This pattern cycles {@code A -> S -> D -> A}. Row-end detection only
 * tracks the world-axis component used by the A/D strafe, while the S
 * transition tracks the perpendicular component.
 */
public class SDSMushroomMacro extends AbstractMacro {
    private static final double PROGRESS_EPSILON = 0.005;
    private static final int STALL_THRESHOLD = 2;
    private static final float CARDINAL_LEFT_OFFSET_DEGREES = 16f;

    private int graceTicks = 0;
    private int stallTicks = 0;
    private double previousTrackedCoord = 0.0;
    private int lastDebugTick = Integer.MIN_VALUE;

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);

        if (mc.player != null) {
            previousTrackedCoord = trackedCoord(mc, State.LEFT);
            layerY = mc.player.blockPosition().getY();
        }

        graceTicks = 20;
        stallTicks = 0;

        AbstractMacro.State cached = FarmingMacroManager.getCachedDirection();
        if (cached == State.LEFT || cached == State.RIGHT || cached == State.BACKWARD) {
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
            State startState = previousState == State.LEFT || previousState == State.RIGHT || previousState == State.BACKWARD
                    ? previousState
                    : State.LEFT;
            changeState(startState);
            resetMovementTracking(mc, 10);
            FarmingMacroManager.saveDirection(currentState);
        }

        if (currentState == State.DROPPING) {
            if (mc.player.onGround()) {
                double droppedY = Math.abs(layerY - mc.player.blockPosition().getY());
                rotateAfterDropIfConfigured(droppedY);
                layerY = mc.player.blockPosition().getY();
                changeState(State.NONE);
                resetMovementTracking(mc, 2);
            }
            return;
        }

        if (!mc.player.onGround()
                && Math.abs(layerY - mc.player.getY()) > 1.5
                && mc.player.getY() < 80
                && !isDropDetectionSuppressed()) {
            changeState(State.DROPPING);
            return;
        }

        if ((currentState == State.LEFT || currentState == State.RIGHT) && shouldSuppressLaneChangeForDirt(mc)) {
            resetMovementTracking(mc, 0);
            return;
        }

        if (graceTicks > 0) {
            graceTicks--;
            previousTrackedCoord = trackedCoord(mc, currentState);
            return;
        }

        double currentCoord = trackedCoord(mc, currentState);
        double movement = Math.abs(currentCoord - previousTrackedCoord);
        boolean fastLaneSwitch = FastLaneSwitchManager.shouldFastSwitch(mc, currentState);
        previousTrackedCoord = currentCoord;

        if (shouldIgnoreMovementStall(mc)) {
            graceTicks = Math.max(graceTicks, 2);
            stallTicks = 0;
            return;
        }

        if (movement >= PROGRESS_EPSILON && !fastLaneSwitch) {
            stallTicks = 0;
            return;
        }

        stallTicks++;
        if (!fastLaneSwitch && stallTicks < STALL_THRESHOLD) {
            return;
        }
        stallTicks = 0;

        State next = nextState(currentState);
        debugEvery(mc, 10, "Switching " + currentState + " -> " + next + " after stall");
        changeState(next);
        resetMovementTracking(mc, fastLaneSwitch ? 0 : 2);
        FarmingMacroManager.saveDirection(currentState);
    }

    @Override
    public void invokeState(Minecraft mc) {
        if (mc.player == null) return;

        switch (currentState) {
            case LEFT:
                holdKeys(mc, true, false, false, false, true, false, false);
                break;
            case RIGHT:
                holdKeys(mc, false, true, false, false, true, false, false);
                break;
            case BACKWARD:
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
        ClientUtils.sendDebugMessage(mc, "SDSMushroom: " + message);
    }

    private void resetMovementTracking(Minecraft mc, int newGraceTicks) {
        stallTicks = 0;
        graceTicks = Math.max(0, newGraceTicks);
        previousTrackedCoord = trackedCoord(mc, currentState);
    }

    private static State nextState(State state) {
        return switch (state) {
            case LEFT -> State.BACKWARD;
            case BACKWARD -> State.RIGHT;
            case RIGHT -> State.LEFT;
            default -> State.LEFT;
        };
    }

    private double trackedCoord(Minecraft mc, State state) {
        if (mc.player == null) return previousTrackedCoord;
        float trackingYaw = yaw.orElseGet(mc.player::getYRot);
        double rad = Math.toRadians(trackingYaw);

        if (state == State.BACKWARD) {
            double backX = Math.sin(rad);
            double backZ = -Math.cos(rad);
            return Math.abs(backX) >= Math.abs(backZ) ? mc.player.getX() : mc.player.getZ();
        }

        double strafeLeftX = Math.cos(rad);
        double strafeLeftZ = Math.sin(rad);
        return Math.abs(strafeLeftX) >= Math.abs(strafeLeftZ) ? mc.player.getX() : mc.player.getZ();
    }

    private static float nearestCardinal(float yaw) {
        float wrapped = Mth.wrapDegrees(yaw);
        float nearest = Math.round(wrapped / 90f) * 90f;
        return Mth.wrapDegrees(nearest - CARDINAL_LEFT_OFFSET_DEGREES);
    }

    protected void setPitchDefault() {
        pitch = Optional.of((float) (6.5f + Math.random()));
    }
}
