package dev.aether.config

import java.util.function.Consumer

class RewarpPointPairs private constructor() {
    companion object {
        @JvmStatic
        fun get(): List<RewarpPointPair> {
            val entries = AetherConfig.REWARP_POINT_PAIRS.get()
            if (entries.isEmpty() || shouldUseLegacyEntry(entries)) return java.util.Collections.singletonList(RewarpPointPair.fromLegacyConfig())
            return java.util.ArrayList(entries.mapIndexed { index, entry -> RewarpPointPair(entry, index) })
        }

        private fun shouldUseLegacyEntry(entries: List<String>): Boolean {
            if (entries.size != 1 || RewarpPointPair.defaultConfig(0) != entries[0]) return false
            return hasNonZeroLegacyCoordinate(AetherConfig.REWARP_START_X.get(), AetherConfig.REWARP_START_Y.get(), AetherConfig.REWARP_START_Z.get()) ||
                hasNonZeroLegacyCoordinate(AetherConfig.REWARP_END_X.get(), AetherConfig.REWARP_END_Y.get(), AetherConfig.REWARP_END_Z.get())
        }

        private fun hasNonZeroLegacyCoordinate(x: Double, y: Double, z: Double): Boolean =
            kotlin.math.abs(x) > 0.0001 || kotlin.math.abs(y) > 0.0001 || kotlin.math.abs(z) > 0.0001

        @JvmStatic
        fun get(index: Int): RewarpPointPair {
            val pairs = get()
            if (pairs.isEmpty()) return RewarpPointPair.defaultPair(index)
            return pairs[index.coerceIn(0, pairs.lastIndex)]
        }

        @JvmStatic
        fun update(index: Int, updater: Consumer<RewarpPointPair>) {
            val entries = AetherConfig.REWARP_POINT_PAIRS.get().toMutableList()
            if (shouldUseLegacyEntry(entries)) entries[0] = RewarpPointPair.fromLegacyConfig().toString()
            while (entries.size <= index) entries.add(RewarpPointPair.defaultConfig(entries.size))
            val pair = RewarpPointPair(entries[index], index)
            updater.accept(pair)
            entries[index] = pair.toString()
            AetherConfig.REWARP_POINT_PAIRS.set(java.util.ArrayList(entries))
            AetherConfig.save()
        }

        @JvmStatic
        fun add() {
            val entries = AetherConfig.REWARP_POINT_PAIRS.get().toMutableList()
            if (shouldUseLegacyEntry(entries)) entries[0] = RewarpPointPair.fromLegacyConfig().toString()
            entries.add(RewarpPointPair.defaultConfig(entries.size))
            AetherConfig.REWARP_POINT_PAIRS.set(java.util.ArrayList(entries))
            AetherConfig.save()
        }

        @JvmStatic
        fun remove(index: Int) {
            val entries = AetherConfig.REWARP_POINT_PAIRS.get().toMutableList()
            if (entries.size <= 1 || index !in entries.indices) return
            entries.removeAt(index)
            AetherConfig.REWARP_POINT_PAIRS.set(java.util.ArrayList(entries))
            AetherConfig.save()
        }
    }
}
