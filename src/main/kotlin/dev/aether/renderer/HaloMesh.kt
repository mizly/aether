package dev.aether.renderer

import org.joml.Matrix4f
import org.joml.Vector3f

class HaloMesh {
    companion object {
        const val MAX_VERTICES = 768
        private const val SEGMENTS = 64
        private val COS = FloatArray(SEGMENTS + 1)
        private val SIN = FloatArray(SEGMENTS + 1)
        
        init {
            for (i in 0 until SEGMENTS) {
                COS[i] = Math.cos(i * Math.PI * 2 / SEGMENTS).toFloat()
                SIN[i] = Math.sin(i * Math.PI * 2 / SEGMENTS).toFloat()
            }
            COS[SEGMENTS] = COS[0]
            SIN[SEGMENTS] = SIN[0]
        }
    }

    private val ring = Matrix4f()
    private val points = Array(SEGMENTS + 1) { Vector3f() }
    private val widths = Array(SEGMENTS + 1) { Vector3f() }
    private val tangent = Vector3f()
    private val normal = Vector3f()
    private val center = Vector3f()

    fun append(mesh: CosmeticMesh, transform: Matrix4f, style: Int, phase: Float, tint: Int, glow: Float) {
        if (!mesh.hasRoom(MAX_VERTICES) || (tint ushr 24) == 0) return
        if (style == 1) {
            ring.set(transform).rotateY(phase * 0.25f).rotateX(0.24f)
            appendRing(mesh, 0.38f, 0.045f, tint, 0.85f, glow, false)
            ring.set(transform).rotateY(phase * 0.25f).rotateX(-0.24f)
            appendRing(mesh, 0.32f, 0.038f, tint, 0.7f, glow, false)
        } else {
            ring.set(transform).rotateY(if (style == 2) phase * 0.2f else 0.0f)
            appendRing(mesh, 0.38f, 0.055f, tint, 0.9f, glow, style == 2)
            if (style == 2) {
                var i = 0
                while (i < SEGMENTS) {
                    ring.transformPosition(tangent.set(COS[i] * 0.45f, 0.0f, SIN[i] * 0.45f))
                    appendGlint(mesh, tangent, 0.055f * ring.getScale(widths[0]).x, tint, glow)
                    i += SEGMENTS / 4
                }
            }
        }
    }

    private fun appendRing(mesh: CosmeticMesh, radius: Float, width: Float, tint: Int, opacity: Float,
                          glow: Float, segmented: Boolean) {
        val scale = ring.getScale(tangent).x
        ring.transformDirection(normal.set(0.0f, 1.0f, 0.0f)).normalize()
        ring.getTranslation(center)
        val edgeOn = Math.abs(normal.dot(center)) <= center.length() * 0.1f
        for (i in 0..SEGMENTS) {
            ring.transformPosition(points[i].set(COS[i] * radius, 0.0f, SIN[i] * radius))
            if (edgeOn) {
                widths[i].set(normal).mul(width * scale)
                continue
            }
            ring.transformDirection(tangent.set(-SIN[i], 0.0f, COS[i]))
            tangent.cross(points[i], widths[i])
            if (widths[i].lengthSquared() < 1.0e-8f) {
                tangent.cross(0.0f, 1.0f, 0.0f, widths[i])
                if (widths[i].lengthSquared() < 1.0e-8f) tangent.cross(1.0f, 0.0f, 0.0f, widths[i])
            }
            widths[i].normalize(width * scale)
            if (i > 0 && widths[i].dot(widths[i - 1]) < 0.0f) widths[i].negate()
        }
        for (i in 0 until SEGMENTS) {
            if (segmented && (i % 16 < 2 || i % 16 >= 14)) continue
            val start = if (segmented) (i % 16 - 2) / 12f else 0.5f
            val end = if (segmented) (i % 16 - 1) / 12f else 0.5f
            vertex(mesh, i, -1.0f, start, tint, opacity, glow)
            vertex(mesh, i, 1.0f, start, tint, opacity, glow)
            vertex(mesh, i + 1, 1.0f, end, tint, opacity, glow)
            vertex(mesh, i, -1.0f, start, tint, opacity, glow)
            vertex(mesh, i + 1, 1.0f, end, tint, opacity, glow)
            vertex(mesh, i + 1, -1.0f, end, tint, opacity, glow)
        }
    }

    private fun vertex(mesh: CosmeticMesh, index: Int, side: Float, along: Float, tint: Int, opacity: Float, glow: Float) {
        val point = points[index]
        val width = widths[index]
        mesh.vertex(null, point.x + width.x * side, point.y + width.y * side, point.z + width.z * side,
                along, side, tint, opacity, 6, 0f, glow)
    }

    private fun appendGlint(mesh: CosmeticMesh, center: Vector3f, radius: Float, tint: Int, glow: Float) {
        val right = widths[1].set(-center.z, 0.0f, center.x)
        if (right.lengthSquared() < 1.0e-8f) right.set(1.0f, 0.0f, 0.0f)
        right.normalize()
        val up = widths[2].set(center).cross(right)
        if (up.lengthSquared() < 1.0e-8f) up.set(0.0f, 1.0f, 0.0f)
        up.normalize()
        mesh.billboard(center.x, center.y, center.z, right, up, radius, tint, 0.8f, 7, 0f, glow)
    }
}