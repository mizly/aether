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
 * Farms crops in an S-shape (serpentine) pattern.
 *
 * <h2>Pattern</h2>
 * <pre>
 *   <-<-<-<-<-<-<-<- (row N)
 *             down (lane switch)
 *   ->->->->->->->-> (row N+1)
 *             down
 *   <-<-<-<-<-<-<-<- ...
 * </pre>
 *
 * <h2>States</h2>
 * <ul>
 *   <li>{@code LEFT}  - strafing left (A) and breaking crops</li>
 *   <li>{@code RIGHT} - strafing right (D) and breaking crops</li>
 *   <li>{@code SWITCHING_LANE} - moving forward or backward into the next row</li>
 *   <li>{@code DROPPING} - the player dropped to a lower farm layer; wait for landing</li>
 *   <li>{@code NONE}    - direction not yet determined; {@link #updateState} picks one</li>
 * </ul>
 */
public class SShapeCropMacro extends AbstractMacro {

    /** Minimum motion along the row axis to count as progress this tick. */
    private static final double ROW_PROGRESS_EPSILON = 0.02;
    /** Minimum motion along the lane-switch axis to count as forward/back progress. */
    private static final double LANE_SWITCH_PROGRESS_EPSILON = 0.02;
    /** Number of stalled ticks before retrying / finishing a lane switch. */
    private static final int LANE_SWITCH_STALL_THRESHOLD = 4;

    private int rowStartGraceTicks = 0;
    private State laneSwitchFromState = State.NONE;
    private double previousRowCoord = 0.0;
    private double laneSwitchStartCoord = 0.0;
    private double previousLaneSwitchCoord = 0.0;
    private int laneSwitchStallTicks = 0;
    private boolean fastLaneSwitchPending = false;
    private double fastLaneSwitchStartCoord = 0.0;
    private double previousFastLaneSwitchCoord = 0.0;
    private int fastLaneSwitchStallTicks = 0;
    /** Number of consecutive ticks with negligible row movement before switching lanes. */
    private int rowStallTicks = 0;
    private static final int ROW_STALL_THRESHOLD = 2;
    private int lastRowStopDebugTick = Integer.MIN_VALUE;
    private int lastSwitchDebugTick = Integer.MIN_VALUE;

    // -- Lifecycle -------------------------------------------------------------

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);
        changeLaneDirection = null;
        rowStartGraceTicks  = 0;
        laneSwitchFromState = State.NONE;
        previousRowCoord    = mc.player != null ? rowCoord(mc) : 0.0;
        laneSwitchStartCoord = mc.player != null ? laneSwitchCoord(mc) : 0.0;
        previousLaneSwitchCoord = laneSwitchStartCoord;
        laneSwitchStallTicks = 0;
        fastLaneSwitchPending = false;
        fastLaneSwitchStartCoord = 0.0;
        previousFastLaneSwitchCoord = 0.0;
        fastLaneSwitchStallTicks = 0;
        rowStallTicks = 0;

        // Restore the last known row direction so we don't scan from scratch
        // and risk picking the wrong direction on re-entry.
        AbstractMacro.State cached = FarmingMacroManager.getCachedDirection();
        if (cached == State.LEFT || cached == State.RIGHT) {
            previousState = cached;
        }

        // If pitch not already set by config, apply a sensible default (look
        // slightly downward so the player can see and reach the crop row).
        if (!isPitchSet()) {
            setPitchDefault();
        }

        // If yaw not set by config, snap to the nearest 90 deg cardinal.
        if (!isYawSet() && mc.player != null) {
            yaw = Optional.of(nearestCardinal(mc.player.getYRot()));
        }

        // Immediately rotate to the chosen yaw/pitch.
        if (mc.player != null && (yaw.isPresent() || pitch.isPresent())) {
            float targetYaw   = yaw.orElseGet(mc.player::getYRot);
            float targetPitch = pitch.orElseGet(mc.player::getXRot);
            RotationManager.rotateToYawPitch(mc, targetYaw, targetPitch,
                    AetherConfig.ROTATION_TIME.get());
            rotated = true;
        }
    }

    // -- State machine ---------------------------------------------------------

    @Override
    public void updateState(Minecraft mc) {
        if (mc.player == null) return;

        if (currentState == null) {
            changeState(State.NONE);
        }

        switch (currentState) {

            case LEFT:
            case RIGHT: {
                if (rowStartGraceTicks > 0) {
                    previousRowCoord = rowCoord(mc);
                    rowStartGraceTicks--;
                    break;
                }

                boolean movingLeft  = (currentState == State.LEFT);
                double currentRowCoord = rowCoord(mc);
                boolean madeRowProgress = Math.abs(currentRowCoord - previousRowCoord) > ROW_PROGRESS_EPSILON;
                previousRowCoord = currentRowCoord;
                boolean fastLaneSwitch = FastLaneSwitchManager.shouldFastSwitch(mc, currentState);

                if (shouldSuppressLaneChangeForDirt(mc)) {
                    rowStallTicks = 0;
                    break;
                }

                if (shouldIgnoreMovementStall(mc)) {
                    // When other systems suppress movement detection (freecam),
                    // don't count these ticks toward the stall threshold.
                    rowStallTicks = 0;
                    debugEvery(mc, "stall-suppressed", 20,
                            "row stall suppressed state=" + currentState
                                    + " rowCoord=" + fmt(currentRowCoord));
                    break;
                }

                if (!fastLaneSwitch && fastLaneSwitchPending) {
                    double currentLaneCoord = laneSwitchCoord(mc);
                    boolean madeLaneProgress =
                            Math.abs(currentLaneCoord - previousFastLaneSwitchCoord)
                                    > LANE_SWITCH_PROGRESS_EPSILON;
                    boolean advancedFromStart =
                            Math.abs(currentLaneCoord - fastLaneSwitchStartCoord)
                                    > LANE_SWITCH_PROGRESS_EPSILON;

                    if (madeLaneProgress) {
                        fastLaneSwitchStallTicks = 0;
                        previousFastLaneSwitchCoord = currentLaneCoord;
                        rowStallTicks = 0;
                        previousRowCoord = currentRowCoord;
                        break;
                    }

                    fastLaneSwitchStallTicks++;
                    if (fastLaneSwitchStallTicks < LANE_SWITCH_STALL_THRESHOLD) {
                        rowStallTicks = 0;
                        break;
                    }

                    fastLaneSwitchPending = false;
                    fastLaneSwitchStallTicks = 0;
                    if (advancedFromStart) {
                        rowStallTicks = 0;
                        previousRowCoord = currentRowCoord;
                        break;
                    }
                }

                if (madeRowProgress && !fastLaneSwitch) {
                    // As long as the player is still sliding along the row,
                    // keep forcing the strafe key down and reset stall counter.
                    rowStallTicks = 0;
                    break;
                }

                if (!fastLaneSwitch) {
                    // No measurable row progress this tick. Require a small number of
                    // consecutive non-progress ticks to avoid reacting to transient
                    // server-side position corrections (rubberband/jitter).
                    rowStallTicks++;
                    if (rowStallTicks < ROW_STALL_THRESHOLD) {
                        break;
                    }
                }
                // Threshold reached: reset counter and proceed to lane switch.
                rowStallTicks = 0;

                if (isLaneSwitchOnCooldown()) {
                    rowStartGraceTicks = Math.max(rowStartGraceTicks, getLaneSwitchCooldownRemainingTicks());
                    previousRowCoord = currentRowCoord;
                    break;
                }

                if (fastLaneSwitch && AetherConfig.MACRO_HOLD_W_WHILE_FARMING.get()) {
                    State nextRowState = currentState == State.LEFT ? State.RIGHT : State.LEFT;
                    FarmingMacroManager.saveDirection(nextRowState);
                    rowStartGraceTicks = 0;
                    rowStallTicks = 0;
                    fastLaneSwitchPending = true;
                    fastLaneSwitchStartCoord = laneSwitchCoord(mc);
                    previousFastLaneSwitchCoord = fastLaneSwitchStartCoord;
                    fastLaneSwitchStallTicks = 0;
                    previousRowCoord = rowCoord(mc);
                    debugNow(mc, "switch-finish",
                            "fast row flip " + currentState + " -> " + nextRowState
                                    + " while holding forward");
                    changeState(nextRowState);
                    break;
                }

                // Horizontal movement stopped, so treat it as the row ending
                // and immediately start the lane switch based on actual motion
                // instead of walkability probes, which can false-negative here.
                boolean frontWalkable = isFrontWalkable(mc);
                boolean backWalkable = isBackWalkable(mc);
                debugEvery(mc, "row-stop", 10,
                        "row stop state=" + currentState
                                + " movingLeft=" + movingLeft
                                + " rowCoord=" + fmt(currentRowCoord)
                                + " front=" + frontWalkable
                                + " back=" + backWalkable);

                laneSwitchFromState = currentState;
                changeLaneDirection = ChangeLaneDirection.FORWARD;
                laneSwitchStartCoord = laneSwitchCoord(mc);
                previousLaneSwitchCoord = laneSwitchStartCoord;
                previousWalkingCoord = laneCoord(mc);
                laneSwitchStallTicks = 0;
                debugNow(mc, "switch-start",
                        "enter SWITCHING_LANE dir=FORWARD from=" + currentState
                                + " laneCoord=" + previousWalkingCoord
                                + " front=" + frontWalkable
                                + " back=" + backWalkable);
                changeState(State.SWITCHING_LANE);
                break;
            }

            case SWITCHING_LANE: {
                if (shouldSuppressLaneChangeForDirt(mc) && (laneSwitchFromState == State.LEFT || laneSwitchFromState == State.RIGHT)) {
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
                    changeState(State.NONE);
                    break;
                }

                double currentLaneSwitchCoord = laneSwitchCoord(mc);
                boolean madeLaneProgress =
                        Math.abs(currentLaneSwitchCoord - previousLaneSwitchCoord) > LANE_SWITCH_PROGRESS_EPSILON;
                boolean advancedFromStart =
                        Math.abs(currentLaneSwitchCoord - laneSwitchStartCoord) > LANE_SWITCH_PROGRESS_EPSILON;
                if (shouldIgnoreMovementStall(mc)) {
                    previousLaneSwitchCoord = currentLaneSwitchCoord;
                    previousWalkingCoord = laneCoord(mc);
                    laneSwitchStallTicks = 0;
                    debugEvery(mc, "stall-suppressed", 20,
                            "lane switch stall suppressed dir=" + changeLaneDirection
                                    + " laneSwitchCoord=" + fmt(currentLaneSwitchCoord));
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

                // No motion after attempting the forward lane switch: escape by
                // flipping row direction so we can move out of corner traps.
                if (!advancedFromStart) {
                    State escapeRowState;
                    if (laneSwitchFromState == State.LEFT) {
                        escapeRowState = State.RIGHT;
                    } else if (laneSwitchFromState == State.RIGHT) {
                        escapeRowState = State.LEFT;
                    } else {
                        escapeRowState = calculateDirection(mc);
                    }
                    FarmingMacroManager.saveDirection(escapeRowState);
                    int graceMs = ConfigHelpers.getRandomizedDelay(
                            AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                            AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get());
                    rowStartGraceTicks = (graceMs + 25) / 50;
                    previousRowCoord = rowCoord(mc);
                    debugNow(mc, "switch-blocked",
                            "forward lane switch stalled from=" + laneSwitchFromState
                                    + ", escapeRow=" + escapeRowState);
                    changeLaneDirection = null;
                    laneSwitchFromState = State.NONE;
                    changeState(escapeRowState);
                    break;
                }

                // Movement stopped after successfully advancing: lane switch complete.
                {
                    State nextRowState;
                    if (laneSwitchFromState == State.LEFT) {
                        nextRowState = State.RIGHT;
                    } else if (laneSwitchFromState == State.RIGHT) {
                        nextRowState = State.LEFT;
                    } else {
                        nextRowState = calculateDirection(mc);
                    }

                    FarmingMacroManager.saveDirection(nextRowState);
                    markLaneSwitchComplete();
                    changeLaneDirection = null;
                    laneSwitchFromState = State.NONE;
                    int waitMs = ConfigHelpers.getRandomizedDelay(
                            AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                            AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get());
                    rowStartGraceTicks = (waitMs + 25) / 50;
                    previousRowCoord = rowCoord(mc);
                    debugNow(mc, "switch-finish",
                        "finish SWITCHING_LANE nextRow=" + nextRowState
                            + " rowCoord=" + fmt(previousRowCoord));
                    changeState(nextRowState);
                    break;
                }
            }

            case DROPPING: {
                if (mc.player.onGround()) {
                    double droppedY = Math.abs(layerY - mc.player.blockPosition().getY());
                    rotateAfterDropIfConfigured(droppedY);
                    changeLaneDirection = null;
                    layerY = mc.player.blockPosition().getY();
                    changeState(State.NONE);
                } else {
                    // Still falling
                    if (!mc.player.onGround()
                            && Math.abs(layerY - mc.player.getY()) > 0.75
                            && mc.player.getY() < 80) {
                        // let the player fall freely
                    }
                }
                break;
            }

            case NONE:
            default: {
                // Recovery path: keep the previous row direction to avoid
                // noisy re-detection flipping LEFT/RIGHT mid-run.
                if (previousState == State.LEFT || previousState == State.RIGHT) {
                    FarmingMacroManager.saveDirection(previousState);
                    changeState(previousState);
                } else {
                    // First acquisition only.
                    State detected = calculateDirection(mc);
                    FarmingMacroManager.saveDirection(detected);
                    changeState(detected);
                }
                break;
            }
        }

        // Drop detection: if the player fell more than 1.5 blocks below layerY
        // mid-farming, switch to DROPPING state.
        if ((currentState == State.LEFT || currentState == State.RIGHT)
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

            case LEFT:
                // Strafe left + break crops.
                holdKeys(mc,
                        /*left*/    true,
                        /*right*/   false,
                    /*forward*/ AetherConfig.MACRO_HOLD_W_WHILE_FARMING.get(),
                        /*back*/    false,
                        /*attack*/  true,
                        /*sprint*/  false,
                        /*sneak*/   false);
                break;

            case RIGHT:
                holdKeys(mc,
                        false,
                        true,
                        AetherConfig.MACRO_HOLD_W_WHILE_FARMING.get(),
                        false,
                        true,
                        false,
                        false);
                break;

            case SWITCHING_LANE: {
                if (changeLaneDirection == null) {
                    // Forward-only lane switching.
                    if (isFrontWalkable(mc)) {
                        changeLaneDirection = ChangeLaneDirection.FORWARD;
                    } else {
                        changeState(State.NONE);
                        return;
                    }
                    previousWalkingCoord = laneCoord(mc);
                }

                boolean goForward = (changeLaneDirection == ChangeLaneDirection.FORWARD);
                holdKeys(mc,
                        /*left*/    false,
                        /*right*/   false,
                        /*forward*/ goForward,
                        /*back*/    !goForward,
                        /*attack*/  true,
                        /*sprint*/  goForward,
                        /*sneak*/   false);
                break;
            }

            case DROPPING:
                // Release everything and let gravity do the work.
                stopMovementKeepAttack(mc);
                break;

            case NONE:
            default:
                stopMovementKeepAttack(mc);
                break;
        }
    }

    // -- Private helpers -------------------------------------------------------

    /**
     * Returns the coordinate to watch while switching lanes.
     * The watched coordinate must be the axis that changes when moving
     * forward/backward, otherwise lag-back detection can falsely trigger.
     */
    private int laneCoord(Minecraft mc) {
        // Forward direction: (-sin(yaw), 0, cos(yaw)).
        // If |sin(yaw)| > |cos(yaw)| the player moves mostly along X when
        // going forward, so X is the lane-switching axis.
        double rad = Math.toRadians(mc.player.getYRot());
        if (Math.abs(Math.sin(rad)) > Math.abs(Math.cos(rad))) {
            return mc.player.blockPosition().getX();
        } else {
            return mc.player.blockPosition().getZ();
        }
    }

    /**
     * Returns the coordinate that changes while strafing along a row.
     */
    private double rowCoord(Minecraft mc) {
        double rad = Math.toRadians(mc.player.getYRot());
        if (Math.abs(Math.cos(rad)) > Math.abs(Math.sin(rad))) {
            return mc.player.getX();
        } else {
            return mc.player.getZ();
        }
    }

    /**
     * Returns the coordinate that changes while moving forward/back during a lane switch.
     */
    private double laneSwitchCoord(Minecraft mc) {
        double rad = Math.toRadians(mc.player.getYRot());
        if (Math.abs(Math.sin(rad)) > Math.abs(Math.cos(rad))) {
            return mc.player.getX();
        } else {
            return mc.player.getZ();
        }
    }

    private void debugNow(Minecraft mc, String channel, String message) {
        debugEvery(mc, channel, 0, message);
    }

    private void debugEvery(Minecraft mc, String channel, int intervalTicks, String message) {
        if (mc.player == null) {
            return;
        }

        int currentTick = mc.player.tickCount;
        int lastTick;
        switch (channel) {
            case "row-stop":
                lastTick = lastRowStopDebugTick;
                break;
            case "switching":
            case "switch-start":
            case "switch-finish":
            case "switch-blocked":
            case "stall-suppressed":
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
            case "switch-finish":
            case "switch-blocked":
            case "stall-suppressed":
                lastSwitchDebugTick = currentTick;
                break;
            default:
                break;
        }

        ClientUtils.sendDebugMessage(mc, "[SShape] " + message);
    }

    private static String fmt(double value) {
        return String.format("%.3f", value);
    }

    /** Nearest 90-degree cardinal yaw from the given yaw value. */
    private static float nearestCardinal(float yaw) {
        float wrapped = Mth.wrapDegrees(yaw);
        float nearest = Math.round(wrapped / 90f) * 90f;
        return Mth.wrapDegrees(nearest);
    }

    /**
     * Sets a sensible default pitch for normal vertical crop farming.
     * Subclasses may override this to customise the default pitch by crop type.
     */
    protected void setPitchDefault() {
        // ~3 deg upward - comfortable for most crop rows
        pitch = Optional.of(-3f + (float) (Math.random() * 2.0 - 1.0));
    }

    // Public accessor for testing / HUD display
    public ChangeLaneDirection getChangeLaneDirection() { return changeLaneDirection; }
}
