package dev.aether.modules.pathfinding.rotation

import net.minecraft.client.player.LocalPlayer

interface IRotationStrategy {
    // called each tick while rotating; null signals completion
    fun onRotate(player: LocalPlayer, targetYaw: Float, targetPitch: Float): Rotation?

    fun onStart() {}

    fun onStop() {}
}
