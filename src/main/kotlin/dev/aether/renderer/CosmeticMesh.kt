package dev.aether.renderer

import org.joml.Matrix4f
import org.joml.Vector3f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CosmeticMesh {
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(MAX_VERTICES * FLOATS_PER_VERTEX * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val point: Vector3f = Vector3f()

    fun clear() { vertices.clear() }
    fun size(): Int = vertices.position() / FLOATS_PER_VERTEX
    fun data(): FloatBuffer = vertices.duplicate().flip()
    fun hasRoom(count: Int): Boolean = vertices.remaining() >= count * FLOATS_PER_VERTEX

    fun vertex(transform: Matrix4f?, x: Float, y: Float, z: Float, u: Float, v: Float,
               color: Int, opacity: Float, material: Int, age: Float, seed: Float) {
        point.set(x, y, z)
        if (transform != null) transform.transformPosition(point)
        vertices.put(point.x).put(point.y).put(point.z).put(u).put(v)
            .put(((color ushr 16) and 255) / 255f).put(((color ushr 8) and 255) / 255f)
            .put((color and 255) / 255f).put(((color ushr 24) and 255) / 255f * opacity)
            .put(material.toFloat()).put(age).put(seed)
    }

    fun billboard(x: Float, y: Float, z: Float, right: Vector3f, up: Vector3f, radius: Float,
                  color: Int, opacity: Float, material: Int, age: Float, seed: Float) {
        if (!hasRoom(6)) return
        billboardVertex(x, y, z, right, up, radius, -1f, -1f, color, opacity, material, age, seed)
        billboardVertex(x, y, z, right, up, radius, 1f, -1f, color, opacity, material, age, seed)
        billboardVertex(x, y, z, right, up, radius, 1f, 1f, color, opacity, material, age, seed)
        billboardVertex(x, y, z, right, up, radius, -1f, -1f, color, opacity, material, age, seed)
        billboardVertex(x, y, z, right, up, radius, 1f, 1f, color, opacity, material, age, seed)
        billboardVertex(x, y, z, right, up, radius, -1f, 1f, color, opacity, material, age, seed)
    }

    private fun billboardVertex(x: Float, y: Float, z: Float, right: Vector3f, up: Vector3f, radius: Float,
                                u: Float, v: Float, color: Int, opacity: Float, material: Int, age: Float, seed: Float) {
        vertex(null, x + radius * (u * right.x + v * up.x), y + radius * (u * right.y + v * up.y),
            z + radius * (u * right.z + v * up.z), u, v, color, opacity, material, age, seed)
    }

    companion object {
        @JvmField
        val FLOATS_PER_VERTEX: Int = 12
        @JvmField
        val MAX_VERTICES: Int = 8190
    }
}