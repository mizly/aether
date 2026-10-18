package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestLeaveOneControllerTest {
    @Test
    void parsesCurrentPlotCountAndOnlyFinishesForRememberedPlots() {
        List<String> sidebar = List.of("The Garden x6", "Plot - 15 x4");

        assertEquals(4, PestLeaveOneController.parsePlotPestCount(sidebar, "15"));
        assertEquals(-1, PestLeaveOneController.parsePlotPestCount(sidebar, "17"));
        assertFalse(PestLeaveOneController.shouldFinishForCounts(1, 0));
        assertFalse(PestLeaveOneController.shouldFinishForCounts(2, 1));
        assertTrue(PestLeaveOneController.shouldFinishForCounts(2, 2));
        assertEquals(5, PestLeaveOneController.killBudgetForCount(6));
        assertEquals(4, PestLeaveOneController.consumeKillBudget(5));
        assertEquals(0, PestLeaveOneController.consumeKillBudget(0));
        assertEquals(0, PestLeaveOneController.consumeKillBudget(1));
        assertEquals(2, PestLeaveOneController.remainingKillBudget(4, 1));
        assertEquals(2, PestTargetTracker.mostIsolatedIndex(List.of(
                new Vec3(0, 0, 0),
                new Vec3(1, 0, 0),
                new Vec3(10, 0, 0))));
    }
}
