package dev.aether.util

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket

// times a vanilla REQUEST_STATS packet against its reply, because hypixel pins the tab-list latency field near 0-1ms
// same approach as skytils' ping feature
class PingTracker private constructor() {
    companion object {
        private const val INTERVAL_MS = 5000L
        private const val TIMEOUT_MS = 10_000L

        private var lastSentAtNanos = -1L
        private var lastSentAtMillis = 0L
        private var cachedPingMs = -1.0
        private var wasConnected = false

        @JvmStatic
        fun tick(client: Minecraft) {
            val connection = client.getConnection()
            if (client.player == null || connection == null) {
                wasConnected = false
                return
            }
            if (!wasConnected) {
                wasConnected = true
                reset()
            }
            val now = System.currentTimeMillis()
            if (lastSentAtNanos > 0 && now - lastSentAtMillis > TIMEOUT_MS) lastSentAtNanos = -1L
            if (lastSentAtNanos > 0 || now - lastSentAtMillis < INTERVAL_MS) return
            lastSentAtMillis = now
            lastSentAtNanos = System.nanoTime()
            connection.send(ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS))
        }

        @JvmStatic
        fun onStatsReceived() {
            if (lastSentAtNanos < 0) return
            cachedPingMs = (System.nanoTime() - lastSentAtNanos) / 1_000_000.0
            lastSentAtNanos = -1L
        }

        @JvmStatic
        fun reset() {
            lastSentAtNanos = -1L
            lastSentAtMillis = 0L
            cachedPingMs = -1.0
        }

        @JvmStatic
        fun getFormattedPing(): String = if (cachedPingMs >= 0) Math.round(cachedPingMs).toString() + "ms" else "---"
    }
}
