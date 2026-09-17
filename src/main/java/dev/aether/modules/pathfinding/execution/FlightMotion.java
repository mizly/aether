package dev.aether.modules.pathfinding.execution;

import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

public final class FlightMotion {
    private static final double HORIZONTAL_DRAG = 0.91;
    private static final double DRIVE_DEADBAND = 0.04;
    private static final double BRAKE_DEADBAND = 0.18;

    private FlightMotion() {
    }

    public record Input(int forward, int right) {
    }

    public static double coastTicks(double extraTicks) {
        return 1.0 / (1.0 - HORIZONTAL_DRAG) + Math.max(0.0, extraTicks);
    }

    public static Vec3 approachVelocity(
            Vec3 offset, Vec3 targetVelocity, double distance, double maxSpeed, double extraTicks) {
        double horizontal = offset.horizontalDistance();
        double speed = Math.min(maxSpeed, Math.max(0.0, horizontal - distance) / coastTicks(extraTicks));
        Vec3 closing = horizontal > 1.0e-6
                ? new Vec3(offset.x * speed / horizontal, 0.0, offset.z * speed / horizontal)
                : Vec3.ZERO;
        Vec3 desired = closing.add(targetVelocity.x, 0.0, targetVelocity.z);
        double length = desired.horizontalDistance();
        return length > maxSpeed ? desired.scale(maxSpeed / length) : desired;
    }

    public static Input horizontalInput(Vec3 desiredVelocity, Vec3 velocity, float yaw) {
        return horizontalInput(desiredVelocity, velocity, yaw, BRAKE_DEADBAND);
    }

    public static Input brakingInput(Vec3 velocity, float yaw) {
        return horizontalInput(Vec3.ZERO, velocity, yaw, 0.001);
    }

    public static Input avoidObstacles(Input requested, Vec3 velocity, float yaw, double acceleration,
                                       Predicate<Vec3> canCoast) {
        Vec3 desired = velocity.add(acceleration(requested, yaw, acceleration));
        if (canCoast.test(desired)) return requested;
        Input best = brakingInput(velocity, yaw);
        double bestScore = Double.POSITIVE_INFINITY;
        for (int forward = -1; forward <= 1; forward++) {
            for (int right = -1; right <= 1; right++) {
                Input candidate = new Input(forward, right);
                Vec3 next = velocity.add(acceleration(candidate, yaw, acceleration));
                double score = next.distanceToSqr(desired);
                if (score < bestScore && canCoast.test(next)) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    public static Vec3 acceleration(Input input, float yaw, double amount) {
        Vec3 local = new Vec3(-input.right(), 0, input.forward());
        if (local.lengthSqr() > 1) local = local.normalize();
        return local.yRot((float) -Math.toRadians(yaw)).scale(amount);
    }

    private static Input horizontalInput(Vec3 desiredVelocity, Vec3 velocity, float yaw, double brakeDeadband) {
        double angle = Math.toRadians(yaw);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        return new Input(
                axisInput(-desiredVelocity.x * sin + desiredVelocity.z * cos,
                        -velocity.x * sin + velocity.z * cos, brakeDeadband),
                axisInput(-desiredVelocity.x * cos - desiredVelocity.z * sin,
                        -velocity.x * cos - velocity.z * sin, brakeDeadband));
    }

    private static int axisInput(double desired, double current, double brakeDeadband) {
        double error = desired - current;
        // A wider brake band lets a single strong flight input settle without alternating opposite keys.
        double deadband = desired * error <= 0.0 ? brakeDeadband : DRIVE_DEADBAND;
        if (Math.abs(error) <= deadband) {
            return 0;
        }
        return error > 0.0 ? 1 : -1;
    }

    public static boolean shouldCoast(Vec3 offset, Vec3 velocity, double tolerance, double extraTicks) {
        double distance = offset.horizontalDistance();
        if (distance <= tolerance) {
            return true;
        }
        double speed = velocity.horizontalDistance();
        if (speed < 0.01) {
            return false;
        }
        double along = (offset.x * velocity.x + offset.z * velocity.z) / speed;
        double lateral = Math.abs(offset.x * velocity.z - offset.z * velocity.x) / speed;
        // Test the whole coast segment, including a stop beyond the goal.
        return along > 0.0 && lateral <= tolerance
                && speed * coastTicks(extraTicks) >= along - tolerance;
    }

    public static int verticalInput(double heightError, double velocity, double tolerance) {
        double projectedError = heightError - velocity / (1.0 - 0.6);
        if (heightError > tolerance && projectedError > tolerance) {
            return 1;
        }
        if (heightError < -tolerance && projectedError < -tolerance) {
            return -1;
        }
        return 0;
    }

    public static void apply(Minecraft client, Input input) {
        ClientUtils.setKeyMappingState(client.options.keyUp, input.forward() > 0);
        ClientUtils.setKeyMappingState(client.options.keyDown, input.forward() < 0);
        ClientUtils.setKeyMappingState(client.options.keyRight, input.right() > 0);
        ClientUtils.setKeyMappingState(client.options.keyLeft, input.right() < 0);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
    }
}
