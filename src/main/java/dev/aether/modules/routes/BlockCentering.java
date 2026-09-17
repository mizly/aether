package dev.aether.modules.routes;

import dev.aether.macro.MacroInput;
import dev.aether.modules.pathfinding.execution.WalkingMotion;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// shuffles the player onto the middle of the block they stand on, crouched so an edge can never be walked off;
// a warp landing or a walk can stop right at the lip, and aiming from there is not the spot the route was recorded on
final class BlockCentering {
    // inside this the eye is within a hair of where it was recorded, and a crouched step overshoots by less
    static final double TOLERANCE = 0.12;
    private static final long TIMEOUT_MS = 2_500L;

    private final long startedAt;
    private BlockPos block;

    // target is the recorded block when the route knows it, otherwise the block the player is held up by
    BlockCentering(long now, BlockPos target) {
        this.startedAt = now;
        this.block = target;
    }

    // true once centred, or once it has tried long enough that waiting longer would only stall the route
    boolean tick(Minecraft mc, long now) {
        MacroInput.set(mc.options.keyShift, true);
        if (!mc.player.onGround()) {
            releaseSteps(mc);
            return false;
        }
        if (block == null) {
            block = supportingBlock(mc);
        }

        Vec3 offset = offsetToCentre(mc.player.position(), block);
        if (offset.horizontalDistance() <= TOLERANCE) {
            releaseSteps(mc);
            return true;
        }
        if (now - startedAt > TIMEOUT_MS) {
            ClientUtils.sendDebugMessage(String.format(Locale.ROOT,
                    "[Route] could not centre on the block, %.2f off", offset.horizontalDistance()));
            releaseSteps(mc);
            return true;
        }
        WalkingMotion.apply(mc, WalkingMotion.horizontalInput(offset, mc.player.getYRot(), 0.0));
        return false;
    }

    // standing on the lip puts the feet over the neighbour's air, so the block under the feet is not always the floor
    private static BlockPos supportingBlock(Minecraft mc) {
        Vec3 feet = mc.player.position();
        BlockPos under = BlockPos.containing(feet.x, feet.y - 0.2, feet.z);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos candidate = under.offset(dx, 0, dz);
                if (mc.level.getBlockState(candidate).getCollisionShape(mc.level, candidate).isEmpty()) {
                    continue;
                }
                // the player's footprint reaches 0.3 past its centre, so only blocks under that can hold it up
                double gapX = Math.max(0.0, Math.abs(candidate.getX() + 0.5 - feet.x) - 0.5);
                double gapZ = Math.max(0.0, Math.abs(candidate.getZ() + 0.5 - feet.z) - 0.5);
                if (gapX > 0.3 || gapZ > 0.3) {
                    continue;
                }
                double distance = offsetToCentre(feet, candidate).horizontalDistance();
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best != null ? best : under;
    }

    static Vec3 offsetToCentre(Vec3 feet, BlockPos block) {
        return new Vec3(block.getX() + 0.5 - feet.x, 0.0, block.getZ() + 0.5 - feet.z);
    }

    static void releaseSteps(Minecraft mc) {
        MacroInput.set(mc.options.keyUp, false);
        MacroInput.set(mc.options.keyDown, false);
        MacroInput.set(mc.options.keyLeft, false);
        MacroInput.set(mc.options.keyRight, false);
        MacroInput.set(mc.options.keySprint, false);
    }
}
