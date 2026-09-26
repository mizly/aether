package dev.aether.modules.profit.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillXpTrackerTest {
    @Test
    void fishingCapsAtFiftyWhileFarmingRunsToSixty() {
        SkillXpTracker fishing = new SkillXpTracker("Fishing", 50);
        SkillXpTracker farming = new SkillXpTracker("Farming", 60);
        assertEquals(50, fishing.levelForNeeded(0L));
        assertEquals(60, farming.levelForNeeded(0L));
        // 4.3m is only needed past level 50, which fishing never reaches
        assertEquals(-1, fishing.levelForNeeded(4_300_000L));
        assertEquals(50, farming.levelForNeeded(4_300_000L));
    }

    @Test
    void aLevelPastTheSkillCapIsIgnored() {
        SkillXpTracker fishing = new SkillXpTracker("Fishing", 50);
        fishing.setAnchor(55, 1_000L);
        assertFalse(fishing.hasData());
    }

    @Test
    void gainIsCountedFromTheFirstAnchorOfTheSession() {
        SkillXpTracker fishing = new SkillXpTracker("Fishing", 50);
        fishing.setAnchor(20, 1_000L);
        fishing.setAnchor(20, 51_000L);
        assertTrue(fishing.hasData());
        assertEquals(20, fishing.getLevel());
        assertEquals(50_000L, fishing.getSessionXpGained());
        assertEquals(51_000L, fishing.getXpIntoLevel());
    }

    @Test
    void eachSkillKeepsItsOwnProgress() {
        SkillXpTracker fishing = new SkillXpTracker("Fishing", 50);
        SkillXpTracker farming = new SkillXpTracker("Farming", 60);
        fishing.setAnchor(10, 0L);
        assertTrue(fishing.hasData());
        assertFalse(farming.hasData());
    }
}
