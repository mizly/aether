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

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

// one etherwarp straight onto a block: no route search, just crouch, flick over and click
// used by routes and by the fishing macro walking home, so neither ever spams warps
public final class EtherwarpLeg {
    public enum Result { RUNNING, LANDED, FAILED }

    private enum Phase { CENTER, AIM, TURNING, CLICK_DELAY, WAIT_LAND }

    private static final long CLICK_DELAY_MIN_MS = 45L;
    private static final long CLICK_DELAY_MAX_MS = 120L;
    // hypixel only etherwarps a crouched player, and a click sent with the sneak still in flight is a plain teleport
    private static final long CROUCH_SETTLE_MS = 150L;
    // right after a landing the position is still settling, so a missing line of sight gets a moment
    private static final long SIGHT_GRACE_MS = 800L;
    private static final long LAND_TIMEOUT_MS = 1_000L;
    private static final double LEFT_START_DISTANCE = 2.0;
    private static final int MAX_TURNS = 4;
    // an aim is only as good as the spot it was worked out from, so the player has to be at rest first
    private static final long STILL_MS = 200L;
    private static final double STILL_SPEED = 0.003;
    private static final double MOVED_SINCE_AIM = 0.05;
    private static final int MAX_CLICKS = 3;
    // an aim point should keep hitting the block with the crosshair this far off it at the target's distance,
    // and never tighter than a mouse sensitivity step can round
    private static final double AIM_SLACK_BLOCKS = 0.2;
    private static final double MIN_SLACK_DEGREES = 0.1;
    private static final double MAX_SLACK_DEGREES = 0.3;
    private static final double[] FACE_GRID = {0.2, 0.35, 0.5, 0.65, 0.8};

    // how sure an aim is: both eye heights with slack beats one of them without it
    private record Aim(Vec3 point, float yaw, float pitch, boolean bothEyes, boolean slack) {
        int tier() {
            return (bothEyes ? 2 : 0) + (slack ? 1 : 0);
        }
    }

    private final Route.Waypoint waypoint;
    private final PathPosition feet;
    private final BlockPos block;
    private Phase phase = Phase.CENTER;
    private BlockCentering centering;
    private final BlockPos standingOn;
    private long phaseAt;
    private long clickAt;
    private long crouchedSince;
    private long stillSince;
    private Vec3 aimedFrom;
    private int turns;
    private int clicks;
    private Vec3 clickedFrom;
    private Aim aim;
    private String failure = "";

    // standingOn is the recorded block the warp is thrown from, or null when there is none
    public EtherwarpLeg(Route.Waypoint waypoint, BlockPos standingOn) {
        this.waypoint = waypoint;
        this.standingOn = standingOn;
        this.feet = new PathPosition(waypoint.x(), waypoint.y(), waypoint.z());
        this.block = new BlockPos(waypoint.x(), waypoint.y() - 1, waypoint.z());
    }

    public String failure() {
        return failure;
    }

