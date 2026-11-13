package dev.aether.modules.pest.helpers

import net.minecraft.world.phys.Vec3
import java.util.ArrayList

class PestTrackerTrail {
    private val points = ArrayList<Vec3>()
    private var origin: Vec3? = null
    private var startedAt = 0L
    private var lastParticleAt = 0L
    private var prediction: Prediction? = null

    fun begin(origin: Vec3, now: Long) { reset(); this.origin = origin; startedAt = now; lastParticleAt = now }
    fun reset() { points.clear(); origin = null; prediction = null; startedAt = 0L; lastParticleAt = 0L }

    fun add(point: Vec3, now: Long): Boolean {
        val start = origin ?: return false
        if (now < startedAt || now - startedAt > CAPTURE_TIMEOUT_MS || points.size >= MAX_POINTS || !isFinite(point)) return false
        if (points.isEmpty()) { if (point.distanceTo(start) > 5.0) return false }
        else {
            val step = point.subtract(points.last()); val distance = step.length()
            if (distance < 0.02 || distance > 3.0) return false
            if (points.size > 1) {
                val previous = points.last().subtract(points[points.size - 2])
                if (step.normalize().dot(previous.normalize()) < -0.25) return false
            }
        }
        points.add(point); lastParticleAt = now; prediction = estimate(); return true
    }

    fun belongsTo(requestedAt: Long): Boolean = origin != null && startedAt >= requestedAt
    fun isComplete(now: Long): Boolean = origin != null && (now - startedAt >= CAPTURE_TIMEOUT_MS || points.size >= MIN_POINTS && now - startedAt >= 600L && now - lastParticleAt >= 250L)
    fun prediction(now: Long): Prediction? = if (origin != null && now >= startedAt && now - startedAt <= DISPLAY_TTL_MS) prediction else null
    fun observed(now: Long): List<Vec3> = if (origin != null && now >= startedAt && now - startedAt <= DISPLAY_TTL_MS) java.util.List.copyOf(points) else emptyList()

    private fun estimate(): Prediction? {
        if (points.size < MIN_POINTS) return null
        var length = 0.0
        for (i in 1 until points.size) length += points[i].distanceTo(points[i - 1])
        if (length < 3.0) return null
        val anchor = points.first(); val coefficients = fit(anchor) ?: return null
        var error = 0.0
        for (i in points.indices) error += evaluate(coefficients, i / (points.size - 1).toDouble()).add(anchor).distanceToSqr(points[i])
        if (Math.sqrt(error / points.size) > 0.6) return null
        val tangent = coefficients[1].add(coefficients[2].scale(2.0))
        if (tangent.length() < 1.0) return null
        val extensionLength = Math.min(32.0, length); val extensionTime = Math.min(1.0, extensionLength / tangent.length())
        val end = points.last(); val fittedEnd = evaluate(coefficients, 1.0)
        val extension = ArrayList<Vec3>(); extension.add(end)
        var extended = 0.0
        for (i in 1..32) {
            val next = end.add(evaluate(coefficients, 1 + extensionTime * i / 32.0).subtract(fittedEnd))
            val step = next.subtract(extension.last()); val distance = step.length()
            if (!isFinite(next) || distance < 1.0E-6 || step.normalize().dot(tangent.normalize()) < 0.5) break
            if (extended + distance > extensionLength) { extension.add(extension.last().add(step.scale((extensionLength - extended) / distance))); break }
            extension.add(next); extended += distance
        }
        if (extension.size < 2) return null
        return Prediction(java.util.List.copyOf(points), java.util.List.copyOf(extension), extension.last())
    }

    private fun fit(anchor: Vec3): Array<Vec3>? {
        val matrix = Array(3) { DoubleArray(6) }
        for (i in points.indices) {
            val t = i / (points.size - 1).toDouble(); val basis = doubleArrayOf(1.0, t, t * t); val point = points[i].subtract(anchor)
            for (row in 0..2) { for (col in 0..2) matrix[row][col] += basis[row] * basis[col]; matrix[row][3] += basis[row] * point.x; matrix[row][4] += basis[row] * point.y; matrix[row][5] += basis[row] * point.z }
        }
        for (col in 0..2) {
            var pivot = col
            for (row in col + 1..2) if (Math.abs(matrix[row][col]) > Math.abs(matrix[pivot][col])) pivot = row
            val swap = matrix[col]; matrix[col] = matrix[pivot]; matrix[pivot] = swap
            val divisor = matrix[col][col]; if (Math.abs(divisor) < 1.0E-9) return null
            for (j in col until 6) matrix[col][j] /= divisor
            for (row in 0..2) { if (row == col) continue; val factor = matrix[row][col]; for (j in col until 6) matrix[row][j] -= factor * matrix[col][j] }
        }
        return arrayOf(Vec3(matrix[0][3], matrix[0][4], matrix[0][5]), Vec3(matrix[1][3], matrix[1][4], matrix[1][5]), Vec3(matrix[2][3], matrix[2][4], matrix[2][5]))
    }

    class Prediction(private val observed: List<Vec3>, private val extension: List<Vec3>, private val target: Vec3) {
        fun observed(): List<Vec3> = observed
        fun extension(): List<Vec3> = extension
        fun target(): Vec3 = target
        override fun equals(other: Any?): Boolean = other is Prediction && observed == other.observed && extension == other.extension && target == other.target
        override fun hashCode(): Int = java.util.Objects.hash(observed, extension, target)
        override fun toString(): String = "Prediction[observed=$observed, extension=$extension, target=$target]"
    }

    companion object {
        const val CAPTURE_TIMEOUT_MS = 2_500L
        const val DISPLAY_TTL_MS = 5_000L
        private const val MIN_POINTS = 6
        private const val MAX_POINTS = 128
        private fun evaluate(coefficients: Array<Vec3>, t: Double): Vec3 = coefficients[0].add(coefficients[1].scale(t)).add(coefficients[2].scale(t * t))
        private fun isFinite(point: Vec3?): Boolean = point != null && java.lang.Double.isFinite(point.x) && java.lang.Double.isFinite(point.y) && java.lang.Double.isFinite(point.z)
    }
}
