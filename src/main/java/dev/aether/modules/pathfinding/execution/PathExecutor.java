package dev.aether.modules.pathfinding.execution;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.Node.MoveType;
import dev.aether.modules.pathfinding.rotation.AngleUtils;
import dev.aether.modules.pathfinding.rotation.EasingType;
import dev.aether.modules.pathfinding.rotation.Rotation;
import dev.aether.modules.pathfinding.rotation.RotationExecutor;
import dev.aether.modules.pathfinding.rotation.strategy.TimedEaseStrategy;
import net.minecraft.util.Mth;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * State machine that walks a player along a pre-computed path using keyboard injection.
 * Includes jump handling and automatic stall detection.
 */
public final class PathExecutor {

    public enum State { IDLE, WALKING, REPLANNING, FINISHED, FAILED }

    // Stall detection thresholds
    private static final double STUCK_DIST_THRESHOLD = 0.2;
    private static final long   STUCK_TIME_MS        = 2000;
    private static final double DRIFT_DISTANCE       = 4.5;
    private static final long   REPLAN_COOLDOWN_MS   = 5000;

    // Jump triggers
    private static final double JUMP_TRIGGER_DIST      = 0.6;
    private static final double STEP_UP_TRIGGER_DIST   = 1.0;
    private static final int    JUMP_COOLDOWN_TICKS = 8;
    private static final long   STALL_JUMP_PROGRESS_MS = 450;

    // Anti-unstuck escalation
    private static final long   UNSTUCK_JUMP_MS    = 1200;  // try jumping after 1.2s
    private static final long   UNSTUCK_BACKUP_MS  = 2200;  // try backing up after 2.2s
    private static final int    BACKUP_TICKS       = 8;      // back up for 8 ticks
    private static final long   PATH_REPLAN_STALE_MS = 1400;
    private static final double PATH_REPLAN_DRIFT_DISTANCE = 1.75;
    private static final double PATH_PROGRESS_EPSILON = 0.08;
    private static final long   PATH_REPLAN_HARD_STALE_MS = 4200;

    private static final double WALK_TARGET_DEADZONE = 0.12;
    private static final double WALK_FORWARD_DOT     = 0.18;
    private static final double WALK_BACKWARD_DOT    = -0.45;
    private static final double WALK_STRAFE_DOT      = 0.18;

    private static final long   COAST_TIMEOUT_MS    = 3000;
    private static final int    CAMERA_LOOKAHEAD    = 32; // legacy nav-node camera lookahead
    /** Max perpendicular deviation (blocks) allowed from direct player->candidate line. */
    private static final double CAM_MAX_LATERAL_DEV = 2.5;

    // Reversible switch: set false to restore legacy keynode-driven camera targeting.
    private static final boolean USE_CAMERA_RAIL = true;
    private static final double  CAMERA_RAIL_REACHED_DIST = 1.15;
    private static final double  CAMERA_RAIL_MAX_STEP_DIST = 3.0;
    private static final double  LEGACY_CAMERA_EYE_Y = 1.6;
    private static final double  CAMERA_RAIL_EYE_Y = 1.52;
    private static final double  CAMERA_RAIL_GUIDE_LOOKAHEAD_DIST = 3.5;
    // Density controls: raise DENSITY_SCALE for more rail points, lower for fewer.
    private static final double  CAMERA_RAIL_DENSITY_SCALE = 0.1;
    private static final int     CAMERA_RAIL_MAX_STEPS_PER_SEGMENT = 18;
    private static final double  CAMERA_RAIL_MIN_POINT_SPACING = 0.44;
    private static final double  CAMERA_RAIL_MIN_HORIZONTAL_SPACING = 0.16;
    private static final float   CAMERA_RAIL_MAX_TURN_DEG = 14.0f;

    /** Direct goal proximity check - if player is this close horizontally, finish. */
    private static final double GOAL_REACHED_HDIST = 1.2;
    private static final double GOAL_REACHED_VDIST = 2.0;
    private static final double STRICT_GOAL_REACHED_VDIST = 0.75;

    private State      state = State.IDLE;
    private List<Node> path;
    private int        pursuitSegment;
    private boolean    precise;

    private Vec3 lastPos         = Vec3.ZERO;
    private long lastProgressTime;
    private long lastReplanTime  = 0;
    private long coastStartTime  = 0;
    private int  jumpCooldown    = 0;
    private int  goalX, goalY, goalZ;
    private double goalCenterX   = 0.5;
    private double goalCenterZ   = 0.5;
    private Entity rotationTarget;
    private Vec3 lookTargetPos   = null;
    private boolean allowReplan  = true;
    private boolean allowJumps   = true;
    private boolean allowRotation = true;
    private boolean exactGoalCentering = false;
    private boolean strictGoalCompletion = false;
    private double stickySneakDistance = -1.0;
    private boolean sneakLatched = false;
    private double preciseGoalTolerance = 0.5;
    private Runnable onFinished;
    private int  debugTick       = 0;
    private int  camTargetIdx    = -1;
    private int  backupTicksLeft = 0;

    private List<Vec3> cameraPath = Collections.emptyList();
    private int        cameraIndex = 0;
    private Vec3       lastCameraCheckPos = null;
    private double     bestPathProgress = Double.NEGATIVE_INFINITY;
    private long       lastPathProgressTime = 0;

    // --- Public API ----------------------------------------------------------

