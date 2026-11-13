package dev.aether.renderer

import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.Skybox
import net.minecraft.client.Minecraft
import org.joml.Matrix4f

class SkyboxRenderer private constructor() {
    private val shader = SkyboxShader()
    private val inverseViewProjection = Matrix4f()
    private var lastFrameNanos: Long = 0
    private var animationSeconds: Double = 0.0

    private fun draw(client: Minecraft): Boolean {
        val target = client.gameRenderer.mainRenderTarget() ?: return false
        val now = System.nanoTime()
        if (lastFrameNanos != 0L && !client.isPaused) {
            animationSeconds += Math.min((now - lastFrameNanos) / 1e9, 0.1) * AetherConfig.SKYBOX_SPEED.get()
        }
        lastFrameNanos = now
        client.gameRenderer.mainCamera().getViewRotationProjectionMatrix(inverseViewProjection)
        inverseViewProjection.invert()
        return shader.draw(inverseViewProjection, Skybox.preset(), animationSeconds.toFloat(),
            AetherConfig.SKYBOX_BRIGHTNESS.get(), target.colorTextureView)
    }

    companion object {
        private var instance: SkyboxRenderer? = null
        private var renderedThisFrame = false

        @JvmStatic fun beginFrame() {
            renderedThisFrame = false
            if (!Skybox.isEnabled()) close()
        }

        @JvmStatic fun render(): Boolean {
            if (!Skybox.isEnabled()) return false
            val client = Minecraft.getInstance()
            if (client.level == null || client.window == null) return false
            val renderer = instance ?: SkyboxRenderer().also { instance = it }
            renderedThisFrame = renderer.draw(client)
            return renderedThisFrame
        }

        @JvmStatic fun hidesVanillaClouds(): Boolean = Skybox.isEnabled() && renderedThisFrame

        @JvmStatic fun close() {
            renderedThisFrame = false
            instance?.shader?.close()
            instance = null
        }
    }
}
