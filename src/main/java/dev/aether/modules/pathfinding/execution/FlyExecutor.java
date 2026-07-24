package dev.aether.modules.pathfinding.execution;

import java.util.ArrayList;
import java.util.List;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.rotation.AngleUtils;
import dev.aether.modules.pathfinding.rotation.EasingType;
import dev.aether.modules.pathfinding.rotation.Rotation;
import dev.aether.modules.pathfinding.rotation.RotationExecutor;
import dev.aether.modules.pathfinding.rotation.strategy.TimedEaseStrategy;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fly executor inspired by FarmHelper's FlyPathFinderExecutor.
 *
 * Key design decisions mirroring FarmHelper:
 * - Rotation is horizontal only (yaw). The player looks toward the target,
 * BUT vertical movement is controlled entirely by Space/Shift based on
 * raycast collision checks in front of and behind the player - not by pitch.
 * - Waypoint advancement uses the closest reachable waypoint (1.5 block
 * radius).
 * - Stopping: once we'd arrive within stoppingThreshold after deceleration,
 * keys release.
 * - Stuck recovery: at 1.5s hold Space to climb; at 3s abort.
 */
public final class FlyExecutor {

    public enum State {
        IDLE, FLYING, DECELERATING, FINISHED, FAILED
    }

    // How close to a waypoint counts as "reached" (horizontal + vertical)
    private static final double REACH = 1.5;
    // Stopping threshold: stop pressing W when predicted stop is within this
    // distance
    private static final double STOP_THRESH = 0.5;
    // Stuck timers
    private static final long STUCK_CLIMB_MS = 1500;
    private static final long STUCK_ABORT_MS = 3000;
    // Net-progress stall detection. The per-tick displacement check above cannot
    // catch oscillation (a bouncing player keeps moving >0.15/tick yet never gets
    // closer to the goal), so we also track how close to the goal we have ever got.
    private static final double GOAL_PROGRESS_EPSILON = 0.2;
    private static final long NO_PROGRESS_ABORT_MS = 3000;
    // How far ahead to raycast for block detection (blocks)
    private static final double RAY_DIST = 2.5;
    private static final long ROTATION_DURATION_MS = 550L;
    private static final float YAW_ROTATION_THRESHOLD = 1.5f;
    private static final float PITCH_ROTATION_THRESHOLD = 2.5f;

    private State state = State.IDLE;
    private List<Node> path;
    private int wpIndex;
    private int goalX, goalY, goalZ;

    /** Rolling stuck detection */
    private Vec3 lastPosCheck = Vec3.ZERO;
    private long lastProgressTime;
    private long decelStartTime = 0;
    private int ticksSinceLastMove = 0;
    private static final int TICKS_FOR_STUCK = 15; // ~750ms at 20 tps

    /** Net distance-to-goal progress (catches oscillation the per-tick check misses) */
    private double bestDistToGoal = Double.MAX_VALUE;
    private long lastGoalProgressTime;
    /** Times we've resumed flying after braking left us short of the goal */
    private int decelResumes = 0;

    private Runnable onFinished;
    private boolean usePitchControl = false;
    private float targetPitch = 0;
    private boolean useLookTargetRotation = false;
    private Vec3 lookTarget = null;
    private double finalWaypointReach = REACH;
    private double goalStopThreshold = STOP_THRESH;

    // --- Public API ----------------------------------------------------------

    public void start(List<Node> path, int goalX, int goalY, int goalZ) {
        start(path, goalX, goalY, goalZ, null);
    }

    public void start(List<Node> path, int goalX, int goalY, int goalZ, Runnable onFinished) {
        this.path = new ArrayList<>(path);
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.wpIndex = 0;
        this.decelStartTime = 0;
        this.lastProgressTime = System.currentTimeMillis();
        this.lastPosCheck = Vec3.ZERO;
        this.ticksSinceLastMove = 0;
        this.bestDistToGoal = Double.MAX_VALUE;
        this.lastGoalProgressTime = System.currentTimeMillis();
        this.decelResumes = 0;
        this.onFinished = onFinished;
        this.usePitchControl = false;
        this.useLookTargetRotation = false;
        this.lookTarget = null;
        this.finalWaypointReach = REACH;
        this.goalStopThreshold = STOP_THRESH;
        state = State.FLYING;
        ClientUtils.sendDebugMessage("fly start: " + this.path.size() + " wps, goal ("
                + goalX + ", " + goalY + ", " + goalZ + ")");
    }

