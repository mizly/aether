package dev.aether.macro.fishing;

import net.minecraft.core.BlockPos;
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

    record Step(Status status, CastSim.CastAim aim) {
    }

    private static final int SCAN_DEPTH = 4;
    // the nearest lava is usually straight down at our feet, which is no way to cast
    private static final double MIN_CAST_HORIZONTAL = 1.0;
    // an upward lob is allowed, since a rim above the lava can leave no downward throw that clears it
    private static final float CAST_PITCH_MIN = -30.0f;
    private static final float CAST_PITCH_MAX = 89.0f;
    private static final float CAST_PITCH_STEP = 0.5f;
    // aim somewhere inside the block rather than its exact centre, so casts do not stack on one pixel
    private static final double CAST_TARGET_JITTER = 0.2;

    private final CollisionGetter level;
    private final Vec3 eye;
    private final Set<BlockPos> rejected;
    private final Predicate<BlockState> liquid;
    private final RandomGenerator random;
    private final List<BlockPos> cells;
    private int next;

    CastAimSearch(CollisionGetter level, BlockPos base, Vec3 eye, int radius, Set<BlockPos> rejected,
                  Predicate<BlockState> liquid, RandomGenerator random) {
        this.level = level;
        this.eye = eye;
        this.rejected = rejected;
        this.liquid = liquid;
        this.random = random;
        this.cells = surfaceCells(level, base, radius, liquid);
        cells.sort(Comparator.comparingDouble(cell -> horizontalDistance(eye, cell)));
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

    // the throw with the most room for error wins, then the one that comes down nearest the block centre
    private CastSim.CastAim aimAt(BlockPos cell) {
        double tx = cell.getX() + 0.5 + random.nextDouble(-CAST_TARGET_JITTER, CAST_TARGET_JITTER);
        double tz = cell.getZ() + 0.5 + random.nextDouble(-CAST_TARGET_JITTER, CAST_TARGET_JITTER);
        if (Math.hypot(tx - eye.x, tz - eye.z) < MIN_CAST_HORIZONTAL) {
            return null;
        }
        float yaw = CastSim.yawTo(tx - eye.x, tz - eye.z);
        int steps = Math.round((CAST_PITCH_MAX - CAST_PITCH_MIN) / CAST_PITCH_STEP) + 1;
        boolean[] lands = new boolean[steps];
        Vec3[] landings = new Vec3[steps];
        for (int i = 0; i < steps; i++) {
            landings[i] = CastSim.predictCastLanding(level, eye, yaw, CAST_PITCH_MIN + i * CAST_PITCH_STEP,
                    liquid, CastSim.DEFAULT_TICKS);
            lands[i] = landings[i] != null && CastSim.acceptsLanding(BlockPos.containing(landings[i]), rejected);
        }

        CastSim.CastAim best = null;
        int bestMargin = 0;
        double bestError = Double.MAX_VALUE;
        for (int i = 0; i < steps; i++) {
            if (!lands[i]) {
                continue;
            }
            int margin = CastSim.castMargin(lands, i);
            double error = Math.hypot(landings[i].x - tx, landings[i].z - tz);
            if (margin < 1 || margin < bestMargin || (margin == bestMargin && error >= bestError)) {
                continue;
            }
            best = new CastSim.CastAim(cell, yaw, CAST_PITCH_MIN + i * CAST_PITCH_STEP);
            bestMargin = margin;
            bestError = error;
        }
        return best;
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

    private static double horizontalDistance(Vec3 eye, BlockPos cell) {
        return Math.hypot(cell.getX() + 0.5 - eye.x, cell.getZ() + 0.5 - eye.z);
    }
}
