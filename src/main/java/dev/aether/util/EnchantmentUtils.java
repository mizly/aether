package dev.aether.util;

import dev.aether.config.AetherConfig;
import java.util.HashMap;
import java.util.Map;

public class EnchantmentUtils {
    private static final Map<String, Integer> MAX_LEVELS = new HashMap<>();

    static {
        MAX_LEVELS.put("Absorb", 10);
        MAX_LEVELS.put("Angler", 6);
        MAX_LEVELS.put("Aqua Affinity", 1);
        MAX_LEVELS.put("Bane of Arthropods", 7);
        MAX_LEVELS.put("Big Brain", 5);
        MAX_LEVELS.put("Blast Protection", 7);
        MAX_LEVELS.put("Blessing", 6);
        MAX_LEVELS.put("Caster", 6);
        MAX_LEVELS.put("Cayenne", 5);
        MAX_LEVELS.put("Champion", 10);
        MAX_LEVELS.put("Chance", 5);
        MAX_LEVELS.put("Charm", 6);
        MAX_LEVELS.put("Cleave", 6);
        MAX_LEVELS.put("Compact", 10);
        MAX_LEVELS.put("Corruption", 5);
        MAX_LEVELS.put("Counter-Strike", 5);
        MAX_LEVELS.put("Critical", 7);
        MAX_LEVELS.put("Cubism", 6);
        MAX_LEVELS.put("Cultivating", 10);
        MAX_LEVELS.put("Dedication", 4);
        MAX_LEVELS.put("Delicate", 5);
        MAX_LEVELS.put("Depth Strider", 3);
        MAX_LEVELS.put("Divine Gift", 3);
        MAX_LEVELS.put("Dragon Hunter", 5);
        MAX_LEVELS.put("Dragon Tracer", 5);
        MAX_LEVELS.put("Drain", 5);
        MAX_LEVELS.put("Efficiency", 10);
        MAX_LEVELS.put("Ender Slayer", 7);
        MAX_LEVELS.put("Execute", 6);
        MAX_LEVELS.put("Experience", 5);
        MAX_LEVELS.put("Expertise", 10);
        MAX_LEVELS.put("Feather Falling", 10);
        MAX_LEVELS.put("Ferocious Mana", 10);
        MAX_LEVELS.put("Fire Aspect", 3);
        MAX_LEVELS.put("Fire Protection", 7);
        MAX_LEVELS.put("First Impression", 5);
        MAX_LEVELS.put("First Strike", 5);
        MAX_LEVELS.put("Flame", 2);
        MAX_LEVELS.put("Forest Pledge", 5);
        MAX_LEVELS.put("Fortune", 4);
        MAX_LEVELS.put("Frail", 7);
        MAX_LEVELS.put("Giant Killer", 7);
        MAX_LEVELS.put("Gravity", 6);
        MAX_LEVELS.put("Great Spook", 1);
        MAX_LEVELS.put("Green Thumb", 5);
        MAX_LEVELS.put("Growth", 7);
        MAX_LEVELS.put("Hardened Mana", 10);
        MAX_LEVELS.put("Harvesting", 6);
        MAX_LEVELS.put("Hecatomb", 10);
        MAX_LEVELS.put("Ice Cold", 5);
        MAX_LEVELS.put("Impaling", 5);
        MAX_LEVELS.put("Infinite Quiver", 10);
        MAX_LEVELS.put("Knockback", 2);
        MAX_LEVELS.put("Lethality", 6);
        MAX_LEVELS.put("Life Steal", 5);
        MAX_LEVELS.put("Looting", 5);
        MAX_LEVELS.put("Luck", 7);
        MAX_LEVELS.put("Luck of the Sea", 6);
        MAX_LEVELS.put("Lure", 6);
        MAX_LEVELS.put("Mana Steal", 3);
        MAX_LEVELS.put("Mana Vampire", 10);
        MAX_LEVELS.put("Magnet", 6);
        MAX_LEVELS.put("Overload", 5);
        MAX_LEVELS.put("Pesterminator", 6);
        MAX_LEVELS.put("Piercing", 1);
        MAX_LEVELS.put("Piscary", 7);
        MAX_LEVELS.put("Power", 7);
        MAX_LEVELS.put("Prismatic", 5);
        MAX_LEVELS.put("Projectile Protection", 7);
        MAX_LEVELS.put("Prosecute", 6);
        MAX_LEVELS.put("Prosperity", 5);
        MAX_LEVELS.put("Protection", 7);
        MAX_LEVELS.put("Punch", 2);
        MAX_LEVELS.put("Quantum", 5);
        MAX_LEVELS.put("Rainbow", 3);
        MAX_LEVELS.put("Rejuvenate", 5);
        MAX_LEVELS.put("Reflection", 5);
        MAX_LEVELS.put("Replenish", 1);
        MAX_LEVELS.put("Respiration", 4);
        MAX_LEVELS.put("Respite", 5);
        MAX_LEVELS.put("Scavenger", 6);
        MAX_LEVELS.put("Scuba", 5);
        MAX_LEVELS.put("Sharpness", 7);
        MAX_LEVELS.put("Silk Touch", 1);
        MAX_LEVELS.put("Smelting Touch", 1);
        MAX_LEVELS.put("Smarty Pants", 5);
        MAX_LEVELS.put("Smite", 7);
        MAX_LEVELS.put("Snipe", 4);
        MAX_LEVELS.put("Spiked Hook", 7);
        MAX_LEVELS.put("Strong Mana", 10);
        MAX_LEVELS.put("Sugar Rush", 3);
        MAX_LEVELS.put("Sunder", 6);
        MAX_LEVELS.put("Tabasco", 3);
        MAX_LEVELS.put("Thorns", 3);
        MAX_LEVELS.put("Thunderbolt", 7);
        MAX_LEVELS.put("Thunderlord", 7);
        MAX_LEVELS.put("Titan Killer", 7);
        MAX_LEVELS.put("Transylvanian", 10);
        MAX_LEVELS.put("Triple-Strike", 5);
        MAX_LEVELS.put("True Protection", 1);
        MAX_LEVELS.put("Turbo-Crop", 5);
        MAX_LEVELS.put("Vampirism", 6);
        MAX_LEVELS.put("Venomous", 6);
        MAX_LEVELS.put("Vicious", 5);
        MAX_LEVELS.put("Woodsplitter", 6);
        MAX_LEVELS.put("Bank", 5);
        MAX_LEVELS.put("Bobbin' Time", 5);
        MAX_LEVELS.put("Chimera", 5);
        MAX_LEVELS.put("Combo", 5);
        MAX_LEVELS.put("Crop Fever", 5);
        MAX_LEVELS.put("Duplex", 5);
        MAX_LEVELS.put("Fatal Tempo", 5);
        MAX_LEVELS.put("First Impression", 5);
        MAX_LEVELS.put("Flash", 5);
        MAX_LEVELS.put("Flowstate", 5);
        MAX_LEVELS.put("Habanero Tactics", 4);
        MAX_LEVELS.put("Inferno", 5);
        MAX_LEVELS.put("Last Stand", 5);
        MAX_LEVELS.put("Legion", 5);
        MAX_LEVELS.put("No Pain No Gain", 5);
        MAX_LEVELS.put("One For All", 1);
        MAX_LEVELS.put("Rend", 5);
        MAX_LEVELS.put("Soul Eater", 5);
        MAX_LEVELS.put("Swarm", 5);
        MAX_LEVELS.put("Ultimate Jerry", 5);
        MAX_LEVELS.put("Ultimate Wise", 5);
        MAX_LEVELS.put("Wisdom", 5);
    }

