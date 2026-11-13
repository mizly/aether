package dev.aether.renderer

import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.FreecamManager
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

class HaloRenderer {
    private val model = HaloMesh()
    private val head = Matrix4f()
    private var phase = 0f
    private var lastFrame = 0L

    companion object {
        @JvmStatic
        fun visible(client: Minecraft): Boolean {
            return AetherConfig.HALO_ENABLED.get() && client.player != null && client.level != null
                    && client.player!!.isAlive && !client.player!!.isSpectator
                    && !client.player!!.isInvisible && !client.player!!.isSleeping
                    && (FreecamManager.isEnabled() || !client.options.cameraType.isFirstPerson)
        }
    }

    fun append(mesh: CosmeticMesh, client: Minecraft, camera: Vec3, now: Long) {
        val dt = if (lastFrame == 0L) 0f else Math.clamp((now - lastFrame) / 1_000_000_000f, 0f, 0.1f)
        lastFrame = now
        val speed = AetherConfig.HALO_SPEED.get()
        phase = if (speed == 0f) 0f else (phase + dt * speed * 0.9f) % (Math.PI * 40).toFloat()
        val player = client.player!!
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(false)
        val position = player.getEyePosition(partial)
        val scale = player.scale
        val yaw = Mth.rotLerp(partial, player.yHeadRotO, player.yHeadRot)
        head.identity().translate((position.x - camera.x).toFloat(), (position.y - camera.y).toFloat(),
                (position.z - camera.z).toFloat()).rotateY(Math.toRadians(-yaw.toDouble()).toFloat()).scale(scale)
        val swim = if (player.isFallFlying) 1f else player.getSwimAmount(partial)
        head.translate(0f, 0f, swim * 0.65f)
        val bob = Math.sin(phase.toDouble()).toFloat() * 0.025f
        head.translate(0f, 0.22f + AetherConfig.HALO_HEIGHT.get() + bob, 0f)
                .rotateX(Math.toRadians(AetherConfig.HALO_TILT.get().toDouble()).toFloat())
                .rotateZ(Math.toRadians((AetherConfig.HALO_TILT.get() * 0.35f).toDouble()).toFloat())
                .scale(AetherConfig.HALO_SCALE.get())
        model.append(mesh, head, AetherConfig.HALO_STYLE.get(), phase,
                AetherConfig.HALO_COLOR.get(), AetherConfig.HALO_GLOW.get())
    }
}