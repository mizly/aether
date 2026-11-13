package dev.aether.modules.visuals

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.modules.pest.helpers.PestTargetTracker
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashMap
import java.util.Map
import java.util.UUID

class PestDefeatEffects private constructor() {
    companion object {
        private val EFFECTS = DefeatEffectPool()
        private val tracked: MutableMap<UUID, Tracked> = LinkedHashMap()
        private var level: ClientLevel? = null

        @JvmStatic fun tick(client: Minecraft) {
            if (client.level != level) { clear(); level = client.level }
            if (!enabled(client)) { clear(); return }
            val now = System.nanoTime()
            EFFECTS.prune(now)
            if (ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) { tracked.clear(); return }
            val player = client.player ?: return
            for (pest in PestTargetTracker.getLoadedPestMobs(client)) {
                if (player.distanceToSqr(pest) > 48 * 48) continue
                val previous = tracked[pest.uuid]
                if (previous == null) {
                    if (tracked.size >= 64) continue
                    tracked[pest.uuid] = Tracked(pest, now)
                } else previous.lastSeen = now
            }
            for (pest in tracked.values) {
                if (!pest.defeated && (pest.entity.removalReason == Entity.RemovalReason.KILLED
                        || pest.entity is LivingEntity && (pest.entity as LivingEntity).isDeadOrDying)) {
                    onDefeat(pest.entity)
                    pest.defeated = true
                }
            }
            tracked.values.removeIf { now - it.lastSeen > 1_000_000_000L }
        }

        @JvmStatic fun onDefeat(entity: Entity?) {
            val client = Minecraft.getInstance()
            if (entity == null || client == null) return
            if (!client.isSameThread) { client.execute { onDefeat(entity) }; return }
            if (!enabled(client) || entity.level() != client.level
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
                || client.player!!.distanceToSqr(entity) > 48 * 48) return
            if (level != client.level) { clear(); level = client.level }
            spawn(entity.uuid, entity.boundingBox.center)
        }

        @JvmStatic fun onCatchChat() {
            val client = Minecraft.getInstance()
            if (PestDestroyer.isCatchInProgress() || !enabled(client) || level != client.level
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) return
            var candidate: Entity? = null
            var best = Double.MAX_VALUE
            val eye = client.player!!.eyePosition
            val look = client.player!!.getViewVector(1f)
            val now = System.nanoTime()
            for (pest in tracked.values) {
                val entity = pest.entity
                val distance = client.player!!.distanceToSqr(entity)
                if (distance > 12 * 12 || now - pest.lastSeen > 750_000_000L) continue
                val attached = entity is Leashable && (entity as Leashable).leashHolder == client.player
                val alignment = entity.boundingBox.center.subtract(eye).normalize().dot(look)
                if (!attached && !entity.isRemoved && alignment < 0.92) continue
                val score = distance + (if (attached) -200 else 0) + (if (entity.isRemoved) -100 else 0)
                if (score < best) { candidate = entity; best = score }
            }
            candidate?.let { onDefeat(it) }
        }

        @JvmStatic fun preview() {
            val client = Minecraft.getInstance()
            if (client.player == null || client.level == null || StreamerModeManager.isEnabled()) return
            AetherConfig.PEST_DEFEAT_EFFECTS.set(true)
            AetherConfig.save()
            if (level != client.level) { clear(); level = client.level }
            client.gui.setScreen(null)
            val player = client.player!!
            spawn(UUID.randomUUID(), player.eyePosition.add(player.getViewVector(1f).scale(3.0)))
        }

        private fun spawn(id: UUID, position: Vec3) {
            EFFECTS.spawn(id, position, AetherConfig.PEST_DEFEAT_STYLE.get(), AetherConfig.PEST_DEFEAT_SCALE.get(),
                AetherConfig.PEST_DEFEAT_PARTICLES.get(), System.nanoTime())
        }
        private fun enabled(client: Minecraft?): Boolean = client != null && client.player != null && client.level != null
            && AetherConfig.PEST_DEFEAT_EFFECTS.get() && !StreamerModeManager.isEnabled()
        @JvmStatic fun effects(): DefeatEffectPool = EFFECTS
        @JvmStatic fun hasActiveEffects(client: Minecraft): Boolean = enabled(client) && level == client.level && EFFECTS.active().isNotEmpty()
        @JvmStatic fun clear() { EFFECTS.clear(); tracked.clear() }

        private class Tracked(val entity: Entity, var lastSeen: Long, var defeated: Boolean = false) {
            constructor(entity: Entity, now: Long) : this(entity, now, false)
        }
    }
}
