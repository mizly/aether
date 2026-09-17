package dev.aether.renderer;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorGlDevice;
import dev.aether.mixin.AccessorGpuDevice;
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
        if (shader.isFailed()) return false;
        var target = client.getMainRenderTarget();
        if (!(target.getColorTexture() instanceof GlTexture color)) return false;
        long now = System.nanoTime();
        if (lastFrameNanos != 0 && !client.isPaused()) {
            animationSeconds += Math.min((now - lastFrameNanos) / 1e9, 0.1) * AetherConfig.SKYBOX_SPEED.get();
        }
        lastFrameNanos = now;
        client.gameRenderer.getMainCamera().getViewRotationProjectionMatrix(inverseViewProjection);
        inverseViewProjection.invert();
        var access = ((AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).aether$getBackend())
                .aether$directStateAccess();
        return shader.draw(inverseViewProjection, Skybox.preset(), (float) animationSeconds,
                AetherConfig.SKYBOX_BRIGHTNESS.get(), color.getFbo(access, target.getDepthTexture()),
                target.width, target.height);
    }

    public static void close() {
        renderedThisFrame = false;
        if (instance != null) {
            instance.shader.close();
            instance = null;
        }
    }
}
