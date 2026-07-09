package dev.aether.modules.pathfinding.execution;

import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class EtherwarpExecutor {

    public enum FailureReason {
        PLAYER_CONTEXT_MISSING,
        LOST_LINE_OF_SIGHT,
        MISSING_ETHERWARP_ITEM,
        WARP_TIMEOUT
    }

    public enum State {
        IDLE,
        ROTATING,
        WAITING_FOR_WARP,
        FINISHED,
        FAILED
    }

    private static final long ROTATION_RETRY_MS = 1200L;
    private static final long WARP_SETTLE_TIMEOUT_MS = 900L;
    private static final int MAX_WARP_ATTEMPTS = 3;
    private static final double WAYPOINT_REACHED_DIST = 1.35;

    private State state = State.IDLE;
    private List<Node> path = List.of();
    private int waypointIndex = 1;
    private long stateSince = 0L;
    private int warpAttempts = 0;
    private Vec3 warpStartPos = Vec3.ZERO;
    private Runnable onFinished;
    private Function<FailureReason, Boolean> onFailed;

    public void start(List<Node> path, Runnable onFinished, Function<FailureReason, Boolean> onFailed) {
        this.path = path == null ? List.of() : new ArrayList<>(path);
        this.waypointIndex = this.path.size() > 1 ? 1 : this.path.size();
        this.state = this.path.size() <= 1 ? State.FINISHED : State.IDLE;
        this.stateSince = System.currentTimeMillis();
        this.warpAttempts = 0;
        this.warpStartPos = Vec3.ZERO;
        this.onFinished = onFinished;
        this.onFailed = onFailed;
    }

    public State getState() {
        return state;
    }

    public int getWaypointIndex() {
        return waypointIndex;
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            fail(mc, FailureReason.PLAYER_CONTEXT_MISSING, "Etherwarp stopped: player context missing.");
            return;
        }
        if (mc.options != null) {
            ClientUtils.setKeyMappingState(mc.options.keyShift, true);
        }
        mc.player.setShiftKeyDown(true);
        if (state == State.IDLE || state == State.ROTATING || state == State.WAITING_FOR_WARP) {
            advanceReachedWaypoints(mc);
        }
        if (state == State.FINISHED || waypointIndex >= path.size()) {
            finish(mc);
            return;
        }
        if (state == State.FAILED || ClientUtils.isInventoryScreenOpen(mc)) {
            return;
        }

        Node next = path.get(waypointIndex);
        WalkabilityChecker checker = new WalkabilityChecker(mc.level);
        Vec3 eyePos = EtherwarpHelper.getEyePosition(mc, mc.player.position());
        Vec3 targetPoint = EtherwarpHelper.findVisibleTargetPoint(mc, checker, eyePos, next.position);
        if (targetPoint == null) {
            fail(mc, FailureReason.LOST_LINE_OF_SIGHT,
                    "Etherwarp lost line of sight to waypoint " + waypointIndex + ".");
            return;
        }

        int etherwarpSlot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(mc);
        if (etherwarpSlot < 0) {
            fail(mc, FailureReason.MISSING_ETHERWARP_ITEM, "No hotbar AOTV with Ether Transmission found.");
            return;
        }

        long now = System.currentTimeMillis();
        if (state == State.WAITING_FOR_WARP) {
            if (now - stateSince > WARP_SETTLE_TIMEOUT_MS) {
                if (warpAttempts >= MAX_WARP_ATTEMPTS) {
                    fail(mc, FailureReason.WARP_TIMEOUT,
                            "Etherwarp timed out at waypoint " + waypointIndex + ".");
                    return;
                }
                state = State.IDLE;
                return;
            }
            return;
        }

        if (!RotationUtils.isLookingAt(
                mc.player.getYRot(),
                mc.player.getXRot(),
                eyePos,
                targetPoint,
                2.0f)) {
            if (!RotationManager.isRotating() || now - stateSince > ROTATION_RETRY_MS) {
                RotationManager.cancelRotation();
                RotationUtils.Rotation lookRotation = RotationUtils.calculateLookAt(eyePos, targetPoint);
                RotationManager.rotateToYawPitch(mc, lookRotation.yaw, lookRotation.pitch, 80L);
                state = State.ROTATING;
                stateSince = now;
            }
            return;
        }

        if (FailsafeManager.getCurrentSelectedSlot(mc) != etherwarpSlot) {
            FailsafeManager.selectHotbarSlot(mc, etherwarpSlot);
            return;
        }

        ClientUtils.performUseClick(mc);
        warpAttempts++;
        warpStartPos = mc.player.position();
        state = State.WAITING_FOR_WARP;
        stateSince = now;
    }

    public void stop(Minecraft mc) {
        RotationManager.cancelRotation();
        state = State.IDLE;
        path = List.of();
        waypointIndex = 0;
        warpAttempts = 0;
        onFinished = null;
        onFailed = null;
        if (mc != null && mc.options != null) {
            ClientUtils.setKeyMappingState(mc.options.keyUse, false);
            ClientUtils.setKeyMappingState(mc.options.keyShift, false);
        }
        if (mc != null && mc.player != null) {
            mc.player.setShiftKeyDown(false);
        }
    }

    private void advanceReachedWaypoints(Minecraft mc) {
        while (waypointIndex < path.size()) {
            Vec3 waypointPos = EtherwarpHelper.getCenteredFeet(path.get(waypointIndex).position);
            if (mc.player.position().distanceTo(waypointPos) > WAYPOINT_REACHED_DIST) {
                return;
            }
            waypointIndex++;
            warpAttempts = 0;
            state = State.IDLE;
            stateSince = System.currentTimeMillis();
        }
    }

    private void finish(Minecraft mc) {
        if (state == State.FINISHED) {
            return;
        }
        RotationManager.cancelRotation();
        if (mc != null && mc.options != null) {
            ClientUtils.setKeyMappingState(mc.options.keyUse, false);
            ClientUtils.setKeyMappingState(mc.options.keyShift, false);
        }
        if (mc != null && mc.player != null) {
            mc.player.setShiftKeyDown(false);
        }
        state = State.FINISHED;
        if (onFinished != null) {
            onFinished.run();
        }
    }

    private void fail(Minecraft mc, FailureReason reason, String message) {
        RotationManager.cancelRotation();
        state = State.FAILED;
        boolean handled = onFailed != null && Boolean.TRUE.equals(onFailed.apply(reason));
        if (mc != null) {
            if (!handled) {
                ClientUtils.sendMessage(mc, "\u00A7c" + message, false);
            }
            if (mc.options != null) {
                ClientUtils.setKeyMappingState(mc.options.keyUse, false);
                ClientUtils.setKeyMappingState(mc.options.keyShift, false);
            }
            if (mc.player != null) {
                mc.player.setShiftKeyDown(false);
            }
        }
    }
}
