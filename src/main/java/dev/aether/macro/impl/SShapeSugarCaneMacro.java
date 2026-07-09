package dev.aether.macro.impl;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.AbstractMacro;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.Optional;

/**
 * Sugar cane macro with a fixed two-key pattern.
 *
 * <p>The player faces 45 degrees to the right of the nearest cardinal yaw and
 * alternates only between strafing left with {@code A} and moving backward
 * with {@code S}.
 */
public class SShapeSugarCaneMacro extends AbstractMacro {
    private static final double PROGRESS_EPSILON_SQ = 0.005 * 0.005;
    private static final int STALL_THRESHOLD = 2;
    private static final float CARDINAL_RIGHT_OFFSET_DEGREES = 45f;

    private int graceTicks = 0;
    private int stallTicks = 0;
    private double previousX = 0.0;
    private double previousZ = 0.0;
    private int lastDebugTick = Integer.MIN_VALUE;

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);

        if (mc.player != null) {
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            layerY = mc.player.blockPosition().getY();
        }

        graceTicks = 20;
        stallTicks = 0;

        AbstractMacro.State cached = FarmingMacroManager.getCachedDirection();
        if (cached == State.LEFT || cached == State.BACKWARD) {
            previousState = cached;
        }

        if (!isPitchSet()) {
            setPitchDefault();
        }
        if (!isYawSet() && mc.player != null) {
            yaw = Optional.of(snapYawRight(mc.player.getYRot()));
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
            if (previousState == State.LEFT || previousState == State.BACKWARD) {
                changeState(previousState);
            } else {
                changeState(State.LEFT);
            }
            FarmingMacroManager.saveDirection(currentState);
            graceTicks = 10;
        }

        if (currentState == State.DROPPING) {
            if (mc.player.onGround()) {
                double droppedY = Math.abs(layerY - mc.player.blockPosition().getY());
                rotateAfterDropIfConfigured(droppedY);
                layerY = mc.player.blockPosition().getY();
                changeState(State.NONE);
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

        if ((currentState == State.LEFT || currentState == State.BACKWARD) && shouldSuppressLaneChangeForDirt(mc)) {
            graceTicks = 0;
            stallTicks = 0;
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            return;
        }

        if (graceTicks > 0) {
            graceTicks--;
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
            stallTicks = 0;
            return;
        }

        if (distSq >= PROGRESS_EPSILON_SQ && !fastLaneSwitch) {
            stallTicks = 0;
            return;
        }

        stallTicks++;
        if (!fastLaneSwitch && stallTicks < STALL_THRESHOLD) {
            return;
        }
        stallTicks = 0;

        State next = currentState == State.LEFT ? State.BACKWARD : State.LEFT;
        int totalMs = fastLaneSwitch ? 0 : ConfigHelpers.getRandomizedDelay(
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get());
        graceTicks = (totalMs + 25) / 50;
        debugEvery(mc, 10, "Switching " + currentState + " -> " + next + " after stall");
        changeState(next);
        FarmingMacroManager.saveDirection(next);
    }

    @Override
    public void invokeState(Minecraft mc) {
        if (mc.player == null) return;

        switch (currentState) {
            case LEFT:
                holdKeys(mc, true, false, false, false, true, false, false);
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
        ClientUtils.sendDebugMessage(mc, "SugarCane: " + message);
    }

    private static float snapYawRight(float yaw) {
        float wrapped = Mth.wrapDegrees(yaw);
        float nearest = Math.round(wrapped / 90f) * 90f;
        return Mth.wrapDegrees(nearest + CARDINAL_RIGHT_OFFSET_DEGREES);
    }

    protected void setPitchDefault() {
        pitch = Optional.of(-1f + (float) (Math.random() * 2.0));
    }
}
