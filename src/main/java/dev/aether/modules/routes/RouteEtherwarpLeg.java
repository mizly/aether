package dev.aether.modules.routes;

import dev.aether.macro.MacroInput;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.modules.rotation.HumanFlick;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

// one etherwarp straight onto the recorded block: no route search, just crouch, flick over and click
final class RouteEtherwarpLeg {
    enum Result { RUNNING, LANDED, FAILED }

    private enum Phase { AIM, TURNING, CLICK_DELAY, WAIT_LAND }

    private static final long CLICK_DELAY_MIN_MS = 45L;
    private static final long CLICK_DELAY_MAX_MS = 120L;
    // hypixel only etherwarps a crouched player, and a click sent with the sneak still in flight is a plain teleport
    private static final long CROUCH_SETTLE_MS = 150L;
    // right after a landing the position is still settling, so a missing line of sight gets a moment
    private static final long SIGHT_GRACE_MS = 800L;
    private static final long LAND_TIMEOUT_MS = 1_000L;
    private static final double LEFT_START_DISTANCE = 2.0;
    private static final int MAX_TURNS = 4;
    private static final int MAX_CLICKS = 3;
    // an aim point should keep hitting the block with the crosshair this far off it at the target's distance,
    // and never tighter than a mouse sensitivity step can round
    private static final double AIM_SLACK_BLOCKS = 0.2;
    private static final double MIN_SLACK_DEGREES = 0.1;
    private static final double MAX_SLACK_DEGREES = 0.3;
    private static final double[] FACE_GRID = {0.2, 0.35, 0.5, 0.65, 0.8};

    private final Route.Waypoint waypoint;
    private final PathPosition feet;
    private final BlockPos block;
    private Phase phase = Phase.AIM;
    private long phaseAt;
    private long clickAt;
    private long crouchedSince;
    private int turns;
    private int clicks;
    private Vec3 clickedFrom;
    private String failure = "";

    RouteEtherwarpLeg(Route.Waypoint waypoint) {
        this.waypoint = waypoint;
        this.feet = new PathPosition(waypoint.x(), waypoint.y(), waypoint.z());
        this.block = new BlockPos(waypoint.x(), waypoint.y() - 1, waypoint.z());
        this.phaseAt = System.currentTimeMillis();
    }

    String failure() {
        return failure;
    }

    Result tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        MacroInput.set(mc.options.keyShift, true);
        if (mc.player.isCrouching()) {
            if (crouchedSince == 0L) {
                crouchedSince = now;
            }
        } else {
            crouchedSince = 0L;
        }

        int slot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(mc);
        if (slot < 0) {
            return fail(mc, "no AOTV with Ether Transmission in the hotbar");
        }
        if (FailsafeManager.getCurrentSelectedSlot(mc) != slot) {
            FailsafeManager.selectHotbarSlot(mc, slot);
        }

