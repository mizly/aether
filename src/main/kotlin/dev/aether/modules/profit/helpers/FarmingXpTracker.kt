package dev.aether.modules.profit.helpers

import dev.aether.macro.MacroStateManager
import dev.aether.modules.profit.ProfitManager
import dev.aether.util.NumberUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.HashMap
import java.util.regex.Pattern

object FarmingXpTracker {
    const val MAX_LEVEL = 60
    private val LEVEL_INCREMENTS = longArrayOf(
        50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500, 5000, 7500, 10000, 15000, 20000, 30000,
        50000, 75000, 100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000,
        1100000, 1200000, 1300000, 1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000,
        2100000, 2200000, 2300000, 2400000, 2500000, 2600000, 2750000, 2900000, 3100000, 3400000,
        3700000, 4000000, 4300000, 4600000, 4900000, 5200000, 5500000, 5800000, 6100000, 6400000,
        6700000, 7000000
    )
    private val XP_TO_LEVEL = buildCumulative()
    private val NEEDED_TO_LEVEL = buildNeededMap()
    private val AB_FRACTION = Pattern.compile("Farming\\s+\\(([\\d.,]+)/([\\d.,]+[kKmMbB]?)\\)")
    private val TAB_MAX = Pattern.compile("Farming\\s+(\\d+):\\s+MAX", Pattern.CASE_INSENSITIVE)
    private val TAB_FRACTION = Pattern.compile("Farming\\s+(\\d+):\\s+([\\d.,]+)/([\\d.,]+[kKmMbB]?)")
    private val TAB_PERCENT = Pattern.compile("Farming\\s+(\\d+):\\s+([\\d.,]+)%")
    @JvmField val XP_TO_MAX = XP_TO_LEVEL[MAX_LEVEL]

    private val LOCK = Any()
    @Volatile private var sessionXpGained = 0L
    @Volatile private var currentLevel = -1
    @Volatile private var absoluteXp = -1L
    @Volatile private var sessionStartAbsoluteXp = -1L

    @JvmStatic fun onActionBar(component: Component?) {
        if (component == null || !ProfitManager.isProfitTrackingActive()) return
        val text = TablistUtils.stripColors(component.string)
        if (text.isEmpty() || !text.contains("Farming")) return
        val matcher = AB_FRACTION.matcher(text)
        if (matcher.find()) try {
            val current = parseNum(matcher.group(1)).toLong()
            val needed = NumberUtils.parseShorthand(matcher.group(2))
            val level = levelForNeeded(needed)
            if (level >= 0) setAnchor(level, current)
        } catch (_: NumberFormatException) { }
    }

    @JvmStatic fun updateFromTablist(client: Minecraft?) {
        if (client == null || client.connection == null || !ProfitManager.isProfitTrackingActive()) return
        for (line in TablistUtils.getTabLines(client)) {
            val max = TAB_MAX.matcher(line)
            if (max.find()) { setAnchor(MAX_LEVEL, 0); return }
            val fraction = TAB_FRACTION.matcher(line)
            if (fraction.find()) try {
                setAnchor(fraction.group(1).toInt(), parseNum(fraction.group(2)).toLong())
                return
            } catch (_: NumberFormatException) { }
            val percent = TAB_PERCENT.matcher(line)
            if (percent.find()) try {
                val level = percent.group(1).toInt()
                val increment = LEVEL_INCREMENTS.getOrElse(level) { 0L }
                setAnchor(level, (increment * parseNum(percent.group(2)) / 100.0).toLong())
                return
            } catch (_: NumberFormatException) { }
        }
    }

    @JvmStatic fun reset() = synchronized(LOCK) {
        sessionXpGained = 0; currentLevel = -1; absoluteXp = -1; sessionStartAbsoluteXp = -1
    }

    @JvmStatic fun resetLiveState() = synchronized(LOCK) {
        sessionXpGained = 0; currentLevel = -1; absoluteXp = -1
    }

