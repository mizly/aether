package dev.aether.modules.pathfinding.execution;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WalkingObstacleProbe {
    private static final double EPSILON = 0.02;
    private static final double MAX_LOOKAHEAD = 4.0;
    private static final Result CLEAR = new Result(false, false, Double.POSITIVE_INFINITY, 0.0, true);

    public record Result(boolean obstacleAhead, boolean jumpRequired, double clearance,
                         double obstacleHeight, boolean headroomClear) {}

    private record Sweep(AABB bounds, double enter, double exit) {}

    interface CollisionSpace {
        Iterable<AABB> collisions(AABB bounds);
        boolean clear(AABB bounds);
    }

    private WalkingObstacleProbe() {}

    public static Result probe(Minecraft client, Vec3 movementDirection, double baseLookahead,
                               double predictionTicks, double maxJumpHeight) {
        if (client.player == null || client.level == null) {
            return CLEAR;
        }
        double jumpHeight = Math.min(maxJumpHeight + 0.125, jumpHeight(client) - EPSILON);
        return probe(collisionSpace(client), client.player.getBoundingBox(), movementDirection,
                client.player.getDeltaMovement(), client.player.maxUpStep(), jumpHeight,
                baseLookahead, predictionTicks);
    }

    public static boolean hasJumpHeadroom(Minecraft client) {
        return client.player != null && client.level != null
                && hasHeadroom(collisionSpace(client), client.player.getBoundingBox(),
                Math.min(1.0, jumpHeight(client)));
    }

    static Result probe(CollisionSpace space, AABB bounds, Vec3 movementDirection, Vec3 velocity,
                        double stepHeight, double maxJumpHeight, double baseLookahead, double predictionTicks) {
        double length = movementDirection.horizontalDistance();
        if (length < 1.0e-6) {
            return CLEAR;
        }
        Vec3 direction = new Vec3(movementDirection.x / length, 0.0, movementDirection.z / length);
        double closingSpeed = Math.max(0.0, velocity.x * direction.x + velocity.z * direction.z);
        double reach = Math.min(MAX_LOOKAHEAD, Math.max(0.05, baseLookahead)
                + closingSpeed * Math.max(0.0, predictionTicks));
        Vec3 travel = direction.scale(reach);
        List<Sweep> collisions = new ArrayList<>();
        AABB search = bounds.expandTowards(travel).expandTowards(0, reach + maxJumpHeight, 0).inflate(EPSILON);
        for (AABB obstacle : space.collisions(search)) {
            Sweep collision = sweep(bounds, obstacle, direction, reach);
            if (collision != null) collisions.add(collision);
        }
        collisions.sort(Comparator.comparingDouble(Sweep::enter));
        double nearestDistance = Double.POSITIVE_INFINITY;
        double obstacleHeight = 0.0;
        double walkingY = bounds.minY;
        double supportedUntil = reach;
        for (Sweep collision : collisions) {
            double distance = collision.enter();
            if (walkingY > bounds.minY + EPSILON && distance > supportedUntil + EPSILON) {
                walkingY = bounds.minY;
                supportedUntil = reach;
            }
            double top = walkingY;
            for (Sweep candidate : collisions) {
                if (candidate.enter() <= distance + EPSILON && candidate.exit() > distance + 1.0e-6
                        && candidate.bounds().minY < walkingY + bounds.getYsize() - EPSILON) {
                    top = Math.max(top, candidate.bounds().maxY);
                }
            }
            double step = top - walkingY;
            if (step > stepHeight + EPSILON) {
                nearestDistance = Math.max(0, distance);
                obstacleHeight = top - bounds.minY;
                break;
            }
            if (step > EPSILON) {
                AABB atStep = bounds.move(direction.scale(distance + 2 * EPSILON)).move(0, walkingY - bounds.minY, 0);
                if (!hasHeadroom(space, atStep, step)
                        || !space.clear(atStep.deflate(EPSILON).move(0, step, 0))) {
                    return new Result(true, false, distance, top - bounds.minY, false);
                }
                walkingY = top;
                supportedUntil = distance;
            }
            for (Sweep candidate : collisions) {
                if (candidate.enter() <= supportedUntil + EPSILON
                        && Math.abs(candidate.bounds().maxY - walkingY) <= EPSILON) {
                    supportedUntil = Math.max(supportedUntil, candidate.exit());
                }
            }
        }
        if (!Double.isFinite(nearestDistance)) {
            return CLEAR;
        }
        boolean heightReachable = obstacleHeight <= maxJumpHeight;
        double rise = obstacleHeight + EPSILON;
        boolean headroomClear = heightReachable && hasHeadroom(space, bounds, rise);
        if (headroomClear) {
            AABB elevated = bounds.deflate(EPSILON).move(0.0, rise, 0.0);
            double landingDistance = nearestDistance + 0.15;
            int samples = Math.max(1, (int) Math.ceil(landingDistance / 0.2));
            for (int sample = 1; sample <= samples; sample++) {
                if (!space.clear(elevated.move(direction.scale(landingDistance * sample / samples)))) {
                    headroomClear = false;
                    break;
                }
            }
        }
        return new Result(true, heightReachable && headroomClear, nearestDistance, obstacleHeight, headroomClear);
    }

    private static Sweep sweep(AABB bounds, AABB obstacle, Vec3 direction, double reach) {
        double enter = 0;
        double exit = reach;
        double[] minimum = {obstacle.minX - bounds.maxX, obstacle.minZ - bounds.maxZ};
        double[] maximum = {obstacle.maxX - bounds.minX, obstacle.maxZ - bounds.minZ};
        double[] movement = {direction.x, direction.z};
        for (int axis = 0; axis < movement.length; axis++) {
            double speed = movement[axis];
            if (Math.abs(speed) < 1.0e-6) {
                if (minimum[axis] >= 0 || maximum[axis] <= 0) return null;
            } else {
                double first = minimum[axis] / speed;
                double last = maximum[axis] / speed;
                enter = Math.max(enter, Math.min(first, last));
                exit = Math.min(exit, Math.max(first, last));
                if (exit <= enter + 1.0e-6) return null;
            }
        }
        return new Sweep(obstacle, enter, exit);
    }

    static double jumpHeight(double initialVelocity, double gravity) {
        if (initialVelocity <= 0.0 || gravity <= 0.0) {
            return 0.0;
        }
        double height = 0.0;
        double velocity = initialVelocity;
        for (int tick = 0; tick < 100 && velocity > 0.0; tick++) {
            height += velocity;
            velocity = (velocity - gravity) * 0.98;
        }
        return height;
    }

    private static double jumpHeight(Minecraft client) {
        var player = client.player;
        float jumpFactor = client.level.getBlockState(player.blockPosition()).getBlock().getJumpFactor();
        if (jumpFactor == 1.0f) {
            BlockPos support = BlockPos.containing(player.getX(), player.getY() - 0.500001, player.getZ());
            jumpFactor = client.level.getBlockState(support).getBlock().getJumpFactor();
        }
        double initialVelocity = player.getAttributeValue(Attributes.JUMP_STRENGTH) * jumpFactor
                + player.getJumpBoostPower();
        return jumpHeight(initialVelocity, player.getGravity());
    }

    private static boolean hasHeadroom(CollisionSpace space, AABB bounds, double rise) {
        return rise > 0.0 && space.clear(new AABB(bounds.minX + EPSILON, bounds.maxY,
                bounds.minZ + EPSILON, bounds.maxX - EPSILON, bounds.maxY + rise, bounds.maxZ - EPSILON));
    }

    private static CollisionSpace collisionSpace(Minecraft client) {
        return new CollisionSpace() {
            @Override
            public Iterable<AABB> collisions(AABB bounds) {
                List<AABB> boxes = new ArrayList<>();
                for (var shape : client.level.getBlockCollisions(client.player, bounds)) {
                    boxes.addAll(shape.toAabbs());
                }
                return boxes;
            }

            @Override
            public boolean clear(AABB bounds) {
                return client.level.noBlockCollision(client.player, bounds);
            }
        };
    }
}
