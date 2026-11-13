package dev.aether.modules.pest.helpers

import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Map
import java.util.stream.Collectors

class VacuumParticleDebug private constructor() {
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("aether")
        private val CAPTURE_LOCK = Any()
        private var capturing = false
        private var captureStartedAt = 0L
        private var packetCount = 0L
        private var particleCount = 0L
        private val packetTypeCounts: MutableMap<String, Int> = LinkedHashMap()

        @JvmStatic fun onClientTick() {
            val client = Minecraft.getInstance()
            if (client == null || client.player == null || client.options == null) {
                if (capturing) flush("context lost")
                return
            }
            val active = isVacuumLeftClickActive(client)
            if (active && !capturing) { beginCapture(); return }
            if (!active && capturing) flush("left click released")
        }

        @JvmStatic fun onParticlePacket(packet: ClientboundLevelParticlesPacket?) {
            if (packet == null) return
            val options: ParticleOptions? = packet.particle
            var particleId = "unknown"
            if (options != null) {
                val key: Identifier? = BuiltInRegistries.PARTICLE_TYPE.getKey(options.type)
                particleId = key?.toString() ?: options.type.toString()
            }
            synchronized(CAPTURE_LOCK) {
                if (!capturing) return
                packetTypeCounts.merge(particleId, 1, Integer::sum)
                packetCount++
                particleCount += Math.max(packet.count, 1)
            }
        }

        private fun isVacuumLeftClickActive(client: Minecraft): Boolean {
            if (!client.options.keyAttack.isDown) return false
            val selected = (client.player!!.inventory as dev.aether.mixin.AccessorInventory).getSelected()
            if (selected < 0 || selected >= 9) return false
            val held: ItemStack = client.player!!.mainHandItem
            if (held.isEmpty) return false
            val itemName = held.hoverName.string.replace(Regex("\\u00A7[0-9a-fk-or]"), "").trim().lowercase()
            return itemName.contains("vacuum")
        }

        private fun beginCapture() {
            synchronized(CAPTURE_LOCK) {
                capturing = true; captureStartedAt = System.currentTimeMillis(); packetCount = 0L; particleCount = 0L; packetTypeCounts.clear()
            }
            ClientUtils.sendDebugMessage("VacuumParticleDebug: capture started")
        }

        private fun flush(reason: String) {
            val durationMs: Long
            val capturedPacketCount: Long
            val capturedParticleCount: Long
            val capturedPacketTypeCounts: MutableMap<String, Int>
            synchronized(CAPTURE_LOCK) {
                durationMs = Math.max(0L, System.currentTimeMillis() - captureStartedAt)
                capturedPacketCount = packetCount
                capturedParticleCount = particleCount
                capturedPacketTypeCounts = LinkedHashMap(packetTypeCounts)
                resetLocked()
            }
            if (capturedPacketTypeCounts.isEmpty()) {
                ClientUtils.sendDebugMessage("VacuumParticleDebug: capture ended ($reason) with no particle packets in ${durationMs}ms")
                LOGGER.info("VacuumParticleDebug: no particle packets captured. reason={}, durationMs={}", reason, durationMs)
                return
            }
            val summary = capturedPacketTypeCounts.entries.sortedByDescending { it.value }
                .joinToString(", ") { it.key + "=" + it.value }
            ClientUtils.sendDebugMessage("VacuumParticleDebug: captured ${capturedPacketTypeCounts.size} particle type(s), $capturedPacketCount packet(s), $capturedParticleCount spawned particles in ${durationMs}ms")
            ClientUtils.sendDebugMessage("VacuumParticleDebug: types: $summary")
            LOGGER.info("VacuumParticleDebug: reason={}, durationMs={}, packetTypes={}, packets={}, particles={}, types=[{}]", reason, durationMs, capturedPacketTypeCounts.size, capturedPacketCount, capturedParticleCount, summary)
        }

        private fun reset() { synchronized(CAPTURE_LOCK) { resetLocked() } }
        private fun resetLocked() {
            capturing = false; captureStartedAt = 0L; packetCount = 0L; particleCount = 0L; packetTypeCounts.clear()
        }
    }
}