    @JvmStatic fun hasData() = absoluteXp >= 0 && currentLevel >= 0
    @JvmStatic fun isMaxed() = currentLevel >= MAX_LEVEL && absoluteXp >= xpToMax()
    @JvmStatic fun isPaused() = MacroStateManager.getSessionRunningTime() <= 0L
    @JvmStatic fun getLevel() = currentLevel
    @JvmStatic fun getSessionXpGained() = sessionXpGained
    @JvmStatic fun getXpPerHour(): Long {
        val sessionMs = MacroStateManager.getSessionRunningTime()
        return if (sessionMs > 0) (sessionXpGained * 3_600_000.0 / sessionMs).toLong() else 0L
    }
    @JvmStatic fun getRemainingToMax() = maxOf(0L, xpToMax() - maxOf(0L, absoluteXp))
    @JvmStatic fun getProgressToMax(): Float = if (absoluteXp <= 0) 0f else (absoluteXp.toFloat() / xpToMax()).coerceIn(0f, 1f)
    @JvmStatic fun getEtaToMaxMs(): Long {
        val rate = getXpPerHour()
        return if (rate <= 0 || isMaxed()) -1L else (getRemainingToMax() / rate.toDouble() * 3_600_000).toLong()
    }
    @JvmStatic fun getXpIntoLevel(): Long = if (absoluteXp < 0 || currentLevel < 0) 0 else maxOf(0, absoluteXp - XP_TO_LEVEL[currentLevel.coerceAtMost(MAX_LEVEL)])
    @JvmStatic fun getXpForNextLevel(): Long = if (currentLevel < 0 || currentLevel >= LEVEL_INCREMENTS.size) 0 else LEVEL_INCREMENTS[currentLevel]
    @JvmStatic fun getRemainingToNextLevel() = getXpForNextLevel().let { if (it <= 0) 0 else maxOf(0, it - getXpIntoLevel()) }
    @JvmStatic fun getEtaToNextLevelMs(): Long {
        val rate = getXpPerHour()
        return if (rate <= 0 || currentLevel >= MAX_LEVEL) -1 else (getRemainingToNextLevel() / rate.toDouble() * 3_600_000).toLong()
    }

    @JvmStatic fun setAnchor(level: Int, currentXpInLevel: Long) {
        if (level !in 0..MAX_LEVEL) return
        synchronized(LOCK) {
            var next = XP_TO_LEVEL[level] + maxOf(0, currentXpInLevel)
            if (level == MAX_LEVEL && currentXpInLevel > 0 && absoluteXp == xpToMax() && sessionStartAbsoluteXp == xpToMax() && sessionXpGained == 0L) {
                sessionStartAbsoluteXp = next
            }
            if (level == MAX_LEVEL && currentXpInLevel == 0L && absoluteXp > next) next = absoluteXp
            currentLevel = level
            absoluteXp = next
            if (sessionStartAbsoluteXp < 0) sessionStartAbsoluteXp = absoluteXp
            sessionXpGained = maxOf(sessionXpGained, maxOf(0, absoluteXp - sessionStartAbsoluteXp))
        }
    }

    @JvmStatic fun levelForNeeded(needed: Long): Int {
        if (needed == 0L) return MAX_LEVEL
        NEEDED_TO_LEVEL[needed]?.let { return it }
        var best = -1
        var bestDelta = Long.MAX_VALUE
        for (level in LEVEL_INCREMENTS.indices) {
            val delta = kotlin.math.abs(LEVEL_INCREMENTS[level] - needed)
            if (delta < bestDelta) { bestDelta = delta; best = level }
        }
        return if (bestDelta <= needed * 0.05) best else -1
    }

    private fun xpToMax() = XP_TO_LEVEL[MAX_LEVEL]
    private fun parseNum(value: String) = value.replace(",", "").trim().toDouble()
    private fun buildCumulative(): LongArray = LongArray(MAX_LEVEL + 1).also { table ->
        for (level in 1..MAX_LEVEL) table[level] = table[level - 1] + LEVEL_INCREMENTS[level - 1]
    }
    private fun buildNeededMap() = HashMap<Long, Int>().also { map -> LEVEL_INCREMENTS.forEachIndexed { level, value -> map[value] = level } }
}