    public void setPitchControl(float pitch) {
        this.usePitchControl = true;
        this.targetPitch = pitch;
    }

    public void setLookTargetRotation(Vec3 lookTarget) {
        this.useLookTargetRotation = true;
        this.lookTarget = lookTarget;
        this.usePitchControl = false;
    }

    public void setPreciseGoalTolerance(double tolerance) {
        double clamped = Math.max(0.01, tolerance);
        this.finalWaypointReach = clamped;
        this.goalStopThreshold = clamped;
    }

    public State getState() {
        return state;
    }

    public void tick(Minecraft mc) {
        if (mc.player == null)
            return;

        if (ClientUtils.isInventoryScreenOpen()) {
            releaseAll(mc);
            return;
        }

        if (state == State.DECELERATING) {
            tickDecelerate(mc);
            return;
        }

        if (state != State.FLYING)
            return;
        if (path == null || path.isEmpty()) {
            finish(mc);
            return;
        }

        Vec3 pos = mc.player.position();

        // -- Waypoint advancement -------------------------------------------
        while (wpIndex < path.size()) {
            Node wp = path.get(wpIndex);
            double dx = (wp.position.flooredX() + 0.5) - pos.x;
            double dy = (wp.position.flooredY() + 0.15) - pos.y;
            double dz = (wp.position.flooredZ() + 0.5) - pos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            // Advance if reaching the waypoint with high precision for critical points
            // Check if we have reached the final waypoint that should trigger rewarp
            if (wpIndex >= path.size() - 1) {
                // Special handling for final waypoint (likely rewarp point)
                if (distSq <= finalWaypointReach * finalWaypointReach * 1.5) {  // Slightly more tolerant but precise
                    lastProgressTime = System.currentTimeMillis();
                    ticksSinceLastMove = 0;
                    wpIndex++;
                    break; // Stop advancing if we get close to final waypoint
                }
            }

            // Advance normally for intermediate waypoints
            double waypointReach = wpIndex == path.size() - 1 ? finalWaypointReach : REACH;
            boolean reached = distSq <= waypointReach * waypointReach;
            if (!reached && wpIndex > 0) {
                // Dot product check: if we've passed the plane of the waypoint
                Vec3 toWp = new Vec3(dx, dy, dz);
                Vec3 prevWp = new Vec3(
                        path.get(wpIndex - 1).position.flooredX() + 0.5,
                        path.get(wpIndex - 1).position.flooredY() + 0.15,
                        path.get(wpIndex - 1).position.flooredZ() + 0.5);
                Vec3 pathDir = new Vec3(
                        wp.position.flooredX() + 0.5 - prevWp.x,
                        wp.position.flooredY() + 0.15 - prevWp.y,
                        wp.position.flooredZ() + 0.5 - prevWp.z).normalize();
                if (toWp.dot(pathDir) < 0) {
                    reached = true; // We've flown past it
                }
            }

            if (reached) {
                lastProgressTime = System.currentTimeMillis();
                ticksSinceLastMove = 0;
                wpIndex++;
            } else {
                break;
            }
        }

        // -- Goal waypoint reached ------------------------------------------
        Vec3 goal = new Vec3(goalX + 0.5, goalY + 0.15, goalZ + 0.5);
        double distToGoal = pos.distanceTo(goal);

        // -- Net-progress stall detection -----------------------------------
        // Fail if we haven't got meaningfully closer to the goal for a while.
        // This is what catches oscillating "stuck between two positions": the
        // per-tick displacement check never fires because we keep moving.
        if (distToGoal < bestDistToGoal - GOAL_PROGRESS_EPSILON) {
            bestDistToGoal = distToGoal;
            lastGoalProgressTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastGoalProgressTime > NO_PROGRESS_ABORT_MS) {
            ClientUtils.sendMessage(String.format(
                    "§cFly not progressing, recovering. (wp %d/%d, dist %.1f, pos %.1f %.1f %.1f)",
                    Math.min(wpIndex + 1, path.size()), path.size(), distToGoal,
                    pos.x, pos.y, pos.z), false);
            fail(mc);
            return;
        }

        // Check if we should stop early based on momentum
        if (shouldStopNow(mc, goal)) {
            beginDecelerate(mc);
            return;
        }

        if (wpIndex >= path.size()) {
            // We have reached/passed the final waypoint's radius.
            beginDecelerate(mc);
            return;
        }

        // -- Determine next waypoint target --------------------------------
        Node wp = path.get(wpIndex);
        double dx = (wp.position.flooredX() + 0.5) - pos.x;
        double dz = (wp.position.flooredZ() + 0.5) - pos.z;
        double dyWp = wp.position.flooredY() + 0.15;

        // -- Deceleration check ---------------------------------------------
        if (wpIndex >= path.size() - 2 && shouldStopNow(mc, goal)) {
            beginDecelerate(mc);
            return;
        }

        // -- Rotation -------------------------------------------------------
        if (useLookTargetRotation && lookTarget != null) {
            rotateTowardLookTarget(mc, lookTarget);
        } else if (distToGoal > 3.0) {
            setHorizontalRotation(mc, dx, dz, usePitchControl ? targetPitch : mc.player.getXRot());
        } else if (usePitchControl) {
            rotateSmoothly(mc, new Rotation(mc.player.getYRot(), targetPitch));
        }

        // -- Forward movement -----------------------------------------------
        applyStrafingMovement(mc, dx, dz);
        // Generous no-sprint zone: sprint momentum near the goal is what causes
        // multi-block overshoots.
        ClientUtils.setKeyMappingState(mc.options.keySprint, distToGoal > 12.0);
        adjustVerticalKeysWithRaycast(mc, pos, dyWp);

        // -- Stuck detection ------------------------------------------------
        double moved = pos.distanceTo(lastPosCheck);
        if (moved < 0.15) {
            ticksSinceLastMove++;
        } else {
            ticksSinceLastMove = 0;
            lastPosCheck = pos;
            lastProgressTime = System.currentTimeMillis();
        }

        long stuckMs = System.currentTimeMillis() - lastProgressTime;
        if (ticksSinceLastMove > TICKS_FOR_STUCK || stuckMs > STUCK_ABORT_MS) {
            if (stuckMs > STUCK_ABORT_MS) {
                if (mc.player != null) {
                    ClientUtils.sendMessage(String.format(
                            "\u00A7cFly stuck! Aborting navigation. (wp %d/%d, dist %.1f, pos %.1f %.1f %.1f)",
                            Math.min(wpIndex + 1, path.size()), path.size(), distToGoal,
                            pos.x, pos.y, pos.z), false);
                }
                fail(mc);
                return;
            } else if (stuckMs > STUCK_CLIMB_MS) {
                // Recovery: try climbing over the obstruction
                ClientUtils.setKeyMappingState(mc.options.keyJump, true);
                ClientUtils.setKeyMappingState(mc.options.keyShift, false);
            }
        }

        // Periodic debug output
        if (AetherConfig.SHOW_DEBUG.get()) {
            ClientUtils.sendDebugMessage(String.format(
                    "fly wp=%d/%d dist=%.2f best=%.2f noProg=%dms noMove=%dt state=%s",
                    Math.min(wpIndex + 1, path.size()), path.size(), distToGoal,
                    bestDistToGoal == Double.MAX_VALUE ? -1.0 : bestDistToGoal,
                    System.currentTimeMillis() - lastGoalProgressTime,
                    ticksSinceLastMove, state));
        }
    }

