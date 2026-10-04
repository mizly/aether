package dev.aether.macro.fishing;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

// where a cast float comes down and which look puts it in the liquid, worked out without touching the camera
final class CastSim {

    static final int DEFAULT_TICKS = 60;

    private static final double SCAN_DEPTH = 4.0;
    // the nearest lava is usually straight down at our feet, which is no way to cast
    private static final double MIN_CAST_HORIZONTAL = 1.0;
    // vanilla bobber flight: 0.3 ahead of the eye, 0.6/len + ~0.5 speed per axis, -0.03 gravity then 0.92 drag each tick
    private static final double CAST_START_OFFSET = 0.3;
    private static final double CAST_SPEED_BASE = 0.6;
    private static final double CAST_SPEED_BONUS = 0.5;
    private static final double CAST_GRAVITY = 0.03;
    private static final double CAST_DRAG = 0.92;
    private static final int CAST_SUBSTEPS = 4;
    private static final double CAST_HOOK_HALF_WIDTH = 0.125;
    private static final double CAST_HOOK_HEIGHT = 0.25;
    // an upward lob is allowed, since a rim above the lava can leave no downward throw that clears it
    private static final float CAST_PITCH_MIN = -30.0f;
    private static final float CAST_PITCH_MAX = 89.0f;
    private static final float CAST_PITCH_STEP = 0.5f;
    // pitches either side that still land in lava, so a little aim error or throw scatter does not hit the rim
    private static final int CAST_MARGIN_CAP = 6;
    // aim somewhere inside the block rather than its exact centre, so casts do not stack on one pixel
    private static final double CAST_TARGET_JITTER = 0.2;

    private CastSim() {
    }

    record CastAim(BlockPos block, float yaw, float pitch) {
    }

    static boolean isLava(BlockState state) {
        if (state.getBlock() == Blocks.LAVA) {
            return true;
        }
        var fluid = state.getFluidState();
        return !fluid.isEmpty() && fluid.getType().isSame(Fluids.LAVA);
    }

    // walks the bobber's flight through the world; null when it clips a block or never reaches the liquid
    // at leg-height lava the rim sits above the surface and the float drops under the crosshair, so a look is not enough
    static Vec3 predictCastLanding(CollisionGetter level, Vec3 eye, float yaw, float pitch,
                                   Predicate<BlockState> liquid, int ticks) {
        Vec3[] path = castPath(eye, yaw, pitch, ticks);
        for (int i = 1; i < path.length; i++) {
            for (int step = 1; step <= CAST_SUBSTEPS; step++) {
                Vec3 at = path[i - 1].lerp(path[i], step / (double) CAST_SUBSTEPS);
                BlockPos pos = BlockPos.containing(at);
                BlockState state = level.getBlockState(pos);
                if (liquid.test(state) && at.y <= pos.getY() + state.getFluidState().getHeight(level, pos)) {
                    return at;
                }
                AABB hook = new AABB(at.x - CAST_HOOK_HALF_WIDTH, at.y, at.z - CAST_HOOK_HALF_WIDTH,
                        at.x + CAST_HOOK_HALF_WIDTH, at.y + CAST_HOOK_HEIGHT, at.z + CAST_HOOK_HALF_WIDTH);
                if (!level.noCollision(hook)) {
                    return null;
                }
            }
        }
        return null;
    }

    // a rejected cell is one the sim promised and a real float then missed, so no throw may count on it again
    static boolean acceptsLanding(BlockPos landing, Set<BlockPos> rejected) {
        return landing != null && !rejected.contains(landing);
    }

    static Vec3[] castPath(Vec3 eye, float yaw, float pitch, int ticks) {
        double yawRad = Math.toRadians(yaw);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        double rise = Mth.clamp(-Math.tan(Math.toRadians(pitch)), -5.0, 5.0);
        double speed = CAST_SPEED_BASE / Math.sqrt(1.0 + rise * rise) + CAST_SPEED_BONUS;

        double vx = dirX * speed;
        double vy = rise * speed;
        double vz = dirZ * speed;
        Vec3[] path = new Vec3[ticks + 1];
        path[0] = new Vec3(eye.x + dirX * CAST_START_OFFSET, eye.y, eye.z + dirZ * CAST_START_OFFSET);
        for (int tick = 1; tick <= ticks; tick++) {
            vy -= CAST_GRAVITY;
            Vec3 last = path[tick - 1];
            path[tick] = new Vec3(last.x + vx, last.y + vy, last.z + vz);
            vx *= CAST_DRAG;
            vy *= CAST_DRAG;
            vz *= CAST_DRAG;
        }
        return path;
    }

    // how many pitch steps either side of the pick still land, capped so a wide pool does not beat a close one
    static int castMargin(boolean[] lands, int index) {
        int left = 0;
        while (left < CAST_MARGIN_CAP && index - left - 1 >= 0 && lands[index - left - 1]) {
            left++;
        }
        int right = 0;
        while (right < CAST_MARGIN_CAP && index + right + 1 < lands.length && lands[index + right + 1]) {
            right++;
        }
        return Math.min(left, right);
    }

    // the throw with the most room for error wins, then the one that comes down nearest the block centre
    static CastAim findCastAim(CollisionGetter level, BlockPos base, Vec3 eye, int radius, Set<BlockPos> rejected,
                               Predicate<BlockState> liquid, RandomGenerator random) {
        int steps = Math.round((CAST_PITCH_MAX - CAST_PITCH_MIN) / CAST_PITCH_STEP) + 1;
        boolean[] lands = new boolean[steps];
        Vec3[] landings = new Vec3[steps];
        CastAim best = null;
        int bestMargin = 0;
        double bestError = Double.MAX_VALUE;

        int depth = (int) SCAN_DEPTH;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -depth; dy <= 1; dy++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (rejected.contains(pos) || !liquid.test(level.getBlockState(pos))) {
                        continue;
                    }
                    BlockPos above = pos.above();
                    if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
                        continue;
                    }
                    double tx = pos.getX() + 0.5 + random.nextDouble(-CAST_TARGET_JITTER, CAST_TARGET_JITTER);
                    double tz = pos.getZ() + 0.5 + random.nextDouble(-CAST_TARGET_JITTER, CAST_TARGET_JITTER);
                    if (Math.hypot(tx - eye.x, tz - eye.z) < MIN_CAST_HORIZONTAL) {
                        continue;
                    }
                    float yaw = yawTo(tx - eye.x, tz - eye.z);
                    for (int i = 0; i < steps; i++) {
                        landings[i] = predictCastLanding(level, eye, yaw, CAST_PITCH_MIN + i * CAST_PITCH_STEP,
                                liquid, DEFAULT_TICKS);
                        lands[i] = landings[i] != null && acceptsLanding(BlockPos.containing(landings[i]), rejected);
                    }
                    for (int i = 0; i < steps; i++) {
                        if (!lands[i]) {
                            continue;
                        }
                        int margin = castMargin(lands, i);
                        double error = Math.hypot(landings[i].x - tx, landings[i].z - tz);
                        if (margin < 1 || margin < bestMargin || (margin == bestMargin && error >= bestError)) {
                            continue;
                        }
                        best = new CastAim(pos, yaw, CAST_PITCH_MIN + i * CAST_PITCH_STEP);
                        bestMargin = margin;
                        bestError = error;
                    }
                }
            }
        }
        return best;
    }

    static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    static float pitchTo(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }
}
