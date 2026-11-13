package dev.aether.modules.profit

import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.function.Function

internal class ProfitDisplayService(
    private val countsResolver: Function<String, Map<String, Long>>,
    private val pricing: ProfitPricing
) {
    private val displayCaches = HashMap<String, ProfitViewCache>()
    @Volatile private var displayVersion = 1L

    fun invalidate() { displayVersion++ }

    fun getActiveDrops(mode: String): Map<String, Long> {
        val cache = getDisplayCache(mode)
        val version = displayVersion
        synchronized(cache) {
            if (cache.activeVersion != version) {
                cache.activeDrops = Collections.unmodifiableMap(buildActiveDrops(countsResolver.apply(mode)))
                cache.activeVersion = version
            }
            return cache.activeDrops
        }
    }

    fun getCompactDrops(mode: String): Map<String, Long> {
        val cache = getDisplayCache(mode)
        val version = displayVersion
        synchronized(cache) {
            if (cache.compactVersion != version) {
                cache.compactDrops = Collections.unmodifiableMap(buildCompactDrops(countsResolver.apply(mode)))
                cache.compactVersion = version
            }
            return cache.compactDrops
        }
    }

    fun getTotalProfit(mode: String): Long {
        val cache = getDisplayCache(mode)
        val version = displayVersion
        synchronized(cache) {
            if (cache.totalVersion != version) {
                cache.totalProfit = buildTotalProfit(countsResolver.apply(mode))
                cache.totalVersion = version
            }
            return cache.totalProfit
        }
    }

    private fun getDisplayCache(mode: String): ProfitViewCache = synchronized(displayCaches) {
        displayCaches.getOrPut(mode) { ProfitViewCache() }
    }

    private fun buildActiveDrops(counts: Map<String, Long>): Map<String, Long> = normalizeCounts(counts)
        .entries.sortedWith { left, right ->
            java.lang.Double.compare(
                pricing.getItemValue(right.key, right.value),
                pricing.getItemValue(left.key, left.value)
            )
        }.associateTo(LinkedHashMap()) { it.key to it.value }

    private fun buildCompactDrops(counts: Map<String, Long>): Map<String, Long> {
        val compact = linkedMapOf(
            "Crops" to 0L, "Shards" to 0L, "Pest Items" to 0L, "Pets" to 0L,
            "Feast" to 0L, "Misc Drops" to 0L, "Visitor" to 0L, "Costs" to 0L, "Others" to 0L
        )
        for ((name, count) in normalizeCounts(counts)) {
            val profit = pricing.getItemValue(name, count).toLong()
            val category = when {
                pricing.isCrop(name) -> "Crops"
                pricing.isShard(name) -> "Shards"
                pricing.isPestItem(name) -> "Pest Items"
                pricing.isPet(name) -> "Pets"
                pricing.isFeastItem(name) -> "Feast"
                pricing.isMiscDrop(name) || pricing.isPetXpEntry(name) -> "Misc Drops"
                name == "[Visitor] Visitor Cost" || name == "[Spray] Sprayonator" -> "Costs"
                name.startsWith("[Visitor] ") -> "Visitor"
                profit < 0 -> "Costs"
                else -> "Others"
            }
            compact[category] = compact.getValue(category) + profit
        }
        return compact.entries.sortedByDescending { it.value }.associateTo(LinkedHashMap()) { it.key to it.value }
    }

    private fun buildTotalProfit(counts: Map<String, Long>): Long {
        var total = 0.0
        for ((name, count) in normalizeCounts(counts)) total += pricing.getItemValue(name, count)
        return total.toLong()
    }

    private fun normalizeCounts(counts: Map<String, Long>): Map<String, Long> {
        val normalized = LinkedHashMap<String, Long>()
        for ((key, value) in counts) {
            val normalizedKey = normalizeCountKey(key)
            normalized[normalizedKey] = normalized.getOrDefault(normalizedKey, 0L) + value
        }
        return normalized
    }

    private fun normalizeCountKey(key: String?): String {
        if (key.isNullOrBlank()) return "Unknown Item"
        if (key == "[Spray] Sprayonator") return key
        if (key.startsWith("[Visitor] ")) {
            val visitorItem = key.substring(10)
            if (visitorItem.equals("Visitor Cost", ignoreCase = true)) return "[Visitor] Visitor Cost"
            return "[Visitor] ${pricing.canonicalizeTrackedName(visitorItem)}"
        }
        return pricing.canonicalizeTrackedName(key)
    }

    private class ProfitViewCache {
        var activeVersion = -1L
        var compactVersion = -1L
        var totalVersion = -1L
        var activeDrops: Map<String, Long> = Collections.emptyMap()
        var compactDrops: Map<String, Long> = Collections.emptyMap()
        var totalProfit = 0L
    }
}
