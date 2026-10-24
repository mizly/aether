package dev.aether.renderer;

import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.Skybox;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

public final class SkyboxRenderer {
    private static SkyboxRenderer instance;
    private static boolean renderedThisFrame;
    private final SkyboxShader shader = new SkyboxShader();
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private long lastFrameNanos;
    private double animationSeconds;

    private SkyboxRenderer() {}

    public static void beginFrame() {
        renderedThisFrame = false;
        if (!Skybox.isEnabled()) close();
    }

    public static boolean render() {
        if (!Skybox.isEnabled()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.getWindow() == null) return false;
        if (instance == null) instance = new SkyboxRenderer();
        renderedThisFrame = instance.draw(client);
        return renderedThisFrame;
    }

    public static boolean hidesVanillaClouds() {
        return Skybox.isEnabled() && renderedThisFrame;
    }

    private boolean draw(Minecraft client) {
        var target = client.gameRenderer.mainRenderTarget();
        if (target == null) return false;
        long now = System.nanoTime();
        if (lastFrameNanos != 0 && !client.isPaused()) {
            animationSeconds += Math.min((now - lastFrameNanos) / 1e9, 0.1) * AetherConfig.SKYBOX_SPEED.get();
        }
        lastFrameNanos = now;
        client.gameRenderer.mainCamera().getViewRotationProjectionMatrix(inverseViewProjection);
        inverseViewProjection.invert();
        return shader.draw(inverseViewProjection, Skybox.preset(), (float) animationSeconds,
                AetherConfig.SKYBOX_BRIGHTNESS.get(), target.getColorTextureView());
    }

    public static void close() {
        renderedThisFrame = false;
        if (instance != null) {
            instance.shader.close();
            instance = null;
        }
    }
}
