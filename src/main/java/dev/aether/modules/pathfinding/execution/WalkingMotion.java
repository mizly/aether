package dev.aether.modules.pathfinding.execution;

import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class WalkingMotion {
    private static final int[] FORWARD = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] RIGHT = {0, 1, 1, 1, 0, -1, -1, -1};

    private WalkingMotion() {
    }

    public record Input(int forward, int right) {
    }

    public static Input horizontalInput(Vec3 desiredDirection, float actualPlayerYaw, double deadzone) {
        double distance = desiredDirection.horizontalDistance();
        if (!Double.isFinite(distance) || !Float.isFinite(actualPlayerYaw)
                || distance <= Math.max(1.0e-6, deadzone)) {
            return new Input(0, 0);
        }
        double yaw = Math.toRadians(actualPlayerYaw);
        double forward = -desiredDirection.x * Math.sin(yaw) + desiredDirection.z * Math.cos(yaw);
        double right = -desiredDirection.x * Math.cos(yaw) - desiredDirection.z * Math.sin(yaw);
        int octant = Math.floorMod((int) Math.round(Math.atan2(right, forward) / (Math.PI / 4.0)), 8);
        return new Input(FORWARD[octant], RIGHT[octant]);
    }

    public static Vec3 direction(Input input, float actualPlayerYaw) {
        double yaw = Math.toRadians(actualPlayerYaw);
        Vec3 direction = new Vec3(
                -input.forward() * Math.sin(yaw) - input.right() * Math.cos(yaw),
                0.0,
                input.forward() * Math.cos(yaw) - input.right() * Math.sin(yaw));
        return direction.lengthSqr() > 1.0e-12 ? direction.normalize() : Vec3.ZERO;
    }

    public static void apply(Minecraft client, Input input) {
        ClientUtils.setKeyMappingState(client.options.keyUp, input.forward() > 0);
        ClientUtils.setKeyMappingState(client.options.keyDown, input.forward() < 0);
        ClientUtils.setKeyMappingState(client.options.keyRight, input.right() > 0);
        ClientUtils.setKeyMappingState(client.options.keyLeft, input.right() < 0);
    }
}
