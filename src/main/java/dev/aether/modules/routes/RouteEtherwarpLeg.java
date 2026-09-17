package dev.aether.modules.routes;

import dev.aether.macro.MacroInput;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

// one etherwarp straight onto the recorded block: no route search, just crouch, ease the camera over and click
final class RouteEtherwarpLeg {
    enum Result { RUNNING, LANDED, FAILED }

    private enum Phase { AIM, TURNING, CLICK_DELAY, WAIT_LAND }

    private static final long TURN_MIN_MS = 240L;
    private static final long TURN_MAX_MS = 460L;
    // a beat between the crosshair settling and the click, the way a player confirms the spot first
    private static final long CLICK_DELAY_MIN_MS = 80L;
    private static final long CLICK_DELAY_MAX_MS = 200L;
    // the crouch lowers the eye over a few ticks, so a missing line of sight gets a moment before it counts
    private static final long SIGHT_GRACE_MS = 600L;
    private static final long LAND_TIMEOUT_MS = 1_000L;
    private static final double LEFT_START_DISTANCE = 2.0;
    private static final int MAX_TURNS = 4;
    private static final int MAX_CLICKS = 3;

    private final Route.Waypoint waypoint;
    private final PathPosition feet;
    private Phase phase = Phase.AIM;
    private long phaseAt;
    private long clickAt;
    private int turns;
    private int clicks;
    private Vec3 clickedFrom;
    private String failure = "";

    RouteEtherwarpLeg(Route.Waypoint waypoint) {
        this.waypoint = waypoint;
        this.feet = new PathPosition(waypoint.x(), waypoint.y(), waypoint.z());
        this.phaseAt = System.currentTimeMillis();
    }

    String failure() {
        return failure;
    }

    Result tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        MacroInput.set(mc.options.keyShift, true);

        int slot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(mc);
        if (slot < 0) {
            return fail(mc, "no AOTV with Ether Transmission in the hotbar");
        }
        if (FailsafeManager.getCurrentSelectedSlot(mc) != slot) {
            FailsafeManager.selectHotbarSlot(mc, slot);
        }

        Vec3 eye = EtherwarpHelper.getEyePosition(mc, mc.player.position());
        switch (phase) {
            case AIM -> {
                Vec3 target = EtherwarpHelper.findVisibleTargetPoint(mc, new WalkabilityChecker(mc.level), eye, feet);
                if (target == null) {
                    return now - phaseAt > SIGHT_GRACE_MS
                            ? fail(mc, "no line of sight to the etherwarp block")
                            : Result.RUNNING;
                }
                if (++turns > MAX_TURNS) {
                    return fail(mc, "could not line up the etherwarp");
                }
                RotationUtils.Rotation look = RotationUtils.calculateLookAt(eye, target);
                RotationManager.cancelRotation();
                RotationManager.rotateToYawPitch(mc, look.yaw, look.pitch,
                        ThreadLocalRandom.current().nextLong(TURN_MIN_MS, TURN_MAX_MS + 1));
                enter(Phase.TURNING, now);
            }
            case TURNING -> {
                if (RotationManager.isRotating()) {
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
                if (now < clickAt || FailsafeManager.getCurrentSelectedSlot(mc) != slot) {
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
                    release(mc);
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
        RotationManager.cancelRotation();
        if (mc.options != null) {
            MacroInput.set(mc.options.keyShift, false);
        }
    }
}
