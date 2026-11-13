package dev.aether.modules.pathfinding.wrapper

import kotlin.math.sqrt

class PathVector(
    @JvmField val x: Double,
    @JvmField val y: Double,
    @JvmField val z: Double
) {
    fun dot(other: PathVector): Double = x * other.x + y * other.y + z * other.z

    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun distance(other: PathVector): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun setX(x: Double): PathVector = PathVector(x, y, z)

    fun setY(y: Double): PathVector = PathVector(x, y, z)

    fun setZ(z: Double): PathVector = PathVector(x, y, z)

    fun subtract(other: PathVector): PathVector = PathVector(x - other.x, y - other.y, z - other.z)

    fun multiply(value: Double): PathVector = PathVector(x * value, y * value, z * value)

    fun normalize(): PathVector {
        val mag = length()
        return PathVector(x / mag, y / mag, z / mag)
    }

    fun divide(value: Double): PathVector = PathVector(x / value, y / value, z / value)

    fun add(other: PathVector): PathVector = PathVector(x + other.x, y + other.y, z + other.z)

    override fun toString(): String = "PathVector{$x, $y, $z}"
}
