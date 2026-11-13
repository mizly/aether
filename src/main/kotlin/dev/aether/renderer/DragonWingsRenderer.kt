package dev.aether.renderer

import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.FreecamManager
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

class DragonWingsRenderer {
    private val model = DragonWingMesh()
    private val body = Matrix4f()
    private var phase = 0f
    private var amplitude = 0.16f
    private var fold = 0.25f
    private var lastFrame = 0L

    companion object {
        @JvmStatic
        fun visible(client: Minecraft): Boolean {
            return AetherConfig.DRAGON_WINGS_ENABLED.get() && client.player != null && client.level != null
                    && !client.player!!.isSpectator && !client.player!!.isInvisible && !client.player!!.isSleeping
                    && (FreecamManager.isEnabled() || !client.options.cameraType.isFirstPerson)
        }
    }

    fun append(mesh: CosmeticMesh, client: Minecraft, camera: Vec3, now: Long) {
        val dt = if (lastFrame == 0L) 0f else Math.clamp((now - lastFrame) / 1_000_000_000f, 0f, 0.1f)
        lastFrame = now
        val player = client.player
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(false)
        val movement = Math.clamp(player!!.deltaMovement.horizontalDistance() * 6, 0.0, 1.0).toFloat()
        val gliding = player.isFallFlying
        val flying = player.abilities.flying
        val targetAmplitude = if (gliding) 0.045f else if (flying) 0.42f else 0.16f + movement * 0.14f
        val targetFold = if (player.isCrouching) 0.75f else if (gliding) 0.06f else 0.25f
        val follow = 1f - Math.exp(-6f * dt.toDouble()).toFloat()
        amplitude += (targetAmplitude - amplitude) * follow
        fold += (targetFold - fold) * follow
        phase = (phase + dt * AetherConfig.DRAGON_WINGS_SPEED.get() * (if (gliding) 1.8f else 3.2f + movement))
                .rem(Math.PI.toFloat() * 2)
        val position = player.getPosition(partial)
        val yaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot)
        body.identity().translate((position.x - camera.x).toFloat(), (position.y - camera.y).toFloat(),
                (position.z - camera.z).toFloat()).rotateY(Math.toRadians(-yaw.toDouble()).toFloat())
        if (gliding || player.isVisuallySwimming) {
            body.translate(0f, 0.9f, 0f).rotateX(Math.toRadians(-90.0 - player.xRot.toDouble()).toFloat()).translate(0f, -0.9f, 0f)
        } else if (player.isCrouching) {
            body.translate(0f, -0.2f, 0f).rotateX(-0.18f)
        }
        val scale = AetherConfig.DRAGON_WINGS_SCALE.get()
        body.translate(0f, 1.38f, -0.18f).scale(scale).translate(0f, -1.38f, 0.18f)
        model.append(mesh, body, phase, amplitude, fold, AetherConfig.DRAGON_WINGS_COLOR.get(),
                AetherConfig.DRAGON_WINGS_GLOW.get(), AetherConfig.DRAGON_WINGS_WIREFRAME.get())
    }
}