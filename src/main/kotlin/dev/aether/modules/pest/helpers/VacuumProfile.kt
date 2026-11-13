package dev.aether.modules.pest.helpers

import java.util.Locale
import java.util.regex.Pattern

internal class VacuumProfile(private val rarity: Int, private val range: Float) {
    fun rarity(): Int = rarity
    fun range(): Float = range

    fun effectiveRange(respectTrueRange: Boolean): Float = if (respectTrueRange) range else range * 0.9f

    companion object {
        private val FORMATTING = Pattern.compile("§.")
        private val RARITY = Pattern.compile("\\b(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC) VACUUM\\b")
        private val ABILITY = Pattern.compile("ABILITY: VACUUM\\b(.*?)(?=ABILITY:|VACUUM BAG:|$)")
        private val RANGE = Pattern.compile("\\bWITHIN\\s+(\\d+(?:\\.\\d+)?)\\s+BLOCKS\\b")
        private val RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

        @JvmStatic fun parse(name: String, lore: List<String>, recombobulated: Boolean): VacuumProfile {
            var rarity = -1
            for (line in lore) {
                val matcher = RARITY.matcher(clean(line))
                if (matcher.find()) rarity = RARITIES.indexOf(matcher.group(1))
            }
            val normalizedName = clean(name).replace("™", "")
            val namedRange = when {
                normalizedName.contains("INFINIVACUUM HOOVERIUS") -> 15f
                normalizedName.contains("INFINIVACUUM") -> 12.5f
                normalizedName.contains("HYPER VACUUM") -> 10f
                normalizedName.contains("TURBO VACUUM") -> 7.5f
                normalizedName.contains("SKYMART VACUUM") -> 5f
                else -> 0f
            }
            val baseRarity = Math.max(0, rarity - if (recombobulated) 1 else 0)
            var range = if (namedRange > 0) namedRange else 5f + Math.min(4, baseRarity) * 2.5f
            val ability = ABILITY.matcher(clean(java.lang.String.join(" ", lore)))
            if (ability.find()) {
                val explicitRange = RANGE.matcher(ability.group(1))
                if (explicitRange.find()) {
                    val value = java.lang.Float.parseFloat(explicitRange.group(1))
                    if (value >= 5f && value <= 15f) range = value
                }
            }
            return VacuumProfile(rarity, range)
        }

        private fun clean(text: String): String = FORMATTING.matcher(text).replaceAll("").uppercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }
}