        // the server traces from its own crouched eye, which is not always where the client camera sits
        Vec3 eye = EtherwarpHelper.getEyePosition(mc, mc.player.position());
        switch (phase) {
            case AIM -> {
                if (!mc.player.onGround()) {
                    return Result.RUNNING;
                }
                if (!EtherwarpHelper.isValidLandingFeet(new WalkabilityChecker(mc.level), feet)) {
                    return fail(mc, "no crouched headroom on the etherwarp block");
                }
                Vec3 target = findSafeAimPoint(mc, eye, block);
                if (target == null) {
                    return now - phaseAt > SIGHT_GRACE_MS
                            ? fail(mc, "no clear line of sight to the etherwarp block")
                            : Result.RUNNING;
                }
                if (++turns > MAX_TURNS) {
                    return fail(mc, "could not line up the etherwarp");
                }
                RotationUtils.Rotation look = RotationUtils.calculateLookAt(eye, target);
                HumanFlick.start(mc, look.yaw, look.pitch);
                enter(Phase.TURNING, now);
            }
            case TURNING -> {
                if (HumanFlick.isActive()) {
                    return Result.RUNNING;
                }
                if (!EtherwarpHelper.isLookingAtTarget(mc, eye, feet)) {
                    enter(Phase.AIM, now);
                    return Result.RUNNING;
                }
                clickAt = now + ThreadLocalRandom.current().nextLong(CLICK_DELAY_MIN_MS, CLICK_DELAY_MAX_MS + 1);
                enter(Phase.CLICK_DELAY, now);
            }
            case CLICK_DELAY -> {
                if (now < clickAt || crouchedSince == 0L || now - crouchedSince < CROUCH_SETTLE_MS
                        || FailsafeManager.getCurrentSelectedSlot(mc) != slot) {
                    return Result.RUNNING;
                }
                if (!EtherwarpHelper.isLookingAtTarget(mc, eye, feet)) {
                    enter(Phase.AIM, now);
                    return Result.RUNNING;
                }
                clicks++;
                clickedFrom = mc.player.position();
                ClientUtils.performUseClick();
                enter(Phase.WAIT_LAND, now);
            }
            case WAIT_LAND -> {
                if (RouteRunner.isStandingAt(mc, waypoint)) {
                    return Result.LANDED;
                }
                if (mc.player.position().distanceTo(clickedFrom) > LEFT_START_DISTANCE) {
                    return fail(mc, "etherwarp landed off the waypoint");
                }
                if (now - phaseAt > LAND_TIMEOUT_MS) {
                    if (clicks >= MAX_CLICKS) {
                        return fail(mc, "etherwarp never went off");
                    }
                    enter(Phase.AIM, now);
                }
            }
        }
        return Result.RUNNING;
    }

    private void enter(Phase next, long now) {
        phase = next;
        phaseAt = now;
    }

    private Result fail(Minecraft mc, String reason) {
        failure = reason;
        release(mc);
        return Result.FAILED;
    }

    static void release(Minecraft mc) {
        HumanFlick.cancel();
        RotationManager.cancelRotation();
        if (mc.options != null) {
            MacroInput.set(mc.options.keyShift, false);
        }
    }

    // the most central point on a visible face whose ray keeps hitting the block when the aim is slightly off,
    // so rounding to mouse steps or the server's own float maths never tips the warp onto a neighbour
    private static Vec3 findSafeAimPoint(Minecraft mc, Vec3 eye, BlockPos block) {
        Vec3 best = null;
        double bestScore = -1.0;
        Vec3 fallback = null;
        double fallbackScore = -1.0;
        for (Direction face : Direction.values()) {
            Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
            Vec3 faceCentre = Vec3.atCenterOf(block).add(normal.scale(0.5));
            if (faceCentre.subtract(eye).dot(normal) >= 0.0) {
                continue;
            }
            for (double u : FACE_GRID) {
                for (double v : FACE_GRID) {
                    Vec3 point = facePoint(block, face, u, v);
                    if (eye.distanceToSqr(point) > EtherwarpHelper.MAX_ETHERWARP_DISTANCE_SQ) {
                        continue;
                    }
                    double score = Math.min(Math.min(u, 1.0 - u), Math.min(v, 1.0 - v));
                    if (score > fallbackScore && hits(mc, eye, point, block, 0.0f)) {
                        fallbackScore = score;
                        fallback = point;
                        if (score > bestScore && hits(mc, eye, point, block, slackDegrees(eye, point))) {
                            bestScore = score;
                            best = point;
                        }
                    }
                }
            }
        }
        return best != null ? best : fallback;
    }

    static float slackDegrees(Vec3 eye, Vec3 point) {
        double degrees = Math.toDegrees(Math.atan(AIM_SLACK_BLOCKS / Math.max(1.0, eye.distanceTo(point))));
        return (float) Math.clamp(degrees, MIN_SLACK_DEGREES, MAX_SLACK_DEGREES);
    }

    private static Vec3 facePoint(BlockPos block, Direction face, double u, double v) {
        double x = block.getX();
        double y = block.getY();
        double z = block.getZ();
        return switch (face.getAxis()) {
            case X -> new Vec3(x + (face.getStepX() > 0 ? 1.0 : 0.0), y + u, z + v);
            case Y -> new Vec3(x + u, y + (face.getStepY() > 0 ? 1.0 : 0.0), z + v);
            case Z -> new Vec3(x + u, y + v, z + (face.getStepZ() > 0 ? 1.0 : 0.0));
        };
    }

    private static boolean hits(Minecraft mc, Vec3 eye, Vec3 point, BlockPos block, float slack) {
        RotationUtils.Rotation look = RotationUtils.calculateLookAt(eye, point);
        float[][] offsets = slack <= 0.0f
                ? new float[][] {{0f, 0f}}
                : new float[][] {{slack, 0f}, {-slack, 0f}, {0f, slack}, {0f, -slack}};
        for (float[] offset : offsets) {
            Vec3 direction = Vec3.directionFromRotation(look.pitch + offset[1], look.yaw + offset[0]);
            Vec3 end = eye.add(direction.scale(EtherwarpHelper.MAX_ETHERWARP_DISTANCE + 1.0));
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(block)) {
                return false;
            }
        }
        return true;
    }
}
