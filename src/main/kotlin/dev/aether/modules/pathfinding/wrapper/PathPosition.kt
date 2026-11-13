package dev.aether.modules.pathfinding.wrapper

import net.minecraft.util.Mth

class PathPosition(
    @JvmField val x: Double,
    @JvmField val y: Double,
    @JvmField val z: Double
) {
    fun flooredX(): Int = Mth.floor(x)

    fun flooredY(): Int = Mth.floor(y)

    fun flooredZ(): Int = Mth.floor(z)

    fun centeredX(): Double = flooredX() + 0.5

    fun centeredY(): Double = flooredY() + 0.5

    fun centeredZ(): Double = flooredZ() + 0.5

    fun distanceSquared(other: PathPosition): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    fun distance(other: PathPosition): Double = Math.sqrt(distanceSquared(other))

    fun setX(x: Double): PathPosition = PathPosition(x, y, z)

    fun setY(y: Double): PathPosition = PathPosition(x, y, z)

    fun setZ(z: Double): PathPosition = PathPosition(x, y, z)

    fun add(x: Double, y: Double, z: Double): PathPosition = PathPosition(this.x + x, this.y + y, this.z + z)

    fun add(v: PathVector): PathPosition = add(v.x, v.y, v.z)

    fun subtract(x: Double, y: Double, z: Double): PathPosition = PathPosition(this.x - x, this.y - y, this.z - z)

    fun subtract(v: PathVector): PathPosition = subtract(v.x, v.y, v.z)

    fun toVector(): PathVector = PathVector(x, y, z)

    fun floor(): PathPosition = PathPosition(flooredX().toDouble(), flooredY().toDouble(), flooredZ().toDouble())

    fun mid(): PathPosition = PathPosition(flooredX() + 0.5, flooredY() + 0.5, flooredZ() + 0.5)

    fun midPoint(end: PathPosition): PathPosition =
        PathPosition((x + end.x) / 2, (y + end.y) / 2, (z + end.z) / 2)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathPosition) return false
        return flooredX() == other.flooredX() && flooredY() == other.flooredY() && flooredZ() == other.flooredZ()
    }

    override fun hashCode(): Int {
        var result = flooredX()
        result = 31 * result + flooredY()
        result = 31 * result + flooredZ()
        return result
    }

    override fun toString(): String = "PathPosition{$x, $y, $z}"
}
