package dev.aether.modules.profit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitTrackingRulesTest {
    @Test
    void parsesWholePurse() {
        assertEquals(26_000_000L, ProfitTrackingRules.parsePurseLine("Purse: 26,000,000 (+300)"));
    }

    @Test
    void fractionalPurseIsNotInflatedTenTimes() {
        assertEquals(1_234L, ProfitTrackingRules.parsePurseLine("Purse: 1,234.5"));
        assertEquals(1_234L, ProfitTrackingRules.parsePurseLine("Purse: 1,234.5 (+0.5)"));
    }

    @Test
    void parsesPiggyAndRejectsOtherLines() {
        assertEquals(500L, ProfitTrackingRules.parsePurseLine("Piggy: 500"));
        assertEquals(-1L, ProfitTrackingRules.parsePurseLine("Bits: 1,200"));
        assertEquals(-1L, ProfitTrackingRules.parsePurseLine("Purse: ???"));
        assertEquals(-1L, ProfitTrackingRules.parsePurseLine(null));
    }

    @Test
    void mapsCropSpecificTools() {
        assertEquals("Carrot", ProfitTrackingRules.cropForToolId("THEORETICAL_HOE_CARROT_3"));
        assertEquals("Nether Wart", ProfitTrackingRules.cropForToolId("THEORETICAL_HOE_WARTS_2"));
        assertEquals("Melon Slice", ProfitTrackingRules.cropForToolId("MELON_DICER_3"));
        assertEquals("Cocoa Beans", ProfitTrackingRules.cropForToolId("COCO_CHOPPER"));
        assertNull(ProfitTrackingRules.cropForToolId("FUNGI_CUTTER"));
        assertNull(ProfitTrackingRules.cropForToolId("BASIC_GARDENING_HOE"));
        assertTrue(ProfitTrackingRules.isMushroomTool("FUNGI_CUTTER"));
    }

    @Test
    void rejectsDropsTypedByPlayers() {
        String fake = "[MVP+] someone: RARE DROP! Overbloom";
        assertFalse(ProfitTrackingRules.isSystemMatch(fake, fake.indexOf("RARE DROP!")));
        String party = "Party > [VIP] friend: PET DROP! Slug";
        assertFalse(ProfitTrackingRules.isSystemMatch(party, party.indexOf("PET DROP!")));
        String real = "RARE DROP! Overbloom (+250% Magic Find)";
        assertTrue(ProfitTrackingRules.isSystemMatch(real, 0));
    }
}
