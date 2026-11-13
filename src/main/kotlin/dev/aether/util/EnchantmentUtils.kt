package dev.aether.util

import dev.aether.config.AetherConfig
import java.util.HashMap
import java.util.Map

class EnchantmentUtils private constructor() {
    companion object {
        private val MAX_LEVELS: HashMap<String, Int> = HashMap<String, Int>().apply {
            put("Absorb", 10); put("Angler", 6); put("Aqua Affinity", 1); put("Bane of Arthropods", 7); put("Big Brain", 5); put("Blast Protection", 7); put("Blessing", 6); put("Caster", 6); put("Cayenne", 5); put("Champion", 10); put("Chance", 5); put("Charm", 6); put("Cleave", 6); put("Compact", 10); put("Corruption", 5); put("Counter-Strike", 5); put("Critical", 7); put("Cubism", 6); put("Cultivating", 10); put("Dedication", 4); put("Delicate", 5); put("Depth Strider", 3); put("Divine Gift", 3); put("Dragon Hunter", 5); put("Dragon Tracer", 5); put("Drain", 5); put("Efficiency", 10); put("Ender Slayer", 7); put("Execute", 6); put("Experience", 5); put("Expertise", 10); put("Feather Falling", 10); put("Ferocious Mana", 10); put("Fire Aspect", 3); put("Fire Protection", 7); put("First Impression", 5); put("First Strike", 5); put("Flame", 2); put("Forest Pledge", 5); put("Fortune", 4); put("Frail", 7); put("Giant Killer", 7); put("Gravity", 6); put("Great Spook", 1); put("Green Thumb", 5); put("Growth", 7); put("Hardened Mana", 10); put("Harvesting", 6); put("Hecatomb", 10); put("Ice Cold", 5); put("Impaling", 5); put("Infinite Quiver", 10); put("Knockback", 2); put("Lethality", 6); put("Life Steal", 5); put("Looting", 5); put("Luck", 7); put("Luck of the Sea", 6); put("Lure", 6); put("Mana Steal", 3); put("Mana Vampire", 10); put("Magnet", 6); put("Overload", 5); put("Pesterminator", 6); put("Piercing", 1); put("Piscary", 7); put("Power", 7); put("Prismatic", 5); put("Projectile Protection", 7); put("Prosecute", 6); put("Prosperity", 5); put("Protection", 7); put("Punch", 2); put("Quantum", 5); put("Rainbow", 3); put("Rejuvenate", 5); put("Reflection", 5); put("Replenish", 1); put("Respiration", 4); put("Respite", 5); put("Scavenger", 6); put("Scuba", 5); put("Sharpness", 7); put("Silk Touch", 1); put("Smelting Touch", 1); put("Smarty Pants", 5); put("Smite", 7); put("Snipe", 4); put("Spiked Hook", 7); put("Strong Mana", 10); put("Sugar Rush", 3); put("Sunder", 6); put("Tabasco", 3); put("Thorns", 3); put("Thunderbolt", 7); put("Thunderlord", 7); put("Titan Killer", 7); put("Transylvanian", 10); put("Triple-Strike", 5); put("True Protection", 1); put("Turbo-Crop", 5); put("Vampirism", 6); put("Venomous", 6); put("Vicious", 5); put("Woodsplitter", 6); put("Bank", 5); put("Bobbin' Time", 5); put("Chimera", 5); put("Combo", 5); put("Crop Fever", 5); put("Duplex", 5); put("Fatal Tempo", 5); put("First Impression", 5); put("Flash", 5); put("Flowstate", 5); put("Habanero Tactics", 4); put("Inferno", 5); put("Last Stand", 5); put("Legion", 5); put("No Pain No Gain", 5); put("One For All", 1); put("Rend", 5); put("Soul Eater", 5); put("Swarm", 5); put("Ultimate Jerry", 5); put("Ultimate Wise", 5); put("Wisdom", 5)
        }

        @JvmStatic fun getMaxLevel(enchantmentName: String?): Int {
            if (enchantmentName == null) return 5
            for (custom in AetherConfig.CUSTOM_ENCHANTMENT_LEVELS.get()) {
                val parts = custom.split(":").toTypedArray()
                if (parts.size >= 2 && parts[0].trim().equals(enchantmentName, ignoreCase = true)) {
                    try { return Integer.parseInt(parts[1].trim()) } catch (_: NumberFormatException) { }
                }
            }
            if (enchantmentName.lowercase().startsWith("turbo-")) return 5
            for ((key, value) in MAX_LEVELS) if (key.equals(enchantmentName, ignoreCase = true)) return value
            return 5
        }

        @JvmStatic fun parseLevel(levelStr: String?): Int {
            if (levelStr == null || levelStr.isEmpty()) return 0
            try { return Integer.parseInt(levelStr) } catch (_: NumberFormatException) { return romanToInt(levelStr.uppercase()) }
        }

        @JvmStatic fun findEnchantmentNameIn(text: String?): String? {
            if (text == null || text.isEmpty()) return null
            val lower = text.lowercase()
            val normalized = lower.replace(Regex("[^a-z0-9\\s-]"), " ").replace(Regex("\\s+"), " ").trim()
            for (key in MAX_LEVELS.keys) {
                val keyLower = key.lowercase()
                if (normalized == keyLower || normalized.contains(" $keyLower ") || normalized.startsWith("$keyLower ") || normalized.endsWith(" $keyLower") || normalized.contains(keyLower)) return key
            }
            return null
        }

        private fun romanToInt(s: String): Int {
            val roman = HashMap<Char, Int>()
            roman['I'] = 1; roman['V'] = 5; roman['X'] = 10; roman['L'] = 50; roman['C'] = 100; roman['D'] = 500; roman['M'] = 1000
            var total = 0
            for (i in s.indices) {
                val current = roman[s[i]] ?: continue
                if (i + 1 < s.length && (roman[s[i + 1]] ?: 0) > current) total -= current else total += current
            }
            return total
        }
    }
}
