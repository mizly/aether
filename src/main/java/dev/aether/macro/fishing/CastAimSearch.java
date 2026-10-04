package dev.aether.macro.fishing;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

// one search per origin: the cells are sorted once and tried a few per tick, and after a miss it carries on
// past the cells it already tried, since starting over would only find the same nearest ones again
final class CastAimSearch {

    enum Status { FOUND, WORKING, EXHAUSTED }

    // NEAREST tries the closest liquid first; MID_RANGE starts about 3.5 blocks out and in front of the camera
    enum Order { NEAREST, MID_RANGE }

    record Step(Status status, CastSim.CastAim aim) {
    }

    // margins count pitch steps either side of the pick that land too, so aim error or scatter cannot hit the rim;
    // with a target only it is aimed at, and only a landing within targetTolerance of it counts
    record Spec(int radius, boolean widens, float pitchMin, float pitchMax, float pitchStep,
                double maxLandingHorizontal, Order order, Vec3 target, double targetTolerance,
                int cellsPerTick, int requiredMargin, int marginCap) {

        // lobs upward too, since a rim above the lava can leave no downward throw that clears it
        static final Spec STRIDER_CLASSIC = new Spec(6, true, -30.0f, 89.0f, 0.5f, Double.POSITIVE_INFINITY,
                Order.NEAREST, null, 1.0, 2, 1, 6);
        // past five blocks the old and new hook physics disagree by blocks, so no throw relies on landing out there
        static final Spec GENERAL = new Spec(6, false, 20.0f, 70.0f, 1.0f, 5.0,
                Order.MID_RANGE, null, 1.0, 2, 2, 2);

        Spec withTarget(Vec3 target) {
            return new Spec(radius, widens, pitchMin, pitchMax, pitchStep, maxLandingHorizontal, order, target,
                    targetTolerance, cellsPerTick, requiredMargin, marginCap);
        }

        Spec widened(int sweeps) {
            if (!widens) {
                return this;
            }
            int wider = (int) Math.min(radius + sweeps * WIDEN_STEP, MAX_RADIUS);
            return new Spec(wider, widens, pitchMin, pitchMax, pitchStep, maxLandingHorizontal, order, target,
                    targetTolerance, cellsPerTick, requiredMargin, marginCap);
        }
    }

    private static final double WIDEN_STEP = 2.0;
    private static final double MAX_RADIUS = 14.0;
    private static final int SCAN_DEPTH = 4;
    // the nearest lava is usually straight down at our feet, which is no way to cast
    private static final double MIN_CAST_HORIZONTAL = 1.0;
    // aim somewhere inside the block rather than its exact centre, so casts do not stack on one pixel
    private static final double CAST_TARGET_JITTER = 0.2;
    // well inside the landing cap, without casting at our own feet
    private static final double MID_RANGE_DISTANCE = 3.5;

    private final CollisionGetter level;
    private final Vec3 eye;
    private final Spec spec;
    private final Set<BlockPos> rejected;
    private final Predicate<BlockState> liquid;
    private final RandomGenerator random;
    private final List<BlockPos> cells;
    private int next;

    CastAimSearch(CollisionGetter level, BlockPos base, Vec3 eye, float yaw, Spec spec, Set<BlockPos> rejected,
                  Predicate<BlockState> liquid, RandomGenerator random) {
        this.level = level;
        this.eye = eye;
        this.spec = spec;
        this.rejected = rejected;
        this.liquid = liquid;
        this.random = random;
        this.cells = spec.target() != null
                ? List.of(BlockPos.containing(spec.target()))
                : ordered(surfaceCells(level, base, spec.radius(), liquid), base, eye, yaw, spec.order());
    }

    Step step() {
        return step(spec.cellsPerTick());
    }

    // runs the next few cells; WORKING only means this tick's budget ran out, EXHAUSTED that every cell was tried
    Step step(int budget) {
        int tried = 0;
        while (next < cells.size() && tried < budget) {
            BlockPos cell = cells.get(next++);
            if (rejected.contains(cell)) {
                continue;
            }
            tried++;
            CastSim.CastAim aim = aimAt(cell);
            if (aim != null) {
                return new Step(Status.FOUND, aim);
            }
        }
        return new Step(next < cells.size() ? Status.WORKING : Status.EXHAUSTED, null);
    }

