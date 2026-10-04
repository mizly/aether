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

// where a cast float comes down and which look puts it in the liquid, worked out without touching the camera
final class CastSim {

    static final int DEFAULT_TICKS = 60;

    // vanilla bobber flight: 0.3 ahead of the eye, 0.6/len + ~0.5 speed per axis, -0.03 gravity then 0.92 drag each tick
    private static final double CAST_START_OFFSET = 0.3;
    private static final double CAST_SPEED_BASE = 0.6;
    private static final double CAST_SPEED_BONUS = 0.5;
    private static final double CAST_GRAVITY = 0.03;
    private static final double CAST_DRAG = 0.92;
    private static final int CAST_SUBSTEPS = 4;
    private static final double CAST_HOOK_HALF_WIDTH = 0.125;
    private static final double CAST_HOOK_HEIGHT = 0.25;
    // pitches either side that still land in lava, so a little aim error or throw scatter does not hit the rim
    private static final int CAST_MARGIN_CAP = 6;

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
                // blocks only; the entity half of noCollision ran an entity lookup every substep for nothing a cast meets
                if (!level.noBlockCollision(null, hook)) {
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

    static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    static float pitchTo(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }
}
