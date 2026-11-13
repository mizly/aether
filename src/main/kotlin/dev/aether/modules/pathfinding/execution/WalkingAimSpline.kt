package dev.aether.modules.pathfinding.execution

import net.minecraft.world.phys.Vec3

class WalkingAimSpline(feetAnchors: List<Vec3>) {
    private val anchors: List<Vec3> = java.util.List.copyOf(feetAnchors)
    private val samples = ArrayList<Vec3>()
    private val parameters = ArrayList<Double>()
    private val distances = ArrayList<Double>()
    private var progress = 0.0
    private var sampleIndex = 0

    init {
        if (anchors.isNotEmpty()) {
            append(anchors.first(), 0.0)
            for (i in 1 until anchors.size - 1) {
                val before = anchors[i - 1]; val corner = anchors[i]; val after = anchors[i + 1]
                val entryRatio = cornerRatio(before, corner); val exitRatio = cornerRatio(corner, after)
                val entry = corner.lerp(before, entryRatio); val exit = corner.lerp(after, exitRatio)
                append(entry, i - entryRatio)
                val steps = Math.max(4, Math.ceil((entry.distanceTo(corner) + corner.distanceTo(exit)) / SAMPLE_SPACING).toInt())
                for (step in 1..steps) {
                    val t = step.toDouble() / steps
                    val point = entry.scale((1.0 - t) * (1.0 - t)).add(corner.scale(2.0 * (1.0 - t) * t)).add(exit.scale(t * t))
                    append(point, i - entryRatio + (entryRatio + exitRatio) * t)
                }
            }
            if (anchors.size > 1) append(anchors.last(), anchors.size - 1.0)
        }
    }

    fun aimPoint(playerFeet: Vec3, pursuitSegment: Int, lookaheadBlocks: Double, eyeHeight: Double): Vec3 {
        if (samples.isEmpty()) return playerFeet.add(0.0, eyeHeight, 0.0)
        val segment = Math.max(0, Math.min(pursuitSegment, anchors.size - 1))
        val useHeight = segment + 1 < anchors.size && WalkingRoute.useHeightForProjection(anchors[segment + 1].subtract(anchors[segment]))
        val minParameter = if (segment == 0) 0.0 else segment - cornerRatio(anchors[segment - 1], anchors[segment])
        var end = Math.min(segment + 1, anchors.size - 1)
        while (end + 1 < anchors.size && anchors[end].y < anchors[end - 1].y) {
            val nextDirection = anchors[end + 1].subtract(anchors[end])
            if (nextDirection.y >= 0.0 || nextDirection.horizontalDistanceSqr() > 1.0e-12) break
            end++
        }
        val maxParameter = if (end + 1 >= anchors.size) anchors.size - 1.0 else end + cornerRatio(anchors[end], anchors[end + 1])
        var closestDistance = Double.POSITIVE_INFINITY
        var projectedProgress = progress
        var i = lowerSample(minParameter)
        while (i + 1 < samples.size) {
            if (parameters[i] > maxParameter) break
            val a = samples[i]; val delta = samples[i + 1].subtract(a)
            val parameterSpan = parameters[i + 1] - parameters[i]
            var minT = if (parameterSpan > 1.0e-9) Math.max(0.0, (minParameter - parameters[i]) / parameterSpan) else 0.0
            val maxT = if (parameterSpan > 1.0e-9) Math.min(1.0, (maxParameter - parameters[i]) / parameterSpan) else 1.0
            val distanceSpan = distances[i + 1] - distances[i]
            if (distanceSpan > 1.0e-9) minT = Math.max(minT, (progress - distances[i]) / distanceSpan)
            if (minT <= maxT) {
                val offset = playerFeet.subtract(a); val horizontalLength = delta.horizontalDistanceSqr()
                val projectHeight = useHeight || horizontalLength < 1.0e-12
                val length = if (projectHeight) delta.lengthSqr() else horizontalLength
                if (length >= 1.0e-12) {
                    var t = if (projectHeight) offset.dot(delta) / length else (offset.x * delta.x + offset.z * delta.z) / length
                    t = Math.max(minT, Math.min(maxT, t))
                    val error = offset.subtract(delta.scale(t))
                    val distance = if (projectHeight) error.lengthSqr() else error.horizontalDistanceSqr()
                    if (distance < closestDistance - 1.0e-9) {
                        closestDistance = distance
                        projectedProgress = distances[i] + (distances[i + 1] - distances[i]) * t
                    }
                }
            }
            i++
        }
        progress = Math.max(progress, projectedProgress)
        val targetDistance = Math.min(distances.last(), progress + Math.max(0.0, lookaheadBlocks))
        sampleIndex = lowerDistance(targetDistance)
        if (sampleIndex + 1 >= samples.size) return samples.last().add(0.0, eyeHeight, 0.0)
        val length = distances[sampleIndex + 1] - distances[sampleIndex]
        val t = if (length > 1.0e-9) (targetDistance - distances[sampleIndex]) / length else 0.0
        return samples[sampleIndex].lerp(samples[sampleIndex + 1], t).add(0.0, eyeHeight, 0.0)
    }

    fun points(eyeHeight: Double): List<Vec3> = samples.stream().map { it.add(0.0, eyeHeight, 0.0) }.toList()
    private fun cornerRatio(from: Vec3, to: Vec3): Double { val length = from.distanceTo(to); return if (length > 1.0e-6) Math.min(0.5, CORNER_RADIUS / length) else 0.0 }
    fun index(): Int = sampleIndex
    private fun append(point: Vec3, parameter: Double) {
        val distance = if (samples.isEmpty()) 0.0 else distances.last() + samples.last().distanceTo(point)
        samples.add(point); parameters.add(parameter); distances.add(distance)
    }
    private fun lowerSample(parameter: Double) = lowerIndex(parameters, parameter)
    private fun lowerDistance(distance: Double) = lowerIndex(distances, distance)

    companion object {
        private const val SAMPLE_SPACING = 0.15
        private const val CORNER_RADIUS = 1.0
        private fun lowerIndex(values: List<Double>, target: Double): Int {
            var low = 0; var high = values.size - 1
            while (low < high) {
                val middle = (low + high + 1) ushr 1
                if (values[middle] <= target) low = middle else high = middle - 1
            }
            return low
        }
    }
}
