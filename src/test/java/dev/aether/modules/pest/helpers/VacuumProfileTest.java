package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VacuumProfileTest {
    @Test
    void detectsAllGardenRangesFromFormattedRarityLore() {
        String[] rarities = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};
        float[] ranges = {5f, 7.5f, 10f, 12.5f, 15f, 15f};
        for (int i = 0; i < rarities.length; i++) {
            var profile = VacuumProfile.parse("Vacuum", List.of("§kX§r §6§l" + rarities[i] + " VACUUM §kX"), false);
            assertEquals(i, profile.rarity());
            assertEquals(ranges[i], profile.range());
        }
    }

    @Test
    void recognizesReforgesTrademarkAndCapitalizationWithoutLore() {
        assertEquals(15f, VacuumProfile.parse("§6Buzzing InfiniVacuum™ Hooverius", List.of(), false).range());
        assertEquals(12.5f, VacuumProfile.parse("Beady InfiniVacuum™", List.of(), false).range());
        assertEquals(10f, VacuumProfile.parse("SkyMart Hyper Vacuum", List.of(), false).range());
        assertEquals(7.5f, VacuumProfile.parse("SKYMART TURBO VACUUM", List.of(), false).range());
        assertEquals(5f, VacuumProfile.parse("SkyMart Vacuum", List.of(), false).range());
    }

    @Test
    void recombobulationDoesNotUpgradeTheAbilityRange() {
        assertEquals(12.5f, VacuumProfile.parse("InfiniVacuum™", List.of("LEGENDARY VACUUM"), false).range());
        assertEquals(5f, VacuumProfile.parse("SkyMart Vacuum", List.of("UNCOMMON VACUUM"), true).range());
        assertEquals(7.5f, VacuumProfile.parse("Vacuum", List.of("RARE VACUUM"), true).range());
        assertEquals(15f, VacuumProfile.parse("Vacuum", List.of("MYTHIC VACUUM"), true).range());
    }

    @Test
    void prefersTheVacuumAbilityRangeAcrossWrappedLoreLines() {
        var profile = VacuumProfile.parse("Vacuum", List.of(
                "Ability: Vacuum HOLD RIGHT CLICK", "Aim at a nearby Pest within §e12.5",
                "§7blocks to suck it in.", "Ability: Pest Tracker LEFT CLICK", "LEGENDARY VACUUM"), false);
        assertEquals(12.5f, profile.range());
    }

    @Test
    void ignoresOtherAbilitiesMalformedRangesAndUnrelatedRarityText() {
        assertEquals(5f, VacuumProfile.parse("Vacuum", List.of("LEGENDARY ACCESSORY"), false).range());
        assertEquals(5f, VacuumProfile.parse("Vacuum", List.of(
                "Ability: Other", "Within 15 blocks", "COMMON VACUUM"), false).range());
        for (String range : List.of("0", "9999999999999999999999999999999999999999999999999", "NaN", "-15")) {
            assertEquals(5f, VacuumProfile.parse("Vacuum", List.of(
                    "Ability: Vacuum", "Within " + range + " blocks", "COMMON VACUUM"), false).range());
        }
    }

    @Test
    void toggleRetainsTheConservativeMarginWhenDisabled() {
        var profile = VacuumProfile.parse("InfiniVacuum™ Hooverius", List.of("LEGENDARY VACUUM"), false);
        assertEquals(15f, profile.effectiveRange(true));
        assertEquals(13.5f, profile.effectiveRange(false));
    }
}
