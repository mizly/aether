package dev.aether.modules.pathfinding.execution;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// everything FlightGuidance needs to know about the player and the blocks around it
public interface FlightView {
    Vec3 position();

    Vec3 eyePosition();

    Vec3 velocity();

    float yaw();

    float pitch();

    double flyingSpeed();

    boolean sprinting();

    long nowMillis();

    AABB bodyAt(Vec3 feet);

    Iterable<AABB> collisions(AABB search);
}