    public void start(List<Node> path, int goalX, int goalY, int goalZ, boolean precise) {
        start(path, goalX, goalY, goalZ, precise, null);
    }

    public void start(List<Node> path, int goalX, int goalY, int goalZ, boolean precise, Entity rotationTarget) {
        start(path, goalX, goalY, goalZ, precise, rotationTarget, null);
    }

    public void start(List<Node> path, int goalX, int goalY, int goalZ, boolean precise,
                      Entity rotationTarget, Runnable onFinished) {
        this.path             = path;
        this.goalX            = goalX;
        this.goalY            = goalY;
        this.goalZ            = goalZ;
        this.goalCenterX      = 0.5;
        this.goalCenterZ      = 0.5;
        this.precise          = precise;
        this.pursuitSegment   = 0;
        this.rotationTarget   = rotationTarget;
        this.lookTargetPos    = null;
        this.allowReplan      = true;
        this.allowJumps       = true;
        this.allowRotation    = true;
        this.exactGoalCentering = false;
        this.strictGoalCompletion = false;
        this.stickySneakDistance = -1.0;
        this.preciseGoalTolerance = 0.5;
        this.onFinished       = onFinished;
        this.jumpCooldown     = 0;
        this.coastStartTime   = 0;
        this.lastProgressTime = System.currentTimeMillis();
        this.camTargetIdx     = -1;
        this.backupTicksLeft  = 0;
        this.cameraPath         = USE_CAMERA_RAIL ? buildCameraRail(path) : Collections.emptyList();
        this.cameraIndex        = 0;
        this.lastCameraCheckPos = null;
        this.bestPathProgress   = Double.NEGATIVE_INFINITY;
        this.lastPathProgressTime = this.lastProgressTime;
        state = State.WALKING;
    }

    public void setLookTarget(Vec3 lookTargetPos) {
        this.lookTargetPos = lookTargetPos;
    }

    public void setAllowReplan(boolean allowReplan) {
        this.allowReplan = allowReplan;
    }

    public void setPreciseGoalTolerance(double preciseGoalTolerance) {
        this.preciseGoalTolerance = Math.max(0.01, preciseGoalTolerance);
    }

    public void setAllowJumps(boolean allowJumps) {
        this.allowJumps = allowJumps;
    }

    public void setAllowRotation(boolean allowRotation) {
        this.allowRotation = allowRotation;
    }

    public void setExactGoalCentering(boolean exactGoalCentering) {
        this.exactGoalCentering = exactGoalCentering;
    }

    public void setStrictGoalCompletion(boolean strictGoalCompletion) {
        this.strictGoalCompletion = strictGoalCompletion;
    }

    public void setStickySneakDistance(double stickySneakDistance) {
        this.stickySneakDistance = stickySneakDistance;
    }

    public void setSneakLatched(boolean sneakLatched) {
        this.sneakLatched = sneakLatched;
    }

    public void setGoalCenterOffsets(double goalCenterX, double goalCenterZ) {
        this.goalCenterX = goalCenterX;
        this.goalCenterZ = goalCenterZ;
    }

    public boolean isSneakLatched() {
        return sneakLatched;
    }

    public State getState() { return state; }
    public int getWaypointIndex() { return pursuitSegment; }
    public int getCamTargetIdx()  { return camTargetIdx; }
    public List<Vec3> getCameraPath() { return cameraPath; }
    public int getCameraIndex() { return cameraIndex; }

    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        if (state != State.WALKING && state != State.REPLANNING) return;
        if (ClientUtils.isInventoryScreenOpen()) {
            releaseAll(mc);
            return;
        }

        // -- Unfly guard: hold sneak to exit flight before walking ----------
        if (mc.player.getAbilities().flying) {
            releaseAll(mc);
            ClientUtils.setKeyMappingState(mc.options.keyShift, true);
            return;
        }

        jumpCooldown = Math.max(0, jumpCooldown - 1);

        Vec3 playerPos = mc.player.position();

        if (path == null || path.isEmpty()) {
            finish(mc);
            return;
        }

        // -- Direct goal proximity check -------------------------------------
        // Catches cases where waypoint advancement gets stuck but player is at the goal.
        {
            double gx = goalX + goalCenterX, gz = goalZ + goalCenterZ;
            double gdx = gx - playerPos.x, gdz = gz - playerPos.z;
            double gHDist = Math.sqrt(gdx * gdx + gdz * gdz);
            double gVDist = Math.abs(goalY - playerPos.y);
            boolean reachedGoal = strictGoalCompletion
                    ? gHDist <= preciseGoalTolerance && gVDist <= STRICT_GOAL_REACHED_VDIST
                    : gHDist <= GOAL_REACHED_HDIST && gVDist <= GOAL_REACHED_VDIST;
            if (reachedGoal) {
                finish(mc);
                return;
            }
        }

        // -- Movement progress tracking -------------------------------------
        double distMoved = playerPos.distanceTo(lastPos);
        if (distMoved >= STUCK_DIST_THRESHOLD) {
            lastProgressTime = System.currentTimeMillis();
        }
        lastPos = playerPos;

