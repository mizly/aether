package dev.aether.modules.pathfinding.harness;

import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.execution.FlightGuidance;
import java.util.EnumMap;
import java.util.Map;
import dev.aether.modules.pathfinding.execution.FlightPathClearance;
import dev.aether.modules.pathfinding.movement.FlightCollisionChecker;
import dev.aether.modules.pathfinding.movement.FlightPathSmoother;
import dev.aether.modules.pathfinding.pathfinder.AStarPathfinder;
import dev.aether.modules.pathfinding.pathing.NeighborStrategies;
import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration;
import dev.aether.modules.pathfinding.pathing.processing.impl.FlyPathProcessor;
import dev.aether.modules.pathfinding.pathing.result.PathfinderResult;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// replays what PathfindingManager does for a fly route: plan, execute, replan when the executor bails
public final class FlightTrial {
    public static final int REPATH_LIMIT = 3;
    private static final int REPLAN_LATENCY_TICKS = 2;
    private static final double SMOOTHING_MARGIN = 0.05;

    private FlightTrial() {
    }

    public record Result(String name, boolean reached, String failure, int ticks, int repaths, int plans,
                         int firstWaypoints, int totalWaypoints, double travelled, int stallTicks,
                         int blockedTicks, int verticalFlips, int verticalTicks, List<String> notes,
                         Map<FlightGuidance.Mode, Integer> modes) {

        public double meanSpeed() {
            return ticks == 0 ? 0.0 : travelled / ticks;
        }

        public double seconds() {
            return ticks / 20.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "%-22s %-7s t=%4d (%4.1fs) replans=%d wp=%2d/%2d speed=%.3f stalled=%3d blocked=%3d vert=%3d/%3d flips=%3d %s%s",
                    name, reached ? "OK" : "FAIL", ticks, seconds(), repaths, firstWaypoints, totalWaypoints,
                    meanSpeed(), stallTicks, blockedTicks, verticalTicks, ticks, verticalFlips, modes,
                    failure == null ? "" : "  <" + failure + ">");
        }
    }

    private record Route(List<Node> nodes, int goalY) {
    }

    public static Result run(String name, BlockWorld world, Vec3 start, BlockPos goal, float startYaw, int maxTicks) {
        FlightSim sim = new FlightSim(world, start, startYaw);
        FlightGuidance guidance = new FlightGuidance();
        List<String> notes = new ArrayList<>();
        Map<FlightGuidance.Mode, Integer> modes = new EnumMap<>(FlightGuidance.Mode.class);

        Route route = plan(world, sim.position(), goal);
        if (route == null) {
            return new Result(name, false, "no initial path", 0, 0, 1, 0, 0, 0.0, 0, 0, 0, 0, notes, modes);
        }
        guidance.start(route.nodes(), goal.getX(), route.goalY(), goal.getZ());

        int ticks = 0;
        int repaths = 0;
        int plans = 1;
        int firstWaypoints = route.nodes().size();
        int totalWaypoints = route.nodes().size();
        double travelled = 0.0;
        int stallTicks = 0;
        int blockedTicks = 0;
        int verticalFlips = 0;
        int verticalTicks = 0;
        int previousVertical = 0;

        while (ticks < maxTicks) {
            FlightGuidance.Command command = guidance.tick(sim);
            switch (command.status()) {
                case ARRIVED -> {
                    return new Result(name, true, null, ticks, repaths, plans, firstWaypoints, totalWaypoints,
                            travelled, stallTicks, blockedTicks, verticalFlips, verticalTicks, notes, modes);
                }
                case HOLD -> {
                    return new Result(name, false, "guidance idle", ticks, repaths, plans, firstWaypoints,
                            totalWaypoints, travelled, stallTicks, blockedTicks, verticalFlips, verticalTicks, notes, modes);
                }
                case REPATH -> {
                    notes.add(command.note());
                    repaths++;
                    if (repaths > REPATH_LIMIT) {
                        return new Result(name, false, "gave up after " + repaths + " replans", ticks, repaths,
                                plans, firstWaypoints, totalWaypoints, travelled, stallTicks, blockedTicks, verticalFlips, verticalTicks, notes, modes);
                    }
                    sim.stopTurning();
                    for (int i = 0; i < REPLAN_LATENCY_TICKS && ticks < maxTicks; i++) {
                        Vec3 before = sim.position();
                        sim.coast();
                        travelled += sim.position().distanceTo(before);
                        ticks++;
                    }
                    Route next = plan(world, sim.position(), goal);
                    plans++;
                    if (next == null) {
                        return new Result(name, false, "no path on replan", ticks, repaths, plans, firstWaypoints,
                                totalWaypoints, travelled, stallTicks, blockedTicks, verticalFlips, verticalTicks, notes, modes);
                    }
                    totalWaypoints += next.nodes().size();
                    guidance.start(next.nodes(), goal.getX(), next.goalY(), goal.getZ());
                }
                case STEER -> {
                    modes.merge(command.mode(), 1, Integer::sum);
                    Vec3 before = sim.position();
                    sim.advance(command);
                    double step = sim.position().distanceTo(before);
                    travelled += step;
                    if (step < 0.05) {
                        stallTicks++;
                    }
                    if (sim.blocked()) {
                        blockedTicks++;
                    }
                    if (command.vertical() != 0) {
                        verticalTicks++;
                    }
                    if (command.vertical() != previousVertical) {
                        verticalFlips++;
                        previousVertical = command.vertical();
                    }
                    ticks++;
                }
            }
        }
        return new Result(name, false, "timed out", ticks, repaths, plans, firstWaypoints, totalWaypoints,
                travelled, stallTicks, blockedTicks, verticalFlips, verticalTicks, notes, modes);
    }

    private static Route plan(BlockWorld world, Vec3 from, BlockPos goal) {
        FlightCollisionChecker checker = FlightCollisionChecker.over(world::collisions);
        FlyPathProcessor processor = new FlyPathProcessor(checker);

        int goalY = goal.getY();
        if (!processor.hasFlightClearance(goal.getX(), goalY, goal.getZ())
                && processor.hasFlightClearance(goal.getX(), goalY + 1, goal.getZ())) {
            goalY = goalY + 1;
        }

        PathPosition start = checker.findStart(from, body(from));
        if (start == null) return null;

        PathfinderConfiguration config = PathfinderConfiguration.builder()
                .provider((position, context) -> null)
                .processors(List.of(processor))
                .neighborStrategy(NeighborStrategies.HORIZONTAL_DIAGONAL_AND_VERTICAL)
                .maxIterations(300000)
                .maxLength(50000)
                .async(false)
                .fallback(true)
                .build();

        PathfinderResult result = new AStarPathfinder(config)
                .findPath(start, new PathPosition(goal.getX(), goalY, goal.getZ()))
                .toCompletableFuture().join();
        if (!result.successful() && !result.hasFallenBack()) {
            return null;
        }

        List<Node> nodes = new ArrayList<>();
        for (PathPosition position : result.getPath().collect()) {
            nodes.add(new Node(position));
        }
        if (nodes.isEmpty()) {
            return null;
        }

        List<Node> smoothed = FlightPathSmoother.smooth(nodes, (a, b) -> freePath(world, a, b));
        return smoothed.isEmpty() ? null : new Route(smoothed, goalY);
    }

    private static boolean freePath(BlockWorld world, PathPosition from, PathPosition to) {
        Vec3 start = FlightGuidance.waypoint(from.flooredX(), from.flooredY(), from.flooredZ());
        Vec3 end = FlightGuidance.waypoint(to.flooredX(), to.flooredY(), to.flooredZ());
        return FlightPathClearance.isClear(body(start).inflate(SMOOTHING_MARGIN, 0.0, SMOOTHING_MARGIN),
                end.subtract(start), world::collisions);
    }

    private static net.minecraft.world.phys.AABB body(Vec3 feet) {
        return new net.minecraft.world.phys.AABB(feet.x - 0.3, feet.y, feet.z - 0.3,
                feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
    }
}
