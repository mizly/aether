package dev.aether.modules.pathfinding.execution;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.BiPredicate;

public final class FlightPathClearance {
    private static final double EPSILON = 1.0e-6;
    private static final double SEGMENT_LENGTH = 4.0;

    private FlightPathClearance() {}

    public static Vec3 clearCorner(Vec3 from, Vec3 to, BiPredicate<Vec3, Vec3> clear) {
        Vec3 levelled = new Vec3(from.x, to.y, from.z);
        if (clear.test(from, levelled) && clear.test(levelled, to)) return levelled;
        Vec3 across = new Vec3(to.x, from.y, to.z);
        return clear.test(from, across) && clear.test(across, to) ? across : null;
    }

    public static boolean isClear(Minecraft client, Vec3 from, Vec3 to) {
        return isClear(client, from, to, 0.0);
    }

    public static boolean isClear(Minecraft client, Vec3 from, Vec3 to, double margin) {
        return isClear(client, from, to, margin, 0.0);
    }

    public static boolean isClear(Minecraft client, Vec3 from, Vec3 to, double margin, double verticalMargin) {
        if (client.player == null || client.level == null) {
            return false;
        }
        AABB bounds = client.player.getBoundingBox().inflate(margin, verticalMargin, margin)
                .move(from.subtract(client.player.position()));
        return isClear(bounds, to.subtract(from), search -> collisions(client, search));
    }

    public static boolean canCoastHorizontally(Minecraft client) {
        if (client.player == null || client.level == null) return false;
        return canCoastHorizontally(client, client.player.getDeltaMovement());
    }

    public static boolean canCoastHorizontally(Minecraft client, Vec3 velocity) {
        if (client.player == null || client.level == null) return false;
        return canCoastHorizontally(client.player.getBoundingBox(), velocity,
                search -> collisions(client, search));
    }

    static boolean canCoastHorizontally(AABB bounds, Vec3 velocity, Function<AABB, Iterable<AABB>> collisions) {
        for (int tick = 0; tick < 12; tick++) {
            Iterable<AABB> obstacles = collisions.apply(bounds.expandTowards(velocity));
            double vertical = velocity.y;
            if (Math.abs(vertical) > EPSILON) {
                for (AABB obstacle : obstacles) {
                    vertical = Shapes.create(obstacle).collide(Direction.Axis.Y, bounds, vertical);
                }
            }
            Vec3 travel = new Vec3(velocity.x, vertical, velocity.z);
            if (!isClear(bounds, travel, search -> obstacles)
                    || Math.abs(vertical) > EPSILON
                    && !isClear(bounds.move(0, vertical, 0), new Vec3(velocity.x, 0, velocity.z), search -> obstacles)) {
                return false;
            }
            bounds = bounds.move(travel);
            velocity = velocity.multiply(0.91, 0.6, 0.91);
        }
        return true;
    }

    private static List<AABB> collisions(Minecraft client, AABB search) {
        List<AABB> obstacles = new ArrayList<>();
        for (var shape : client.level.getBlockCollisions(client.player, search)) {
            obstacles.addAll(shape.toAabbs());
        }
        return obstacles;
    }

    public static boolean isClear(AABB bounds, Vec3 travel, Function<AABB, Iterable<AABB>> collisions) {
        int segments = Math.max(1, (int) Math.ceil(travel.length() / SEGMENT_LENGTH));
        Vec3 step = travel.scale(1.0 / segments);
        AABB body = bounds.deflate(EPSILON);
        for (int segment = 0; segment < segments; segment++) {
            AABB start = body.move(step.scale(segment));
            for (AABB obstacle : collisions.apply(start.expandTowards(step))) {
                if (intersectsSweep(start, step, obstacle)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean intersectsSweep(AABB bounds, Vec3 travel, AABB obstacle) {
        double enter = 0.0;
        double exit = 1.0;
        double[] minimum = {obstacle.minX - bounds.maxX, obstacle.minY - bounds.maxY, obstacle.minZ - bounds.maxZ};
        double[] maximum = {obstacle.maxX - bounds.minX, obstacle.maxY - bounds.minY, obstacle.maxZ - bounds.minZ};
        double[] movement = {travel.x, travel.y, travel.z};
        for (int axis = 0; axis < movement.length; axis++) {
            if (Math.abs(movement[axis]) < EPSILON) {
                if (minimum[axis] >= 0.0 || maximum[axis] <= 0.0) {
                    return false;
                }
            } else {
                double first = minimum[axis] / movement[axis];
                double last = maximum[axis] / movement[axis];
                enter = Math.max(enter, Math.min(first, last));
                exit = Math.min(exit, Math.max(first, last));
                if (exit <= enter) {
                    return false;
                }
            }
        }
        return true;
    }
}
