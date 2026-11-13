package dev.aether.modules.pest

import dev.aether.modules.pest.helpers.PestBonusManager
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import java.util.Collections
import java.util.LinkedHashSet
import java.util.regex.Pattern

class PestTabSnapshot(
    private val aliveCountValue: Int,
    private val cooldownSecondsValue: Int,
    private val bonusInactiveValue: Boolean?,
    infestedPlotsValue: Set<String>
) {
    private val infestedPlotsValue: Set<String> = Collections.unmodifiableSet(LinkedHashSet(infestedPlotsValue)).toSet()
    fun aliveCount(): Int = aliveCountValue
    fun cooldownSeconds(): Int = cooldownSecondsValue
    fun bonusInactive(): Boolean? = bonusInactiveValue
    fun infestedPlots(): Set<String> = infestedPlotsValue

    companion object {
        private val PESTS_ALIVE_PATTERN = Pattern.compile("(?i)(?:Pests|Alive):?\\s*\\(?(\\d+)\\)?")
        private val COOLDOWN_PATTERN = Pattern.compile("(?i)Cooldown:\\s*\\(?(READY|MAX\\s*PESTS?|(?:(\\d+)m)?\\s*(?:(\\d+)s)?)\\)?")
        private val INFESTED_PLOTS_PATTERN = Pattern.compile("(?i)Plots?:\\s*(.+)")

        @JvmStatic fun empty() = PestTabSnapshot(-1, -1, null, emptySet())
        @JvmStatic fun read(client: Minecraft?): PestTabSnapshot {
            if (client == null || client.connection == null || client.player == null) return empty()
            return parse(TablistUtils.getRawTabLines(client))
        }
        @JvmStatic fun parse(lines: List<String>): PestTabSnapshot {
            var aliveCount = -1
            var cooldownSeconds = -1
            var bonusInactive: Boolean? = null
            val infestedPlots = LinkedHashSet<String>()
            for (normalized in lines) {
                val aliveMatcher = PESTS_ALIVE_PATTERN.matcher(normalized)
                if (aliveMatcher.find()) aliveCount = Math.max(aliveCount, Integer.parseInt(aliveMatcher.group(1)))
                val cooldownMatcher = COOLDOWN_PATTERN.matcher(normalized)
                if (cooldownMatcher.find()) {
                    val value = cooldownMatcher.group(1).uppercase()
                    if (value.contains("MAX PEST")) cooldownSeconds = 999
                    else if (value == "READY") cooldownSeconds = 0
                    else {
                        val minutes = parseInt(cooldownMatcher.group(2))
                        val seconds = parseInt(cooldownMatcher.group(3))
                        if (minutes > 0 || seconds > 0) cooldownSeconds = minutes * 60 + seconds
                    }
                }
                val plotsMatcher = INFESTED_PLOTS_PATTERN.matcher(normalized)
                if (plotsMatcher.find()) for (part in plotsMatcher.group(1).split(",".toRegex()).dropLastWhile { it.isEmpty() }) {
                    val plot = part.trim().replace(Regex("\\D"), "")
                    if (plot.isNotEmpty()) infestedPlots.add(plot)
                }
                if (bonusInactive == null) bonusInactive = PestBonusManager.parseBonusState(normalized)
            }
            return PestTabSnapshot(aliveCount, cooldownSeconds, bonusInactive, infestedPlots)
        }
        private fun parseInt(value: String?): Int = if (value == null || value.isBlank()) 0 else Integer.parseInt(value)
    }
}
