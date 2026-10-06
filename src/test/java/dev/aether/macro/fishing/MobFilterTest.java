package dev.aether.macro.fishing;

import dev.aether.macro.fishing.MobFilter.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MobFilterTest {
    @Test
    void aCatchWithoutAPlateIsUnknown() {
        assertEquals(Verdict.UNKNOWN, MobFilter.classify(null, List.of(), List.of()));
        assertEquals(Verdict.UNKNOWN, MobFilter.classify("", List.of("squid"), List.of()));
        assertEquals(Verdict.UNKNOWN, MobFilter.classify("   ", List.of(), List.of("squid")));
        assertEquals(Verdict.UNKNOWN, MobFilter.classify("§c§r", List.of("squid"), List.of()));
    }

    @Test
    void theBlacklistBeatsTheWhitelist() {
        assertEquals(Verdict.IGNORE, MobFilter.classify("§e[Lv45] §cLord Jawbus §a75M§f/§a75M§c❤",
                List.of("Lord Jawbus"), List.of("jawbus")));
        assertEquals(Verdict.ACCEPT, MobFilter.classify("[Lv45] Lord Jawbus", List.of("Lord Jawbus"), List.of("thunder")));
    }

    @Test
    void entriesOnlyMatchWholeWords() {
        assertEquals(Verdict.ACCEPT, MobFilter.classify("Night Squid", List.of("Squid"), List.of()));
        assertEquals(Verdict.IGNORE, MobFilter.classify("Squidward", List.of("Squid"), List.of()));
        assertEquals(Verdict.ACCEPT, MobFilter.classify("Squidward", List.of(), List.of("squid")));
        assertEquals(Verdict.IGNORE, MobFilter.classify("[Lv8] Sea Walker 1,500/1,500❤", List.of(), List.of("sea walker")));
    }

    @Test
    void colourCodesAndFontGlyphsAreStripped() {
        String plate = "§b §cNight Squid §e1.2k§c❤";
        assertEquals("night squid 1.2k❤", MobFilter.normalize(plate));
        assertEquals(Verdict.ACCEPT, MobFilter.classify(plate, List.of("§anight  squid"), List.of()));
        assertEquals(Verdict.IGNORE, MobFilter.classify(plate, List.of(), List.of("Night Squid")));
    }

    @Test
    void blankEntriesAreSkipped() {
        assertEquals(Verdict.ACCEPT, MobFilter.classify("Night Squid", List.of("", "  ", "§c"), List.of()));
        assertEquals(Verdict.ACCEPT, MobFilter.classify("[Lv5] Squid", List.of(), Arrays.asList("", null, "  ")));
    }

    @Test
    void anEmptyWhitelistAcceptsEveryNamedCatch() {
        assertEquals(Verdict.ACCEPT, MobFilter.classify("Sea Walker", List.of(), List.of()));
        assertEquals(Verdict.ACCEPT, MobFilter.classify("Sea Walker", null, null));
    }

    @Test
    void aListEditedInPlaceIsMatchedByItsNewContents() {
        List<String> whitelist = new ArrayList<>(List.of("squid"));
        assertEquals(Verdict.IGNORE, MobFilter.classify("Sea Walker", whitelist, List.of()));
        whitelist.set(0, "sea walker");
        assertEquals(Verdict.ACCEPT, MobFilter.classify("Sea Walker", whitelist, List.of()));
        whitelist.clear();
        assertEquals(Verdict.ACCEPT, MobFilter.classify("Night Squid", whitelist, List.of()));
    }
}
