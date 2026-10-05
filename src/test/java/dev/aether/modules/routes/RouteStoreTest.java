package dev.aether.modules.routes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteStoreTest {
    private static final RouteStore.Folder FOLDER = new RouteStore.Folder("strider_fishing", "Strider Fishing", List.of());

    @TempDir
    Path root;

    private static Route sampleRoute(String name) {
        Route route = new Route(name, "galatea");
        route.add(new Route.Waypoint(10, 64, -3, Route.LegType.WALK));
        route.add(new Route.Waypoint(40, 70, 12, Route.LegType.ETHERWARP));
        return route;
    }

    @Test
    void savedRouteLoadsBackWithEveryLegInOrder() {
        RouteStore store = new RouteStore(root);
        assertTrue(store.save(FOLDER, sampleRoute("Lava Pool")));

        Route loaded = store.load(FOLDER, "Lava Pool");

        assertNotNull(loaded);
        assertEquals("galatea", loaded.warp());
        assertEquals(List.of(
                new Route.Waypoint(10, 64, -3, Route.LegType.WALK),
                new Route.Waypoint(40, 70, 12, Route.LegType.ETHERWARP)), loaded.waypoints());
        assertEquals(new Route.Waypoint(40, 70, 12, Route.LegType.ETHERWARP), loaded.end());
    }

    @Test
    void routesLiveInAFolderNamedAfterTheMacro() {
        RouteStore store = new RouteStore(root);
        store.save(FOLDER, sampleRoute("Spot"));

        assertTrue(Files.isRegularFile(root.resolve("strider_fishing").resolve("Spot.json")));
        assertEquals(List.of("Spot"), store.list(FOLDER));
    }

    @Test
    void unknownLegTypeFallsBackToWalking() {
        Route route = RouteStore.fromJson("Odd", """
                {"warp": "hub", "waypoints": [{"x": 1, "y": 2, "z": 3, "type": "FLY"}]}
                """);

        assertEquals(Route.LegType.WALK, route.waypoints().getFirst().type());
    }

    @Test
    void newRoutesTakeTheFirstFreeNumber() {
        RouteStore store = new RouteStore(root);
        store.save(FOLDER, sampleRoute("Route 1"));
        store.save(FOLDER, sampleRoute("Route 3"));

        assertEquals("Route 2", store.nextFreeName(FOLDER));
    }

    @Test
    void renameMovesTheFileAndRefusesATakenName() {
        RouteStore store = new RouteStore(root);
        Route route = sampleRoute("Old");
        store.save(FOLDER, route);
        store.save(FOLDER, sampleRoute("Taken"));

        assertFalse(store.rename(FOLDER, route, "taken"));
        assertTrue(store.rename(FOLDER, route, "New"));

        assertEquals("New", route.name());
        assertNull(store.load(FOLDER, "Old"));
        assertNotNull(store.load(FOLDER, "New"));
    }

    @Test
    void deleteRemovesTheRoute() {
        RouteStore store = new RouteStore(root);
        store.save(FOLDER, sampleRoute("Gone"));

        assertTrue(store.delete(FOLDER, "Gone"));
        assertTrue(store.list(FOLDER).isEmpty());
    }

    @Test
    void namesCannotEscapeTheRoutesFolder() {
        assertEquals("etcpasswd", RouteStore.sanitizeName("../etc/passwd"));
        assertEquals("", RouteStore.sanitizeName("///"));
    }

    @Test
    void warpCommandAcceptsTheIslandWithOrWithoutTheCommand() {
        assertEquals("/warp galatea", new Route("a", "galatea").warpCommand());
        assertEquals("/warp galatea", new Route("a", "/warp galatea").warpCommand());
        assertEquals("/warp galatea", new Route("a", "warp galatea").warpCommand());
        assertEquals("", new Route("a", "  ").warpCommand());
    }

    @Test
    void aWarpWithNoLegsIsAWarpOnlyRoute() {
        Route warpOnly = new Route("a", "crimson");
        assertTrue(warpOnly.hasWarp());
        assertTrue(warpOnly.isWarpOnly());

        Route nothing = new Route("a", "  ");
        assertFalse(nothing.hasWarp());
        assertFalse(nothing.isWarpOnly());

        assertTrue(sampleRoute("a").hasWarp());
        assertFalse(sampleRoute("a").isWarpOnly());
    }

    @Test
    void theBundledStriderRouteIsPutBackWhenMissing() {
        RouteStore store = new RouteStore(root);

        assertTrue(store.list(RouteStore.STRIDER_FISHING).contains("default_strider"));
        Route route = store.load(RouteStore.STRIDER_FISHING, "default_strider");

        assertNotNull(route);
        assertEquals("/warp galatea", route.warpCommand());
        assertEquals(9, route.waypoints().size());
    }

    @Test
    void theSawyerSpotShipsAsAOneWalkRouteOntoTheSpot() {
        RouteStore store = new RouteStore(root);

        assertTrue(store.list(RouteStore.STRIDER_FISHING).contains("sawyer_spot"));
        Route route = store.load(RouteStore.STRIDER_FISHING, "sawyer_spot");

        assertNotNull(route);
        assertEquals("/warp galatea", route.warpCommand());
        assertEquals(new Route.Waypoint(-694, 120, 78, Route.LegType.WALK), route.end());
        assertEquals(1, route.waypoints().size());
    }

    @Test
    void resetRestoresTheBundledCopyOverAnEditedOne() {
        RouteStore store = new RouteStore(root);
        Route edited = new Route("default_strider", "hub");
        store.save(RouteStore.STRIDER_FISHING, edited);

        assertTrue(store.resetDefault(RouteStore.STRIDER_FISHING, "default_strider"));

        assertEquals("galatea", store.load(RouteStore.STRIDER_FISHING, "default_strider").warp());
    }

    @Test
    void theBundledRouteCannotBeRenamed() {
        RouteStore store = new RouteStore(root);
        Route route = store.load(RouteStore.STRIDER_FISHING, "default_strider");

        assertFalse(store.rename(RouteStore.STRIDER_FISHING, route, "Mine"));
    }
}
