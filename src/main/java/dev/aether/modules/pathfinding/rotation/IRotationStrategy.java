package dev.aether.modules.pathfinding.rotation;

import net.minecraft.client.player.LocalPlayer;

public interface IRotationStrategy {
    /**
     * Called each tick while rotating. Return null to signal completion.
     */
    Rotation onRotate(LocalPlayer player, float targetYaw, float targetPitch);

    default void onStart() {}
    default void onStop() {}
}
