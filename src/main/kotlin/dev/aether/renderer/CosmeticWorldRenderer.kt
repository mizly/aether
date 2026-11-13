package dev.aether.renderer

import dev.aether.modules.visuals.PestDefeatEffects
import dev.aether.modules.visuals.StreamerModeManager
import net.minecraft.client.Minecraft
import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.joml.Vector3f

class CosmeticWorldRenderer private constructor() {
    private val mesh = CosmeticMesh()
    private val shader = CosmeticShader()
    private val wings = DragonWingsRenderer()
    private val halo = HaloRenderer()
    private val projection = Matrix4f()
    private val frustum = FrustumIntersection()
    private val right = Vector3f()
    private val up = Vector3f()

    private fun draw(client: Minecraft) {
        val target = client.gameRenderer.mainRenderTarget()
        if (target == null || target.getDepthTextureView() == null) return
        val now = System.nanoTime()
        val camera = client.gameRenderer.mainCamera()
        val position = camera.position()
        camera.getViewRotationProjectionMatrix(projection)
        frustum.set(projection)
        right.set(1.0f, 0.0f, 0.0f).rotate(camera.rotation())
        up.set(0.0f, 1.0f, 0.0f).rotate(camera.rotation())
        mesh.clear()
        val player = client.player ?: return
        val playerPos = player.position()
        if (DragonWingsRenderer.visible(client) && player.distanceToSqr(position.x, position.y, position.z) < 64 * 64
                && frustum.testSphere((playerPos.x - position.x).toFloat(), (playerPos.y + 1 - position.y).toFloat(),
                (playerPos.z - position.z).toFloat(), 4f)) {
            wings.append(mesh, client, position, now)
        }
        val wingVertices = mesh.size()
        if (HaloRenderer.visible(client) && player.distanceToSqr(position.x, position.y, position.z) < 64 * 64
                && frustum.testSphere((playerPos.x - position.x).toFloat(),
                (playerPos.y + player.getEyeHeight() - position.y).toFloat(),
                (playerPos.z - position.z).toFloat(), 2f * player.getScale())) {
            halo.append(mesh, client, position, now)
        }
        if (PestDefeatEffects.hasActiveEffects(client)) {
            for (burst in PestDefeatEffects.effects().active()) {
                val p = burst.position()
                if (frustum.testSphere((p.x - position.x).toFloat(), (p.y - position.y).toFloat(),
                        (p.z - position.z).toFloat(), burst.scale() * 2)) {
                    PestDefeatMesh.append(mesh, burst, position, right, up, now)
                }
            }
        }
        if (mesh.size() == 0) return
        shader.draw(mesh, projection, target.getColorTextureView(), target.getDepthTextureView(), wingVertices)
    }

    companion object {
        private var instance: CosmeticWorldRenderer? = null

        @JvmStatic
        fun hasVisibleEffects(): Boolean {
            if (StreamerModeManager.isEnabled()) return false
            val client = Minecraft.getInstance()
            return DragonWingsRenderer.visible(client)
                    || HaloRenderer.visible(client)
                    || PestDefeatEffects.hasActiveEffects(client)
        }

        @JvmStatic
        fun render() {
            val client = Minecraft.getInstance()
            if (client.player == null || client.level == null || !hasVisibleEffects()) return
            if (instance == null) instance = CosmeticWorldRenderer()
            instance!!.draw(client)
        }

        @JvmStatic
        fun close() {
            if (instance != null) {
                instance!!.shader.close()
                instance = null
            }
            PestDefeatEffects.clear()
        }
    }
}