package dev.aether.macro;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.farming.UngrabMouse;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.modules.visuals.FreecamManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.ProgrammaticAttackTracker;
import dev.aether.util.ProgrammaticMovementTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * Base class for all in-mod farming macros.
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
public abstract class AbstractMacro {
    private static final long GLOBAL_LANE_SWITCH_COOLDOWN_MS = 10_000L;

    // -- State -----------------------------------------------------------------

    public enum State {
        NONE,
        DROPPING,
        SWITCHING_LANE,
        LEFT,
        RIGHT,
        FORWARD,
        BACKWARD
    }

    public enum ChangeLaneDirection {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT
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

    /** Suppress drop detection until this timestamp (ms). Set after a plotTP to prevent false DROPPING state. */
    private volatile long suppressDropUntilMs = 0;
    /** False after a GUI interrupts farming; movement may continue, but left-click should not. */
    private boolean continueAttack = true;
    /** Tracks the macro-owned attack edge separately from Minecraft's raw key state. */
    private boolean attackHeldByMacro = false;

    public void suppressDropDetection(long durationMs) {
        suppressDropUntilMs = System.currentTimeMillis() + durationMs;
    }

    protected boolean isDropDetectionSuppressed() {
        return System.currentTimeMillis() < suppressDropUntilMs;
    }

    // -- Layer tracking --------------------------------------------------------

    /** Y-coordinate of the layer being farmed, used for drop-detection. */
    protected int layerY = 0;

    // -- Lane switching --------------------------------------------------------

    // -- Lane switching --------------------------------------------------------

    /** Direction chosen for the current lane switch. */
    protected ChangeLaneDirection changeLaneDirection = null;
    /** Player coordinate (X or Z) at the start of a lane switch; lag-back detection. */
    protected int previousWalkingCoord = 0;
    /** Global per-macro lockout after a completed lane switch. */
    private long laneSwitchCooldownUntilMs = 0L;

    // -- Lifecycle -------------------------------------------------------------

    /**
     * Called once when the macro is enabled.
     * Subclasses should call {@code super.onEnable(mc)} first, then set
     * additional rotation / state as needed.
     */
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

        if (mc.player != null) {
            layerY = mc.player.blockPosition().getY();
        }

        FarmingMacroManager.loadDirection();
        rotated = false;
        continueAttack = true;
        attackHeldByMacro = false;
        changeLaneDirection = null;
        laneSwitchCooldownUntilMs = 0L;
        FastLaneSwitchManager.resetRuntime();
        changeState(State.NONE);
    }

    /**
     * Called once when the macro is disabled.
     * Releases all movement keys and re-grabs the mouse.
     */
    public void onDisable(Minecraft mc) {
        releaseAll(mc);
        changeState(State.NONE);
        yaw   = Optional.empty();
        pitch = Optional.empty();
        rotated = false;
        continueAttack = false;
        attackHeldByMacro = false;
        changeLaneDirection = null;
        laneSwitchCooldownUntilMs = 0L;
        FastLaneSwitchManager.resetRuntime();
    }

    /**
     * Main per-tick entry point. Called from {@link FarmingMacroManager} on
     * every {@code END_CLIENT_TICK} while the macro is active.
     */
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
            // Press sneak to descend, stop horizontal movement
            holdKeys(mc, false, false, false, false, true, false, true);
            return;
        }

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
    public abstract void updateState(Minecraft mc);

    /**
     * Execute the physical actions (key presses, etc.) for {@link #currentState}.
     * Triggered every tick, immediately after {@link #updateState}.
     */
    public abstract void invokeState(Minecraft mc);

    /**
     * Returns true if the macro is currently in a "farming" state (e.g. breaking crops),
     * which triggers global behaviors like holding the attack key.
     */
    public boolean isFarmingState() {
        return currentState == State.LEFT || currentState == State.RIGHT ||
               currentState == State.FORWARD || currentState == State.BACKWARD;
    }

    public boolean shouldContinueAttack() {
        return continueAttack;
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

    public boolean isYawSet()   { return yaw.isPresent();   }
    public boolean isPitchSet() { return pitch.isPresent(); }
    public float   getYaw()     { return yaw.orElse(0f);    }
    public float   getPitch()   { return pitch.orElse(0f);  }

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
        boolean shouldAttack = attack && shouldContinueAttack();
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
        ClientUtils.setKeyMappingState(opts.keyAttack, shouldAttack);
        if (shouldAttack && !wasAttackHeldByMacro) {
            ClientUtils.clickKeyMapping(opts.keyAttack);
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

    protected final void rotateAfterDropIfConfigured(double droppedY) {
        int yawDelta = AetherConfig.MACRO_DROP_ROTATION_DEGREES.get();
        if (droppedY <= 1.5 || !AetherConfig.MACRO_ROTATE_ON_DROP.get() || yaw.isEmpty() || yawDelta == 0) {
            return;
        }

        float newYaw = Mth.wrapDegrees(getYaw() + yawDelta);
        yaw = Optional.of(newYaw);
        rotated = false;
    }

    protected final boolean isLaneSwitchOnCooldown() {
        return getLaneSwitchCooldownRemainingMs() > 0L;
    }

    protected final long getLaneSwitchCooldownRemainingMs() {
        return Math.max(0L, laneSwitchCooldownUntilMs - System.currentTimeMillis());
    }

    protected final int getLaneSwitchCooldownRemainingTicks() {
        long remainingMs = getLaneSwitchCooldownRemainingMs();
        return remainingMs <= 0L ? 0 : (int) ((remainingMs + 49L) / 50L);
    }

    protected final void markLaneSwitchComplete() {
        laneSwitchCooldownUntilMs = System.currentTimeMillis() + GLOBAL_LANE_SWITCH_COOLDOWN_MS;
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