    public void stop(Minecraft mc) {
        state = State.IDLE;
        RotationExecutor.stopRotating();
        releaseAll(mc);
    }

    public void releaseAll(Minecraft mc) {
        if (mc == null || mc.options == null)
            return;
        ClientUtils.setKeyMappingState(mc.options.keyUp, false);
        ClientUtils.setKeyMappingState(mc.options.keyDown, false);
        ClientUtils.setKeyMappingState(mc.options.keyLeft, false);
        ClientUtils.setKeyMappingState(mc.options.keyRight, false);
        ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        ClientUtils.setKeyMappingState(mc.options.keySprint, false);
        ClientUtils.setKeyMappingState(mc.options.keyShift, false);
    }

    // --- Internal helpers ----------------------------------------------------

    private void beginDecelerate(Minecraft mc) {
        state = State.DECELERATING;
        decelStartTime = System.currentTimeMillis();
        releaseAll(mc);
    }

    private void tickDecelerate(Minecraft mc) {
        if (mc.player == null) {
            finish(mc);
            return;
        }
        Vec3 vel = mc.player.getDeltaMovement();
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        // Actively brake instead of coasting: momentum from sprint flight can
        // carry several blocks past the goal if we just release the keys.
        // Airborne only - on the ground there's no glide to kill and holding S
        // just walks us backwards off the spot.
        boolean airborne = mc.player.getAbilities().flying && !mc.player.onGround();
        ClientUtils.setKeyMappingState(mc.options.keyDown, airborne && horizSpeed > 0.08);
        boolean stopped = horizSpeed < 0.05 && Math.abs(vel.y) < 0.05;
        if (!stopped && System.currentTimeMillis() - decelStartTime <= 2000) {
            return;
        }
        ClientUtils.setKeyMappingState(mc.options.keyDown, false);

        // If braking still left us short of (or past) the goal, resume flying
        // to close the gap rather than finishing wherever momentum ran out.
        Vec3 goal = new Vec3(goalX + 0.5, goalY + 0.15, goalZ + 0.5);
        double dist = mc.player.position().distanceTo(goal);
        if (dist > finalWaypointReach * 1.2 && decelResumes < 3 && path != null && !path.isEmpty()) {
            decelResumes++;
            wpIndex = path.size() - 1;
            state = State.FLYING;
            return;
        }
        finish(mc);
    }

