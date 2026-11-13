package dev.aether.modules.pest.helpers

import net.minecraft.world.entity.Entity
import java.util.concurrent.ConcurrentHashMap

// a timeout means unreachable right now, not dead, so it has to expire: blacklisting for the whole run left the cleaner sweeping a plot whose remaining pests it had made invisible to itself
class PestTargetDeferrals {
    private val entries = ConcurrentHashMap<Int, Entry>()

    fun defer(entity: Entity?) {
        if (entity == null) return
        val now = System.currentTimeMillis()
        entries.compute(entity.id) { _, previous ->
            val attempts = if (previous == null) 1 else previous.attempts() + 1
            val until = if (attempts >= MAX_ATTEMPTS) Long.MAX_VALUE else now + Math.min(MAX_DEFER_MS, BASE_DEFER_MS * attempts)
            Entry(attempts, until)
        }
    }

    fun isDeferred(entityId: Int): Boolean {
        val entry = entries[entityId]
        return entry != null && System.currentTimeMillis() < entry.until()
    }

    // for when a full plot sweep turned up nothing else to do
    fun releaseRetryable(): Boolean {
        var released = false
        val now = System.currentTimeMillis()
        for (mapping in entries.entries) {
            val entry = mapping.value
            if (entry.attempts() < MAX_ATTEMPTS && now < entry.until()
                && entries.replace(mapping.key, entry, Entry(entry.attempts(), 0L))) {
                released = true
            }
        }
        return released
    }

    fun clear() {
        entries.clear()
    }

    private class Entry(private val attempts: Int, private val until: Long) {
        fun attempts(): Int = attempts
        fun until(): Long = until
    }

    companion object {
        private const val BASE_DEFER_MS = 8_000L
        private const val MAX_DEFER_MS = 30_000L
        private const val MAX_ATTEMPTS = 3
    }
}
