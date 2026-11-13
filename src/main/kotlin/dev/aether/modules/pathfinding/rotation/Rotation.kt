package dev.aether.modules.pathfinding.rotation

class Rotation(
    @JvmField val yaw: Float,
    @JvmField val pitch: Float
) {
    override fun toString(): String = "Rotation{yaw=$yaw, pitch=$pitch}"
}
