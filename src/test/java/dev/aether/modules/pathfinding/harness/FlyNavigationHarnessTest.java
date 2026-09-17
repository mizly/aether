package dev.aether.modules.pathfinding.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

class FlyNavigationHarnessTest {

    private record Scenario(String name, BlockWorld world, Vec3 start, float yaw, BlockPos goal, int maxTicks) {
    }

    private static List<Scenario> scenarios() {
        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new Scenario("greenhouse entry", Structures.greenhouse(),
                new Vec3(7.5, 66.15, -5.5), 0.0f, new BlockPos(11, 65, 9), 800));
        scenarios.add(new Scenario("greenhouse crossing", Structures.greenhouse(),
                new Vec3(1.5, 65.15, 1.5), 0.0f, new BlockPos(13, 65, 9), 800));
        scenarios.add(new Scenario("greenhouse aisle", Structures.greenhouse(),
                new Vec3(2.5, 65.15, 3.5), 90.0f, new BlockPos(12, 65, 7), 800));
        scenarios.add(new Scenario("doorway hop", Structures.doorwayHop(),
                new Vec3(6.5, 65.15, 3.5), 0.0f, new BlockPos(9, 67, 15), 800));
        scenarios.add(new Scenario("narrow bend", Structures.narrowBend(),
                new Vec3(2.5, 65.15, 2.5), 0.0f, new BlockPos(16, 67, 10), 1200));
        scenarios.add(new Scenario("field wall", Structures.fieldWall(),
                new Vec3(0.5, 67.15, 0.5), 0.0f, new BlockPos(0, 67, 20), 800));
        return scenarios;
    }

    @Test
    void escapesSlabAndStairOverhangsFromActualPlayerHeight() {
        for (boolean stair : new boolean[]{false, true}) {
            BlockWorld world = new BlockWorld().ground(63)
                    .shape(new AABB(-2, 64, -2, 3, 64.5, 3))
                    .shape(new AABB(-2, 66.5, -2, 3, 67, 3));
            if (stair) world.shape(new AABB(-2, 66, 1, 3, 66.5, 3));
            for (float yaw : new float[]{0, 90, 180, -90}) {
                FlightTrial.Result result = FlightTrial.run(stair ? "stair eave" : "slab eave", world,
                        new Vec3(0.5, 64.7, 0.5), new BlockPos(0, 68, 8), yaw, 400);
                System.out.println(result);
                assertTrue(result.reached(), result::toString);
                assertEquals(0, result.repaths(), result::toString);
                assertEquals(0, result.blockedTicks(), result::toString);
                assertTrue(result.verticalFlips() <= 8, result::toString);
            }
        }
    }

    @Test
    void fliesTightStructuresWithoutReplanningOrCrawling() {
        List<FlightTrial.Result> results = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            long start = System.nanoTime();
            FlightTrial.Result result = FlightTrial.run(scenario.name(), scenario.world(), scenario.start(),
                    scenario.goal(), scenario.yaw(), scenario.maxTicks());
            results.add(result);
            System.out.println(result + String.format("  [%.0fms]", (System.nanoTime() - start) / 1e6));
            for (String note : result.notes()) {
                System.out.println("        replan: " + note);
            }
        }

        long reached = results.stream().filter(FlightTrial.Result::reached).count();
        double seconds = results.stream().mapToDouble(FlightTrial.Result::seconds).sum();
        int ticks = results.stream().mapToInt(FlightTrial.Result::ticks).sum();
        int replans = results.stream().mapToInt(FlightTrial.Result::repaths).sum();
        int stalled = results.stream().mapToInt(FlightTrial.Result::stallTicks).sum();
        int flips = results.stream().mapToInt(FlightTrial.Result::verticalFlips).sum();
        System.out.printf("TOTAL reached=%d/%d time=%.1fs waypoints=%d replans=%d stalled=%d flips=%d%n",
                reached, results.size(), seconds,
                results.stream().mapToInt(FlightTrial.Result::totalWaypoints).sum(), replans, stalled, flips);

        // budgets sit above what these routes cost now and below the stop-at-every-waypoint behaviour
        // they replaced, which needed 671 ticks, 62 waypoints, 4 replans and 41 stalled ticks
        assertAll(
                () -> assertEquals(results.size(), reached, () -> "routes that never arrived: "
                        + results.stream().filter(result -> !result.reached()).toList()),
                () -> assertEquals(0, replans, () -> "routes were abandoned mid-flight: " + results),
                () -> assertTrue(ticks <= 520, () -> "flights got slower: " + ticks + " ticks, " + results),
                () -> assertTrue(stalled <= 25, () -> "flights stalled more often: " + stalled + ", " + results),
                // climbs and descents should be held, not tapped on and off tick by tick
                () -> assertTrue(flips <= 34, () -> "vertical input is feathering: " + flips + ", " + results));
    }
}
