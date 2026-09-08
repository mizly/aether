package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestPlotPriorityTest {
    private static Set<String> plots(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    @Test
    void currentPlotFirstPutsTheStandingPlotAtTheHead() {
        assertEquals(List.of("4", "11", "7"),
                PestPlotPriority.order(plots("11", "4", "7"), "4", false));
    }

    @Test
    void otherPlotsFirstLeavesTheStandingPlotForLast() {
        assertEquals(List.of("11", "7", "4"),
                PestPlotPriority.order(plots("11", "4", "7"), "4", true));
    }

    @Test
    void plotLabelsAreMatchedByNumberNotByText() {
        assertEquals(List.of("Plot 4", "11"),
                PestPlotPriority.order(plots("11", "Plot 4"), "#4", false));
    }

    @Test
    void orderingIsStableWhenTheStandingPlotIsNotInfested() {
        assertEquals(List.of("11", "7"),
                PestPlotPriority.order(plots("11", "7"), "4", false));
        assertEquals(List.of("11", "7"),
                PestPlotPriority.order(plots("11", "7"), "4", true));
    }

    @Test
    void currentPlotFirstHoldsThePlotWhileItStillReportsPests() {
        assertTrue(PestPlotPriority.shouldHoldCurrentPlot(plots("4", "11"), "4", false, 0));
        assertFalse(PestPlotPriority.shouldHoldCurrentPlot(plots("11"), "4", false, 0));
    }

    @Test
    void otherPlotsFirstNeverHoldsTheStandingPlot() {
        assertFalse(PestPlotPriority.shouldHoldCurrentPlot(plots("4", "11"), "4", true, 0));
    }

    @Test
    void theHoldGivesUpAfterTooManyFruitlessSweeps() {
        assertTrue(PestPlotPriority.shouldHoldCurrentPlot(
                plots("4"), "4", false, PestPlotPriority.MAX_CURRENT_PLOT_HOLDS - 1));
        assertFalse(PestPlotPriority.shouldHoldCurrentPlot(
                plots("4"), "4", false, PestPlotPriority.MAX_CURRENT_PLOT_HOLDS));
    }

    @Test
    void anUnknownPlotIsNeverHeld() {
        assertFalse(PestPlotPriority.shouldHoldCurrentPlot(plots("4"), "Unknown", false, 0));
        assertFalse(PestPlotPriority.shouldHoldCurrentPlot(plots("4"), null, false, 0));
    }
}
