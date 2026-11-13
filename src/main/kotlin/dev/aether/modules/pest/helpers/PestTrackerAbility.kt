package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.gizmos.Gizmos
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.phys.Vec3

class PestTrackerAbility private constructor() {
    companion object {
        private val TRAIL = PestTrackerTrail()
        private var captureLevel: ClientLevel? = null
        private var nextUseAt = 0L

        @JvmStatic @Synchronized fun onAttack(client: Minecraft) {
            if (client.player == null || client.level == null || client.player!!.isShiftKeyDown
                || !PestLoadoutHelper.isVacuum(client.player!!.mainHandItem)
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
                || !AetherConfig.PEST_TRACKER_DRAW_ARC.get()
                && !(AetherConfig.USE_PEST_TRACKER_ABILITY.get() && PestDestroyer.isActive() && PestDestroyer.getState() == PestDestroyer.State.GET_LOCATION)) return
            val now = System.currentTimeMillis()
            if (!canUse(now)) return
            captureLevel = client.level
            TRAIL.begin(client.player!!.position(), now)
            nextUseAt = now + 1_100L
        }

        @JvmStatic @Synchronized fun tick(client: Minecraft) {
            if (client.player == null || client.level !== captureLevel || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
                || !AetherConfig.PEST_TRACKER_DRAW_ARC.get() && !(AetherConfig.USE_PEST_TRACKER_ABILITY.get() && PestDestroyer.isActive())) clear()
        }

        @JvmStatic @Synchronized fun clear() { TRAIL.reset(); captureLevel = null }
        @JvmStatic @Synchronized fun canUse(now: Long): Boolean = now >= nextUseAt

        @JvmStatic @Synchronized fun onParticlePacket(client: Minecraft, packet: ClientboundLevelParticlesPacket) {
            if (!client.isSameThread || client.level !== captureLevel || captureLevel == null || !matchesPacket(packet)) return
            TRAIL.add(Vec3(packet.x, packet.y, packet.z), System.currentTimeMillis())
        }

        @JvmStatic fun matchesPacket(packet: ClientboundLevelParticlesPacket?): Boolean = packet != null
            && packet.particle.type == ParticleTypes.ANGRY_VILLAGER && packet.count == 1
            && packet.xMaxSpeed() == 0f && packet.yMaxSpeed() == 0f && packet.zMaxSpeed() == 0f
            && packet.xDist() == 0f && packet.yDist() == 0f && packet.zDist() == 0f

        @JvmStatic @Synchronized fun predictionSince(requestedAt: Long, now: Long): PestTrackerTrail.Prediction? = if (TRAIL.belongsTo(requestedAt)) TRAIL.prediction(now) else null
        @JvmStatic @Synchronized fun isComplete(requestedAt: Long, now: Long): Boolean = now - requestedAt >= PestTrackerTrail.CAPTURE_TIMEOUT_MS || TRAIL.belongsTo(requestedAt) && TRAIL.isComplete(now)

        @JvmStatic @Synchronized fun hasVisibleArc(): Boolean {
            val client = Minecraft.getInstance()
            return AetherConfig.PEST_TRACKER_DRAW_ARC.get() && !StreamerModeManager.isEnabled() && client.player != null
                && captureLevel != null && client.level === captureLevel && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN
                && TRAIL.observed(System.currentTimeMillis()).size > 1
        }

        @JvmStatic @Synchronized fun renderWorld() {
            if (!hasVisibleArc()) return
            val now = System.currentTimeMillis()
            draw(TRAIL.observed(now), 0xFFF06A5D.toInt())
            val prediction = TRAIL.prediction(now)
            if (prediction != null) draw(prediction.extension(), 0xB3FFCD66.toInt())
        }

        private fun draw(points: List<Vec3>, color: Int) {
            for (i in 1 until points.size) Gizmos.line(points[i - 1], points[i], color, 2.0f).setAlwaysOnTop()
        }
    }
}
