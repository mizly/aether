package dev.aether.renderer;

import dev.aether.modules.visuals.PestDefeatEffects;
import dev.aether.modules.visuals.StreamerModeManager;
import net.minecraft.client.Minecraft;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class CosmeticWorldRenderer {
    private static CosmeticWorldRenderer instance;
    private final CosmeticMesh mesh = new CosmeticMesh();
    private final CosmeticShader shader = new CosmeticShader();
    private final DragonWingsRenderer wings = new DragonWingsRenderer();
    private final HaloRenderer halo = new HaloRenderer();
    private final Matrix4f projection = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Vector3f right = new Vector3f(), up = new Vector3f();

    private CosmeticWorldRenderer() {}

    public static boolean hasVisibleEffects() {
        if (StreamerModeManager.isEnabled()) return false;
        Minecraft client = Minecraft.getInstance();
        return DragonWingsRenderer.visible(client)
                || HaloRenderer.visible(client)
                || PestDefeatEffects.hasActiveEffects(client);
    }

    public static void render() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !hasVisibleEffects()) return;
        if (instance == null) instance = new CosmeticWorldRenderer();
        instance.draw(client);
    }

    private void draw(Minecraft client) {
        var target = client.gameRenderer.mainRenderTarget();
        if (target == null || target.getDepthTextureView() == null) return;
        long now = System.nanoTime();
        var camera = client.gameRenderer.mainCamera();
        var position = camera.position();
        camera.getViewRotationProjectionMatrix(projection);
        frustum.set(projection);
        right.set(1, 0, 0).rotate(camera.rotation());
        up.set(0, 1, 0).rotate(camera.rotation());
        mesh.clear();
        var player = client.player.position();
        if (DragonWingsRenderer.visible(client) && player.distanceToSqr(position) < 64 * 64
                && frustum.testSphere((float) (player.x - position.x), (float) (player.y + 1 - position.y),
                (float) (player.z - position.z), 4f)) {
            wings.append(mesh, client, position, now);
        }
        int wingVertices = mesh.size();
        if (HaloRenderer.visible(client) && player.distanceToSqr(position) < 64 * 64
                && frustum.testSphere((float) (player.x - position.x),
                (float) (player.y + client.player.getEyeHeight() - position.y),
                (float) (player.z - position.z), 2f * client.player.getScale())) {
            halo.append(mesh, client, position, now);
        }
        if (PestDefeatEffects.hasActiveEffects(client)) {
            for (var burst : PestDefeatEffects.effects().active()) {
                var p = burst.position();
                if (frustum.testSphere((float) (p.x - position.x), (float) (p.y - position.y),
                        (float) (p.z - position.z), burst.scale() * 2)) {
                    PestDefeatMesh.append(mesh, burst, position, right, up, now);
                }
            }
        }
        if (mesh.size() == 0) return;
        shader.draw(mesh, projection, target.getColorTextureView(), target.getDepthTextureView(), wingVertices);
    }

    public static void close() {
        if (instance != null) { instance.shader.close(); instance = null; }
        PestDefeatEffects.clear();
    }
}