    private void finish(Minecraft mc) {
        RotationExecutor.stopRotating();
        releaseAll(mc);
        state = State.FINISHED;
        if (onFinished != null) {
            onFinished.run();
        }
    }

    /**
     * Terminal failure. Unlike {@link #stop}, this leaves the executor in a
     * distinct FAILED state so the manager can tell "gave up" apart from
     * "reached goal" and hand off to recovery (unstuck + recompute).
     */
    private void fail(Minecraft mc) {
        RotationExecutor.stopRotating();
        releaseAll(mc);
        state = State.FAILED;
    }

    /**
     * Sets only the player's yaw to face (dx, dz). Does not touch pitch.
     * Yaw is smoothed through the shared pathfinding RotationExecutor.
     * Guard: skip update when the horizontal distance is too small.
     */
    private void setHorizontalRotation(Minecraft mc, double dx, double dz, float pitch) {
        if (mc.player == null)
            return;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        if (horizDist < 3.0)
            return; // rotation lock range - don't update yaw to avoid spinning near targets

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        rotateSmoothly(mc, new Rotation(targetYaw, pitch));
    }

    private void rotateTowardLookTarget(Minecraft mc, Vec3 target) {
        if (mc.player == null) {
            return;
        }

        rotateSmoothly(mc, AngleUtils.getRotation(target));
    }

    private void rotateSmoothly(Minecraft mc, Rotation desiredRot) {
        if (mc.player == null) {
            return;
        }

        float sourceYaw = RotationExecutor.isRotating()
                ? RotationExecutor.getTargetYaw()
                : mc.player.getYRot();
        float sourcePitch = RotationExecutor.isRotating()
                ? RotationExecutor.getTargetPitch()
                : mc.player.getXRot();
        float yawDrift = Math.abs(AngleUtils.getRotationDelta(sourceYaw, desiredRot.yaw));
        float pitchDrift = Math.abs(AngleUtils.getRotationDelta(sourcePitch, desiredRot.pitch));

        if (yawDrift > YAW_ROTATION_THRESHOLD || pitchDrift > PITCH_ROTATION_THRESHOLD) {
            RotationExecutor.rotateTo(desiredRot,
                    new TimedEaseStrategy(EasingType.EASE_OUT_CUBIC, ROTATION_DURATION_MS));
        }
    }

    private void applyStrafingMovement(Minecraft mc, double dx, double dz) {
        float yawRad = (float) Math.toRadians(mc.player.getYRot());
        double fX = -Math.sin(yawRad), fZ = Math.cos(yawRad);
        double sX = -Math.sin(yawRad + Math.PI / 2), sZ = Math.cos(yawRad + Math.PI / 2);
        double dotF = dx * fX + dz * fZ;
        double dotS = dx * sX + dz * sZ;

        ClientUtils.setKeyMappingState(mc.options.keyUp, dotF > 0.1);
        ClientUtils.setKeyMappingState(mc.options.keyDown, dotF < -0.1);
        ClientUtils.setKeyMappingState(mc.options.keyRight, dotS > 0.1);
        ClientUtils.setKeyMappingState(mc.options.keyLeft, dotS < -0.1);
    }