        // -- Pure Pursuit: advance pursuitSegment monotonically ------------
        // Player projection onto current segment; advance when t >= 1.0 (player past end).
        long now = System.currentTimeMillis();
        while (pursuitSegment + 1 < path.size()) {
            double ax = path.get(pursuitSegment).position.centeredX();
            double az = path.get(pursuitSegment).position.centeredZ();
            double bx = path.get(pursuitSegment + 1).position.centeredX();
            double bz = path.get(pursuitSegment + 1).position.centeredZ();
            double dx = bx - ax, dz = bz - az;
            double lenSq = dx * dx + dz * dz;
            if (lenSq < 1e-6) { pursuitSegment++; lastProgressTime = now; continue; }
            double t = ((playerPos.x - ax) * dx + (playerPos.z - az) * dz) / lenSq;
            if (t < 1.0) break;
            pursuitSegment++;
            lastProgressTime = now;
        }

        double pathProgress = computePathProgress(playerPos);
        if (pathProgress > bestPathProgress + PATH_PROGRESS_EPSILON) {
            bestPathProgress = pathProgress;
            lastPathProgressTime = now;
        }

        // Periodic debug
        if (AetherConfig.SHOW_DEBUG.get() && ++debugTick % 40 == 0 && pursuitSegment < path.size()) {
            Node wp   = path.get(pursuitSegment);
            double dx = wp.position.centeredX() - playerPos.x;
            double dz = wp.position.centeredZ() - playerPos.z;
            double hDist = Math.sqrt(dx * dx + dz * dz);
            ClientUtils.sendDebugMessage(String.format(
                    "path seg=%d/%d hDist=%.2f",
                    pursuitSegment, path.size(), hDist));
        }

        // -- Goal reached / coasting ----------------------------------------
        if (pursuitSegment >= path.size() - 1) {
            if (!precise) {
                // Approximate mode: release all keys immediately
                finish(mc);
                return;
            }

            // Precise mode: try to reach the exact block center horizontally.
            if (coastStartTime == 0) coastStartTime = System.currentTimeMillis();
            double dx = (goalX + goalCenterX) - playerPos.x;
            double dz = (goalZ + goalCenterZ) - playerPos.z;
            double hDist = Math.sqrt(dx * dx + dz * dz);

            if (exactGoalCentering && hDist > preciseGoalTolerance) {
                applyGoalCenteringMovement(mc, dx, dz);
            } else {
                releaseAll(mc);
            }

            if (hDist <= preciseGoalTolerance) {
                finish(mc);
            } else if (System.currentTimeMillis() - coastStartTime > COAST_TIMEOUT_MS) {
                if (strictGoalCompletion) {
                    fail(mc);
                } else {
                    finish(mc);
                }
            }
            return;
        }

        // -- Stall + drift detection with escalating recovery ----------------
        long    staleDuration = now - lastProgressTime;
        long    pathStaleDuration = now - lastPathProgressTime;
        boolean progressStale = staleDuration > STUCK_TIME_MS;
        boolean pathProgressStale = pathStaleDuration > PATH_REPLAN_STALE_MS;
        double  hDistToPath   = minHorizDistToPath(playerPos);
        boolean drifted       = hDistToPath > DRIFT_DISTANCE;
        boolean cooldownPassed = now - lastReplanTime > REPLAN_COOLDOWN_MS;

        if (allowReplan && pathStaleDuration > PATH_REPLAN_HARD_STALE_MS && cooldownPassed) {
            System.out.printf("[Aether] PathExecutor replan: no path progress, stale=%d ms%n",
                    pathStaleDuration);
            triggerReplan(mc);
            return;
        }

        // Handle backup movement if active
        if (backupTicksLeft > 0) {
            backupTicksLeft--;
            ClientUtils.setKeyMappingState(mc.options.keyUp, false);
            ClientUtils.setKeyMappingState(mc.options.keyDown, true);
            ClientUtils.setKeyMappingState(mc.options.keyLeft, false);
            ClientUtils.setKeyMappingState(mc.options.keyRight, false);
            ClientUtils.setKeyMappingState(mc.options.keyJump, false);
            ClientUtils.setKeyMappingState(mc.options.keySprint, false);
            if (backupTicksLeft == 0) {
                ClientUtils.setKeyMappingState(mc.options.keyDown, false);
            }
            return;
        }

