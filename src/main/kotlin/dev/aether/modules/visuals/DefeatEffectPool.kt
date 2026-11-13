package dev.aether.modules.visuals

import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Map
import java.util.UUID

class DefeatEffectPool {
    private val active: MutableList<Burst> = ArrayList<Burst>(MAX_ACTIVE)
    private val activeView: kotlin.collections.List<Burst> = java.util.Collections.unmodifiableList<Burst>(active)
    private val recent: MutableMap<UUID, Long> = LinkedHashMap()

    fun spawn(id: UUID, position: Vec3, style: Int, scale: Float, particles: Int, now: Long): Boolean {
        prune(now)
        if (recent.containsKey(id)) return false
        recent[id] = now
        if (recent.size > 64) recent.remove(recent.keys.iterator().next())
        if (active.size == MAX_ACTIVE) active.removeAt(0)
        active.add(Burst(position, Math.min(2, Math.max(0, style)), Math.clamp(scale, 0.5f, 2f),
            Math.min(24, Math.max(8, particles)), ((id.hashCode() and 65535) / 65535f) * 6.283185f, now))
        return true
    }

    fun prune(now: Long) {
        active.removeAll { burst -> now - burst.startedAt() >= DURATION_NANOS }
        recent.values.removeIf { start -> now - start >= DEDUP_NANOS }
    }

    fun active(): kotlin.collections.List<Burst> = activeView
    fun clear() { active.clear(); recent.clear() }

    class Burst(
        private val positionValue: Vec3,
        private val styleValue: Int,
        private val scaleValue: Float,
        private val particlesValue: Int,
        private val seedValue: Float,
        private val startedAtValue: Long
    ) {
        fun position(): Vec3 = positionValue
        fun style(): Int = styleValue
        fun scale(): Float = scaleValue
        fun particles(): Int = particlesValue
        fun seed(): Float = seedValue
        fun startedAt(): Long = startedAtValue
        fun age(now: Long): Float = Math.max(0f, Math.min(1f, (now - startedAt()) / DURATION_NANOS.toFloat()))
    }

    companion object {
        const val MAX_ACTIVE: Int = 8
        const val DURATION_NANOS: Long = 1_250_000_000L
        private const val DEDUP_NANOS: Long = 5_000_000_000L
    }
}