    public static int getMaxLevel(String enchantmentName) {
        if (enchantmentName == null)
            return 5;

        // Check custom user configuration first
        for (String custom : dev.aether.config.AetherConfig.CUSTOM_ENCHANTMENT_LEVELS.get()) {
            String[] parts = custom.split(":");
            if (parts.length >= 2 && parts[0].trim().equalsIgnoreCase(enchantmentName)) {
                try {
                    return Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // Handle "Turbo-" enchantments generally as they all have max level 5
        if (enchantmentName.toLowerCase().startsWith("turbo-")) {
            return 5;
        }

        // Search effectively case-insensitively in predefined map
        for (Map.Entry<String, Integer> entry : MAX_LEVELS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(enchantmentName)) {
                return entry.getValue();
            }
        }
        return 5;
    }

    public static int parseLevel(String levelStr) {
        if (levelStr == null || levelStr.isEmpty())
            return 0;
        try {
            return Integer.parseInt(levelStr);
        } catch (NumberFormatException e) {
            return romanToInt(levelStr.toUpperCase());
        }
    }

    /**
     * Attempt to find a known enchantment canonical name inside the provided text.
     * Returns the canonical key from MAX_LEVELS when a match is found (case-insensitive),
     * or null if no known enchantment name appears in the text.
     */
    public static String findEnchantmentNameIn(String text) {
        if (text == null || text.isEmpty()) return null;
        String lower = text.toLowerCase();
        // Normalize text by removing common punctuation to improve matching
        String normalized = lower.replaceAll("[^a-z0-9\\s-]", " ").replaceAll("\\s+", " ").trim();

        // First try exact/word matches against keys
        for (String key : MAX_LEVELS.keySet()) {
            String keyLower = key.toLowerCase();
            if (normalized.equals(keyLower) || normalized.contains(" " + keyLower + " ")
                    || normalized.startsWith(keyLower + " ") || normalized.endsWith(" " + keyLower)
                    || normalized.contains(keyLower)) {
                return key; // return canonical key
            }
        }

        return null;
    }

    private static int romanToInt(String s) {
        Map<Character, Integer> roman = new HashMap<>();
        roman.put('I', 1);
        roman.put('V', 5);
        roman.put('X', 10);
        roman.put('L', 50);
        roman.put('C', 100);
        roman.put('D', 500);
        roman.put('M', 1000);

        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            if (!roman.containsKey(s.charAt(i)))
                continue;
            int current = roman.get(s.charAt(i));
            if (i + 1 < s.length() && roman.containsKey(s.charAt(i + 1)) && roman.get(s.charAt(i + 1)) > current) {
                total -= current;
            } else {
                total += current;
            }
        }
        return total;
    }
}
