package dev.aether.ui.orbit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrbitIslandTest {
    @Test
    void readsTheAreaLine() {
        assertEquals(OrbitIsland.CRIMSON_ISLE, OrbitIsland.fromArea("§b§lArea: §r§cCrimson Isle"));
        assertEquals(OrbitIsland.GARDEN, OrbitIsland.fromArea(" Area: Garden"));
        assertEquals(OrbitIsland.OTHER, OrbitIsland.fromArea("Area: Hub"));
        assertEquals(OrbitIsland.OTHER, OrbitIsland.fromArea(null));
    }

    @Test
    void facesTheLastMacroFirst() {
        assertEquals("macros", OrbitIsland.initialCategory("strider_fishing", OrbitIsland.GARDEN));
        assertEquals("farming", OrbitIsland.initialCategory("farming", OrbitIsland.CRIMSON_ISLE));
        assertEquals("macros", OrbitIsland.initialCategory(null, OrbitIsland.CRIMSON_ISLE));
        assertEquals("farming", OrbitIsland.initialCategory(null, OrbitIsland.OTHER));
    }

    @Test
    void travelsOnlyBetweenKnownIslands() {
        OrbitIsland.arrive(OrbitIsland.GARDEN);
        assertNull(OrbitIsland.arrive(OrbitIsland.GARDEN));
        assertNull(OrbitIsland.arrive(OrbitIsland.OTHER));
        assertEquals(OrbitIsland.GARDEN, OrbitIsland.arrive(OrbitIsland.CRIMSON_ISLE));
        assertEquals(OrbitIsland.CRIMSON_ISLE, OrbitIsland.arrive(OrbitIsland.GARDEN));
    }
}