        // Escalating unstuck: jump -> backup -> replan
        boolean recoveryJump = false;
        if (staleDuration > UNSTUCK_BACKUP_MS && !drifted) {
            // Try backing up briefly to unstick from block edges
            if (backupTicksLeft == 0 && mc.player.onGround()) {
                backupTicksLeft = BACKUP_TICKS;
                lastProgressTime = now; // reset stale timer after recovery action
                return;
            }
        } else if (staleDuration > UNSTUCK_JUMP_MS && !drifted) {
            // Try a jump to get unstuck from block edges
            if (mc.player.onGround() && jumpCooldown == 0) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, true);
                jumpCooldown = JUMP_COOLDOWN_TICKS;
                recoveryJump = true;
            }
        }

        if (allowReplan && pathProgressStale && cooldownPassed
                && (hDistToPath > PATH_REPLAN_DRIFT_DISTANCE || distMoved >= STUCK_DIST_THRESHOLD)) {
            System.out.printf("[Aether] PathExecutor replan: no path progress, drift=%.2f blk, stale=%d ms%n",
                    hDistToPath, pathStaleDuration);
            triggerReplan(mc);
            return;
        }

        if (allowReplan && progressStale && drifted && cooldownPassed) {
            System.out.printf("[Aether] PathExecutor replan: drift=%.2f blk, stale=%d ms%n",
                    hDistToPath, staleDuration);
            triggerReplan(mc);
            return;
        }

        // Replan if stuck for too long even without drift (true deadlock)
        if (staleDuration > STUCK_TIME_MS * 2 && cooldownPassed) {
            System.out.printf("[Aether] PathExecutor replan: deadlock, stale=%d ms%n", staleDuration);
            triggerReplan(mc);
            return;
        }

        // -- Rotation: look at best visible waypoint ahead -------------
        if (allowRotation) {
            int camTarget;
            Vec3 rotTargetPos;
            if (USE_CAMERA_RAIL && !cameraPath.isEmpty()) {
                camTarget = pickCameraRailTarget(playerPos);
                rotTargetPos = getCameraRailGuideTarget(playerPos, camTarget);
                camTargetIdx = Math.min(cameraPath.size() - 1, camTarget + 1);
            } else {
                camTarget = pickLegacyCamTarget(mc, playerPos);
                camTargetIdx = camTarget;
                Node rotTarget = path.get(camTarget);
                rotTargetPos = new Vec3(
                        rotTarget.position.centeredX(),
                        rotTarget.position.flooredY() + LEGACY_CAMERA_EYE_Y,
                        rotTarget.position.centeredZ());
            }

            Rotation desiredRot = AngleUtils.getRotation(rotTargetPos);
            float yawDrift = Math.abs(AngleUtils.getRotationDelta(
                    RotationExecutor.getTargetYaw(), desiredRot.yaw));
            float pitchDrift = Math.abs(AngleUtils.getRotationDelta(
                    RotationExecutor.getTargetPitch(), desiredRot.pitch));

            if (yawDrift > 1.5f || pitchDrift > 2.5f) {
                float turnMagnitude = Math.max(yawDrift, pitchDrift);
                long durationMs = Math.round(Math.min(900f, 420f + turnMagnitude * 3f))
                        + ThreadLocalRandom.current().nextLong(80L);
                EasingType easing;
                if (USE_CAMERA_RAIL && !cameraPath.isEmpty()) {
                    easing = EasingType.EASE_OUT_CUBIC;
                } else {
                    float difficulty = computePathDifficulty(mc);
                    easing = difficulty < 0.4f ? EasingType.EASE_OUT_BACK : EasingType.EASE_OUT_CUBIC;
                }
                RotationExecutor.rotateTo(desiredRot, new TimedEaseStrategy(easing, durationMs));
            }
        }

        // -- Distance to final goal -----------------------------------------
        double finalDx = (goalX + goalCenterX) - playerPos.x;
        double finalDy = goalY         - playerPos.y;
        double finalDz = (goalZ + goalCenterZ) - playerPos.z;
        double distToFinal = Math.sqrt(finalDx * finalDx + finalDy * finalDy + finalDz * finalDz);

        if (!sneakLatched && stickySneakDistance > 0 && distToFinal <= stickySneakDistance) {
            sneakLatched = true;
        }

        // -- Movement key injection ----------------------------------------
        Node movementWp = getMovementWaypoint();
        double dxWp = movementWp.position.centeredX() - playerPos.x;
        double dzWp = movementWp.position.centeredZ() - playerPos.z;
        boolean nearStepUp = movementWp.moveType == MoveType.STEP_UP
                && Math.sqrt(dxWp * dxWp + dzWp * dzWp) < 2.0;

        applyPathMovement(mc, playerPos, movementWp, distToFinal, nearStepUp);

        // -- Jump handling --------------------------------------------------
        if (allowJumps) {
            if (!recoveryJump) {
                handleJumps(mc, movementWp, playerPos);
            }
        } else {
            ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        }

        // -- Swim handling --------------------------------------------------
        if (allowJumps && mc.player.isInWater()) {
            if (movementWp.position.flooredY() > mc.player.getBlockY()) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, true);
            }
        }

        // -- Ladder handling ------------------------------------------------
        if (allowJumps && isOnClimbable(mc)) {
            ClientUtils.setKeyMappingState(mc.options.keyUp, true);
            ClientUtils.setKeyMappingState(mc.options.keyDown, false);
            if (movementWp.position.flooredY() > mc.player.getBlockY()) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, true);
            } else if (movementWp.position.flooredY() < mc.player.getBlockY()) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, false);
                ClientUtils.setKeyMappingState(mc.options.keyShift, true);
            }
        } else {
            if (!sneakLatched) {
                ClientUtils.setKeyMappingState(mc.options.keyShift, false);
            }
        }

        if (sneakLatched) {
            ClientUtils.setKeyMappingState(mc.options.keyShift, true);
        }
    }

    public void stop(Minecraft mc) {
        state = State.IDLE;
        RotationExecutor.stopRotating();
        releaseAll(mc);
    }

    public void releaseAll(Minecraft mc) {
        if (mc.options == null) return;
        ClientUtils.setKeyMappingState(mc.options.keyUp, false);
        ClientUtils.setKeyMappingState(mc.options.keyDown, false);
        ClientUtils.setKeyMappingState(mc.options.keyLeft, false);
        ClientUtils.setKeyMappingState(mc.options.keyRight, false);
        ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        ClientUtils.setKeyMappingState(mc.options.keySprint, false);
        ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLatched);
    }

    // --- Internal helpers ----------------------------------------------------

    private Node getMovementWaypoint() {
        int targetIdx = Math.min(path.size() - 1, pursuitSegment + 1);
        return path.get(targetIdx);
    }

    private void applyPathMovement(Minecraft mc, Vec3 playerPos, Node targetWp,
                                   double distToFinal, boolean nearStepUp) {
        if (mc.player == null) {
            return;
        }

        double dx = targetWp.position.centeredX() - playerPos.x;
        double dz = targetWp.position.centeredZ() - playerPos.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        if (hDist < WALK_TARGET_DEADZONE && pursuitSegment + 2 < path.size()) {
            targetWp = path.get(pursuitSegment + 2);
            dx = targetWp.position.centeredX() - playerPos.x;
            dz = targetWp.position.centeredZ() - playerPos.z;
            hDist = Math.sqrt(dx * dx + dz * dz);
        }

        if (hDist < WALK_TARGET_DEADZONE) {
            ClientUtils.setKeyMappingState(mc.options.keyUp, false);
            ClientUtils.setKeyMappingState(mc.options.keyDown, false);
            ClientUtils.setKeyMappingState(mc.options.keyLeft, false);
            ClientUtils.setKeyMappingState(mc.options.keyRight, false);
            ClientUtils.setKeyMappingState(mc.options.keySprint, false);
            return;
        }

        double desiredX = dx / hDist;
        double desiredZ = dz / hDist;
        float yawRad = (float) Math.toRadians(mc.player.getYRot());
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = -Math.sin(yawRad + Math.PI / 2.0);
        double rightZ = Math.cos(yawRad + Math.PI / 2.0);

        double forwardDot = desiredX * forwardX + desiredZ * forwardZ;
        double strafeDot = desiredX * rightX + desiredZ * rightZ;

        boolean forward = forwardDot > WALK_FORWARD_DOT;
        boolean backward = forwardDot < WALK_BACKWARD_DOT;
        boolean right = strafeDot > WALK_STRAFE_DOT;
        boolean left = strafeDot < -WALK_STRAFE_DOT;

        ClientUtils.setKeyMappingState(mc.options.keyUp, forward);
        ClientUtils.setKeyMappingState(mc.options.keyDown, backward);
        ClientUtils.setKeyMappingState(mc.options.keyRight, right);
        ClientUtils.setKeyMappingState(mc.options.keyLeft, left);
        ClientUtils.setKeyMappingState(mc.options.keySprint, forward && !backward && distToFinal > 2.0 && !nearStepUp);
    }

    private void handleJumps(Minecraft mc, Node wp, Vec3 playerPos) {
        MoveType type = wp.moveType;
        boolean partialSupportAscent = isPartialSupportWaypoint(mc, wp);

        // Detect if we're in a stair sequence (next 2-3 waypoints are all partial-support ascents).
        // If so, suppress ALL jumping to let the player walk stairs smoothly.
        boolean inStairSequence = isInStairSequence(mc);

        if (type == MoveType.STEP_UP) {
            // Stair/slab ascents should be walked smoothly; explicit jump causes rare pop-ups.
            if (partialSupportAscent || inStairSequence) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, false);
                return;
            }
            if (!mc.player.onGround()) return;
            if (jumpCooldown > 0) return;
            double dx = wp.position.centeredX() - playerPos.x;
            double dz = wp.position.centeredZ() - playerPos.z;
            if (Math.sqrt(dx * dx + dz * dz) < STEP_UP_TRIGGER_DIST) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, true);
                jumpCooldown = JUMP_COOLDOWN_TICKS;
            } else {
                ClientUtils.setKeyMappingState(mc.options.keyJump, false);
            }
        } else if (type == MoveType.JUMP) {
            if (!mc.player.onGround()) return;
            if (jumpCooldown > 0) return;
            double dx = wp.position.centeredX() - playerPos.x;
            double dz = wp.position.centeredZ() - playerPos.z;
            if (Math.sqrt(dx * dx + dz * dz) < JUMP_TRIGGER_DIST) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, true);
                jumpCooldown = JUMP_COOLDOWN_TICKS;
            }
        } else if (type == MoveType.PARKOUR) {
            if (!mc.player.onGround()) return;
            if (jumpCooldown > 0) return;
            double dx = wp.position.centeredX() - playerPos.x;
            double dz = wp.position.centeredZ() - playerPos.z;
            if (Math.sqrt(dx * dx + dz * dz) < 1.2) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, true);
                jumpCooldown = JUMP_COOLDOWN_TICKS;
            }
        } else {
            // Non-jump move types - only jump when truly necessary
            if (mc.player.onGround() && jumpCooldown == 0) {
                double dx = wp.position.centeredX() - playerPos.x;
                double dz = wp.position.centeredZ() - playerPos.z;
                // Use actual float Y (not getBlockY) so slab surfaces (feet at N+0.5) don't
                // inflate dy by 0.5 and falsely trigger a jump toward the next waypoint.
                double dy = wp.position.flooredY() - mc.player.getY();
                double hDist = Math.sqrt(dx * dx + dz * dz);
                boolean stalled = System.currentTimeMillis() - lastProgressTime > STALL_JUMP_PROGRESS_MS;

                // Don't jump if we're in a stair sequence or the waypoint is on partial support.
                // Threshold 0.6: a full-block step from flat ground gives dy=1.0; a slab step from
                // a slab surface gives dy~=0.5 - below the threshold so no spurious jump.
                boolean wpAboveWithGap = dy > 0.6 && hDist < 2.5
                        && !partialSupportAscent && !inStairSequence;

                if ((stalled && hDist < 1.3 && !inStairSequence) || wpAboveWithGap) {
                    ClientUtils.setKeyMappingState(mc.options.keyJump, true);
                    jumpCooldown = JUMP_COOLDOWN_TICKS;
                } else {
                    ClientUtils.setKeyMappingState(mc.options.keyJump, false);
                }
            }
        }
    }

    /**
     * Returns true if the next 2+ waypoints form a stair sequence
     * (ascending or descending with partial support blocks).
     */
    private boolean isInStairSequence(Minecraft mc) {
        if (mc.level == null) return false;
        int stairCount = 0;
        int checkEnd = Math.min(path.size(), pursuitSegment + 3);
        for (int i = pursuitSegment; i < checkEnd; i++) {
            Node wp = path.get(i);
            if (wp.moveType == MoveType.STEP_UP || wp.moveType == MoveType.FALL) {
                if (isPartialSupportWaypoint(mc, wp)) {
                    stairCount++;
                }
            }
            // Also detect descent on stairs: Y decreases with partial support below
            if (i > 0 && wp.position.flooredY() < path.get(i - 1).position.flooredY()) {
                BlockPos belowWp = new BlockPos(
                        wp.position.flooredX(),
                        wp.position.flooredY() - 1,
                        wp.position.flooredZ());
                var belowState = mc.level.getBlockState(belowWp);
                if (!belowState.isAir() && !belowState.isCollisionShapeFullBlock(mc.level, belowWp)) {
                    stairCount++;
                }
            }
        }
        return stairCount >= 2;
    }

    private boolean isPartialSupportWaypoint(Minecraft mc, Node wp) {
        if (mc.level == null) return false;
        BlockPos support = new BlockPos(
                wp.position.flooredX(),
                wp.position.flooredY() - 1,
                wp.position.flooredZ());
        var supportState = mc.level.getBlockState(support);
        var shape = supportState.getCollisionShape(mc.level, support);
        if (shape.isEmpty()) return false;
        return !supportState.isCollisionShapeFullBlock(mc.level, support);
    }

    private double minHorizDistToPath(Vec3 pos) {
        double minSq = Double.MAX_VALUE;
        int start = Math.max(0, pursuitSegment - 2);
        int end   = Math.min(path.size(), pursuitSegment + 5);
        for (int i = start; i < end; i++) {
            Node n  = path.get(i);
            double dx = n.position.centeredX() - pos.x;
            double dz = n.position.centeredZ() - pos.z;
            double dSq = dx * dx + dz * dz;
            if (dSq < minSq) minSq = dSq;
        }
        return Math.sqrt(minSq);
    }

    /**
     * Returns true if all intermediate path nodes between fromIdx (exclusive) and toIdx
     * (exclusive) lie within CAM_MAX_LATERAL_DEV blocks of the straight line from
     * (px, pz) to path[toIdx].
     */
    private boolean isStraightLineSafe(List<Node> path, int fromIdx, int toIdx,
                                       double px, double pz) {
        Node to   = path.get(toIdx);
        double dx = to.position.centeredX() - px;
        double dz = to.position.centeredZ() - pz;
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 0.001) return true;
        double maxPerpSq = CAM_MAX_LATERAL_DEV * CAM_MAX_LATERAL_DEV;
        for (int j = fromIdx; j < toIdx; j++) {
            Node n    = path.get(j);
            double nx = n.position.centeredX() - px;
            double nz = n.position.centeredZ() - pz;
            double cross = nx * dz - nz * dx; // 2D cross product
            if ((cross * cross) / lenSq > maxPerpSq) return false;
        }
        return true;
    }

    /** Returns true if any node between from and to (inclusive) has a different Y than path[from]. */
    private boolean hasYChangeBetween(List<Node> path, int from, int to) {
        int baseY = path.get(from).position.flooredY();
        for (int i = from + 1; i <= to; i++) {
            if (path.get(i).position.flooredY() != baseY) return true;
        }
        return false;
    }

    /** Returns total absolute turning angle (degrees) between path[from] and path[to]. */
    private double cumulativeTurning(List<Node> path, int from, int to) {
        if (to - from < 2) return 0;
        double totalAngle = 0;
        for (int i = from + 1; i < to; i++) {
            Node prev = path.get(i - 1);
            Node cur  = path.get(i);
            Node next = path.get(i + 1 < path.size() ? i + 1 : i);
            double dx1 = cur.position.centeredX() - prev.position.centeredX();
            double dz1 = cur.position.centeredZ() - prev.position.centeredZ();
            double dx2 = next.position.centeredX() - cur.position.centeredX();
            double dz2 = next.position.centeredZ() - cur.position.centeredZ();
            double len1 = Math.sqrt(dx1 * dx1 + dz1 * dz1);
            double len2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);
            if (len1 > 0.001 && len2 > 0.001) {
                double dot = (dx1 / len1) * (dx2 / len2) + (dz1 / len1) * (dz2 / len2);
                totalAngle += Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
            }
        }
        return totalAngle;
    }

    private static boolean isWaypointVisible(Minecraft mc, Node wp) {
        if (mc.player == null) return false;
        Vec3 target = new Vec3(
                wp.position.centeredX(),
                wp.position.flooredY() + 1.0,
                wp.position.centeredZ());
        return ClientUtils.hasLineOfSight(mc.player, target);
    }

    private void triggerReplan(Minecraft mc) {
        releaseAll(mc);
        lastReplanTime = System.currentTimeMillis();
        state = State.REPLANNING;
    }

    private double computePathProgress(Vec3 playerPos) {
        if (path == null || path.isEmpty()) {
            return 0.0;
        }
        if (pursuitSegment + 1 >= path.size()) {
            return path.size() - 1;
        }

        Node from = path.get(pursuitSegment);
        Node to = path.get(pursuitSegment + 1);
        double dx = to.position.centeredX() - from.position.centeredX();
        double dz = to.position.centeredZ() - from.position.centeredZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1.0e-6) {
            return pursuitSegment;
        }

        double px = playerPos.x - from.position.centeredX();
        double pz = playerPos.z - from.position.centeredZ();
        double t = (px * dx + pz * dz) / lenSq;
        return pursuitSegment + Math.max(0.0, Math.min(0.999, t));
    }

    private void applyGoalCenteringMovement(Minecraft mc, double dx, double dz) {
        if (mc.player == null) {
            return;
        }

        float yawToGoal = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float relYaw = Mth.wrapDegrees(yawToGoal - mc.player.getYRot());

        ClientUtils.setKeyMappingState(mc.options.keyUp, relYaw > -67.5 && relYaw <= 67.5);
        ClientUtils.setKeyMappingState(mc.options.keyDown, relYaw > 112.5 || relYaw <= -112.5);
        ClientUtils.setKeyMappingState(mc.options.keyLeft, relYaw > -157.5 && relYaw <= -22.5);
        ClientUtils.setKeyMappingState(mc.options.keyRight, relYaw > 22.5 && relYaw <= 157.5);
        ClientUtils.setKeyMappingState(mc.options.keySprint, false);
        ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        ClientUtils.setKeyMappingState(mc.options.keyShift, true);
    }

    private void finish(Minecraft mc) {
        RotationExecutor.stopRotating();
        releaseAll(mc);
        state = State.FINISHED;
        if (onFinished != null) {
            onFinished.run();
        }
    }

    private void fail(Minecraft mc) {
        RotationExecutor.stopRotating();
        releaseAll(mc);
        state = State.FAILED;
    }

    private static boolean isOnClimbable(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;
        BlockPos pos = mc.player.blockPosition();
        return mc.level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    /**
     * Returns [0,1] representing how constrained the player's immediate surroundings are.
     * 0 = open space, 1 = tight corridor / ascending step.
     */
    private float computePathDifficulty(Minecraft mc) {
        if (mc.level == null || mc.player == null) return 0f;
        BlockPos pos = mc.player.blockPosition();

        int walls = 0;
        for (Direction dir : new Direction[]{ Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH }) {
            BlockPos adj = pos.relative(dir);
            if (mc.level.getBlockState(adj).canOcclude())        walls++;
            if (mc.level.getBlockState(adj.above()).canOcclude()) walls++;
        }
        float tightness = walls / 8.0f;

        boolean ascending = pursuitSegment < path.size()
                && path.get(pursuitSegment).moveType == MoveType.STEP_UP;

        return Math.min(1.0f, tightness + (ascending ? 0.4f : 0.0f));
    }

    private int pickLegacyCamTarget(Minecraft mc, Vec3 playerPos) {
        int camTarget = pursuitSegment;
        int camScanEnd = Math.min(path.size() - 1, pursuitSegment + CAMERA_LOOKAHEAD);
        for (int i = camScanEnd; i >= pursuitSegment; i--) {
            if (!isWaypointVisible(mc, path.get(i))) continue;
            if (i > pursuitSegment
                    && !isStraightLineSafe(path, pursuitSegment, i, playerPos.x, playerPos.z)) continue;
            if (i > pursuitSegment && hasYChangeBetween(path, pursuitSegment, i)) continue;
            if (i > pursuitSegment && cumulativeTurning(path, pursuitSegment, i) > 45.0) continue;
            camTarget = i;
            break;
        }
        return camTarget;
    }

    private int pickCameraRailTarget(Vec3 playerPos) {
        if (cameraPath.isEmpty()) return 0;

        if (lastCameraCheckPos == null) {
            lastCameraCheckPos = playerPos;
        }

        double distMoved = lastCameraCheckPos.distanceTo(playerPos);
        if (distMoved >= CAMERA_RAIL_REACHED_DIST * 0.5) {
            lastCameraCheckPos = playerPos;

            // Advance potentially multiple nodes per movement gate to avoid falling behind
            // during fast movement or falls.
            while (cameraIndex + 1 < cameraPath.size() && hasPassedCameraNode(playerPos, cameraIndex)) {
                cameraIndex++;
            }
        }

        return cameraIndex;
    }

    private Vec3 getCameraRailGuideTarget(Vec3 playerPos, int idx) {
        int start = Math.max(0, Math.min(idx, cameraPath.size() - 1));
        if (start + 1 >= cameraPath.size()) return cameraPath.getLast();

        Vec3 a = cameraPath.get(start);
        Vec3 b = cameraPath.get(start + 1);
        double abx = b.x - a.x;
        double aby = b.y - a.y;
        double abz = b.z - a.z;
        double lenSq = abx * abx + aby * aby + abz * abz;
        double t = lenSq < 1.0e-6
                ? 0.0
                : ((playerPos.x - a.x) * abx + (playerPos.y - a.y) * aby + (playerPos.z - a.z) * abz) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));

        Vec3 cursor = lerp(a, b, t);
        double remaining = CAMERA_RAIL_GUIDE_LOOKAHEAD_DIST;
        int seg = start;

        while (remaining > 0.0 && seg + 1 < cameraPath.size()) {
            Vec3 from = (seg == start) ? cursor : cameraPath.get(seg);
            Vec3 to = cameraPath.get(seg + 1);
            double segLen = from.distanceTo(to);
            if (segLen < 1.0e-6) {
                seg++;
                continue;
            }
            if (remaining <= segLen) {
                return lerp(from, to, remaining / segLen);
            }
            remaining -= segLen;
            seg++;
        }

        return cameraPath.getLast();
    }

    private boolean hasPassedCameraNode(Vec3 playerPos, int idx) {
        Vec3 cur = cameraPath.get(idx);
        double dx = cur.x - playerPos.x;
        double dz = cur.z - playerPos.z;
        if (Math.sqrt(dx * dx + dz * dz) <= CAMERA_RAIL_REACHED_DIST) return true;

        if (idx + 1 >= cameraPath.size()) return false;
        Vec3 next = cameraPath.get(idx + 1);
        double dirX = next.x - cur.x;
        double dirZ = next.z - cur.z;
        double segLenSq = dirX * dirX + dirZ * dirZ;
        if (segLenSq < 1.0e-6) return false;
        double toPlayerX = playerPos.x - cur.x;
        double toPlayerZ = playerPos.z - cur.z;
        double dot = toPlayerX * dirX + toPlayerZ * dirZ;
        return dot > segLenSq * 0.20;
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t);
    }


    private static List<Vec3> buildCameraRail(List<Node> navPath) {
        if (navPath == null || navPath.isEmpty()) return Collections.emptyList();

        List<Vec3> anchors = new ArrayList<>(navPath.size());
        for (Node n : navPath) {
            anchors.add(new Vec3(
                    n.position.flooredX() + 0.5,
                    n.position.flooredY() + CAMERA_RAIL_EYE_Y,
                    n.position.flooredZ() + 0.5));
        }
        if (anchors.size() <= 1) return anchors;

        List<Vec3> out = new ArrayList<>(anchors.size() * 2);
        appendCameraRailPoint(out, anchors.getFirst());

        for (int i = 0; i < anchors.size() - 1; i++) {
            Vec3 a = anchors.get(i);
            Vec3 b = anchors.get(i + 1);

            double segDist = a.distanceTo(b);
            int byDistance = (int) Math.ceil(segDist / CAMERA_RAIL_MAX_STEP_DIST);

            int byTurn = 1;
            if (i > 0 && i + 2 < anchors.size()) {
                Vec3 prev = anchors.get(i - 1);
                Vec3 next = anchors.get(i + 2);
                double cornerAngle = horizontalAngleDeg(prev, a, b, next);
                byTurn = Math.max(1, (int) Math.ceil(cornerAngle / CAMERA_RAIL_MAX_TURN_DEG));
            }

            int rawSteps = Math.max(byDistance, byTurn);
            int steps = (int) Math.ceil(rawSteps * CAMERA_RAIL_DENSITY_SCALE);
            steps = Math.max(1, Math.min(CAMERA_RAIL_MAX_STEPS_PER_SEGMENT, steps));
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                appendCameraRailPoint(out, new Vec3(
                        a.x + (b.x - a.x) * t,
                        a.y + (b.y - a.y) * t,
                        a.z + (b.z - a.z) * t));
            }
        }

        return compactCameraRail(out);
    }

    private static void appendCameraRailPoint(List<Vec3> out, Vec3 p) {
        if (out.isEmpty()) {
            out.add(p);
            return;
        }
        Vec3 last = out.getLast();
        double dx = p.x - last.x;
        double dy = p.y - last.y;
        double dz = p.z - last.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < CAMERA_RAIL_MIN_POINT_SPACING * CAMERA_RAIL_MIN_POINT_SPACING) return;

        double horizSq = dx * dx + dz * dz;
        if (horizSq < CAMERA_RAIL_MIN_HORIZONTAL_SPACING * CAMERA_RAIL_MIN_HORIZONTAL_SPACING) return;

        out.add(p);
    }

    private static List<Vec3> compactCameraRail(List<Vec3> points) {
        if (points.size() <= 1) return points;
        List<Vec3> compact = new ArrayList<>(points.size());
        for (Vec3 p : points) {
            appendCameraRailPoint(compact, p);
        }
        if (compact.isEmpty()) compact.add(points.getFirst());
        return compact;
    }

    private static double horizontalAngleDeg(Vec3 prev, Vec3 a, Vec3 b, Vec3 next) {
        double inX = a.x - prev.x;
        double inZ = a.z - prev.z;
        double outX = next.x - b.x;
        double outZ = next.z - b.z;

        double inLen = Math.sqrt(inX * inX + inZ * inZ);
        double outLen = Math.sqrt(outX * outX + outZ * outZ);
        if (inLen < 1.0e-3 || outLen < 1.0e-3) return 0.0;

        double dot = (inX / inLen) * (outX / outLen) + (inZ / inLen) * (outZ / outLen);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }
}
