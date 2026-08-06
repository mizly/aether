package dev.aether.modules.visitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VisitorsMacroTest {
    @Test
    void appliesCoinsPerCopperAndRareDropRules() {
        assertEquals(20_000, VisitorsMacro.calculatePricePerCopper(260_000, 13));
        assertEquals(0, VisitorsMacro.calculatePricePerCopper(260_000, 0));
        assertEquals(13, VisitorsMacro.parseCopperReward("+13 Copper (Saying 14k per)"));
        assertTrue(VisitorsMacro.hasRareReward("+1 Overgrown Grass"));

        assertTrue(VisitorsMacro.shouldAcceptOffer(true, 20_000, true, false, 13, 20_000));
        assertFalse(VisitorsMacro.shouldAcceptOffer(true, 20_000, true, false, 13, 20_001));
        assertTrue(VisitorsMacro.shouldAcceptOffer(true, 20_000, true, true, 13, 20_001));
        assertFalse(VisitorsMacro.shouldAcceptOffer(false, 20_000, true, false, 13, 1));
    }
}
