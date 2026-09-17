package dev.aether.renderer;

import dev.aether.modules.visuals.DefeatEffectPool;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class PestDefeatMesh {
    private static final int[] COLORS = {0xFFB179FF, 0xFF63E8E3, 0xFFFF9B39};

    private PestDefeatMesh() {}

    public static void append(CosmeticMesh mesh, DefeatEffectPool.Burst burst, Vec3 camera,
                              Vector3f right, Vector3f up, long now) {
        float age = burst.age(now);
        double distanceSquared = burst.position().distanceToSqr(camera);
        if (age >= 1f || distanceSquared > 48 * 48 || distanceSquared < 0.25) return;
        float distance = (float) Math.sqrt(distanceSquared);
        float nearFade = Math.clamp((distance - 0.5f) / 1.5f, 0f, 1f);
        float x = (float) (burst.position().x - camera.x);
        float y = (float) (burst.position().y - camera.y);
        float z = (float) (burst.position().z - camera.z);
        int color = COLORS[burst.style()];
        mesh.billboard(x, y, z, right, up, 1.35f * Math.min(burst.scale(), distance * 0.3f),
                color, 0.9f * nearFade, burst.style() + 2, age, burst.seed());
        int count = distanceSquared > 24 * 24 ? 8 : burst.particles();
        float travel = (float) (1.0 - Math.exp(-age * 3.2)) * burst.scale();
        float fade = (1f - age) * (1f - age) * nearFade;
        for (int i = 0; i < count; i++) {
            float angle = i * 2.399963f + burst.seed();
            float vertical = 1f - 2f * (i + 0.5f) / count;
            float radial = (float) Math.sqrt(1f - vertical * vertical);
            float radius = travel * (0.75f + (i % 5) * 0.19f);
            float px = x + (float) Math.cos(angle) * radial * radius;
            float py = y + vertical * radius + (burst.style() == 2 ? -0.45f : 0.3f) * age * age;
            float pz = z + (float) Math.sin(angle) * radial * radius;
            mesh.billboard(px, py, pz, right, up, (0.035f + (i % 3) * 0.013f) * burst.scale(),
                    color, fade, 5, age, angle);
        }
    }
}
