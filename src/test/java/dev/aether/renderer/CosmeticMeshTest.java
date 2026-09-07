package dev.aether.renderer;

import dev.aether.modules.visuals.DefeatEffectPool;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticMeshTest {
    @Test
    void allWingPosesAndEightMaximumBurstsFitTheFixedBuffer() {
        var mesh = new CosmeticMesh();
        var wing = new DragonWingMesh();
        var body = new Matrix4f();
        var right = new Vector3f(1, 0, 0);
        var up = new Vector3f(0, 1, 0);
        for (int frame = 0; frame < 100; frame++) {
            mesh.clear();
            wing.append(mesh, body, frame * 0.063f, 0.42f, frame / 100f, 0xFFB080F5, true, frame % 2 == 1);
            int vertices = mesh.size();
            assertTrue(vertices > 0 && vertices < 3100);
            for (int i = 0; i < DefeatEffectPool.MAX_ACTIVE; i++) {
                var burst = new DefeatEffectPool.Burst(new Vec3(0, 0, -4), i % 3, 2, 24, i, 0);
                PestDefeatMesh.append(mesh, burst, Vec3.ZERO, right, up, frame * 12_000_000L);
            }
            assertEquals(vertices + 8 * 25 * 6, mesh.size());
            assertTrue(mesh.size() <= 4300);
            assertEquals(0, mesh.size() % 3);
            var data = mesh.data();
            while (data.hasRemaining()) assertTrue(Float.isFinite(data.get()), "No invalid vertices at folded poses");
        }
    }

    @Test
    void expiredAndDistantBurstsEmitNoGeometry() {
        var mesh = new CosmeticMesh();
        var burst = new DefeatEffectPool.Burst(Vec3.ZERO, 0, 1, 24, 0, 0);
        PestDefeatMesh.append(mesh, burst, Vec3.ZERO, new Vector3f(1, 0, 0), new Vector3f(0, 1, 0),
                DefeatEffectPool.DURATION_NANOS);
        PestDefeatMesh.append(mesh, burst, new Vec3(49, 0, 0), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), 100);
        assertEquals(0, mesh.size());
    }
}
