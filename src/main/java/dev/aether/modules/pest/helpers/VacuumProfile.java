package dev.aether.modules.pest.helpers;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record VacuumProfile(int rarity, float range) {
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern RARITY = Pattern.compile("\\b(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC) VACUUM\\b");
    private static final Pattern ABILITY = Pattern.compile("ABILITY: VACUUM\\b(.*?)(?=ABILITY:|VACUUM BAG:|$)");
    private static final Pattern RANGE = Pattern.compile("\\bWITHIN\\s+(\\d+(?:\\.\\d+)?)\\s+BLOCKS\\b");
    private static final List<String> RARITIES = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

    static VacuumProfile parse(String name, List<String> lore, boolean recombobulated) {
        int rarity = -1;
        for (String line : lore) {
            var matcher = RARITY.matcher(clean(line));
            if (matcher.find()) rarity = RARITIES.indexOf(matcher.group(1));
        }

        String normalizedName = clean(name).replace("™", "");
        float namedRange = normalizedName.contains("INFINIVACUUM HOOVERIUS") ? 15f
                : normalizedName.contains("INFINIVACUUM") ? 12.5f
                : normalizedName.contains("HYPER VACUUM") ? 10f
                : normalizedName.contains("TURBO VACUUM") ? 7.5f
                : normalizedName.contains("SKYMART VACUUM") ? 5f : 0f;
        int baseRarity = Math.max(0, rarity - (recombobulated ? 1 : 0));
        float range = namedRange > 0 ? namedRange : 5f + Math.min(4, baseRarity) * 2.5f;

        var ability = ABILITY.matcher(clean(String.join(" ", lore)));
        if (ability.find()) {
            var explicitRange = RANGE.matcher(ability.group(1));
            if (explicitRange.find()) {
                float value = Float.parseFloat(explicitRange.group(1));
                if (value >= 5f && value <= 15f) range = value;
            }
        }
        return new VacuumProfile(rarity, range);
    }

    float effectiveRange(boolean respectTrueRange) {
        return respectTrueRange ? range : range * 0.9f;
    }

    private static String clean(String text) {
        return FORMATTING.matcher(text).replaceAll("").toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
