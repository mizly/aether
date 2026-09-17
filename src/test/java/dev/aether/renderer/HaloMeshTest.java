package dev.aether.renderer;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HaloMeshTest {
    @Test
    void everyStyleStaysFiniteAndBoundedAtEveryViewingAngle() {
        var halo = new HaloMesh();
        var mesh = new CosmeticMesh();
        var transform = new Matrix4f();
        for (int style = 0; style < 3; style++) {
            for (int frame = 0; frame < 100; frame++) {
                mesh.clear();
                float scale = frame % 2 == 0 ? 0.7f : 1.5f;
                transform.translation(0, 0, -4).rotateX(frame * 0.063f).rotateZ(-0.44f).scale(scale);
                halo.append(mesh, transform, style, frame * 0.13f, 0xFFFFEAC2, frame / 99f);
                assertTrue(mesh.size() > 0 && mesh.size() <= HaloMesh.MAX_VERTICES);
                assertEquals(0, mesh.size() % 3);
                var data = mesh.data();
                for (int i = 0; i < data.limit(); i += CosmeticMesh.FLOATS_PER_VERTEX) {
                    float x = data.get(i), y = data.get(i + 1), z = data.get(i + 2) + 4;
                    assertTrue(x * x + y * y + z * z <= scale * scale * 0.55f * 0.55f);
                }
                while (data.hasRemaining()) assertTrue(Float.isFinite(data.get()));
            }
        }
    }

    @Test
    void cameraAtRingCenterDoesNotProduceInvalidVertices() {
        var halo = new HaloMesh();
        var mesh = new CosmeticMesh();
        for (int style = 0; style < 3; style++) {
            mesh.clear();
            halo.append(mesh, new Matrix4f(), style, 0, 0xFFFFFFFF, 1);
            var data = mesh.data();
            while (data.hasRemaining()) assertTrue(Float.isFinite(data.get()));
        }
    }

    @Test
    void closedRingHasAnExactSeamAndPreservesColorOpacity() {
        var mesh = new CosmeticMesh();
        new HaloMesh().append(mesh, new Matrix4f().translation(0, 0, -4), 0, 0, 0x804080C0, 0);
        var data = mesh.data();
        int last = (mesh.size() - 1) * CosmeticMesh.FLOATS_PER_VERTEX;
        for (int axis = 0; axis < 3; axis++) assertEquals(data.get(axis), data.get(last + axis));
        assertEquals(64 / 255f, data.get(5));
        assertEquals(128 / 255f, data.get(6));
        assertEquals(192 / 255f, data.get(7));
        assertEquals(128 / 255f * 0.9f, data.get(8));
    }

    @Test
    void haloQuadsDoNotTwistAsTheCameraCrossesTheRingPlane() {
        var halo = new HaloMesh();
        var mesh = new CosmeticMesh();
        int stride = CosmeticMesh.FLOATS_PER_VERTEX;
        for (int style = 0; style < 3; style++) for (int frame = -20; frame <= 20; frame++) {
            mesh.clear();
            halo.append(mesh, new Matrix4f().translation(0, frame * 0.001f, -1.2f), style, 0.3f, -1, 1);
            var data = mesh.data();
            for (int base = 0; base < data.limit(); base += 6 * stride) {
                float dot = 0;
                for (int axis = 0; axis < 3; axis++) {
                    float startWidth = data.get(base + stride + axis) - data.get(base + axis);
                    float endWidth = data.get(base + 2 * stride + axis) - data.get(base + 5 * stride + axis);
                    dot += startWidth * endWidth;
                }
                assertTrue(dot >= 0, "Ribbon edges must not cross at the silhouette");
            }
        }
    }

    @Test
    void transparentHaloAndInsufficientCapacityLeaveTheStreamUntouched() {
        var halo = new HaloMesh();
        var mesh = new CosmeticMesh();
        var transform = new Matrix4f().translation(0, 0, -4);
        halo.append(mesh, transform, 0, 0, 0x00FFEAC2, 1);
        assertEquals(0, mesh.size());
        int occupied = CosmeticMesh.MAX_VERTICES - HaloMesh.MAX_VERTICES + 3;
        for (int i = 0; i < occupied; i++) mesh.vertex(null, 0, 0, 0, 0, 0, -1, 1, 1, 0, 0);
        halo.append(mesh, transform, 1, 0, -1, 1);
        assertEquals(occupied, mesh.size());
    }
}
