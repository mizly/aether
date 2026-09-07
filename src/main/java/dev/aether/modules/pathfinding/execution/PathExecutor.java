package dev.aether.modules.pathfinding.execution;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.Node.MoveType;
import dev.aether.modules.pathfinding.rotation.AngleUtils;
import dev.aether.modules.pathfinding.rotation.RotationExecutor;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class PathExecutor {
    public enum State { IDLE, WALKING, REPLANNING, FINISHED, FAILED }

    private static final int JUMP_COOLDOWN_TICKS = 8;
    private static final int BACKUP_TICKS = 6;
    private static final double MOVEMENT_DEADZONE = 0.08;
    private static final double STEERING_LOOKAHEAD = 1.0;
    private static final double PROGRESS_EPSILON = 0.08;
    private static final double GOAL_REACHED_DISTANCE = 1.2;
    private static final long REPLAN_COOLDOWN_MS = 1500;

    private State state = State.IDLE;
    private List<Node> path = List.of();
    private WalkingRoute route = new WalkingRoute(List.of());
    private WalkingAimSpline aimSpline = new WalkingAimSpline(List.of());
    private List<Vec3> cameraPath = List.of();
    private Vec3 aimPoint;
    private Vec3 movementDirection = Vec3.ZERO;
    private Vec3 progressAnchor;
    private Vec3 backupDirection = Vec3.ZERO;
    private int pursuitSegment;
    private int goalX, goalY, goalZ;
    private double goalCenterX = 0.5;
    private double goalCenterZ = 0.5;
    private boolean precise;
    private Entity rotationTarget;
    private Vec3 lookTargetPos;
    private boolean allowReplan = true;
    private boolean allowJumps = true;
    private boolean allowRotation = true;
    private boolean exactGoalCentering;
    private boolean strictGoalCompletion;
    private double stickySneakDistance = -1.0;
    private boolean sneakLatched;
    private double preciseGoalTolerance = 0.5;
    private Runnable onFinished;
    private int jumpCooldown;
    private int backupTicksLeft;
    private boolean recoveryAttempted;
    private long lastProgressTime;
    private long lastPathProgressTime;
    private long lastReplanTime;
    private double bestPathProgress;

    public void start(List<Node> path, int goalX, int goalY, int goalZ, boolean precise) {
        start(path, goalX, goalY, goalZ, precise, null);
    }

    public void start(List<Node> path, int goalX, int goalY, int goalZ, boolean precise, Entity rotationTarget) {
        start(path, goalX, goalY, goalZ, precise, rotationTarget, null);
    }

    public void start(List<Node> path, int goalX, int goalY, int goalZ, boolean precise,
                      Entity rotationTarget, Runnable onFinished) {
        this.path = path == null ? List.of() : List.copyOf(path);
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.goalCenterX = 0.5;
        this.goalCenterZ = 0.5;
        this.precise = precise;
        this.pursuitSegment = 0;
        this.rotationTarget = rotationTarget;
        this.lookTargetPos = null;
        this.allowReplan = true;
        this.allowJumps = true;
        this.allowRotation = true;
        this.exactGoalCentering = false;
        this.strictGoalCompletion = false;
        this.stickySneakDistance = -1.0;
        this.sneakLatched = false;
        this.preciseGoalTolerance = 0.5;
        this.onFinished = onFinished;
        this.jumpCooldown = 0;
        this.backupTicksLeft = 0;
        this.recoveryAttempted = false;
        this.progressAnchor = null;
        this.movementDirection = Vec3.ZERO;
        this.aimPoint = null;
        this.lastProgressTime = System.currentTimeMillis();
        this.lastPathProgressTime = lastProgressTime;
        this.bestPathProgress = Double.NEGATIVE_INFINITY;
        List<Vec3> anchors = this.path.stream().map(PathExecutor::feetCenter).toList();
        this.route = new WalkingRoute(anchors);
        this.aimSpline = new WalkingAimSpline(anchors);
        this.cameraPath = aimSpline.points(1.62);
        state = State.WALKING;
    }

    public void setLookTarget(Vec3 lookTargetPos) { this.lookTargetPos = lookTargetPos; }
    public void setRotationTarget(Entity rotationTarget) { this.rotationTarget = rotationTarget; }
    public void setAllowReplan(boolean allowReplan) { this.allowReplan = allowReplan; }
    public void setPreciseGoalTolerance(double tolerance) { preciseGoalTolerance = Math.max(0.01, tolerance); }
    public void setAllowJumps(boolean allowJumps) { this.allowJumps = allowJumps; }
    public void setAllowRotation(boolean allowRotation) {
        if (this.allowRotation && !allowRotation) RotationExecutor.stopRotating();
        this.allowRotation = allowRotation;
    }
    public void setExactGoalCentering(boolean exactGoalCentering) { this.exactGoalCentering = exactGoalCentering; }
    public void setStrictGoalCompletion(boolean strictGoalCompletion) { this.strictGoalCompletion = strictGoalCompletion; }
    public void setStickySneakDistance(double distance) { stickySneakDistance = distance; }
    public void setSneakLatched(boolean sneakLatched) { this.sneakLatched = sneakLatched; }
    public void setGoalCenterOffsets(double x, double z) {
        goalCenterX = x;
        goalCenterZ = z;
    }
    public boolean isSneakLatched() { return sneakLatched; }
    public State getState() { return state; }
    public int getWaypointIndex() { return pursuitSegment; }
    public int getCamTargetIdx() { return aimPoint == null ? -1 : aimSpline.index(); }
    public List<Vec3> getCameraPath() { return cameraPath; }
    public int getCameraIndex() { return aimSpline.index(); }
    public Vec3 getAimPoint() { return aimPoint; }
    public Vec3 getMovementDirection() { return movementDirection; }

    public void tick(Minecraft mc) {
        if (state != State.WALKING && state != State.REPLANNING) return;
        if (mc.player == null || mc.level == null || state == State.REPLANNING) {
            releaseAll(mc);
            return;
        }
        long now = System.currentTimeMillis();
        if (ClientUtils.isInventoryScreenOpen() || mc.player.getAbilities().flying) {
            releaseAll(mc);
            RotationExecutor.stopRotating();
            resetProgress(mc.player.position(), now);
            if (mc.player.getAbilities().flying) ClientUtils.setKeyMappingState(mc.options.keyShift, true);
            return;
        }
        jumpCooldown = Math.max(0, jumpCooldown - 1);
        ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        if (path.isEmpty()) {
            fail(mc);
            return;
        }

        Vec3 playerPos = mc.player.position();
        Vec3 goal = new Vec3(goalX + goalCenterX, goalY, goalZ + goalCenterZ);
        double goalDistance = playerPos.subtract(goal).horizontalDistance();
        double goalTolerance = precise || exactGoalCentering || strictGoalCompletion
                ? preciseGoalTolerance : GOAL_REACHED_DISTANCE;
        if (route.endsAt(goal) && WalkingRoute.reachedGoal(playerPos, goal, goalTolerance)
                && (mc.player.onGround() || mc.player.isInWater() || isOnClimbable(mc))) {
            finish(mc);
            return;
        }

        pursuitSegment = route.advance(playerPos, pursuitSegment);
        if (pursuitSegment >= path.size() - 1 && !route.endsAt(goal)) {
            if (now - lastReplanTime >= REPLAN_COOLDOWN_MS) {
                triggerReplan(mc);
            } else {
                releaseAll(mc);
            }
            return;
        }
        updateProgress(playerPos, now);
        updateAim(mc, playerPos);
        if (!sneakLatched && stickySneakDistance > 0.0 && playerPos.distanceTo(goal) <= stickySneakDistance) {
            sneakLatched = true;
        }
        ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLatched);

        long timeout = AetherConfig.PATHFINDER_STUCK_TIMEOUT_MS.get();
        long pathStale = now - lastPathProgressTime;
        long motionStale = now - lastProgressTime;
        double drift = route.distance(playerPos, pursuitSegment);
        if ((pathStale > timeout * 2 || (pathStale > timeout && drift > 1.75))
                && now - lastReplanTime >= REPLAN_COOLDOWN_MS) {
            triggerReplan(mc);
            return;
        }

        if (backupTicksLeft > 0) {
            if (!mc.player.onGround() || !hasBackupSupport(mc, backupDirection)) {
                backupTicksLeft = 0;
                releaseAll(mc);
                return;
            }
            backupTicksLeft--;
            WalkingMotion.apply(mc, WalkingMotion.horizontalInput(
                    backupDirection, mc.player.getYRot(), MOVEMENT_DEADZONE));
            movementDirection = backupDirection;
            return;
        }
        if (!recoveryAttempted && motionStale > timeout && mc.player.onGround() && !sneakLatched) {
            recoveryAttempted = true;
            backupDirection = movementDirection.scale(-1.0);
            if (backupDirection.horizontalDistanceSqr() > 1.0e-6 && hasBackupSupport(mc, backupDirection)) {
                backupTicksLeft = BACKUP_TICKS;
                releaseAll(mc);
                return;
            }
        }

        Node waypoint = path.get(Math.min(path.size() - 1, pursuitSegment + 1));
        boolean atRouteEnd = pursuitSegment >= path.size() - 1;
        Vec3 target = atRouteEnd ? goal : route.steeringTarget(playerPos, pursuitSegment, STEERING_LOOKAHEAD);
        boolean centering = route.endsAt(goal) && (precise || exactGoalCentering || strictGoalCompletion)
                && goalDistance < 1.5 && Math.abs(playerPos.y - goal.y) <= 0.75
                && pursuitSegment >= path.size() - 2;
        if (centering) target = goal;
        Vec3 offset = target.subtract(playerPos).multiply(1.0, 0.0, 1.0);
        WalkingMotion.Input input = WalkingMotion.horizontalInput(offset, mc.player.getYRot(),
                centering ? Math.min(MOVEMENT_DEADZONE, goalTolerance * 0.5) : MOVEMENT_DEADZONE);
        WalkingMotion.apply(mc, input);
        movementDirection = WalkingMotion.direction(input, mc.player.getYRot());

        boolean approachingRise = target.y - playerPos.y > 0.6 && offset.horizontalDistance() < 2.0;
        boolean sharpTurn = approachingCorner(playerPos);
        boolean sprint = AetherConfig.PATHFINDER_SPRINT.get()
                && input.forward() > 0 && !sneakLatched && !centering
                && goalDistance > 2.5 && !approachingRise && !sharpTurn;
        ClientUtils.setKeyMappingState(mc.options.keySprint, sprint);
        if (!sprint) mc.player.setSprinting(false);
        if (centering) ClientUtils.setKeyMappingState(mc.options.keyShift, true);

        if (allowJumps && !centering) {
            handleJumps(mc, waypoint, playerPos, motionStale > timeout / 2);
        }
        if (mc.player.isInWater() && allowJumps && target.y > playerPos.y + 0.2) {
            ClientUtils.setKeyMappingState(mc.options.keyJump, true);
        }
        if (isOnClimbable(mc)) {
            if (target.y > playerPos.y + 0.2 && allowJumps) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, true);
            } else if (target.y < playerPos.y - 0.2) {
                ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLatched);
            }
        }

    }

    private void updateAim(Minecraft mc, Vec3 playerPos) {
        Vec3 splinePoint = aimSpline.aimPoint(playerPos, pursuitSegment,
                AetherConfig.PATHFINDER_AIM_LOOKAHEAD.get(), mc.player.getEyeHeight());
        if (lookTargetPos != null) {
            aimPoint = lookTargetPos;
        } else if (rotationTarget != null && rotationTarget.isAlive() && !rotationTarget.isRemoved()
                && rotationTarget.level() == mc.level) {
            aimPoint = rotationTarget.getEyePosition();
        } else {
            aimPoint = splinePoint;
        }
        if (allowRotation && aimPoint != null && aimPoint.distanceToSqr(mc.player.getEyePosition()) > 0.01) {
            RotationExecutor.track(AngleUtils.getRotation(mc.player.getEyePosition(), aimPoint),
                    AetherConfig.PATHFINDER_TURN_SPEED.get());
        }
    }

    private void handleJumps(Minecraft mc, Node waypoint, Vec3 playerPos, boolean stalled) {
        if (!mc.player.onGround() || jumpCooldown > 0 || sneakLatched
                || mc.player.isInWater() || isOnClimbable(mc)) return;
        double predictionTicks = AetherConfig.PATHFINDER_JUMP_LOOKAHEAD_TICKS.get();
        double triggerDistance = 0.35 + mc.player.getDeltaMovement().horizontalDistance() * predictionTicks;
        boolean jump = false;
        boolean obstacleAhead = false;
        if (AetherConfig.PATHFINDER_RAYCAST_JUMP.get()) {
            var obstacle = WalkingObstacleProbe.probe(mc, movementDirection, 0.35, predictionTicks,
                    AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get());
            jump = obstacle.jumpRequired();
            obstacleAhead = obstacle.obstacleAhead();
        } else {
            double rise = waypoint.position.flooredY() - playerPos.y;
            double distance = feetCenter(waypoint).subtract(playerPos).horizontalDistance();
            jump = rise > mc.player.maxUpStep() && distance < 0.8 + triggerDistance
                    && WalkingObstacleProbe.hasJumpHeadroom(mc);
        }
        if (!jump && !obstacleAhead && waypoint.moveType == MoveType.PARKOUR && pursuitSegment < path.size() - 1) {
            Vec3 takeoff = feetCenter(path.get(pursuitSegment));
            double takeoffDistance = takeoff.subtract(playerPos).horizontalDistance();
            jump = takeoffDistance <= 0.4 + triggerDistance && WalkingObstacleProbe.hasJumpHeadroom(mc);
        }
        if (!jump && stalled && mc.player.horizontalCollision && !AetherConfig.PATHFINDER_RAYCAST_JUMP.get()) {
            jump = WalkingObstacleProbe.hasJumpHeadroom(mc);
        }
        if (jump) {
            ClientUtils.setKeyMappingState(mc.options.keyJump, true);
            jumpCooldown = JUMP_COOLDOWN_TICKS;
        }
    }

    private boolean approachingCorner(Vec3 playerPos) {
        if (pursuitSegment + 2 >= path.size()) return false;
        Vec3 corner = feetCenter(path.get(pursuitSegment + 1));
        if (corner.subtract(playerPos).horizontalDistance() > 1.5) return false;
        Vec3 incoming = corner.subtract(feetCenter(path.get(pursuitSegment))).multiply(1.0, 0.0, 1.0).normalize();
        Vec3 outgoing = feetCenter(path.get(pursuitSegment + 2)).subtract(corner).multiply(1.0, 0.0, 1.0).normalize();
        return incoming.dot(outgoing) < 0.7;
    }

    private boolean hasBackupSupport(Minecraft mc, Vec3 direction) {
        double distance = Math.max(0.6, mc.player.getDeltaMovement().horizontalDistance() * 2.0);
        Vec3 offset = direction.normalize().scale(distance);
        int samples = Math.max(1, (int) Math.ceil(distance / 0.2));
        for (int sample = 1; sample <= samples; sample++) {
            var box = mc.player.getBoundingBox().move(offset.scale((double) sample / samples));
            if (!mc.level.noCollision(mc.player, box)
                    || mc.level.noCollision(mc.player, box.move(0.0, -0.5, 0.0))) return false;
        }
        return true;
    }

    private void updateProgress(Vec3 position, long now) {
        if (progressAnchor == null || position.distanceTo(progressAnchor) >= 0.15) {
            progressAnchor = position;
            lastProgressTime = now;
        }
        double progress = route.progress(position, pursuitSegment);
        if (progress > bestPathProgress + PROGRESS_EPSILON) {
            bestPathProgress = progress;
            lastPathProgressTime = now;
            recoveryAttempted = false;
        }
    }

    private void resetProgress(Vec3 position, long now) {
        progressAnchor = position;
        lastProgressTime = now;
        lastPathProgressTime = now;
        backupTicksLeft = 0;
        recoveryAttempted = false;
    }

    private void triggerReplan(Minecraft mc) {
        if (!allowReplan) {
            fail(mc);
            return;
        }
        releaseAll(mc);
        RotationExecutor.stopRotating();
        lastReplanTime = System.currentTimeMillis();
        state = State.REPLANNING;
    }

    public void stop(Minecraft mc) {
        state = State.IDLE;
        sneakLatched = false;
        RotationExecutor.stopRotating();
        releaseAll(mc);
    }

    public void releaseAll(Minecraft mc) {
        movementDirection = Vec3.ZERO;
        if (mc.player != null) mc.player.setSprinting(false);
        if (mc.options == null) return;
        ClientUtils.setKeyMappingState(mc.options.keyUp, false);
        ClientUtils.setKeyMappingState(mc.options.keyDown, false);
        ClientUtils.setKeyMappingState(mc.options.keyLeft, false);
        ClientUtils.setKeyMappingState(mc.options.keyRight, false);
        ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        ClientUtils.setKeyMappingState(mc.options.keySprint, false);
        ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLatched);
    }

    private void finish(Minecraft mc) {
        RotationExecutor.stopRotating();
        releaseAll(mc);
        state = State.FINISHED;
        if (onFinished != null) onFinished.run();
    }

    private void fail(Minecraft mc) {
        RotationExecutor.stopRotating();
        releaseAll(mc);
        state = State.FAILED;
    }

    private static Vec3 feetCenter(Node node) {
        return new Vec3(node.position.centeredX(), node.position.flooredY(), node.position.centeredZ());
    }

    private static boolean isOnClimbable(Minecraft mc) {
        return mc.level.getBlockState(mc.player.blockPosition()).is(BlockTags.CLIMBABLE);
    }
}