    /**
     * FarmHelper-style: raycast in front of the player to detect blocks,
     * then decide whether to go up or down.
     * This avoids drift from pitch-based steering.
     */
    private void adjustVerticalKeysWithRaycast(Minecraft mc, Vec3 pos, double waypointY) {
        if (mc.player == null || mc.level == null)
            return;

        double dy = waypointY - pos.y;

        // If waypoint is significantly above or below, prioritise that
        if (dy > 0.75) {
            ClientUtils.setKeyMappingState(mc.options.keyJump, true);
            ClientUtils.setKeyMappingState(mc.options.keyShift, false);
            return;
        }
        if (dy < -0.75 && mc.player.getAbilities().flying) {
            ClientUtils.setKeyMappingState(mc.options.keyShift, true);
            ClientUtils.setKeyMappingState(mc.options.keyJump, false);
            return;
        }

        // Raycast at feet height and head height in the direction we're facing
        float yaw = (float) Math.toRadians(mc.player.getYRot());
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);

        // Player feet/head positions
        Vec3 feetPos = pos.add(0, 0.1, 0);
        Vec3 headPos = pos.add(0, mc.player.getBbHeight() - 0.1, 0);

        Vec3 feetEnd = feetPos.add(lookX * RAY_DIST, 0, lookZ * RAY_DIST);
        Vec3 headEnd = headPos.add(lookX * RAY_DIST, 0, lookZ * RAY_DIST);

        HitResult feetTrace = mc.level.clip(new ClipContext(feetPos, feetEnd,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        HitResult headTrace = mc.level.clip(new ClipContext(headPos, headEnd,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));

        boolean blockAtFeet = feetTrace.getType() == HitResult.Type.BLOCK;
        boolean blockAtHead = headTrace.getType() == HitResult.Type.BLOCK;

        if (blockAtFeet && !blockAtHead) {
            // Something blocking at feet level -> jump up
            ClientUtils.setKeyMappingState(mc.options.keyJump, true);
            ClientUtils.setKeyMappingState(mc.options.keyShift, false);
        } else if (blockAtHead && !blockAtFeet) {
            // Something blocking at head level -> sneak down
            ClientUtils.setKeyMappingState(mc.options.keyShift, true);
            ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        } else if (blockAtFeet) {
            // Wall taller than the player: climb over it, unless there's a
            // ceiling right above us - then duck under instead of pinning on it.
            HitResult upTrace = mc.level.clip(new ClipContext(headPos,
                    headPos.add(0, RAY_DIST, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            boolean ceilingAbove = upTrace.getType() == HitResult.Type.BLOCK;
            ClientUtils.setKeyMappingState(mc.options.keyJump, !ceilingAbove);
            ClientUtils.setKeyMappingState(mc.options.keyShift, ceilingAbove);
        } else {
            // No obstruction - small Y correction if needed
            ClientUtils.setKeyMappingState(mc.options.keyJump, false);
            ClientUtils.setKeyMappingState(mc.options.keyShift, false);
        }
    }

    /**
     * Predicts whether we will drift within STOP_THRESH of the goal after
     * releasing keys (simplified: checks if current velocity would carry us there).
     */
    private boolean shouldStopNow(Minecraft mc, Vec3 goal) {
        if (mc.player == null)
            return false;
        Vec3 vel = mc.player.getDeltaMovement();
        // Creative flight decelerates roughly 0.09 per tick
        // Predict where we'd be after coasting
        double simX = mc.player.getX();
        double simZ = mc.player.getZ();
        double vx = vel.x, vz = vel.z;
        for (int i = 0; i < 30; i++) {
            simX += vx;
            simZ += vz;
            vx *= 0.91;
            vz *= 0.91;
            if (Math.abs(vx) < 0.01 && Math.abs(vz) < 0.01)
                break;
        }
        double predictedDist = Math.sqrt(
                (simX - goal.x) * (simX - goal.x) + (simZ - goal.z) * (simZ - goal.z));
        return predictedDist < goalStopThreshold;
    }
}

