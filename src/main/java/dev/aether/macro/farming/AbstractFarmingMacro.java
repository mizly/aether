package dev.aether.macro.farming;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.AbstractMacro;
import dev.aether.macro.MacroInput;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.farming.UngrabMouse;
import dev.aether.mixin.MixinMinecraft;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.modules.visuals.FreecamManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.ProgrammaticAttackTracker;
import dev.aether.util.ProgrammaticMovementTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base class for all farming macros.
 *
 * <h2>State machine</h2>
 * Each tick the macro calls {@link #updateState} to decide what the player
 * should do next, then {@link #invokeState} to press the appropriate keys.
 *
 * <h2>Rotation</h2>
 * The macro stores an optional target {@link #yaw} and {@link #pitch}.
 * On {@link #onEnable} (or when populated by a subclass) it triggers a
 * smooth rotation via {@link RotationManager}. While the rotation is
 * pending, movement keys are released.
 *
 * <h2>Key pressing convention</h2>
 * Call {@link #holdKeys} from {@link #invokeState} to press the keys for
 * the current state. All keys not listed will be released.
 */
public abstract class AbstractFarmingMacro extends AbstractMacro {

    // -- State -----------------------------------------------------------------

    public enum State {
        NONE,
        SWITCHING_LANE,
        LEFT,
        RIGHT,
        FORWARD,
        BACKWARD
    }

    protected State currentState  = State.NONE;
    protected State previousState = State.NONE;

    // -- Rotation fields -------------------------------------------------------

    /** Target yaw for the initial rotation, set by config or subclass. */
    protected Optional<Float> yaw   = Optional.empty();
    /** Target pitch for the initial rotation, set by config or subclass. */
    protected Optional<Float> pitch = Optional.empty();
    /** {@code true} once the initial rotation has been dispatched. */
    protected boolean rotated = false;
    private final List<StateCycle> stateCycles = new ArrayList<>();
    private StateCycle defaultStateCycle;

    /** False after a GUI interrupts farming; movement may continue, but left-click should not. */
    private boolean continueAttack = true;
    /** Tracks the macro-owned attack edge separately from Minecraft's raw key state. */
    private boolean attackHeldByMacro = false;
    private int unflyTicks = 0;

    // -- Lifecycle -------------------------------------------------------------

    /**
     * Called once when the macro is enabled.
     * Subclasses should call {@code super.onEnable(mc)} first, then set
     * additional rotation / state as needed.
     */
    @Override
    public void onEnable(Minecraft mc) {
        SqueakyMousematManager.RotationSnapshot mousematRotation =
                SqueakyMousematManager.getMacroRotationOverride(mc);
        if (mousematRotation != null) {
            yaw = Optional.of(mousematRotation.yaw());
            pitch = Optional.of(mousematRotation.pitch());
        } else {
            // Apply config overrides for pitch / yaw
            if (AetherConfig.MACRO_USE_CUSTOM_PITCH.get()) {
                float pitchVal = AetherConfig.MACRO_CUSTOM_PITCH.get();
                float hum = AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION.get();
                if (hum > 0) {
                    pitchVal += (float) ((Math.random() - 0.5) * 2.0 * hum);
                }
                pitch = Optional.of(pitchVal);
            }
            if (AetherConfig.MACRO_USE_CUSTOM_YAW.get()) {
                float yawVal = AetherConfig.MACRO_CUSTOM_YAW.get();
                float hum = AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION.get();
                if (hum > 0) {
                    yawVal += (float) ((Math.random() - 0.5) * 2.0 * hum);
                }
                yaw = Optional.of(yawVal);
            }
        }

        // Ungrab mouse if requested
        if (AetherConfig.MACRO_UNGRAB_MOUSE.get()) {
            UngrabMouse.requestMacroUngrab();
        }

        FarmingMacroManager.loadCycleStep();
        rotated = false;
        continueAttack = true;
        attackHeldByMacro = false;
        unflyTicks = 0;
        FastLaneSwitchManager.resetRuntime();
        changeState(State.NONE);
        stateCycles.forEach(StateCycle::reset);
        applyDefaultOrientation(mc);
        rotateToConfiguredOrientation(mc);
    }

    /**
     * Called once when the macro is disabled.
     * Releases all movement keys and re-grabs the mouse.
     */
    @Override
    public void onDisable(Minecraft mc) {
        releaseAll(mc);
        changeState(State.NONE);
        yaw   = Optional.empty();
        pitch = Optional.empty();
        rotated = false;
        continueAttack = false;
        attackHeldByMacro = false;
        FastLaneSwitchManager.resetRuntime();
    }

    /**
     * Main per-tick entry point. Called from {@link FarmingMacroManager} on
     * every {@code END_CLIENT_TICK} while the macro is active.
     */
    @Override
    public final void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        if (ClientUtils.isInventoryScreenOpen()) {
            interruptAttack();
            releaseAll(mc);
            return;
        }

        if (mc.screen != null && isFarmingState()) {
            interruptAttack();
        }

        // -- Initial rotation -------------------------------------------------
        if (!rotated && (yaw.isPresent() || pitch.isPresent())) {
            float targetYaw   = yaw.orElseGet(mc.player::getYRot);
            float targetPitch = pitch.orElseGet(mc.player::getXRot);
            RotationManager.rotateToYawPitch(mc, targetYaw, targetPitch,
                    AetherConfig.ROTATION_TIME.get());
            rotated = true;
            stopMovementKeepAttack(mc);
            return;
        }

        // -- Wait for rotation ------------------------------------------------
        if (RotationManager.isRotating()) {
            stopMovementKeepAttack(mc);
            return;
        }

        // -- Flying guard -----------------------------------------------------
        if (mc.player.getAbilities().flying) {
            stopMovementKeepAttack(mc);
            MacroInput.unfly(mc, unflyTicks++);
            return;
        }
        unflyTicks = 0;

        // -- State machine ----------------------------------------------------
        FastLaneSwitchManager.tick(mc, currentState);
        updateState(mc);
        invokeState(mc);
    }

    // -- Abstract interface ----------------------------------------------------

    /**
     * Decide what the next {@link #currentState} should be.
     * Implementations should call {@link #changeState} when a transition is
     * needed.  Triggered every tick.
     */
    public void updateState(Minecraft mc) {
        if (defaultStateCycle != null) {
            updateHorizontalStateCycle(mc, defaultStateCycle);
        }
    }

    /**
     * Execute the physical actions (key presses, etc.) for {@link #currentState}.
     * Triggered every tick, immediately after {@link #updateState}.
     */
    public void invokeState(Minecraft mc) {
        if (defaultStateCycle == null || defaultStateCycle.isWaitingAtStateEnd()) {
            stopMovementKeepAttack(mc);
            return;
        }
        StateKeys keys = stateKeys().getOrDefault(currentState, defaultStateKeys(currentState));
        if (keys != null) {
            holdKeys(mc, keys.left(), keys.right(), keys.forward(), keys.back(),
                    keys.attack(), keys.sprint(), keys.sneak());
            return;
        }
        stopMovementKeepAttack(mc);
    }

    /**
     * Returns true if the macro is currently in a "farming" state (e.g. breaking crops),
     * which triggers global behaviors like holding the attack key.
     */
    public boolean isFarmingState() {
        return defaultStateKeys(currentState) != null || stateKeys().containsKey(currentState);
    }

    private void interruptAttack() {
        continueAttack = false;
    }

    // -- State helpers ---------------------------------------------------------

    protected final void changeState(State newState) {
        State oldState = currentState;
        previousState = currentState;
        currentState  = newState;
        if (oldState != newState) {
            FastLaneSwitchManager.onStateChanged(oldState, newState);
        }
    }

    public State getCurrentState()  { return currentState;  }
    public State getPreviousState() { return previousState; }

    private boolean isYawSet()   { return yaw.isPresent();   }
    private boolean isPitchSet() { return pitch.isPresent(); }

    // -- Key-press helpers -----------------------------------------------------

    /**
     * Set movement keys to the requested state.  Any key NOT listed in the
     * parameters is released.
     *
     * @param left    strafe left  (A)
     * @param right   strafe right (D)
     * @param forward walk forward (W)
     * @param back    walk back    (S)
     * @param attack  left-click / break block
     * @param sprint  sprint modifier
     * @param sneak   shift / sneak
     */
    protected final void holdKeys(Minecraft mc,
                                  boolean left, boolean right,
                                  boolean forward, boolean back,
                                  boolean attack, boolean sprint,
                                  boolean sneak) {
        if (ClientUtils.isInventoryScreenOpen()) {
            ProgrammaticMovementTracker.set(mc.options.keyLeft, false);
            ProgrammaticMovementTracker.set(mc.options.keyRight, false);
            ProgrammaticMovementTracker.set(mc.options.keyUp, false);
            ProgrammaticMovementTracker.set(mc.options.keyDown, false);
            ProgrammaticMovementTracker.set(mc.options.keyShift, false);
            ProgrammaticMovementTracker.set(mc.options.keySprint, false);
            ProgrammaticMovementTracker.set(mc.options.keyJump, false);
            ProgrammaticAttackTracker.setHeld(mc.options.keyAttack, false);
            attackHeldByMacro = false;
            ClientUtils.setKeyMappingState(mc.options.keyAttack, false);
            ClientUtils.forceReleaseMovementKeys();
            return;
        }

        var opts = mc.options;
        boolean shouldAttack = attack && continueAttack;
        boolean wasAttackHeldByMacro = attackHeldByMacro;
        attackHeldByMacro = shouldAttack;
        ProgrammaticAttackTracker.setHeld(opts.keyAttack, shouldAttack);
        ProgrammaticMovementTracker.set(opts.keyLeft, left);
        ProgrammaticMovementTracker.set(opts.keyRight, right);
        ProgrammaticMovementTracker.set(opts.keyUp, forward);
        ProgrammaticMovementTracker.set(opts.keyDown, back);
        ProgrammaticMovementTracker.set(opts.keyShift, sneak);
        ProgrammaticMovementTracker.set(opts.keySprint, sprint);
        ProgrammaticMovementTracker.set(opts.keyJump, false);
        ClientUtils.setKeyMappingState(opts.keyLeft, left);
        ClientUtils.setKeyMappingState(opts.keyRight, right);
        ClientUtils.setKeyMappingState(opts.keyUp, forward);
        ClientUtils.setKeyMappingState(opts.keyDown, back);
        ClientUtils.setKeyMappingState(opts.keyShift, sneak);
        ClientUtils.setKeyMappingState(opts.keySprint, sprint);
        ClientUtils.setKeyMappingState(opts.keyAttack, shouldAttack);
        if (shouldAttack) {
            MixinMinecraft minecraft = (MixinMinecraft) mc;
            minecraft.aether$setMissTime(0);
            if (!wasAttackHeldByMacro) {
                minecraft.aether$startAttack();
            }
        }
    }

    /** Release every movement / action key. */
    protected final void releaseAll(Minecraft mc) {
        holdKeys(mc, false, false, false, false, false, false, false);
    }

    /** Stop movement while keeping attack held if the macro is still attacking. */
    protected final void stopMovementKeepAttack(Minecraft mc) {
        holdKeys(mc, false, false, false, false, true, false, false);
    }

    protected static boolean shouldIgnoreMovementStall(Minecraft mc) {
        return mc.player != null && FreecamManager.shouldSuppressMacroMovementDetection(mc.player);
    }

    protected static boolean shouldSuppressLaneChangeForDirt(Minecraft mc) {
        return FailsafeManager.isTouchingDirtBlock(mc);
    }

    /** Default orientation, applied only when the corresponding custom setting is unset. */
    public record DefaultAngle(float pitch, float yawOffset) {
        public static final DefaultAngle NONE = new DefaultAngle(Float.NaN, Float.NaN);
    }

    protected DefaultAngle defaultAngle() {
        return DefaultAngle.NONE;
    }

    /** Keys held for a state. Any combination is supported. */
    public record StateKeys(boolean left, boolean right, boolean forward, boolean back,
                            boolean attack, boolean sprint, boolean sneak) {
    }

    /** Optional per-state key bindings. Unbound cardinal states use the standard A/D/W/S mapping. */
    protected Map<State, StateKeys> stateKeys() {
        return Map.of();
    }

    private static final StateKeys LEFT_KEYS = new StateKeys(true, false, false, false, true, false, false);
    private static final StateKeys RIGHT_KEYS = new StateKeys(false, true, false, false, true, false, false);
    private static final StateKeys FORWARD_KEYS = new StateKeys(false, false, true, false, true, false, false);
    private static final StateKeys BACKWARD_KEYS = new StateKeys(false, false, false, true, true, false, false);
    private static final StateKeys SWITCHING_LANE_KEYS = new StateKeys(false, false, true, false, true, true, false);

    private static StateKeys defaultStateKeys(State state) {
        return switch (state) {
            case LEFT -> LEFT_KEYS;
            case RIGHT -> RIGHT_KEYS;
            case FORWARD -> FORWARD_KEYS;
            case BACKWARD -> BACKWARD_KEYS;
            case SWITCHING_LANE -> SWITCHING_LANE_KEYS;
            default -> null;
        };
    }

    private void applyDefaultOrientation(Minecraft mc) {
        DefaultAngle angle = defaultAngle();
        float configuredDefaultPitch = angle.pitch();
        if (!isPitchSet() && Float.isFinite(configuredDefaultPitch)) {
            pitch = Optional.of(configuredDefaultPitch);
        }
        float configuredDefaultYawOffset = angle.yawOffset();
        if (!isYawSet() && mc.player != null && Float.isFinite(configuredDefaultYawOffset)) {
            float nearest = Math.round(Mth.wrapDegrees(mc.player.getYRot()) / 90f) * 90f;
            yaw = Optional.of(Mth.wrapDegrees(nearest + configuredDefaultYawOffset));
        }
    }

    /** Starts the configured initial rotation after a subclass has chosen any defaults. */
    protected final void rotateToConfiguredOrientation(Minecraft mc) {
        if (mc.player == null || (yaw.isEmpty() && pitch.isEmpty())) {
            return;
        }
        RotationManager.rotateToYawPitch(mc,
                yaw.orElseGet(mc.player::getYRot),
                pitch.orElseGet(mc.player::getXRot),
                AetherConfig.ROTATION_TIME.get());
        rotated = true;
    }

	/**
	 * Reapplies the orientation selected when this macro was enabled.
	 *
	 * <p>
	 * This deliberately uses the cached yaw and pitch instead of reading the config
	 * again, so custom-angle humanization and the macro's default lane orientation
	 * remain the same after a temporary int.
	 *
	 * @return whether a configured orientation was available
	 */
	public final boolean restoreConfiguredOrientation(Minecraft mc) {
		if (mc.player == null || (yaw.isEmpty() && pitch.isEmpty())) {
			return false;
		}
		RotationManager.rotateToYawPitch(mc, 
			yaw.orElseGet(mc.player::getYRot), 
			pitch.orElseGet(mc.player::getXRot),
			AetherConfig.ROTATION_TIME.get(), true
		);
		return true;
	}
	
    /**
     * Reusable row/lane movement detector. It supports both a single world
     * axis and total X/Z movement, and deliberately ignores samples while
     * movement detection is suppressed (for example, during freecam).
     */
    protected static final class MovementStallTracker {
        private final double progressEpsilonSq;
        private final int threshold;
        private boolean initialized;
        private double previousX;
        private double previousZ;
        private int stalledTicks;

        private MovementStallTracker(double progressEpsilon, int threshold) {
            this.progressEpsilonSq = progressEpsilon * progressEpsilon;
            this.threshold = threshold;
        }

        public void resetHorizontal(Minecraft mc) {
            if (mc.player != null) {
                previousX = mc.player.getX();
                previousZ = mc.player.getZ();
                initialized = true;
            }
            stalledTicks = 0;
        }

        private void clear() {
            initialized = false;
            stalledTicks = 0;
        }

        public boolean hasStalledHorizontally(Minecraft mc, boolean forceStall) {
            if (mc.player == null) {
                return false;
            }
            if (!initialized) {
                resetHorizontal(mc);
                return false;
            }
            double dx = mc.player.getX() - previousX;
            double dz = mc.player.getZ() - previousZ;
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
            return recordSample(mc, dx * dx + dz * dz >= progressEpsilonSq, forceStall);
        }

        private boolean recordSample(Minecraft mc, boolean madeProgress, boolean forceStall) {
            if (shouldIgnoreMovementStall(mc) || (madeProgress && !forceStall)) {
                stalledTicks = 0;
                return false;
            }
            if (forceStall || ++stalledTicks >= threshold) {
                stalledTicks = 0;
                return true;
            }
            return false;
        }
    }

    /**
     * Ordered farming states that repeat indefinitely. The cycle owns only
     * generic timing and movement bookkeeping; a macro still chooses which
     * coordinate to monitor and how to press keys for each state.
     */
    protected static final class StateCycle {
        private final State[] states;
        private final MovementStallTracker movement;
        private int currentIndex;
        private int graceTicks;
        private boolean waitingAtStateEnd;

        private StateCycle(double progressEpsilon, int stallThreshold, State... states) {
            if (states.length == 0) {
                throw new IllegalArgumentException("A state cycle requires at least one state");
            }
            this.states = states.clone();
            this.movement = new MovementStallTracker(progressEpsilon, stallThreshold);
        }

        public void resetHorizontal(Minecraft mc) {
            movement.resetHorizontal(mc);
            graceTicks = 20;
            waitingAtStateEnd = false;
        }

        private void reset() {
            movement.clear();
            graceTicks = 20;
            waitingAtStateEnd = false;
            currentIndex = 0;
        }

        public boolean isWaitingAtStateEnd() {
            return waitingAtStateEnd;
        }

        private boolean contains(State state) {
            for (State candidate : states) {
                if (candidate == state) {
                    return true;
                }
            }
            return false;
        }

        private State firstOrCached() {
            Integer cachedStep = FarmingMacroManager.getCachedCycleStep();
            currentIndex = cachedStep == null ? 0 : Math.floorMod(cachedStep, states.length);
            return states[currentIndex];
        }

        private State next(State currentState) {
            if (states[currentIndex] != currentState) {
                select(currentState);
            }
            currentIndex = (currentIndex + 1) % states.length;
            return states[currentIndex];
        }

        private void select(State state) {
            for (int i = 0; i < states.length; i++) {
                if (states[i] == state) {
                    currentIndex = i;
                    return;
                }
            }
            currentIndex = 0;
        }
    }

    protected final StateCycle stateCycle(double progressEpsilon, int stallThreshold, State... states) {
        StateCycle cycle = new StateCycle(progressEpsilon, stallThreshold, states);
        stateCycles.add(cycle);
        if (defaultStateCycle == null) {
            defaultStateCycle = cycle;
        }
        return cycle;
    }

    /** Advances a cycle based on total horizontal X/Z movement. */
    protected final void updateHorizontalStateCycle(Minecraft mc, StateCycle cycle) {
        updateStateCycle(mc, cycle, cycle.movement.hasStalledHorizontally(mc,
                FastLaneSwitchManager.shouldFastSwitch(mc, currentState)), () -> cycle.movement.resetHorizontal(mc));
    }

    private void updateStateCycle(Minecraft mc, StateCycle cycle, boolean stalled, Runnable resetMovement) {
        if (mc.player == null) {
            return;
        }
        if (currentState == State.NONE) {
            changeAndSaveCycleState(cycle, cycle.firstOrCached());
            cycle.graceTicks = 10;
            resetMovement.run();
            return;
        }
        if (!cycle.contains(currentState)) {
            return;
        }
        if (shouldSuppressLaneChangeForDirt(mc)) {
            cycle.waitingAtStateEnd = false;
            cycle.graceTicks = 0;
            resetMovement.run();
            return;
        }
        if (cycle.graceTicks > 0) {
            cycle.graceTicks--;
            resetMovement.run();
            if (cycle.waitingAtStateEnd && cycle.graceTicks == 0) {
                cycle.waitingAtStateEnd = false;
                changeAndSaveCycleState(cycle, cycle.next(currentState));
                cycle.graceTicks = 10;
                resetMovement.run();
            }
            return;
        }
        if (!stalled) {
            return;
        }

        boolean fastLaneSwitch = FastLaneSwitchManager.shouldFastSwitch(mc, currentState);
        int delayMs = fastLaneSwitch ? 0 : ConfigHelpers.getRandomizedDelay(
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get());
        int delayTicks = (delayMs + 25) / 50;
        if (delayTicks > 0) {
            cycle.waitingAtStateEnd = true;
            cycle.graceTicks = delayTicks;
            return;
        }
        changeAndSaveCycleState(cycle, cycle.next(currentState));
        cycle.graceTicks = fastLaneSwitch ? 0 : 10;
        resetMovement.run();
    }

    private void changeAndSaveCycleState(StateCycle cycle, State state) {
        changeState(state);
        FarmingMacroManager.saveCycleStep(cycle.currentIndex);
    }

    // -- Block / walkability helpers -------------------------------------------

    /**
     * Returns {@code true} when the two-block-tall column at {@code base}
     * can be walked into (no solid blocks blocking head or feet).
     */
    protected static boolean isWalkable(Minecraft mc, BlockPos base) {
        if (mc.level == null) return false;
        return isPassable(mc, base) && isPassable(mc, base.above());
    }

    private static boolean isPassable(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        // Treat any collision as blocked so lane-end detection catches
        // doors, slabs, trapdoors, and other partial-height blockers.
        return state.getCollisionShape(mc.level, pos).isEmpty();
    }

    /**
     * Offset a block position by {@code strafeSteps} steps in the player's
     * strafe-left direction (the direction pressed when holding A).
     *
     * <p>Strafe-left movement vector: (cos(yaw deg), 0, sin(yaw deg)).
     */
    protected static BlockPos getStrafeLeftPos(BlockPos origin, float yaw, int strafeSteps) {
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(Math.cos(rad) * strafeSteps);
        int dz = (int) Math.round(Math.sin(rad) * strafeSteps);
        return origin.offset(dx, 0, dz);
    }

    /**
     * Offset a block position by {@code strafeSteps} steps in the player's
     * strafe-right direction (pressed when holding D).
     */
    protected static BlockPos getStrafeRightPos(BlockPos origin, float yaw, int strafeSteps) {
        return getStrafeLeftPos(origin, yaw, -strafeSteps);
    }

    /**
     * Offset a block position by {@code forwardSteps} steps in the player's
     * forward direction.  Forward movement vector: (-sin(yaw deg), 0, cos(yaw deg)).
     */
    protected static BlockPos getForwardPos(BlockPos origin, float yaw, int forwardSteps) {
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(rad) * forwardSteps);
        int dz = (int) Math.round( Math.cos(rad) * forwardSteps);
        return origin.offset(dx, 0, dz);
    }

    // Convenience directional walkability checks

    protected boolean isLeftWalkable(Minecraft mc) {
        return isWalkable(mc, getStrafeLeftPos(mc.player.blockPosition(), mc.player.getYRot(), 1));
    }

    protected boolean isRightWalkable(Minecraft mc) {
        return isWalkable(mc, getStrafeRightPos(mc.player.blockPosition(), mc.player.getYRot(), 1));
    }

    protected boolean isFrontWalkable(Minecraft mc) {
        return isWalkable(mc, getForwardPos(mc.player.blockPosition(), mc.player.getYRot(), 1));
    }

    protected boolean isBackWalkable(Minecraft mc) {
        return isWalkable(mc, getForwardPos(mc.player.blockPosition(), mc.player.getYRot(), -1));
    }

    /**
     * Scan horizontally for a wall and decide which direction (LEFT / RIGHT)
     * to start farming.  Returns {@link State#NONE} if undetermined.
     */
    public State calculateDirection(Minecraft mc) {
        if (mc.player == null) return State.NONE;

        float playerYaw = mc.player.getYRot();
        BlockPos origin = mc.player.blockPosition();

        for (int i = 1; i < 64; i++) {
            BlockPos rightCandidate = getStrafeRightPos(origin, playerYaw, i);
            BlockPos leftCandidate  = getStrafeLeftPos(origin, playerYaw, i);

            boolean rightBlocked = !isWalkable(mc, rightCandidate);
            boolean leftBlocked  = !isWalkable(mc, leftCandidate);

            if (rightBlocked && !leftBlocked) return State.LEFT;
            if (leftBlocked  && !rightBlocked) return State.RIGHT;
            // If both are blocked at this radius, keep scanning to avoid biasing
            // the result from a symmetric or noisy near-field reading.
        }

        // Stable fallback.
        if (currentState == State.LEFT || currentState == State.RIGHT) {
            return currentState;
        }
        if (previousState == State.LEFT || previousState == State.RIGHT) {
            return previousState;
        }

        // Default
        return State.LEFT;
    }
}
