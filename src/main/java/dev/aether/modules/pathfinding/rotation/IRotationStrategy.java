package dev.aether.modules.pathfinding.rotation;

import net.minecraft.client.player.LocalPlayer;

public interface IRotationStrategy {
    // called each tick while rotating; null signals completion
    Rotation onRotate(LocalPlayer player, float targetYaw, float targetPitch);

    default void onStart() {}
    default void onStop() {}
}
