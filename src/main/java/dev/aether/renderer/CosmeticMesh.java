package dev.aether.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Reused, bounded triangle stream for cosmetic geometry. */
public final class CosmeticMesh {
    public static final int FLOATS_PER_VERTEX = 12;
    public static final int MAX_VERTICES = 8190;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(MAX_VERTICES * FLOATS_PER_VERTEX * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final Vector3f point = new Vector3f();

    public void clear() { vertices.clear(); }
    public int size() { return vertices.position() / FLOATS_PER_VERTEX; }
    public FloatBuffer data() { return vertices.duplicate().flip(); }
    public boolean hasRoom(int count) { return vertices.remaining() >= count * FLOATS_PER_VERTEX; }

    public void vertex(Matrix4f transform, float x, float y, float z, float u, float v,
                       int color, float opacity, int material, float age, float seed) {
        point.set(x, y, z);
        if (transform != null) transform.transformPosition(point);
        vertices.put(point.x).put(point.y).put(point.z).put(u).put(v)
                .put(((color >>> 16) & 255) / 255f).put(((color >>> 8) & 255) / 255f)
                .put((color & 255) / 255f).put(((color >>> 24) & 255) / 255f * opacity)
                .put(material).put(age).put(seed);
    }

    public void billboard(float x, float y, float z, Vector3f right, Vector3f up, float radius,
                          int color, float opacity, int material, float age, float seed) {
        if (!hasRoom(6)) return;
        billboardVertex(x, y, z, right, up, radius, -1, -1, color, opacity, material, age, seed);
        billboardVertex(x, y, z, right, up, radius, 1, -1, color, opacity, material, age, seed);
        billboardVertex(x, y, z, right, up, radius, 1, 1, color, opacity, material, age, seed);
        billboardVertex(x, y, z, right, up, radius, -1, -1, color, opacity, material, age, seed);
        billboardVertex(x, y, z, right, up, radius, 1, 1, color, opacity, material, age, seed);
        billboardVertex(x, y, z, right, up, radius, -1, 1, color, opacity, material, age, seed);
    }

    private void billboardVertex(float x, float y, float z, Vector3f right, Vector3f up, float radius,
                                 float u, float v, int color, float opacity, int material, float age, float seed) {
        vertex(null, x + radius * (u * right.x + v * up.x), y + radius * (u * right.y + v * up.y),
                z + radius * (u * right.z + v * up.z), u, v, color, opacity, material, age, seed);
    }
}
