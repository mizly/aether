package dev.aether.modules.farming;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.MacroState;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class BedrockPlotMaker {
    private static final int PLOT_SIZE = 96;
    private static final int PLOT_OFFSET = 48;
    private static final int TARGET_Y = 71;
    private static final int MIN_Y = 66;
    private static final int MAX_Y = 120;
    private static final long PATH_TIMEOUT_MS = 90_000L;
    private static final long REMOVE_TIMEOUT_MS = 25_000L;
    private static final long ROTATION_MS = 450L;
    private static final long COLUMN_CLEAR_TIMEOUT_MS = 8_000L;
    private static final long STEP_RIGHT_TIMEOUT_MS = 4_000L;
    private static final long SHOVEL_CORNER_TIMEOUT_MS = 90_000L;
    private static final long ARCH_BREAK_TIMEOUT_MS = 4_000L;
    private static final long ARCH_ROTATION_MS = 180L;
    private static final long HELD_USE_QUIET_MS = 1_200L;
    private static final int CORNER_FINISH_ROWS = 3;
    private static final int CORNER_FINISH_Y = 68;
    private static final int UPPER_BREAK_Y_OFFSET = 2;
    private static final int ARCH_SCAN_HALF_WIDTH = 8;
    private static final double ARCH_REACH = 5.0;
    private static final double ARCH_WALL_INSET = 0.3;
    private static final float EAST_YAW = -90.0f;
    private static final float DOWN_PITCH = 90.0f;
    private static final float NORTH_YAW = -180.0f;
    private static final float TRENCH_PITCH = -60.0f;
    private static final float RETURN_YAW = 180.0f;
    private static final float RETURN_PITCH = 40.0f;
    private static final float FINISH_SOUTH_YAW = 0.0f;
    private static final float FINISH_WEST_YAW = 90.0f;
    private static final float SHOVEL_CORNER_PITCH = -40.0f;
    private static final float PICKAXE_UP_PITCH = -90.0f;
    private static final float YAW_TOLERANCE = 4.0f;
    private static final float PITCH_TOLERANCE = 2.0f;
    /** Exact center of the drop block one column east of the inclusive west edge. */
    private static final double STAND_EAST_OFFSET = 1.5;
    /** Slightly left/north of center so the Builder drop stays aligned in the trench. */
    private static final double STAND_Z_OFFSET = -0.7;
    private static final double TARGET_TOLERANCE = 0.08;
    private static final double BEDROCK_RAY_DISTANCE = 8.0;
    private static final Pattern REMOVED_BLOCKS =
            Pattern.compile("(?i)you removed\\s+\\d+\\s+blocks!?");

    private static volatile boolean running = false;
    private static volatile boolean countingRemovals = false;
    private static volatile boolean rotationLockActive = false;
    private static volatile float lockedYaw = 0.0f;
    private static volatile float lockedPitch = 0.0f;
    private static final AtomicInteger removedMessages = new AtomicInteger(0);

    private BedrockPlotMaker() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean hasVisibleHighlights() {
        Minecraft mc = Minecraft.getInstance();
        return running
                && mc.level != null
                && mc.player != null
                && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN
                && targetBounds(mc) != null;
    }

    public static void renderWorld() {
        if (!hasVisibleHighlights()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PlotBounds bounds = targetBounds(mc);
        if (bounds == null) {
            return;
        }

        // AABB max edges are exclusive, so the raw maxX/maxZ are correct here.
        AABB box = new AABB(bounds.minX(), MIN_Y, bounds.minZ(), bounds.maxX(), MAX_Y, bounds.maxZ());
        GizmoStyle style = GizmoStyle.strokeAndFill(
                ARGB.color(220, 80, 210, 255),
                3.0f,
                ARGB.color(34, 80, 210, 255));
        var props = Gizmos.cuboid(box, style);
        props.setAlwaysOnTop();
    }

    public static void setConfiguredPlotToCurrent(Minecraft client) {
        String currentPlot = ClientUtils.getCurrentPlot();
        if (parsePlot(currentPlot).isPresent()) {
            AetherConfig.BEDROCK_PLOT_MAKER_PLOT.set(currentPlot);
            AetherConfig.save();
        }
    }

    public static void start(Minecraft client) {
        if (running) {
            return;
        }

        String validationError = validateStart(client);
        if (validationError != null) {
            ClientUtils.sendMessage("\u00A7c" + validationError, false);
            return;
        }

        running = true;
        clearRotationLock();
        removedMessages.set(0);
        prepareRun(client);
        MacroWorkerThread.getInstance().submit("BedrockPlotMaker", () -> runSequence(client));
    }

    private static String validateStart(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return "World not ready.";
        }

        List<String> errors = new ArrayList<>();
        List<String> missingItems = new ArrayList<>();
        if (!hasHotbarItem(client, Items.GOLDEN_PICKAXE)) {
            missingItems.add("Golden pickaxe");
        }
        if (!hasHotbarItem(client, Items.GOLDEN_SHOVEL)) {
            missingItems.add("Golden shovel");
        }
        if (GearManager.findHotbarItemSlot(client, "Builder's Ruler") < 0) {
            missingItems.add("Builder's ruler");
        }
        if (!missingItems.isEmpty()) {
            errors.add(String.join(", ", missingItems) + " not found.");
        }

        PlotBounds bounds = targetBounds(client);
        if (bounds == null) {
            errors.add("Plot not found.");
        } else {
            String plotError = validateSuperflatInterior(client, bounds);
            if (plotError != null) {
                errors.add(plotError + ".");
            }
        }
        return errors.isEmpty() ? null : String.join(" ", errors);
    }

    private static boolean hasHotbarItem(Minecraft client, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static String validateSuperflatInterior(Minecraft client, PlotBounds bounds) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int surfaceY = TARGET_Y - 1;
        for (int x = bounds.westBlockX() + 1; x < bounds.eastBlockX(); x++) {
            for (int z = bounds.northBlockZ() + 1; z < bounds.southBlockZ(); z++) {
                cursor.set(x, surfaceY, z);
                if (!client.level.hasChunkAt(cursor)) {
                    return "Plot is not fully loaded";
                }

                BlockState surface = client.level.getBlockState(cursor);
                if (surface.isAir() || !surface.isCollisionShapeFullBlock(client.level, cursor)) {
                    return "Plot is not superflat";
                }
                for (int y = TARGET_Y; y <= MAX_Y; y++) {
                    if (!client.level.getBlockState(cursor.set(x, y, z)).isAir()) {
                        return "Plot is not superflat";
                    }
                }
            }
        }
        return null;
    }

    public static void startTrenchStep(Minecraft client) {
        if (running) {
            return;
        }

        running = true;
        clearRotationLock();
        removedMessages.set(0);
        prepareRun(client);
        MacroWorkerThread.getInstance().submit("BedrockPlotMaker-Trench", () -> runTrenchSequence(client));
    }

    public static void startEndLaneStep(Minecraft client) {
        if (running) {
            return;
        }

        running = true;
        clearRotationLock();
        removedMessages.set(0);
        prepareRun(client);
        MacroWorkerThread.getInstance().submit("BedrockPlotMaker-EndLane", () -> runEndLaneSequence(client));
    }

    public static void startShovelCornerStep(Minecraft client) {
        if (running) {
            return;
        }

        running = true;
        clearRotationLock();
        prepareRun(client);
        MacroWorkerThread.getInstance().submit("BedrockPlotMaker-ShovelCorner", () -> runShovelCornerSequence(client));
    }

    public static void startPickaxePerimeterStep(Minecraft client) {
        if (running) {
            return;
        }

        running = true;
        clearRotationLock();
        prepareRun(client);
        MacroWorkerThread.getInstance().submit("BedrockPlotMaker-PickaxePerimeter", () -> runPickaxePerimeterSequence(client));
    }

    public static void startArchEndingStep(Minecraft client) {
        if (running) {
            return;
        }

        running = true;
        clearRotationLock();
        prepareRun(client);
        MacroWorkerThread.getInstance().submit("BedrockPlotMaker-FinalArches", () -> runArchEndingSequence(client));
    }

    public static void stop(Minecraft client) {
        boolean wasRunning = running;
        running = false;
        countingRemovals = false;
        clearRotationLock();
        removedMessages.set(0);
        clearMouseUngrab();
        if (wasRunning && client != null) {
            client.execute(() -> {
                releaseHeldKeys(client);
                RotationManager.cancelRotation();
                PathfindingManager.stop(false);
            });
        }
    }

    private static void prepareRun(Minecraft client) {
        if (client != null) {
            Runnable closeConfigScreen = () -> {
                if (AetherBootstrapHooks.isBootstrapConfigScreen(client.screen)) {
                    client.setScreen(null);
                }
            };
            if (client.isSameThread()) {
                closeConfigScreen.run();
            } else {
                client.execute(closeConfigScreen);
            }
        }
        if (AetherConfig.MACRO_UNGRAB_MOUSE.get()) {
            UngrabMouse.requestMacroUngrab();
        }
    }

    private static void clearMouseUngrab() {
        UngrabMouse.clearMacroUngrab();
    }

    public static void onChatMessage(String plainText) {
        if (running && countingRemovals && plainText != null && REMOVED_BLOCKS.matcher(plainText).find()) {
            removedMessages.incrementAndGet();
        }
    }

    public static void update(Minecraft client) {
        if (rotationLockActive && client != null && client.player != null) {
            applyRotationLock(client);
        }
    }

    private static void runSequence(Minecraft client) {
        try {
            if (shouldStop(client)) {
                return;
            }

            PlotBounds bounds = resolveBoundsForRun(client);
            if (bounds == null) {
                return;
            }

            if (!clearFinalArches(client, bounds)) {
                ClientUtils.sendDebugMessage( "Bedrock Plot Maker: initial arch pass failed.");
                return;
            }

            Vec3 dropCenter = bounds.standPoint();
            if (!walkToExactPoint(client, dropCenter)) {
                ClientUtils.sendDebugMessage( "Bedrock Plot Maker: failed to reach plot corner.");
                return;
            }

            if (shouldStop(client)) {
                return;
            }

            if (!GearManager.swapToNamedHotbarItemSync(client, "Builder")) {
                return;
            }

            if (!rotateEastStraightDown(client)) {
                ClientUtils.sendDebugMessage( "Bedrock Plot Maker: not facing east/down, refusing to right click.");
                return;
            }
            if (!holdRulerUntilBedrock(client, dropCenter)) {
                return;
            }
            clearAcrossPlot(client, bounds);
        } finally {
            running = false;
            countingRemovals = false;
            clearRotationLock();
            clearMouseUngrab();
            if (client != null) {
                client.execute(() -> releaseHeldKeys(client));
            }
        }
    }

    private static void runTrenchSequence(Minecraft client) {
        try {
            if (shouldStop(client)) {
                return;
            }
            PlotBounds bounds = resolveBoundsForRun(client);
            if (bounds == null) {
                return;
            }
            if (!GearManager.swapToNamedHotbarItemSync(client, "Builder")) {
                return;
            }
            clearAcrossPlot(client, bounds);
        } finally {
            running = false;
            countingRemovals = false;
            clearRotationLock();
            clearMouseUngrab();
            if (client != null) {
                client.execute(() -> releaseHeldKeys(client));
            }
        }
    }

    private static void runEndLaneSequence(Minecraft client) {
        try {
            PlotBounds bounds = shouldStop(client) ? null : resolveBoundsForRun(client);
            if (bounds != null) {
                finishFromOppositeCorner(client, bounds);
            }
        } finally {
            running = false;
            countingRemovals = false;
            clearRotationLock();
            clearMouseUngrab();
            if (client != null) {
                client.execute(() -> releaseHeldKeys(client));
            }
        }
    }

    private static void runShovelCornerSequence(Minecraft client) {
        try {
            PlotBounds bounds = shouldStop(client) ? null : resolveBoundsForRun(client);
            if (bounds != null) {
                clearPerimeterWithShovel(client, bounds);
            }
        } finally {
            running = false;
            countingRemovals = false;
            clearRotationLock();
            clearMouseUngrab();
            if (client != null) {
                client.execute(() -> releaseHeldKeys(client));
            }
        }
    }

    private static void runPickaxePerimeterSequence(Minecraft client) {
        try {
            PlotBounds bounds = shouldStop(client) ? null : resolveBoundsForRun(client);
            if (bounds != null) {
                clearPerimeterWithPickaxe(client, bounds);
            }
        } finally {
            running = false;
            countingRemovals = false;
            clearRotationLock();
            clearMouseUngrab();
            if (client != null) {
                client.execute(() -> releaseHeldKeys(client));
            }
        }
    }

    private static void runArchEndingSequence(Minecraft client) {
        try {
            PlotBounds bounds = shouldStop(client) ? null : resolveBoundsForRun(client);
            if (bounds != null) {
                clearFinalArches(client, bounds);
            }
        } finally {
            running = false;
            countingRemovals = false;
            clearRotationLock();
            clearMouseUngrab();
            if (client != null) {
                client.execute(() -> releaseHeldKeys(client));
            }
        }
    }

    private static boolean walkToExactPoint(Minecraft client, Vec3 target) {
        AtomicBoolean pathFinished = new AtomicBoolean(false);
        AtomicBoolean pathFailed = new AtomicBoolean(false);

        client.execute(() -> PathfindingManager.startConfiguredWalk(
                client,
                target,
                () -> pathFinished.set(true),
                () -> pathFailed.set(true),
                true,
                TARGET_TOLERANCE,
                true,
                true));

        long deadline = System.currentTimeMillis() + PATH_TIMEOUT_MS;
        while (!pathFinished.get() && !pathFailed.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(20);
        }

        if (!pathFinished.get()) {
            client.execute(() -> PathfindingManager.stop(false));
            return false;
        }

        long settleDeadline = System.currentTimeMillis() + 1500L;
        while (System.currentTimeMillis() < settleDeadline && !shouldStop(client)) {
            if (client.player != null && horizontalDistanceSqr(client.player.position(), target) <= TARGET_TOLERANCE * TARGET_TOLERANCE) {
                return true;
            }
            MacroWorkerThread.sleep(20);
        }
        return client.player != null && horizontalDistanceSqr(client.player.position(), target) <= TARGET_TOLERANCE * TARGET_TOLERANCE;
    }

    private static boolean walkToCommandPoint(Minecraft client, Vec3 target) {
        AtomicBoolean pathFinished = new AtomicBoolean(false);
        AtomicBoolean pathFailed = new AtomicBoolean(false);

        client.execute(() -> PathfindingManager.startConfiguredWalk(
                client,
                Mth.floor(target.x),
                Mth.floor(target.y),
                Mth.floor(target.z),
                () -> pathFinished.set(true),
                () -> pathFailed.set(true),
                true,
                0.5,
                false,
                false));

        long deadline = System.currentTimeMillis() + PATH_TIMEOUT_MS;
        while (!pathFinished.get() && !pathFailed.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(20);
        }

        if (!pathFinished.get()) {
            client.execute(() -> PathfindingManager.stop(false));
            return false;
        }
        return true;
    }

    private static boolean rotateEastStraightDown(Minecraft client) {
        return rotateToLocked(client, EAST_YAW, DOWN_PITCH);
    }

    private static boolean rotateNorthForTrench(Minecraft client) {
        return rotateToLocked(client, NORTH_YAW, TRENCH_PITCH);
    }

    private static boolean rotateForReturnTrench(Minecraft client) {
        return rotateToLocked(client, RETURN_YAW, RETURN_PITCH);
    }

    private static boolean rotateToLocked(Minecraft client, float yaw, float pitch) {
        clearRotationLock();
        AtomicBoolean rotationScheduled = new AtomicBoolean(false);
        client.execute(() -> {
            RotationManager.cancelRotation();
            RotationManager.rotateToYawPitch(client, yaw, pitch, ROTATION_MS, true);
            rotationScheduled.set(true);
        });

        long deadline = System.currentTimeMillis() + ROTATION_MS + 1_500L;
        while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
            if (rotationScheduled.get() && !RotationManager.isRotating() && isFacing(client, yaw, pitch)) {
                lockRotation(client, yaw, pitch);
                return true;
            }
            MacroWorkerThread.sleep(20);
        }
        boolean locked = isFacing(client, yaw, pitch);
        if (locked) {
            lockRotation(client, yaw, pitch);
        }
        return locked;
    }

    private static void lockRotation(Minecraft client, float yaw, float pitch) {
        lockedYaw = yaw;
        lockedPitch = pitch;
        rotationLockActive = true;
        client.execute(() -> applyRotationLock(client));
    }

    private static void clearRotationLock() {
        rotationLockActive = false;
    }

    private static void applyRotationLock(Minecraft client) {
        if (!rotationLockActive || client == null || client.player == null) {
            return;
        }
        client.player.setYRot(lockedYaw);
        client.player.setXRot(lockedPitch);
        client.player.yRotO = lockedYaw;
        client.player.xRotO = lockedPitch;
        FailsafeManager.expectRotation(lockedYaw, lockedPitch);
    }

    private static boolean isFacingEastDown(Minecraft client) {
        return isFacing(client, EAST_YAW, DOWN_PITCH);
    }

    private static boolean isFacingNorthTrench(Minecraft client) {
        return isFacing(client, NORTH_YAW, TRENCH_PITCH);
    }

    private static boolean isFacingReturnTrench(Minecraft client) {
        return isFacing(client, RETURN_YAW, RETURN_PITCH);
    }

    private static boolean isFacing(Minecraft client, float yaw, float pitch) {
        if (client == null || client.player == null) {
            return false;
        }
        float yawDiff = Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw));
        float pitchDiff = Math.abs(client.player.getXRot() - pitch);
        return yawDiff <= YAW_TOLERANCE && pitchDiff <= PITCH_TOLERANCE;
    }

    private static boolean isCrosshairOnBlock(Minecraft client) {
        return crosshairBlockHit(client) != null;
    }

    private static boolean isCrosshairOnBedrock(Minecraft client) {
        BlockHitResult hit = crosshairBlockHit(client);
        return hit != null && client.level.getBlockState(hit.getBlockPos()).is(Blocks.BEDROCK);
    }

    private static BlockHitResult crosshairBlockHit(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return null;
        }

        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(client.player.getViewVector(1.0f).scale(BEDROCK_RAY_DISTANCE));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                client.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static boolean holdRulerUntilBedrock(Minecraft client, Vec3 dropCenter) {
        if (!isFacingEastDown(client)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: use blocked because rotation is not locked.");
            return false;
        }
        removedMessages.set(0);
        countingRemovals = true;
        AtomicBoolean useStarted = new AtomicBoolean(false);
        client.execute(() -> {
            if (client.options == null || !isFacingEastDown(client)) {
                return;
            }
            if (client.player != null) {
                client.player.setShiftKeyDown(true);
            }
            MacroInput.set(client.options.keyShift, true);
            ClientUtils.setKeyMappingState(client.options.keyUse, true);
            useStarted.set(true);
        });

        long useDeadline = System.currentTimeMillis() + 1_000L;
        while (!useStarted.get() && System.currentTimeMillis() < useDeadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(10);
        }
        if (!useStarted.get()) {
            countingRemovals = false;
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: right click never started because rotation was not locked.");
            return false;
        }

        long deadline = System.currentTimeMillis() + REMOVE_TIMEOUT_MS;
        boolean reachedBedrock = false;
        int lastY = client.player.blockPosition().getY();
        while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
            int currentY = client.player.blockPosition().getY();
            if (currentY < lastY && !isCenteredOnDropColumn(client, dropCenter)) {
                releaseHeldKeysSync(client);
                clearRotationLock();
                currentY = waitForLandingY(client, currentY);

                Vec3 lowerCenter = new Vec3(dropCenter.x, currentY, dropCenter.z);
                if (!walkToExactPoint(client, lowerCenter)) {
                    countingRemovals = false;
                    ClientUtils.sendDebugMessage(
                            "Bedrock Plot Maker: failed to recenter after dropping to Y=" + currentY + ".");
                    return false;
                }
                if (!rotateEastStraightDown(client)) {
                    countingRemovals = false;
                    ClientUtils.sendDebugMessage(
                            "Bedrock Plot Maker: failed to restore the downward angle after recentering.");
                    return false;
                }
                deadline = System.currentTimeMillis() + REMOVE_TIMEOUT_MS;
            }
            lastY = currentY;

            client.execute(() -> {
                if (client.options != null && isFacingEastDown(client)) {
                    MacroInput.set(client.options.keyShift, true);
                    ClientUtils.setKeyMappingState(client.options.keyUse, true);
                }
            });
            reachedBedrock = isCrosshairOnBedrock(client);
            if (reachedBedrock && removedMessages.get() > 0) {
                break;
            }
            MacroWorkerThread.sleep(50);
        }
        countingRemovals = false;
        if ((!reachedBedrock || removedMessages.get() == 0) && !shouldStop(client)) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: stopped with bedrock=" + reachedBedrock
                            + ", removal messages=" + removedMessages.get() + ".");
        }
        return reachedBedrock && removedMessages.get() > 0;
    }

    private static int waitForLandingY(Minecraft client, int fallbackY) {
        int landedY = fallbackY;
        long deadline = System.currentTimeMillis() + 1_500L;
        while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
            landedY = client.player.blockPosition().getY();
            if (client.player.onGround()) {
                break;
            }
            MacroWorkerThread.sleep(10);
        }
        return landedY;
    }

    private static boolean isCenteredOnDropColumn(Minecraft client, Vec3 dropCenter) {
        return client.player != null
                && horizontalDistanceSqr(client.player.position(), dropCenter)
                <= TARGET_TOLERANCE * TARGET_TOLERANCE;
    }

    private static void clearAcrossPlot(Minecraft client, PlotBounds bounds) {
        if (bounds == null || shouldStop(client)) {
            return;
        }
        if (!rotateNorthForTrench(client)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: not facing north trench angle.");
            return;
        }

        holdShiftAndForward(client);
        int stopX = bounds.trenchRightStopX();
        while (!shouldStop(client) && client.player != null && client.player.blockPosition().getX() <= stopX) {
            if (!isFacingNorthTrench(client)) {
                if (!rotateNorthForTrench(client)) {
                    break;
                }
            }

            clearCurrentTrenchBlock(client);
            if (client.player == null || client.player.blockPosition().getX() >= stopX) {
                break;
            }
            stepRightOneBlock(client, Math.min(client.player.blockPosition().getX() + 1, stopX));
        }

        if (shouldStop(client) || client.player == null) {
            return;
        }
        if (!rotateForReturnTrench(client)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: not facing return trench angle.");
            return;
        }

        holdShiftOnly(client);
        int leftStopX = bounds.returnLeftStopX();
        while (!shouldStop(client) && client.player != null && client.player.blockPosition().getX() >= leftStopX) {
            if (!isFacingReturnTrench(client)) {
                if (!rotateForReturnTrench(client)) {
                    break;
                }
            }

            clearCurrentReturnBlock(client);
            if (client.player == null || client.player.blockPosition().getX() <= leftStopX) {
                break;
            }
            stepLeftOneBlock(client, Math.max(client.player.blockPosition().getX() - 1, leftStopX));
        }

        finishFromOppositeCorner(client, bounds);
    }

    private static void holdShiftAndForward(Minecraft client) {
        client.execute(() -> {
            if (client.options == null) {
                return;
            }
            if (client.player != null) {
                client.player.setShiftKeyDown(true);
            }
            MacroInput.set(client.options.keyShift, true);
            MacroInput.set(client.options.keyUp, true);
        });
    }

    private static void holdShiftOnly(Minecraft client) {
        client.execute(() -> {
            if (client.options == null) {
                return;
            }
            if (client.player != null) {
                client.player.setShiftKeyDown(true);
            }
            MacroInput.set(client.options.keyShift, true);
            MacroInput.set(client.options.keyUp, false);
        });
    }

    /** Returns the number of removal chat messages seen while clearing, i.e. rows broken. */
    private static int clearCurrentTrenchBlock(Minecraft client) {
        if (!isCrosshairOnBlock(client)) {
            return 0;
        }

        removedMessages.set(0);
        countingRemovals = true;
        client.execute(() -> {
            if (client.options != null && isFacingNorthTrench(client)) {
                ClientUtils.setKeyMappingState(client.options.keyUse, true);
            }
        });

        long deadline = System.currentTimeMillis() + COLUMN_CLEAR_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
            if (!isFacingNorthTrench(client)) {
                client.execute(() -> {
                    if (client.options != null) {
                        ClientUtils.setKeyMappingState(client.options.keyUse, false);
                    }
                });
                rotateNorthForTrench(client);
                client.execute(() -> {
                    if (client.options != null) {
                        ClientUtils.setKeyMappingState(client.options.keyUse, true);
                    }
                });
            }

            if (!isCrosshairOnBlock(client) && removedMessages.get() > 0) {
                break;
            }
            MacroWorkerThread.sleep(50);
        }

        countingRemovals = false;
        client.execute(() -> {
            if (client.options != null) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false);
            }
        });
        if (removedMessages.get() == 0 && !shouldStop(client)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: no removal chat while clearing trench block.");
        }
        return removedMessages.get();
    }

    private static void clearCurrentReturnBlock(Minecraft client) {
        clearHeldRowFacing(client, RETURN_YAW, RETURN_PITCH, 1, Integer.MAX_VALUE, null);
    }

    /** Holds right-click like vanilla until the requested row removals complete. */
    private static boolean clearHeldRowFacing(Minecraft client, float yaw, float pitch,
                                              int maxRemovals, int maxBreakY,
                                              BlockPos advanceTarget) {
        if (hasReachedAdvanceTarget(client, advanceTarget, yaw)) {
            return true;
        }
        if (advanceTarget == null && (isCrosshairAboveBreakLimit(client, maxBreakY)
                || isCrosshairOnBedrock(client)
                || !isCrosshairOnBlock(client))) {
            return true;
        }

        removedMessages.set(0);
        countingRemovals = true;
        AtomicBoolean useStarted = new AtomicBoolean(false);
        client.execute(() -> {
            if (client.options != null && isFacing(client, yaw, pitch)) {
                ClientUtils.setKeyMappingState(client.options.keyUse, true);
                useStarted.set(true);
            }
        });

        long startDeadline = System.currentTimeMillis() + 1_000L;
        while (!useStarted.get() && System.currentTimeMillis() < startDeadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(10);
        }

        boolean rotationFailed = !useStarted.get();
        long deadline = System.currentTimeMillis() + COLUMN_CLEAR_TIMEOUT_MS;
        int lastRemovalCount = 0;
        long lastRemovalAt = System.currentTimeMillis();
        while (!rotationFailed && System.currentTimeMillis() < deadline && !shouldStop(client)) {
            if (!isFacing(client, yaw, pitch)) {
                client.execute(() -> {
                    if (client.options != null) {
                        ClientUtils.setKeyMappingState(client.options.keyUse, false);
                    }
                });
                if (!rotateToLocked(client, yaw, pitch)) {
                    rotationFailed = true;
                    break;
                }
                client.execute(() -> {
                    if (client.options != null) {
                        ClientUtils.setKeyMappingState(client.options.keyUse, true);
                    }
                });
            }

            int removalCount = removedMessages.get();
            if (removalCount > lastRemovalCount) {
                lastRemovalCount = removalCount;
                lastRemovalAt = System.currentTimeMillis();
            }

            boolean advanced = hasReachedAdvanceTarget(client, advanceTarget, yaw);
            if (advanced || (advanceTarget == null
                    && (removalCount >= maxRemovals
                    || isCrosshairAboveBreakLimit(client, maxBreakY)
                    || isCrosshairOnBedrock(client)
                    || (!isCrosshairOnBlock(client) && removalCount > 0)
                    || (removalCount > 0
                            && System.currentTimeMillis() - lastRemovalAt >= HELD_USE_QUIET_MS)))) {
                break;
            }
            MacroWorkerThread.sleep(50);
        }

        countingRemovals = false;
        AtomicBoolean released = new AtomicBoolean(false);
        client.execute(() -> {
            if (client.options != null) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false);
            }
            released.set(true);
        });
        long releaseDeadline = System.currentTimeMillis() + 1_000L;
        while (!released.get() && System.currentTimeMillis() < releaseDeadline) {
            MacroWorkerThread.sleep(10);
        }

        boolean cleared = hasReachedAdvanceTarget(client, advanceTarget, yaw)
                || (advanceTarget == null && (removedMessages.get() > 0
                || isCrosshairAboveBreakLimit(client, maxBreakY)
                || isCrosshairOnBedrock(client)
                || !isCrosshairOnBlock(client)));
        if (!cleared && !rotationFailed && !shouldStop(client)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: held row removal was not confirmed.");
        }
        return released.get() && !rotationFailed && cleared && !shouldStop(client);
    }

    private static void finishFromOppositeCorner(Minecraft client, PlotBounds bounds) {
        if (shouldStop(client) || client.player == null) {
            return;
        }

        clearRotationLock();
        client.execute(() -> releaseHeldKeys(client));
        MacroWorkerThread.sleep(50);

        if (!GearManager.swapToNamedHotbarItemSync(client, "Builder")) {
            return;
        }

        Vec3 pocket = bounds.cornerPocket();
        if (!walkToCommandPoint(client, pocket)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: failed to reach opposite corner pocket.");
            return;
        }

        if (!rotateNorthForTrench(client)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: not facing north for corner finish.");
            return;
        }

        // One clear can remove several rows at once (the ruler keeps firing while the
        // crosshair still reaches farther blocks), so count removal messages rather
        // than loop iterations and stop as soon as the last row is gone.
        int rowsBroken = 0;
        for (int attempt = 0; attempt < CORNER_FINISH_ROWS && !shouldStop(client) && client.player != null; attempt++) {
            if (!isFacingNorthTrench(client) && !rotateNorthForTrench(client)) {
                break;
            }

            holdShiftAndForward(client);
            rowsBroken += clearCurrentTrenchBlock(client);
            if (rowsBroken >= CORNER_FINISH_ROWS
                    || attempt >= CORNER_FINISH_ROWS - 1
                    || client.player == null) {
                break;
            }
            stepLeftOneBlock(client, client.player.blockPosition().getX() - 1);
        }

        // Re-runs on a partially cleared corner can find fewer than 3 rows left, so
        // don't gate the finish phase on the count; each step guards itself. The
        // shovel wall-follow only runs once the finish legs all completed, since it
        // relies on ending up at the south-west corner.
        if (!shouldStop(client) && clearFinalReturnRows(client, bounds)) {
            clearPerimeterWithShovel(client, bounds);
        }

        clearRotationLock();
        releaseHeldKeysSync(client);
    }

    /**
     * After the corner rows are gone: break the return row (180 / 40), turn south and
     * open that wall the same way the corner start does (top rows at the up angle,
     * then the bottom row), pathfind onto the broken spot, turn west and break that
     * wall, then pathfind to the south-west corner, turn north, and break that wall
     * the same way. Returns {@code true} only if every leg completed.
     */
    private static boolean clearFinalReturnRows(Minecraft client, PlotBounds bounds) {
        if (!rotateAndClearRow(client, RETURN_YAW, RETURN_PITCH, 1, false)) {
            return false;
        }
        if (!breakWallFacing(client, FINISH_SOUTH_YAW)) {
            return false;
        }
        clearRotationLock();
        releaseHeldKeysSync(client);
        if (!breakWallFacing(client, FINISH_WEST_YAW)) {
            return false;
        }
        if (!walkToFinishPoint(client, bounds.southWestCorner())) {
            return false;
        }
        return breakWallFacing(client, NORTH_YAW);
    }

    /**
     * Same motion as the corner start, aimed at the given yaw: the up angle takes the
     * top three rows of the first wall row, then the down angle takes the bottom one.
     * Capped by removal count so the ruler never chews into the rows behind it.
     */
    private static boolean breakWallFacing(Minecraft client, float yaw) {
        if (!rotateAndClearRow(client, yaw, TRENCH_PITCH, CORNER_FINISH_ROWS, false)) {
            return false;
        }
        return rotateAndClearRow(client, yaw, RETURN_PITCH, 1, true);
    }

    private static boolean rotateAndClearRow(Minecraft client, float yaw, float pitch,
                                             int maxRemovals, boolean advanceIntoClearedBlock) {
        if (shouldStop(client) || client.player == null) {
            return false;
        }

        holdShiftOnly(client);
        if (!rotateToLocked(client, yaw, pitch)) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: could not face " + yaw + " / " + pitch + " for the finish row.");
            return false;
        }

        // Push into the wall while breaking, same as the corner-start rows.
        holdShiftAndForward(client);
        int maxBreakY = Math.abs(pitch - TRENCH_PITCH) < 0.01f
                ? client.player.blockPosition().getY() + UPPER_BREAK_Y_OFFSET
                : Integer.MAX_VALUE;
        BlockPos advanceTarget = advanceIntoClearedBlock
                ? oneBlockForward(client.player.blockPosition(), yaw)
                : null;
        boolean cleared = clearHeldRowFacing(client, yaw, pitch, maxRemovals, maxBreakY, advanceTarget);
        holdShiftOnly(client);
        return cleared;
    }

    private static boolean isCrosshairAboveBreakLimit(Minecraft client, int maxBreakY) {
        if (maxBreakY == Integer.MAX_VALUE) {
            return false;
        }
        BlockHitResult hit = crosshairBlockHit(client);
        return hit != null && hit.getBlockPos().getY() > maxBreakY;
    }

    private static BlockPos oneBlockForward(BlockPos start, float yaw) {
        double radians = Math.toRadians(yaw);
        int stepX = (int) Math.round(-Math.sin(radians));
        int stepZ = (int) Math.round(Math.cos(radians));
        return new BlockPos(start.getX() + stepX, start.getY(), start.getZ() + stepZ);
    }

    private static boolean hasReachedAdvanceTarget(Minecraft client, BlockPos target, float yaw) {
        if (target == null || client.player == null) {
            return false;
        }
        BlockPos current = client.player.blockPosition();
        double radians = Math.toRadians(yaw);
        int stepX = (int) Math.round(-Math.sin(radians));
        int stepZ = (int) Math.round(Math.cos(radians));
        boolean reachedX = stepX == 0 || (stepX > 0
                ? current.getX() >= target.getX()
                : current.getX() <= target.getX());
        boolean reachedZ = stepZ == 0 || (stepZ > 0
                ? current.getZ() >= target.getZ()
                : current.getZ() <= target.getZ());
        return reachedX && reachedZ;
    }

    /** Releases keys and rotation, then pathfinds to the given finish point. */
    private static boolean walkToFinishPoint(Minecraft client, Vec3 point) {
        clearRotationLock();
        releaseHeldKeysSync(client);
        if (walkToCommandPoint(client, point)) {
            return true;
        }
        if (!shouldStop(client)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: failed to reach the finish point "
                    + Mth.floor(point.x) + " " + Mth.floor(point.y) + " " + Mth.floor(point.z) + ".");
        }
        return false;
    }

    /** Mines all four perimeter legs and returns to the exact starting X/Z. */
    private static void clearPerimeterWithShovel(Minecraft client, PlotBounds bounds) {
        releaseHeldKeysSync(client);
        if (!GearManager.swapToNamedHotbarItemSync(client, "Golden Shovel")) {
            return;
        }
        if (client.player == null) {
            return;
        }

        BlockPos start = client.player.blockPosition();
        int startX = start.getX();
        int startZ = start.getZ();
        int y = start.getY();
        if (!runShovelLeg(client, NORTH_YAW, new BlockPos(bounds.westBlockX(), y, bounds.northBlockZ()))) return;
        if (!runShovelLeg(client, EAST_YAW, new BlockPos(bounds.eastBlockX(), y, bounds.northBlockZ()))) return;
        if (!runShovelLeg(client, FINISH_SOUTH_YAW, new BlockPos(bounds.eastBlockX(), y, bounds.southBlockZ()))) return;
        if (!runShovelLeg(client, FINISH_WEST_YAW, new BlockPos(startX, y, startZ))) return;
        if (!breakForwardAfterShovel(client, bounds, NORTH_YAW, 4)) return;
        ClientUtils.sendDebugMessage( "Bedrock Plot Maker: shovel complete; starting Golden Pickaxe perimeter.");
        clearPerimeterWithPickaxe(client, bounds);
    }

    private static boolean breakForwardAfterShovel(Minecraft client, PlotBounds bounds, float yaw, int blocks) {
        releaseHeldKeysSync(client);
        if (client.player == null || !rotateToLocked(client, yaw, SHOVEL_CORNER_PITCH)) {
            return false;
        }
        if (!startHeldAttack(client, yaw, SHOVEL_CORNER_PITCH)) {
            return false;
        }

        BlockPos start = client.player.blockPosition();
        double radians = Math.toRadians(yaw);
        int stepX = (int) Math.round(-Math.sin(radians));
        int stepZ = (int) Math.round(Math.cos(radians));
        BlockPos target = start.offset(stepX * blocks, 0, stepZ * blocks);
        long deadline = System.currentTimeMillis() + STEP_RIGHT_TIMEOUT_MS;
        while (!hasReachedMiningTarget(client, target, yaw)
                && System.currentTimeMillis() < deadline
                && !shouldStop(client)) {
            client.execute(() -> {
                if (client.options != null && isFacing(client, yaw, SHOVEL_CORNER_PITCH)) {
                    MacroInput.set(client.options.keyUp, true);
                    MacroInput.setAttack(client.options.keyAttack, !isCrosshairOutsidePlot(client, bounds));
                }
            });
            MacroWorkerThread.sleep(20);
        }

        boolean reached = hasReachedMiningTarget(client, target, yaw);
        releaseAttackSync(client);
        releaseHeldKeysSync(client);
        if (!reached && !shouldStop(client)) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: failed to break forward " + blocks + " blocks after shovel perimeter.");
        }
        return reached;
    }

    private static boolean runShovelLeg(Minecraft client, float yaw, BlockPos target) {
        releaseHeldKeysSync(client);
        if (!rotateToLocked(client, yaw, SHOVEL_CORNER_PITCH)) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: could not rotate to " + yaw + " / " + SHOVEL_CORNER_PITCH
                            + " for shovel perimeter.");
            return false;
        }
        if (!startHeldAttack(client, yaw, SHOVEL_CORNER_PITCH)) {
            return false;
        }

        long deadline = System.currentTimeMillis() + SHOVEL_CORNER_TIMEOUT_MS;
        while (!hasReachedMiningTarget(client, target, yaw)
                && System.currentTimeMillis() < deadline
                && !shouldStop(client)) {
            client.execute(() -> {
                if (client.options != null && isFacing(client, yaw, SHOVEL_CORNER_PITCH)) {
                    MacroInput.set(client.options.keyUp, true);
                    MacroInput.set(client.options.keyLeft, true);
                    MacroInput.setAttack(client.options.keyAttack, true);
                }
            });
            MacroWorkerThread.sleep(20);
        }

        boolean reached = hasReachedMiningTarget(client, target, yaw);
        releaseAttackSync(client);
        releaseHeldKeysSync(client);
        if (!reached && !shouldStop(client)) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: shovel perimeter failed to reach "
                            + target.getX() + " " + target.getZ() + ".");
        }
        return reached;
    }

    private static boolean hasReachedMiningTarget(Minecraft client, BlockPos target, float yaw) {
        if (client.player == null) {
            return false;
        }
        Vec3 current = client.player.position();
        double targetCenterX = target.getX() + 0.5;
        double targetCenterZ = target.getZ() + 0.5;
        double radians = Math.toRadians(yaw);
        int stepX = (int) Math.round(-Math.sin(radians));
        int stepZ = (int) Math.round(Math.cos(radians));
        // Wall-follow deliberately strafes into the border, so only the travel
        // axis determines whether the corner or final advance is complete.
        return stepX > 0 ? current.x >= targetCenterX
                : stepX < 0 ? current.x <= targetCenterX
                : stepZ > 0 ? current.z >= targetCenterZ
                : current.z <= targetCenterZ;
    }

    private static boolean isCrosshairOutsidePlot(Minecraft client, PlotBounds bounds) {
        BlockHitResult hit = crosshairBlockHit(client);
        return hit != null && !isInsidePlot(bounds, hit.getBlockPos());
    }

    private static boolean clearPerimeterWithPickaxe(Minecraft client, PlotBounds bounds) {
        releaseHeldKeysSync(client);
        if (!swapToGoldenPickaxeSync(client) || client.player == null) {
            return false;
        }
        if (!holdPickaxeSneakSync(client)) {
            return false;
        }

        BlockPos start = client.player.blockPosition();
        int y = start.getY();
        try {
            if (!runPickaxeLeg(client, NORTH_YAW,
                    new BlockPos(bounds.westBlockX(), y, bounds.northBlockZ()), true)) return false;
            if (!runPickaxeLeg(client, EAST_YAW,
                    new BlockPos(bounds.eastBlockX(), y, bounds.northBlockZ()), false)) return false;
            if (!runPickaxeLeg(client, FINISH_SOUTH_YAW,
                    new BlockPos(bounds.eastBlockX(), y, bounds.southBlockZ()), false)) return false;
            if (!runPickaxeLeg(client, FINISH_WEST_YAW,
                    new BlockPos(bounds.westBlockX(), y, bounds.southBlockZ()), false)) return false;
            return runPickaxeLeg(client, NORTH_YAW, start, false);
        } finally {
            releaseAttackSync(client);
            releaseHeldKeysSync(client);
        }
    }

    private static boolean holdPickaxeSneakSync(Minecraft client) {
        AtomicBoolean held = new AtomicBoolean(false);
        client.execute(() -> {
            if (client.options == null || client.player == null) {
                return;
            }
            MacroInput.set(client.options.keyShift, true);
            client.player.setShiftKeyDown(true);
            held.set(true);
        });
        long deadline = System.currentTimeMillis() + 1_000L;
        while (!held.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(10);
        }
        return held.get();
    }

    private static boolean swapToGoldenPickaxeSync(Minecraft client) {
        if (client.player == null) {
            return false;
        }

        int slot = -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).is(Items.GOLDEN_PICKAXE)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: no Golden Pickaxe item found in the hotbar.");
            return false;
        }

        int targetSlot = slot;
        client.execute(() -> FailsafeManager.selectHotbarSlot(client, targetSlot));
        long deadline = System.currentTimeMillis() + 1_500L;
        while (!shouldStop(client)
                && System.currentTimeMillis() < deadline
                && !client.player.getMainHandItem().is(Items.GOLDEN_PICKAXE)) {
            MacroWorkerThread.sleep(10);
        }
        boolean equipped = client.player != null && client.player.getMainHandItem().is(Items.GOLDEN_PICKAXE);
        if (!equipped && !shouldStop(client)) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: Golden Pickaxe equip timed out.");
        }
        return equipped;
    }

    private static boolean runPickaxeLeg(Minecraft client, float yaw, BlockPos target, boolean startAttack) {
        if (!rotateToLocked(client, yaw, PICKAXE_UP_PITCH)) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: could not rotate to " + yaw + " / " + PICKAXE_UP_PITCH
                            + " for pickaxe perimeter.");
            return false;
        }
        if (startAttack && !startHeldAttack(client, yaw, PICKAXE_UP_PITCH)) {
            return false;
        }

        long deadline = System.currentTimeMillis() + SHOVEL_CORNER_TIMEOUT_MS;
        while (!hasReachedMiningTarget(client, target, yaw)
                && System.currentTimeMillis() < deadline
                && !shouldStop(client)) {
            client.execute(() -> {
                if (client.options == null || !isFacing(client, yaw, PICKAXE_UP_PITCH)) {
                    return;
                }
                if (client.player != null) {
                    client.player.setShiftKeyDown(true);
                }
                MacroInput.set(client.options.keyShift, true);
                MacroInput.set(client.options.keyUp, true);
                MacroInput.set(client.options.keyLeft, true);
                MacroInput.setAttack(client.options.keyAttack, true);
            });
            MacroWorkerThread.sleep(20);
        }

        boolean reached = hasReachedMiningTarget(client, target, yaw);
        if (!reached && !shouldStop(client)) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: pickaxe perimeter failed to reach "
                            + target.getX() + " " + target.getZ() + ".");
        }
        return reached;
    }

    private static boolean clearFinalArches(Minecraft client, PlotBounds bounds) {
        clearRotationLock();
        releaseHeldKeysSync(client);
        if (!swapToGoldenPickaxeSync(client)) {
            return false;
        }

        for (ArchWall wall : archWalls(bounds)) {
            if (shouldStop(client) || !clearArchWall(client, bounds, wall)) {
                return false;
            }
        }
        ClientUtils.sendDebugMessage( "Bedrock Plot Maker: final arch pass complete.");
        return true;
    }

    private static List<ArchWall> archWalls(PlotBounds bounds) {
        int centerX = bounds.minX() + PLOT_SIZE / 2;
        int centerZ = bounds.minZ() + PLOT_SIZE / 2;
        return List.of(
                new ArchWall("north", false, bounds.northBlockZ(), bounds.northBlockZ() - 1, centerX),
                new ArchWall("east", true, bounds.eastBlockX(), bounds.eastBlockX() + 1, centerZ),
                new ArchWall("south", false, bounds.southBlockZ(), bounds.southBlockZ() + 1, centerX),
                new ArchWall("west", true, bounds.westBlockX(), bounds.westBlockX() - 1, centerZ));
    }

    private static boolean clearArchWall(Minecraft client, PlotBounds bounds, ArchWall wall) {
        List<BlockPos> targets = findArchBlocks(client, bounds, wall);
        if (targets.isEmpty()) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: no inside arch blocks remain on the " + wall.name() + " wall.");
            return true;
        }

        if (!walkToExactPoint(client, wall.insideWalkPoint(TARGET_Y))) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: failed to walk to the " + wall.name() + " arch.");
            return false;
        }
        clearReachableArchBlocks(client, bounds, wall);
        releaseHeldKeysSync(client);

        boolean cleared = findArchBlocks(client, bounds, wall).isEmpty();
        if (!cleared && !shouldStop(client)) {
            ClientUtils.sendDebugMessage(
                    "Bedrock Plot Maker: stone-brick or polished-andesite blocks remain on the "
                            + wall.name() + " arch.");
        }
        return cleared;
    }

    private static void clearReachableArchBlocks(Minecraft client, PlotBounds bounds, ArchWall wall) {
        List<BlockPos> remaining = findArchBlocks(client, bounds, wall);
        BlockPos lastBroken = null;
        boolean attackHeld = false;
        try {
            while (!remaining.isEmpty() && !shouldStop(client)) {
                remaining.removeIf(pos -> !isArchBlockAt(client, pos));
                if (remaining.isEmpty()) {
                    return;
                }

                List<BlockPos> reachable = remaining.stream()
                        .filter(pos -> distanceToArchBlockSqr(client, pos) <= ARCH_REACH * ARCH_REACH)
                        .toList();
                boolean movedCloser = reachable.isEmpty();
                BlockPos target = nextClosestArchTarget(client,
                        movedCloser ? remaining : reachable, lastBroken);
                if (movedCloser) {
                    releaseAttackSync(client);
                    attackHeld = false;
                    releaseHeldKeysSync(client);
                    if (!walkToExactPoint(client, wall.insideWalkPoint(target, TARGET_Y))) {
                        ClientUtils.sendDebugMessage(
                                "Bedrock Plot Maker: could not move within reach of " + target.toShortString() + ".");
                        return;
                    }
                }

                boolean broken = breakArchBlockHeld(client, target, !attackHeld);
                if (!broken && !movedCloser) {
                    releaseAttackSync(client);
                    attackHeld = false;
                    releaseHeldKeysSync(client);
                    if (walkToExactPoint(client, wall.insideWalkPoint(target, TARGET_Y))) {
                        broken = breakArchBlockHeld(client, target, true);
                    }
                }
                if (!broken) {
                    ClientUtils.sendDebugMessage(
                            "Bedrock Plot Maker: smooth arch sweep stalled at " + target.toShortString() + ".");
                    return;
                }

                attackHeld = true;
                lastBroken = target;
                remaining.remove(target);
            }
        } finally {
            releaseAttackSync(client);
        }
    }

    private static BlockPos nextClosestArchTarget(Minecraft client, List<BlockPos> remaining,
                                                   BlockPos lastBroken) {
        Comparator<BlockPos> nearest = lastBroken == null
                ? Comparator.comparingDouble(pos -> distanceToArchBlockSqr(client, pos))
                : Comparator.comparingDouble(pos -> distanceBetweenBlocksSqr(lastBroken, pos));
        return remaining.stream().min(nearest).orElseThrow();
    }

    private static double distanceBetweenBlocksSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static List<BlockPos> findArchBlocks(Minecraft client, PlotBounds bounds, ArchWall wall) {
        List<BlockPos> targets = new ArrayList<>();
        if (client == null || client.level == null) {
            return targets;
        }

        for (int parallel = wall.parallelCenter() - ARCH_SCAN_HALF_WIDTH;
             parallel <= wall.parallelCenter() + ARCH_SCAN_HALF_WIDTH;
             parallel++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                BlockPos pos = wall.blockAt(parallel, y);
                if (isInsidePlot(bounds, pos) && isArchBlock(client.level.getBlockState(pos))) {
                    targets.add(pos);
                }
            }
        }
        return targets;
    }

    private static boolean isInsidePlot(PlotBounds bounds, BlockPos pos) {
        return pos.getX() >= bounds.minX() && pos.getX() < bounds.maxX()
                && pos.getZ() >= bounds.minZ() && pos.getZ() < bounds.maxZ();
    }

    private static boolean isArchBlock(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.contains("stone_brick") || path.contains("polished_andesite");
    }

    private static boolean isArchBlockAt(Minecraft client, BlockPos pos) {
        return client != null && client.level != null && isArchBlock(client.level.getBlockState(pos));
    }

    private static double distanceToArchBlockSqr(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null) {
            return Double.MAX_VALUE;
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        double dx = eye.x - target.x;
        double dy = eye.y - target.y;
        double dz = eye.z - target.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean breakArchBlockHeld(Minecraft client, BlockPos target, boolean startAttack) {
        if (!isArchBlockAt(client, target)) {
            return true;
        }
        if (distanceToArchBlockSqr(client, target) > ARCH_REACH * ARCH_REACH
                || !rotateToArchBlock(client, target)) {
            return !isArchBlockAt(client, target);
        }
        if (!isArchBlockAt(client, target)) {
            return true;
        }

        if (startAttack && !startArchHeldAttack(client, target)) {
            return false;
        }

        long breakDeadline = System.currentTimeMillis() + ARCH_BREAK_TIMEOUT_MS;
        while (isArchBlockAt(client, target)
                && System.currentTimeMillis() < breakDeadline && !shouldStop(client)) {
            client.execute(() -> {
                if (client.options != null) {
                    MacroInput.setAttack(client.options.keyAttack, true);
                }
            });
            MacroWorkerThread.sleep(20);
        }
        return !isArchBlockAt(client, target);
    }

    private static boolean startArchHeldAttack(Minecraft client, BlockPos target) {
        AtomicBoolean started = new AtomicBoolean(false);
        client.execute(() -> {
            BlockHitResult hit = crosshairBlockHit(client);
            if (client.options != null && hit != null && hit.getBlockPos().equals(target)) {
                MacroInput.setAttack(client.options.keyAttack, true);
                ClientUtils.clickKeyMapping(client.options.keyAttack);
                started.set(true);
            }
        });
        long deadline = System.currentTimeMillis() + 1_000L;
        while (!started.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(10);
        }
        return started.get();
    }

    private static boolean rotateToArchBlock(Minecraft client, BlockPos target) {
        AtomicBoolean queued = new AtomicBoolean(false);
        client.execute(() -> {
            RotationManager.initiateRotation(client, Vec3.atCenterOf(target), ARCH_ROTATION_MS);
            queued.set(true);
        });
        long queueDeadline = System.currentTimeMillis() + 1_000L;
        while (!queued.get() && System.currentTimeMillis() < queueDeadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(10);
        }
        long rotationDeadline = System.currentTimeMillis() + ARCH_ROTATION_MS + 1_000L;
        while (RotationManager.isRotating()
                && System.currentTimeMillis() < rotationDeadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(20);
        }
        BlockHitResult hit = crosshairBlockHit(client);
        return !isArchBlockAt(client, target) || (hit != null && hit.getBlockPos().equals(target));
    }

    private static boolean startHeldAttack(Minecraft client, float yaw, float pitch) {
        AtomicBoolean started = new AtomicBoolean(false);
        client.execute(() -> {
            if (client.options != null && isFacing(client, yaw, pitch)) {
                MacroInput.setAttack(client.options.keyAttack, true);
                ClientUtils.clickKeyMapping(client.options.keyAttack);
                started.set(true);
            }
        });
        long deadline = System.currentTimeMillis() + 1_000L;
        while (!started.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(10);
        }
        return started.get();
    }

    private static void releaseAttackSync(Minecraft client) {
        AtomicBoolean released = new AtomicBoolean(false);
        client.execute(() -> {
            if (client.options != null) {
                MacroInput.setAttack(client.options.keyAttack, false);
            }
            released.set(true);
        });
        long deadline = System.currentTimeMillis() + 1_000L;
        while (!released.get() && System.currentTimeMillis() < deadline) {
            MacroWorkerThread.sleep(10);
        }
    }

    private static void stepRightOneBlock(Minecraft client, int targetBlockX) {
        holdShiftAndForward(client);
        client.execute(() -> {
            if (client.options != null) {
                MacroInput.set(client.options.keyRight, true);
            }
        });

        long deadline = System.currentTimeMillis() + STEP_RIGHT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
            if (client.player != null && client.player.blockPosition().getX() >= targetBlockX) {
                break;
            }
            MacroWorkerThread.sleep(20);
        }

        client.execute(() -> {
            if (client.options != null) {
                MacroInput.set(client.options.keyRight, false);
            }
        });
    }

    private static void stepLeftOneBlock(Minecraft client, int targetBlockX) {
        holdShiftOnly(client);
        client.execute(() -> {
            if (client.options != null) {
                MacroInput.set(client.options.keyLeft, true);
            }
        });

        long deadline = System.currentTimeMillis() + STEP_RIGHT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
            if (client.player != null && client.player.blockPosition().getX() <= targetBlockX) {
                break;
            }
            MacroWorkerThread.sleep(20);
        }

        client.execute(() -> {
            if (client.options != null) {
                MacroInput.set(client.options.keyLeft, false);
            }
        });
    }

    private static void releaseHeldKeys(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        MacroInput.setAttack(client.options.keyAttack, false);
        MacroInput.set(client.options.keyShift, false);
        MacroInput.set(client.options.keyUp, false);
        MacroInput.set(client.options.keyRight, false);
        MacroInput.set(client.options.keyLeft, false);
        MacroInput.set(client.options.keyJump, false);
        if (client.player != null) {
            client.player.setShiftKeyDown(false);
        }
    }

    private static void releaseHeldKeysSync(Minecraft client) {
        AtomicBoolean released = new AtomicBoolean(false);
        client.execute(() -> {
            releaseHeldKeys(client);
            released.set(true);
        });

        long deadline = System.currentTimeMillis() + 500L;
        while (!released.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
            MacroWorkerThread.sleep(10);
        }
    }

    private static boolean shouldStop(Minecraft client) {
        return !running
                || MacroWorkerThread.getInstance().isCancelled()
                || client == null
                || client.player == null
                || client.level == null;
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static PlotBounds targetBounds(Minecraft mc) {
        OptionalInt configuredPlot = parsePlot(AetherConfig.BEDROCK_PLOT_MAKER_PLOT.get());
        int plot = configuredPlot.isPresent()
                ? configuredPlot.getAsInt()
                : parsePlot(ClientUtils.getCurrentPlot()).orElse(-1);
        return boundsForPlot(plot);
    }

    /**
     * Resolves the target plot for a macro run, reporting how it was resolved and
     * failing loudly when the plot number or bounds cannot be derived. The
     * configured plot wins over the detected one; a mismatch prints a warning.
     * Render paths must keep using the quiet {@link #targetBounds}.
     */
    private static PlotBounds resolveBoundsForRun(Minecraft client) {
        String configured = AetherConfig.BEDROCK_PLOT_MAKER_PLOT.get();
        String scoreboard = ClientUtils.getCurrentPlot();
        OptionalInt configPlot = parsePlot(configured);
        OptionalInt currentPlot = parsePlot(scoreboard);
        int plot = configPlot.orElse(currentPlot.orElse(-1));
        PlotBounds bounds = boundsForPlot(plot);
        if (bounds == null) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: could not resolve plot"
                    + " (config='" + configured + "', scoreboard='" + scoreboard + "').");
            return null;
        }

        if (configPlot.isPresent() && currentPlot.isPresent()
                && configPlot.getAsInt() != currentPlot.getAsInt()) {
            ClientUtils.sendDebugMessage( "Bedrock Plot Maker: WARNING configured plot "
                    + configPlot.getAsInt() + " does not match the plot you are on ("
                    + currentPlot.getAsInt() + "); using the configured plot.");
        }
        ClientUtils.sendDebugMessage( "Bedrock Plot Maker: plot " + plot
                + (configPlot.isPresent() ? " (config), " : " (scoreboard), ") + bounds.describe());
        return bounds;
    }

    private static OptionalInt parsePlot(String value) {
        if (value == null || value.isBlank()) {
            return OptionalInt.empty();
        }

        String digits = value.trim().replaceAll("\\D", "");
        if (digits.isBlank()) {
            return OptionalInt.empty();
        }

        try {
            return OptionalInt.of(Integer.parseInt(digits));
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    /**
     * Garden plot numbers as seen on the map from above: top row is north (-Z),
     * left column is west (-X), 0 is the barn at grid (0, 0).
     */
    private static final int[][] PLOT_LAYOUT = {
            {21, 13,  9, 14, 22},
            {15,  5,  1,  6, 16},
            {10,  2,  0,  3, 11},
            {17,  7,  4,  8, 18},
            {23, 19, 12, 20, 24},
    };

    private static PlotBounds boundsForPlot(int plot) {
        if (plot <= 0) {
            return null;
        }
        for (int row = 0; row < PLOT_LAYOUT.length; row++) {
            for (int col = 0; col < PLOT_LAYOUT[row].length; col++) {
                if (PLOT_LAYOUT[row][col] == plot) {
                    return bounds(col - 2, row - 2);
                }
            }
        }
        return null;
    }

    private static PlotBounds bounds(int gridX, int gridZ) {
        int minX = gridX * PLOT_SIZE - PLOT_OFFSET;
        int minZ = gridZ * PLOT_SIZE - PLOT_OFFSET;
        return new PlotBounds(minX, minZ, minX + PLOT_SIZE, minZ + PLOT_SIZE);
    }

    private record ArchWall(String name, boolean constantX, int insidePlane, int outsidePlane,
                            int parallelCenter) {
        BlockPos blockAt(int parallel, int y) {
            return constantX
                    ? new BlockPos(insidePlane, y, parallel)
                    : new BlockPos(parallel, y, insidePlane);
        }

        Vec3 insideWalkPoint(double y) {
            return insideWalkPoint(parallelCenter, y);
        }

        Vec3 insideWalkPoint(BlockPos target, double y) {
            double parallel = constantX ? target.getZ() + 0.5 : target.getX() + 0.5;
            return insideWalkPoint(parallel, y);
        }

        private Vec3 insideWalkPoint(double parallel, double y) {
            double perpendicular = outsidePlane < insidePlane
                    ? insidePlane + 1.0 + ARCH_WALL_INSET
                    : insidePlane - ARCH_WALL_INSET;
            return constantX
                    ? new Vec3(perpendicular, y, parallel)
                    : new Vec3(parallel, y, perpendicular);
        }
    }

    /**
     * Axis-aligned plot footprint. IMPORTANT: {@code maxX}/{@code maxZ} are EXCLUSIVE
     * (one past the last block), which matches AABB rendering but not block math -
     * use the *BlockX/*BlockZ accessors for anything that names a block. Orientation
     * is shared by every Garden plot: north = -Z, west = -X.
     */
    private record PlotBounds(int minX, int minZ, int maxX, int maxZ) {

        /** First (west-most) block column inside the plot. */
        int westBlockX() {
            return minX;
        }

        /** Last (east-most) block column inside the plot (maxX is exclusive). */
        int eastBlockX() {
            return maxX - 1;
        }

        /** First (north-most) block row inside the plot. */
        int northBlockZ() {
            return minZ;
        }

        /** Last (south-most) block row inside the plot (maxZ is exclusive). */
        int southBlockZ() {
            return maxZ - 1;
        }

        /**
         * Surface stand point at the south-west corner where the Builder ruler
         * drills down, offset from the west edge and south row by the field-tuned
         * stand offsets.
         */
        Vec3 standPoint() {
            return new Vec3(westBlockX() + STAND_EAST_OFFSET, TARGET_Y, southBlockZ() + STAND_Z_OFFSET);
        }

        /** Trench pass stops moving east one block short of the east-most column. */
        int trenchRightStopX() {
            return eastBlockX() - 1;
        }

        /** Return pass stops moving west one block east of the west-most column. */
        int returnLeftStopX() {
            return westBlockX() + 1;
        }

        /**
         * South-east corner pocket at trench level for the corner-finish legs.
         * Intentionally one block PAST the inclusive east edge (the pathfinder
         * floors this); field-tuned on plot 1.
         */
        Vec3 cornerPocket() {
            return new Vec3(eastBlockX() + 1, CORNER_FINISH_Y, southBlockZ());
        }

        /**
         * South-west corner at trench level for the final north-facing break.
         * Intentionally one block PAST the inclusive south edge; field-tuned on
         * plot 1.
         */
        Vec3 southWestCorner() {
            return new Vec3(westBlockX(), CORNER_FINISH_Y - 1, southBlockZ() + 1);
        }

        /** Human-readable inclusive extent for debug chat. */
        String describe() {
            return "x " + westBlockX() + ".." + eastBlockX() + ", z " + northBlockZ() + ".." + southBlockZ();
        }
    }
}