    // the throw with the most room for error wins, then the one that comes down nearest the aim point
    private CastSim.CastAim aimAt(BlockPos cell) {
        Vec3 target = spec.target();
        double centreX = target != null ? target.x : cell.getX() + 0.5;
        double centreZ = target != null ? target.z : cell.getZ() + 0.5;
        double tx = centreX + random.nextDouble(-CAST_TARGET_JITTER, CAST_TARGET_JITTER);
        double tz = centreZ + random.nextDouble(-CAST_TARGET_JITTER, CAST_TARGET_JITTER);
        if (Math.hypot(tx - eye.x, tz - eye.z) < MIN_CAST_HORIZONTAL) {
            return null;
        }
        float yaw = CastSim.yawTo(tx - eye.x, tz - eye.z);
        float lowest = Math.min(spec.pitchMin(), spec.pitchMax());
        float highest = Math.max(spec.pitchMin(), spec.pitchMax());
        int steps = Math.round((highest - lowest) / spec.pitchStep()) + 1;
        boolean[] lands = new boolean[steps];
        Vec3[] landings = new Vec3[steps];
        for (int i = 0; i < steps; i++) {
            landings[i] = CastSim.predictCastLanding(level, eye, yaw, lowest + i * spec.pitchStep(),
                    liquid, CastSim.DEFAULT_TICKS, spec.maxLandingHorizontal());
            lands[i] = landings[i] != null
                    && CastSim.acceptsLanding(BlockPos.containing(landings[i]), rejected)
                    && nearTarget(landings[i]);
        }

        CastSim.CastAim best = null;
        int bestMargin = 0;
        double bestError = Double.MAX_VALUE;
        for (int i = 0; i < steps; i++) {
            if (!lands[i]) {
                continue;
            }
            int margin = CastSim.castMargin(lands, i, spec.marginCap());
            double error = Math.hypot(landings[i].x - tx, landings[i].z - tz);
            if (margin < spec.requiredMargin() || margin < bestMargin
                    || (margin == bestMargin && error >= bestError)) {
                continue;
            }
            best = new CastSim.CastAim(cell, yaw, lowest + i * spec.pitchStep());
            bestMargin = margin;
            bestError = error;
        }
        return best;
    }

    private boolean nearTarget(Vec3 landing) {
        Vec3 target = spec.target();
        return target == null
                || Math.hypot(landing.x - target.x, landing.z - target.z) <= spec.targetTolerance();
    }

    // only the top of the liquid: the cells under it share its column, so they only cost another full pitch sweep
    static List<BlockPos> surfaceCells(CollisionGetter level, BlockPos base, int radius, Predicate<BlockState> liquid) {
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -SCAN_DEPTH; dy <= 1; dy++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (!liquid.test(level.getBlockState(pos))) {
                        continue;
                    }
                    BlockPos above = pos.above();
                    BlockState over = level.getBlockState(above);
                    if (over.getFluidState().isEmpty() && over.getCollisionShape(level, above).isEmpty()) {
                        cells.add(pos);
                    }
                }
            }
        }
        return cells;
    }

    // mid range is measured on the block grid, so cells the same number of blocks out tie
    // wherever on its block the player stands, and the one nearest the camera's heading goes first
    static List<BlockPos> ordered(List<BlockPos> cells, BlockPos base, Vec3 eye, float yaw, Order order) {
        Comparator<BlockPos> comparator = switch (order) {
            case NEAREST -> Comparator.comparingDouble(cell -> horizontalDistance(eye, cell));
            case MID_RANGE -> Comparator.<BlockPos>comparingDouble(cell -> Math.abs(
                            Math.hypot(cell.getX() - base.getX(), cell.getZ() - base.getZ()) - MID_RANGE_DISTANCE))
                    .thenComparingDouble(cell -> Math.abs(Mth.wrapDegrees(CastSim.yawTo(
                            cell.getX() + 0.5 - eye.x, cell.getZ() + 0.5 - eye.z) - yaw)));
        };
        List<BlockPos> sorted = new ArrayList<>(cells);
        sorted.sort(comparator);
        return sorted;
    }

    private static double horizontalDistance(Vec3 eye, BlockPos cell) {
        return Math.hypot(cell.getX() + 0.5 - eye.x, cell.getZ() + 0.5 - eye.z);
    }
}
