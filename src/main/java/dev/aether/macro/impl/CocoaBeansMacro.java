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
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Farms cocoa bean rows in a serpentine pattern with the S-shape axes flipped.
 *
 * <p>The row direction alternates between W and S. Lane switches always try D
 * first, then fall back to A if D cannot move the player.
 */
public class CocoaBeansMacro extends AbstractMacro {
    private static final double ROW_PROGRESS_EPSILON = 0.02;
    private static final double LANE_SWITCH_PROGRESS_EPSILON = 0.02;
    private static final int ROW_STALL_THRESHOLD = 2;
    private static final int LANE_SWITCH_STALL_THRESHOLD = 4;

    private int rowStartGraceTicks = 0;
    private State laneSwitchFromState = State.NONE;
    private double previousRowCoord = 0.0;
    private double laneSwitchStartCoord = 0.0;
    private double previousLaneSwitchCoord = 0.0;
    private int rowStallTicks = 0;
    private int laneSwitchStallTicks = 0;
    private int lastRowStopDebugTick = Integer.MIN_VALUE;
    private int lastSwitchDebugTick = Integer.MIN_VALUE;

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);
        changeLaneDirection = null;
        rowStartGraceTicks = 0;
        laneSwitchFromState = State.NONE;
        previousRowCoord = mc.player != null ? rowCoord(mc) : 0.0;
        laneSwitchStartCoord = mc.player != null ? laneSwitchCoord(mc) : 0.0;
        previousLaneSwitchCoord = laneSwitchStartCoord;
        rowStallTicks = 0;
        laneSwitchStallTicks = 0;

        AbstractMacro.State cached = FarmingMacroManager.getCachedDirection();
        if (cached == State.FORWARD || cached == State.BACKWARD) {
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

        if (currentState == null) {
            changeState(State.NONE);
        }

        switch (currentState) {
            case FORWARD:
            case BACKWARD: {
                if (rowStartGraceTicks > 0) {
                    previousRowCoord = rowCoord(mc);
                    rowStartGraceTicks--;
                    break;
                }

                double currentRowCoord = rowCoord(mc);
                boolean madeRowProgress =
                        rowProgressDelta(mc, currentState, currentRowCoord - previousRowCoord)
                                > ROW_PROGRESS_EPSILON;
                previousRowCoord = currentRowCoord;
                boolean fastLaneSwitch = FastLaneSwitchManager.shouldFastSwitch(mc, currentState);

                if (shouldSuppressLaneChangeForDirt(mc)) {
                    rowStallTicks = 0;
                    break;
                }

                if (shouldIgnoreMovementStall(mc)) {
                    rowStallTicks = 0;
                    break;
                }

                if (madeRowProgress && !fastLaneSwitch) {
                    rowStallTicks = 0;
                    break;
                }

                if (!fastLaneSwitch) {
                    rowStallTicks++;
                    if (rowStallTicks < ROW_STALL_THRESHOLD) {
                        break;
                    }
                }
                rowStallTicks = 0;

                if (isLaneSwitchOnCooldown()) {
                    rowStartGraceTicks = Math.max(rowStartGraceTicks, getLaneSwitchCooldownRemainingTicks());
                    previousRowCoord = currentRowCoord;
                    break;
                }

                boolean rightWalkable = isRightWalkable(mc);
                boolean leftWalkable = isLeftWalkable(mc);
                debugEvery(mc, "row-stop", 10,
                        "row stop state=" + currentState
                                + " rowCoord=" + fmt(currentRowCoord)
                                + " right=" + rightWalkable
                                + " left=" + leftWalkable);

                laneSwitchFromState = currentState;
                changeLaneDirection = ChangeLaneDirection.RIGHT;
                laneSwitchStartCoord = laneSwitchCoord(mc);
                previousLaneSwitchCoord = laneSwitchStartCoord;
                previousWalkingCoord = laneCoord(mc);
                laneSwitchStallTicks = 0;
                debugNow(mc, "switch-start",
                        "enter SWITCHING_LANE dir=RIGHT from=" + currentState
                                + " laneCoord=" + previousWalkingCoord
                                + " right=" + rightWalkable
                                + " left=" + leftWalkable);
                changeState(State.SWITCHING_LANE);
                break;
            }

            case SWITCHING_LANE: {
                if (shouldSuppressLaneChangeForDirt(mc)
                        && (laneSwitchFromState == State.FORWARD || laneSwitchFromState == State.BACKWARD)) {
                    State rowState = laneSwitchFromState;
                    changeLaneDirection = null;
                    laneSwitchFromState = State.NONE;
                    rowStartGraceTicks = 0;
                    rowStallTicks = 0;
                    previousRowCoord = rowCoord(mc);
                    changeState(rowState);
                    break;
                }

                if (changeLaneDirection == null) {
                    changeLaneDirection = ChangeLaneDirection.RIGHT;
                    laneSwitchStartCoord = laneSwitchCoord(mc);
                    previousLaneSwitchCoord = laneSwitchStartCoord;
                    previousWalkingCoord = laneCoord(mc);
                    laneSwitchStallTicks = 0;
                }

                double currentLaneSwitchCoord = laneSwitchCoord(mc);
                boolean madeLaneProgress =
                        Math.abs(currentLaneSwitchCoord - previousLaneSwitchCoord)
                                > LANE_SWITCH_PROGRESS_EPSILON;
                boolean advancedFromStart =
                        Math.abs(currentLaneSwitchCoord - laneSwitchStartCoord)
                                > LANE_SWITCH_PROGRESS_EPSILON;

                if (shouldIgnoreMovementStall(mc)) {
                    previousLaneSwitchCoord = currentLaneSwitchCoord;
                    previousWalkingCoord = laneCoord(mc);
                    laneSwitchStallTicks = 0;
                    break;
                }

                debugEvery(mc, "switching", 10,
                        "switching dir=" + changeLaneDirection
                                + " madeProgress=" + madeLaneProgress
                                + " advancedFromStart=" + advancedFromStart
                                + " laneSwitchCoord=" + fmt(currentLaneSwitchCoord)
                                + " prevSwitchCoord=" + fmt(previousLaneSwitchCoord)
                                + " laneCoord=" + laneCoord(mc)
                                + " prevLaneCoord=" + previousWalkingCoord);

                if (madeLaneProgress) {
                    laneSwitchStallTicks = 0;
                    previousLaneSwitchCoord = currentLaneSwitchCoord;
                    previousWalkingCoord = laneCoord(mc);
                    break;
                }

                laneSwitchStallTicks++;
                if (laneSwitchStallTicks < LANE_SWITCH_STALL_THRESHOLD) {
                    break;
                }
                laneSwitchStallTicks = 0;

                if (!advancedFromStart) {
                    if (changeLaneDirection == ChangeLaneDirection.RIGHT) {
                        changeLaneDirection = ChangeLaneDirection.LEFT;
                        laneSwitchStartCoord = currentLaneSwitchCoord;
                        previousLaneSwitchCoord = currentLaneSwitchCoord;
                        previousWalkingCoord = laneCoord(mc);
                        debugNow(mc, "switch-fallback",
                                "right lane switch stalled from=" + laneSwitchFromState
                                        + ", trying LEFT");
                        break;
                    }

                    State escapeRowState = oppositeRowState(laneSwitchFromState);
                    FarmingMacroManager.saveDirection(escapeRowState);
                    rowStartGraceTicks = laneSwitchGraceTicks();
                    previousRowCoord = rowCoord(mc);
                    debugNow(mc, "switch-blocked",
                            "lane switch stalled both ways from=" + laneSwitchFromState
                                    + ", escapeRow=" + escapeRowState);
                    changeLaneDirection = null;
                    laneSwitchFromState = State.NONE;
                    changeState(escapeRowState);
                    break;
                }

                State nextRowState = oppositeRowState(laneSwitchFromState);
                FarmingMacroManager.saveDirection(nextRowState);
                markLaneSwitchComplete();
                changeLaneDirection = null;
                laneSwitchFromState = State.NONE;
                rowStartGraceTicks = laneSwitchGraceTicks();
                previousRowCoord = rowCoord(mc);
                debugNow(mc, "switch-finish",
                        "finish SWITCHING_LANE nextRow=" + nextRowState
                                + " rowCoord=" + fmt(previousRowCoord));
                changeState(nextRowState);
                break;
            }

            case DROPPING: {
                if (mc.player.onGround()) {
                    double droppedY = Math.abs(layerY - mc.player.blockPosition().getY());
                    rotateAfterDropIfConfigured(droppedY);
                    changeLaneDirection = null;
                    layerY = mc.player.blockPosition().getY();
                    changeState(State.NONE);
                }
                break;
            }

            case NONE:
            default: {
                if (previousState == State.FORWARD || previousState == State.BACKWARD) {
                    FarmingMacroManager.saveDirection(previousState);
                    changeState(previousState);
                } else {
                    State detected = calculateForwardDirection(mc);
                    FarmingMacroManager.saveDirection(detected);
                    changeState(detected);
                }
                break;
            }
        }

        if ((currentState == State.FORWARD || currentState == State.BACKWARD)
                && !mc.player.onGround()
                && Math.abs(layerY - mc.player.getY()) > 1.5
                && mc.player.getY() < 80
                && !isDropDetectionSuppressed()) {
            changeState(State.DROPPING);
        }
    }

    @Override
    public boolean isFarmingState() {
        return super.isFarmingState() || currentState == State.SWITCHING_LANE;
    }

    @Override
    public void invokeState(Minecraft mc) {
        if (mc.player == null || currentState == null) return;

        switch (currentState) {
            case FORWARD:
                holdKeys(mc, false, false, true, false, true, false, false);
                break;

            case BACKWARD:
                holdKeys(mc, false, false, false, true, true, false, false);
                break;

            case SWITCHING_LANE: {
                if (changeLaneDirection == null) {
                    changeLaneDirection = ChangeLaneDirection.RIGHT;
                    previousWalkingCoord = laneCoord(mc);
                }

                boolean goRight = changeLaneDirection == ChangeLaneDirection.RIGHT;
                holdKeys(mc,
                        !goRight,
                        goRight,
                        false,
                        false,
                        true,
                        false,
                        false);
                break;
            }

            case DROPPING:
            case NONE:
            default:
                stopMovementKeepAttack(mc);
                break;
        }
    }

    private int laneCoord(Minecraft mc) {
        double rad = Math.toRadians(mc.player.getYRot());
        if (Math.abs(Math.cos(rad)) > Math.abs(Math.sin(rad))) {
            return mc.player.blockPosition().getX();
        }
        return mc.player.blockPosition().getZ();
    }

    private double rowCoord(Minecraft mc) {
        double rad = Math.toRadians(mc.player.getYRot());
        if (Math.abs(Math.sin(rad)) > Math.abs(Math.cos(rad))) {
            return mc.player.getX();
        }
        return mc.player.getZ();
    }

    private double rowProgressDelta(Minecraft mc, State state, double coordDelta) {
        double rad = Math.toRadians(mc.player.getYRot());
        double forwardAxisDirection = Math.abs(Math.sin(rad)) > Math.abs(Math.cos(rad))
                ? -Math.sin(rad)
                : Math.cos(rad);
        double stateDirection = state == State.BACKWARD ? -forwardAxisDirection : forwardAxisDirection;
        return coordDelta * stateDirection;
    }

    private double laneSwitchCoord(Minecraft mc) {
        double rad = Math.toRadians(mc.player.getYRot());
        if (Math.abs(Math.cos(rad)) > Math.abs(Math.sin(rad))) {
            return mc.player.getX();
        }
        return mc.player.getZ();
    }

    private State calculateForwardDirection(Minecraft mc) {
        if (mc.player == null) return State.FORWARD;

        float playerYaw = mc.player.getYRot();
        BlockPos origin = mc.player.blockPosition();

        for (int i = 1; i < 64; i++) {
            BlockPos frontCandidate = getForwardPos(origin, playerYaw, i);
            BlockPos backCandidate = getForwardPos(origin, playerYaw, -i);

            boolean frontBlocked = !isWalkable(mc, frontCandidate);
            boolean backBlocked = !isWalkable(mc, backCandidate);

            if (frontBlocked && !backBlocked) return State.BACKWARD;
            if (backBlocked && !frontBlocked) return State.FORWARD;
        }

        if (currentState == State.FORWARD || currentState == State.BACKWARD) {
            return currentState;
        }
        if (previousState == State.FORWARD || previousState == State.BACKWARD) {
            return previousState;
        }
        return State.FORWARD;
    }

    private static State oppositeRowState(State state) {
        if (state == State.FORWARD) return State.BACKWARD;
        if (state == State.BACKWARD) return State.FORWARD;
        return State.FORWARD;
    }

    private static int laneSwitchGraceTicks() {
        int waitMs = ConfigHelpers.getRandomizedDelay(
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get());
        return (waitMs + 25) / 50;
    }

    private void debugNow(Minecraft mc, String channel, String message) {
        debugEvery(mc, channel, 0, message);
    }

    private void debugEvery(Minecraft mc, String channel, int intervalTicks, String message) {
        if (mc.player == null) return;

        int currentTick = mc.player.tickCount;
        int lastTick;
        switch (channel) {
            case "row-stop":
                lastTick = lastRowStopDebugTick;
                break;
            case "switching":
            case "switch-start":
            case "switch-fallback":
            case "switch-finish":
            case "switch-blocked":
                lastTick = lastSwitchDebugTick;
                break;
            default:
                lastTick = Integer.MIN_VALUE;
                break;
        }

        if (intervalTicks > 0 && currentTick - lastTick < intervalTicks) {
            return;
        }

        switch (channel) {
            case "row-stop":
                lastRowStopDebugTick = currentTick;
                break;
            case "switching":
            case "switch-start":
            case "switch-fallback":
            case "switch-finish":
            case "switch-blocked":
                lastSwitchDebugTick = currentTick;
                break;
            default:
                break;
        }

        ClientUtils.sendDebugMessage("CocoaBeans: " + message);
    }

    private static String fmt(double value) {
        return String.format("%.3f", value);
    }

    private static float nearestCardinal(float yaw) {
        float wrapped = Mth.wrapDegrees(yaw);
        float nearest = Math.round(wrapped / 90f) * 90f;
        return Mth.wrapDegrees(nearest);
    }

    protected void setPitchDefault() {
        pitch = Optional.of(-75f);
    }
}
