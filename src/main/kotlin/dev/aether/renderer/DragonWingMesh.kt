package dev.aether.renderer

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class DragonWingMesh {
    private val joints = Array(REST.size) { Vector3f() }
    private val wing = Matrix4f()
    private val edgeA = Vector3f()
    private val edgeB = Vector3f()
    private val along = Vector3f()
    private val normal = Vector3f()
    private val tangent = Vector3f()

    fun append(
        mesh: CosmeticMesh, body: Matrix4f, phase: Float, amplitude: Float,
        fold: Float, tint: Int, glow: Boolean
    ) {
        append(mesh, body, phase, amplitude, fold, tint, glow, false)
    }

    fun append(
        mesh: CosmeticMesh, body: Matrix4f, phase: Float, amplitude: Float,
        fold: Float, tint: Int, glow: Boolean, wireframe: Boolean
    ) {
        if (!mesh.hasRoom(3100)) return
        pose(phase, fold)
        for (side in -1..1 step 2) {
            wing.set(body).translate(side * 0.19f, 1.38f, -0.18f)
                .scale(side.toFloat(), 1f, 1f).rotateZ(0.12f + sin(phase) * amplitude)
                .rotateY(fold * 0.35f)
            if (!wireframe) {
                skinVertex(mesh, joints[0], 0f, 0f, tint, phase, glow)
                skinVertex(mesh, joints[2], 1f, 0f, tint, phase, glow)
                skinVertex(mesh, joints[7], 0f, 1f, tint, phase, glow)
            }
            for (panel in 3..6) {
                scallop(panel, 0f, edgeA, phase)
                for (step in 1..EDGE_STEPS) {
                    val t = step / EDGE_STEPS.toFloat()
                    scallop(panel, t, edgeB, phase)
                    if (!wireframe) {
                        skinVertex(mesh, joints[2], 0.5f, 0f, tint, phase, glow)
                        skinVertex(mesh, edgeA, (step - 1f) / EDGE_STEPS, 1f, tint, phase, glow)
                        skinVertex(mesh, edgeB, t, 1f, tint, phase, glow)
                    }
                    bone(mesh, edgeA, edgeB, 0.009f, 0.009f, tint, if (wireframe) 1f else 0.5f)
                    edgeA.set(edgeB)
                }
            }
            val boneColor = if (wireframe) tint else 0xFF756777.toInt()
            bone(mesh, joints[0], joints[1], if (wireframe) 0.018f else 0.075f, if (wireframe) 0.014f else 0.055f, boneColor, 1f)
            bone(mesh, joints[1], joints[2], if (wireframe) 0.014f else 0.055f, if (wireframe) 0.012f else 0.041f, boneColor, 1f)
            for (finger in 3..7) {
                bone(
                    mesh, joints[2], joints[finger], if (wireframe) 0.012f else if (finger == 3) 0.036f else 0.026f,
                    0.006f, boneColor, 1f
                )
            }
            if (wireframe) {
                bone(mesh, joints[7], joints[0], 0.009f, 0.009f, tint, 1f)
            }
        }
    }

    private fun pose(phase: Float, fold: Float) {
        val wristAngle = fold + 0.10f * sin(phase - 0.65f)
        val c = cos(wristAngle)
        val s = sin(wristAngle)
        for (i in REST.indices) {
            val point = REST[i]
            joints[i].set(point[0], point[1], point[2])
            if (i >= 2 && i < 7) {
                val dx = point[0] - REST[1][0]
                val dz = point[2] - REST[1][2]
                joints[i].x = REST[1][0] + c * dx + s * dz
                joints[i].z = REST[1][2] - s * dx + c * dz
            }
        }
    }

    private fun scallop(panel: Int, t: Float, result: Vector3f, phase: Float) {
        val notch = sin(t * PI.toFloat())
        result.set(joints[panel]).lerp(joints[panel + 1], t).lerp(joints[2], notch * 0.24f)
        result.z -= notch * (0.035f + 0.018f * sin(phase - panel * 0.45f))
    }

    private fun skinVertex(mesh: CosmeticMesh, point: Vector3f, u: Float, v: Float, tint: Int, phase: Float, glow: Boolean) {
        mesh.vertex(wing, point.x, point.y, point.z - 0.012f, u, v, tint or 0xFF000000.toInt(), 1f, 0, phase, if (glow) 1f else 0f)
    }

    private fun bone(mesh: CosmeticMesh, a: Vector3f, b: Vector3f, start: Float, end: Float, tint: Int, brightness: Float) {
        along.set(b).sub(a).normalize()
        normal.set(0f, 0f, 1f).cross(along).normalize()
        tangent.set(along).cross(normal)
        for (side in 0 until BONE_SIDES) {
            val light = 0.52f + 0.48f * max(0f, COS[side] * 0.765f + SIN[side] * 0.644f)
            val color = shade(tint, light * brightness)
            boneVertex(mesh, a, start, side, color)
            boneVertex(mesh, b, end, side, color)
            boneVertex(mesh, b, end, side + 1, color)
            boneVertex(mesh, a, start, side, color)
            boneVertex(mesh, b, end, side + 1, color)
            boneVertex(mesh, a, start, side + 1, color)
        }
    }

    private fun boneVertex(mesh: CosmeticMesh, center: Vector3f, radius: Float, side: Int, color: Int) {
        val c = COS[side] * radius
        val s = SIN[side] * radius
        mesh.vertex(
            wing, center.x + normal.x * c + tangent.x * s,
            center.y + normal.y * c + tangent.y * s, center.z + normal.z * c + tangent.z * s,
            0f, 0f, color, 1f, 1, 0f, 0f
        )
    }

    companion object {
        private val REST = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0.62f, 0.30f, -0.03f), floatArrayOf(1.13f, 0.51f, -0.13f),
            floatArrayOf(2.45f, 0.50f, -0.30f), floatArrayOf(2.08f, -0.10f, -0.48f),
            floatArrayOf(1.63f, -0.66f, -0.55f), floatArrayOf(0.95f, -0.83f, -0.48f), floatArrayOf(0.04f, -0.54f, -0.02f)
        )
        private const val EDGE_STEPS = 8
        private const val BONE_SIDES = 6
        private val COS = FloatArray(BONE_SIDES + 1)
        private val SIN = FloatArray(BONE_SIDES + 1)

        init {
            for (i in 0..BONE_SIDES) {
                COS[i] = cos(i * PI * 2 / BONE_SIDES).toFloat()
                SIN[i] = sin(i * PI * 2 / BONE_SIDES).toFloat()
            }
        }

        private fun shade(color: Int, factor: Float): Int {
            return 0xFF000000.toInt() or (((color ushr 16) and 255) * factor).toInt() shl 16 or
                    (((color ushr 8) and 255) * factor).toInt() shl 8 or ((color and 255) * factor).toInt()
        }
    }
}