    public Result tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (phaseAt == 0L) {
            MacroInput.releaseMovement(mc);
            phaseAt = now;
        }
        MacroInput.set(mc.options.keyShift, true);
        if (isStill(mc)) {
            if (stillSince == 0L) {
                stillSince = now;
            }
        } else {
            stillSince = 0L;
        }
        // the server only needs the sneak held, and a player in lava never takes the crouching pose
        if (mc.player.isShiftKeyDown()) {
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

        Vec3 feetPos = mc.player.position();
        switch (phase) {
            case CENTER -> {
                // lava is left by warping out of it, there is no floor to centre on or to stand still on
                if (mc.player.isInLiquid()) {
                    enter(Phase.AIM, now);
                    return Result.RUNNING;
                }
                if (centering == null) {
                    centering = new BlockCentering(now, standingOn);
                }
                if (centering.tick(mc, now)) {
                    stillSince = 0L;
                    enter(Phase.AIM, now);
                }
            }
            case AIM -> {
                if (!mc.player.isInLiquid() && (stillSince == 0L || now - stillSince < STILL_MS)) {
                    return Result.RUNNING;
                }
                double distance = EtherwarpHelper.getEyePosition(mc, feetPos).distanceTo(Vec3.atCenterOf(block));
                if (distance > EtherwarpHelper.MAX_ETHERWARP_DISTANCE + 1.0) {
                    return fail(mc, String.format(Locale.ROOT,
                            "the block is %.0f blocks away, out of etherwarp range", distance));
                }
                // our headroom model is stricter than hypixel on odd blocks, so it only warns and the server decides
                if (turns == 0 && !EtherwarpHelper.isValidLandingFeet(new WalkabilityChecker(mc.level), feet)) {
                    ClientUtils.sendDebugMessage("[Route] headroom looks tight on " + block.getX() + " "
                            + block.getY() + " " + block.getZ() + ", warping anyway");
                }
                aim = findAim(mc, feetPos, block);
                if (aim == null) {
                    return now - Math.max(phaseAt, stillSince) > SIGHT_GRACE_MS
                            ? fail(mc, "no clear line of sight to the etherwarp block")
                            : Result.RUNNING;
                }
                if (++turns > MAX_TURNS) {
                    return fail(mc, "could not line up the etherwarp");
                }
                ClientUtils.sendDebugMessage(String.format(Locale.ROOT,
                        "[Route] aiming at %d %d %d (%.2f %.2f %.2f) tier %d",
                        block.getX(), block.getY(), block.getZ(),
                        aim.point().x, aim.point().y, aim.point().z, aim.tier()));
                aimedFrom = feetPos;
                HumanFlick.start(mc, aim.yaw(), aim.pitch());
                enter(Phase.TURNING, now);
            }
            case TURNING -> {
                if (HumanFlick.isActive()) {
                    return Result.RUNNING;
                }
                if (hasMovedSinceAim(feetPos)) {
                    recentre(now);
                    return Result.RUNNING;
                }
                if (!isLookingAtBlock(mc, feetPos)) {
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
                if (hasMovedSinceAim(feetPos)) {
                    recentre(now);
                    return Result.RUNNING;
                }
                if (!isLookingAtBlock(mc, feetPos)) {
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
                    BlockPos landed = mc.player.blockPosition().below();
                    return fail(mc, "etherwarp landed on " + landed.getX() + " " + landed.getY() + " "
                            + landed.getZ() + " instead of " + block.getX() + " " + block.getY() + " "
                            + block.getZ());
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

    // a shuffle between aiming and clicking spoils the aim, so the player goes back to the middle and aims again
    private void recentre(long now) {
        turns--;
        centering = null;
        enter(Phase.CENTER, now);
    }

    private static boolean isStill(Minecraft mc) {
        Vec3 motion = mc.player.getDeltaMovement();
        return mc.player.onGround() && motion.horizontalDistance() < STILL_SPEED;
    }

    private boolean hasMovedSinceAim(Vec3 feetPos) {
        return aimedFrom != null && feetPos.distanceTo(aimedFrom) > MOVED_SINCE_AIM
                && !Minecraft.getInstance().player.isInLiquid();
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

    public static void release(Minecraft mc) {
        HumanFlick.cancel();
        RotationManager.cancelRotation();
        if (mc.options != null) {
            MacroInput.set(mc.options.keyShift, false);
        }
    }

    // the server ray decides where the warp lands, and whether it starts from the modern or the legacy
    // crouched eye is not something the client can be sure of, so the best aim hits the marked block from both
    // and keeps hitting it with the crosshair slightly off; the most central such point on a visible face wins
    private static Aim findAim(Minecraft mc, Vec3 feetPos, BlockPos block) {
        Vec3 modelEye = EtherwarpHelper.getEyePosition(mc, feetPos);
        Aim best = null;
        double bestScore = -1.0;
        for (Direction face : Direction.values()) {
            Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
            Vec3 faceCentre = Vec3.atCenterOf(block).add(normal.scale(0.5));
            if (faceCentre.subtract(modelEye).dot(normal) >= 0.0) {
                continue;
            }
            for (double u : FACE_GRID) {
                for (double v : FACE_GRID) {
                    Vec3 point = facePoint(block, face, u, v);
                    if (modelEye.distanceToSqr(point) > EtherwarpHelper.MAX_ETHERWARP_DISTANCE_SQ) {
                        continue;
                    }
                    RotationUtils.Rotation look = RotationUtils.calculateLookAt(modelEye, point);
                    if (!hits(mc, modelEye, look.yaw, look.pitch, block, 0.0f)) {
                        continue;
                    }
                    float slack = slackDegrees(modelEye, point);
                    boolean bothEyes = hitsFromEveryEye(mc, feetPos, look.yaw, look.pitch, block, 0.0f);
                    boolean withSlack = bothEyes
                            ? hitsFromEveryEye(mc, feetPos, look.yaw, look.pitch, block, slack)
                            : hits(mc, modelEye, look.yaw, look.pitch, block, slack);
                    Aim candidate = new Aim(point, look.yaw, look.pitch, bothEyes, withSlack);
                    double centrality = Math.min(Math.min(u, 1.0 - u), Math.min(v, 1.0 - v));
                    // hypixel sets the player on top of the block either way, but the top face is the one aim
                    // that cannot graze an edge into the block beside or above it
                    double score = candidate.tier() + centrality + (face == Direction.UP ? 0.1 : 0.0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    // after the flick the real camera angle is checked, from both eyes when the aim promised both
    private boolean isLookingAtBlock(Minecraft mc, Vec3 feetPos) {
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        if (aim != null && aim.bothEyes()) {
            return hitsFromEveryEye(mc, feetPos, yaw, pitch, block, 0.0f);
        }
        return hits(mc, EtherwarpHelper.getEyePosition(mc, feetPos), yaw, pitch, block, 0.0f);
    }

    private static boolean hitsFromEveryEye(Minecraft mc, Vec3 feetPos, float yaw, float pitch, BlockPos block,
                                            float slack) {
        return hits(mc, feetPos.add(0.0, EtherwarpHelper.MODERN_SNEAKING_EYE_HEIGHT, 0.0), yaw, pitch, block, slack)
                && hits(mc, feetPos.add(0.0, EtherwarpHelper.LEGACY_SNEAKING_EYE_HEIGHT, 0.0), yaw, pitch, block,
                slack);
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

    private static boolean hits(Minecraft mc, Vec3 eye, float yaw, float pitch, BlockPos block, float slack) {
        float[][] offsets = slack <= 0.0f
                ? new float[][] {{0f, 0f}}
                : new float[][] {{slack, 0f}, {-slack, 0f}, {0f, slack}, {0f, -slack}};
        for (float[] offset : offsets) {
            Vec3 direction = Vec3.directionFromRotation(pitch + offset[1], yaw + offset[0]);
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
