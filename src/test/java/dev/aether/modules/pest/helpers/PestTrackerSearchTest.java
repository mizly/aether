package dev.aether.modules.pest.helpers;

import dev.aether.util.GardenPlots;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PestTrackerSearchTest {
    @Test
    void keepsSearchInsideThePlotAtTheRoofAltitude() {
        Vec3 waypoint = PestTrackerSearch.constrainWaypoint(new Vec3(0, 90, 0), new Vec3(80, 68, -100),
                GardenPlots.boundsForPlot(0), null);
        assertEquals(new Vec3(40, 90, -40), waypoint);
    }

    @Test
    void rejectsReachedEstimatesAndPreviouslyCheckedLocations() {
        Vec3 player = new Vec3(0, 90, 0);
        assertNull(PestTrackerSearch.constrainWaypoint(player, new Vec3(5, 120, 0), null, null));
        assertNull(PestTrackerSearch.constrainWaypoint(player, new Vec3(30, 70, 30), null, new Vec3(29, 90, 29)));
        assertNotNull(PestTrackerSearch.constrainWaypoint(player, new Vec3(-30, 70, 30), null, new Vec3(29, 90, 29)));
    }
}
