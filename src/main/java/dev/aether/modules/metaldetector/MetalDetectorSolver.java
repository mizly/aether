package dev.aether.modules.metaldetector;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.metaldetector.helpers.MetalDetectorBackpackManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetalDetectorSolver {
    private static final Pattern TREASURE_DISTANCE_PATTERN =
            Pattern.compile("TREASURE[:\\s]*([0-9]+(?:\\.[0-9]+)?)m", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)\u00A7[0-9A-FK-OR]");

    private static final List<BlockPos> DEFAULT_RELATIVE_CHESTS = List.of(
            new BlockPos(-7, 26, -2),
            new BlockPos(-15, 26, 31),
            new BlockPos(-17, 26, 19),
            new BlockPos(47, 25, 33),
            new BlockPos(36, 26, 45),
            new BlockPos(48, 27, 45),
            new BlockPos(45, 27, -13),
            new BlockPos(-38, 26, 21),
            new BlockPos(42, 26, 27),
            new BlockPos(29, 27, -7),
            new BlockPos(22, 26, -15),
            new BlockPos(-7, 27, -26),
            new BlockPos(-2, 26, -6),
            new BlockPos(43, 27, -21),
            new BlockPos(10, 26, -11),
            new BlockPos(17, 26, 49),
            new BlockPos(19, 26, -17),
            new BlockPos(-35, 27, 35),
            new BlockPos(25, 27, 5),
            new BlockPos(-37, 24, 46),
            new BlockPos(-24, 26, 49),
            new BlockPos(-7, 26, 48),
            new BlockPos(-14, 27, -24),
            new BlockPos(-18, 27, 44),
            new BlockPos(-1, 26, -23),
            new BlockPos(41, 25, -37),
            new BlockPos(19, 26, -38),
            new BlockPos(-7, 27, 27),
            new BlockPos(42, 26, 19),
            new BlockPos(-33, 27, 31),
            new BlockPos(6, 27, 25),
            new BlockPos(-2, 26, -17),
            new BlockPos(-15, 27, 5),
            new BlockPos(-20, 27, -12),
            new BlockPos(-25, 26, 30),
            new BlockPos(28, 27, -35),
            new BlockPos(-19, 27, -22),
            new BlockPos(4, 26, -15),
            new BlockPos(36, 26, 17)
    );

    private static final double ATTACK_RANGE = 4.5;
    private static final long RETRY_SAME_TARGET_AFTER_MS = 3500L;
    private static final long ROTATE_TIMEOUT_MS = 2000L;
    private static final long OPEN_TIMEOUT_MS = 1400L;
    private static final long PATH_RETRY_DELAY_MS = 350L;
    private static final long PATH_IDLE_GRACE_MS = 300L;
    private static final long PATH_TIMEOUT_MS = 20_000L;
    private static final long ANCHOR_SCAN_COOLDOWN_MS = 3000L;
    private static final int AUTO_SCAN_HORIZONTAL_RADIUS = 50;
    private static final int AUTO_SCAN_VERTICAL_RADIUS = 35;
    private static final float ROTATION_TOLERANCE = 4.0f;
    private static final String METAL_DETECTOR_ITEM_NAME = "metal detector";

    private static final List<BlockPos> relativeChestCoords = new ArrayList<>();
    private static final List<BlockPos> absoluteChestCoords = new ArrayList<>();
    private static final List<BlockPos> predictedChestLocations = new ArrayList<>();
    private static final Map<BlockPos, Long> attemptedUntil = new HashMap<>();

    private static boolean enabled;
    private static boolean coordsLoaded;
    private static boolean lobbyInitialized;
    private static long lastScanAt;
    private static int levelIdentity = Integer.MIN_VALUE;
    private static int sessionChestsOpened;

    private static BlockPos anchor;
    private static BlockPos ignoreBlockPos;
    private static Vec3 lastTreasureReadPos;

    private static AutomationState automationState = AutomationState.IDLE;
    private static int operationId;
    private static BlockPos automationTarget;
    private static BlockPos automationGoal;
    private static List<BlockPos> activeGoals = List.of();
    private static int activeGoalIndex;
    private static long phaseStartedAt;
    private static long lastAutomationStartAt;
    private static boolean pathCallbackTriggered;
    private static boolean openAttemptTriggered;

    private enum AutomationState {
        IDLE,
        PATHING,
        ROTATING,
        OPENING
    }

    private MetalDetectorSolver() {
    }

    public static boolean toggle(Minecraft client) {
        setEnabled(client, !enabled);
        return enabled;
    }

    public static void setEnabled(Minecraft client, boolean enabled) {
        setEnabled(client, enabled, true, true);
    }

    public static void stopForMacro(Minecraft client) {
        setEnabled(client, false, false, false);
    }

    private static void setEnabled(Minecraft client, boolean enabled, boolean announce, boolean syncMacroState) {
        ensureCoordinatesLoaded(client);
        if (MetalDetectorSolver.enabled == enabled) {
            return;
        }

        if (enabled) {
            if (!ensureMetalDetectorSelected(client)) {
                ClientUtils.sendMessage("\u00A7cNo metal detector found in hotbar.", false);
                return;
            }
            resetSessionState(false);
            MetalDetectorBackpackManager.reset();
            sessionChestsOpened = 0;
            levelIdentity = getLevelIdentity(client);
            if (announce) {
                ClientUtils.sendMessage("\u00A7eScanning for metal detector anchor...", false);
            }

            MetalDetectorSolver.enabled = true;
            BlockPos scannedAnchor = scanForNearbyAnchor(client);
            if (scannedAnchor != null) {
                applyAnchor(client, scannedAnchor, true);
            }
            if (syncMacroState) {
                MacroStateManager.setCurrentState(MacroState.State.METAL_DETECTING);
            }
            if (announce && scannedAnchor != null) {
                ClientUtils.sendMessage("\u00A7aMetal detector solver enabled at "
                                + scannedAnchor.getX() + " "
                                + scannedAnchor.getY() + " "
                                + scannedAnchor.getZ()
                                + " (\u00A7f" + absoluteChestCoords.size() + "\u00A7a spots).",
                        false);
            } else if (announce) {
                ClientUtils.sendMessage("\u00A7eMetal detector solver enabled. Waiting for nearby anchor blocks...",
                        false);
            }
        } else {
            MetalDetectorSolver.enabled = false;
            stopAutomation(client, true);
            resetSessionState(false);
            MetalDetectorBackpackManager.reset();
            if (syncMacroState && MacroStateManager.getCurrentState() == MacroState.State.METAL_DETECTING) {
                MacroStateManager.setCurrentState(MacroState.State.OFF);
            }
            if (announce) {
                ClientUtils.sendMessage("\u00A7eMetal detector solver disabled.", false);
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getSessionChestsOpened() {
        return sessionChestsOpened;
    }

    public static int getScavengedToolCount(Minecraft client) {
        return MetalDetectorBackpackManager.getScavengedToolCount(client);
    }

    public static String getFilledBackpacksSummary() {
        return MetalDetectorBackpackManager.getFilledBackpacksSummary();
    }

    public static void handleContainerMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!enabled) {
            return;
        }
        MetalDetectorBackpackManager.handleContainerMenu(client, screen);
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled) {
            return;
        }

        if (client == null || client.player == null || client.level == null) {
            return;
        }

        int newLevelIdentity = getLevelIdentity(client);
        if (levelIdentity != newLevelIdentity) {
            stopAutomation(client, true);
            resetSessionState(false);
            levelIdentity = newLevelIdentity;
        }

        if (MetalDetectorBackpackManager.shouldHandle(client)) {
            if (automationState != AutomationState.IDLE || PathfindingManager.isNavigating()) {
                stopAutomation(client, true);
            }
            MetalDetectorBackpackManager.update();
            return;
        }

        if (anchor == null || absoluteChestCoords.isEmpty()) {
            tryInitializeAnchor(client, false, false);
        }

        pruneAttemptedTargets();

        switch (automationState) {
            case IDLE -> tickIdle(client);
            case PATHING -> tickPathing(client);
            case ROTATING -> tickRotating(client);
            case OPENING -> tickOpening(client);
        }
    }

    public static void onGameMessage(Minecraft client, String text, boolean overlay) {
        if (!enabled || client == null || client.player == null || client.level == null || text == null) {
            return;
        }

        String clean = stripColors(text).trim();
        if (clean.isEmpty()) {
            return;
        }

        if (clean.contains("TREASURE")) {
            handleTreasureMessage(client, clean);
            return;
        }

        if (!overlay && clean.startsWith("You found") && clean.contains("Metal Detector!")) {
            handleTreasureFound(client);
        }
    }

    public static void forceScan(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }

        ensureCoordinatesLoaded(client);
        ClientUtils.sendMessage("\u00A7eScanning for metal detector anchor...", false);
        BlockPos scannedAnchor = scanForNearbyAnchor(client);

        if (scannedAnchor == null) {
            ClientUtils.sendMessage("\u00A7cNo metal detector anchor found nearby.", false);
            return;
        }

        applyAnchor(client, scannedAnchor, true);
        ClientUtils.sendMessage("\u00A7aMetal detector anchor found at "
                        + scannedAnchor.getX() + " "
                        + scannedAnchor.getY() + " "
                        + scannedAnchor.getZ()
                        + " (\u00A7f" + absoluteChestCoords.size() + "\u00A7a spots).",
                false);
    }

    public static boolean hasVisibleHighlights() {
        return enabled && (!predictedChestLocations.isEmpty() || automationTarget != null || automationGoal != null
                || (anchor != null && !absoluteChestCoords.isEmpty()));
    }

    public static void renderWorld() {
        if (!hasVisibleHighlights()) {
            return;
        }

        if (anchor != null && !absoluteChestCoords.isEmpty()) {
            GizmoStyle anchorStyle = GizmoStyle.strokeAndFill(
                    ARGB.color(210, 255, 255, 255),
                    2.0f,
                    ARGB.color(55, 255, 255, 255));
            var anchorProps = Gizmos.cuboid(new AABB(
                    anchor.getX() + 0.3, anchor.getY() - 0.7, anchor.getZ() + 0.3,
                    anchor.getX() + 0.7, anchor.getY() - 0.3, anchor.getZ() + 0.7), anchorStyle);
            anchorProps.setAlwaysOnTop();

            GizmoStyle spotStyle = GizmoStyle.strokeAndFill(
                    ARGB.color(160, 255, 70, 70),
                    1.0f,
                    ARGB.color(28, 255, 70, 70));
            for (BlockPos chest : absoluteChestCoords) {
                var chestProps = Gizmos.cuboid(new AABB(
                        chest.getX() + 0.375, chest.getY() + 0.375, chest.getZ() + 0.375,
                        chest.getX() + 0.625, chest.getY() + 0.625, chest.getZ() + 0.625), spotStyle);
                chestProps.setAlwaysOnTop();
            }
        }

        GizmoStyle predictedStyle = GizmoStyle.strokeAndFill(
                ARGB.color(230, 60, 255, 120),
                2.0f,
                ARGB.color(45, 60, 255, 120));
        for (BlockPos predicted : predictedChestLocations) {
            var predictedProps = Gizmos.cuboid(new AABB(
                    predicted.getX(), predicted.getY(), predicted.getZ(),
                    predicted.getX() + 1.0, predicted.getY() + 1.0, predicted.getZ() + 1.0), predictedStyle);
            predictedProps.setAlwaysOnTop();
        }

        if (automationTarget != null) {
            GizmoStyle targetStyle = GizmoStyle.strokeAndFill(
                    ARGB.color(255, 80, 200, 255),
                    2.5f,
                    ARGB.color(32, 80, 200, 255));
            var targetProps = Gizmos.cuboid(new AABB(
                    automationTarget.getX(), automationTarget.getY(), automationTarget.getZ(),
                    automationTarget.getX() + 1.0, automationTarget.getY() + 1.0, automationTarget.getZ() + 1.0),
                    targetStyle);
            targetProps.setAlwaysOnTop();
        }

        if (automationGoal != null) {
            GizmoStyle goalStyle = GizmoStyle.strokeAndFill(
                    ARGB.color(210, 80, 120, 255),
                    2.0f,
                    ARGB.color(36, 80, 120, 255));
            var goalProps = Gizmos.cuboid(new AABB(
                    automationGoal.getX(), automationGoal.getY(), automationGoal.getZ(),
                    automationGoal.getX() + 1.0, automationGoal.getY() + 1.0, automationGoal.getZ() + 1.0),
                    goalStyle);
            goalProps.setAlwaysOnTop();
        }
    }

    private static void tickIdle(Minecraft client) {
        if (predictedChestLocations.isEmpty()) {
            return;
        }

        if (System.currentTimeMillis() - lastAutomationStartAt < PATH_RETRY_DELAY_MS) {
            return;
        }

        BlockPos target = chooseBestTarget(client);
        if (target != null) {
            startTargetAutomation(client, target);
        }
    }

    private static void tickPathing(Minecraft client) {
        if (automationTarget == null) {
            stopAutomation(client, true);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - phaseStartedAt > PATH_TIMEOUT_MS) {
            failCurrentTarget(client,
                    "MetalDetector: walk timed out",
                    PATH_RETRY_DELAY_MS);
            return;
        }

        if (!PathfindingManager.isNavigating() && !pathCallbackTriggered && now - phaseStartedAt > PATH_IDLE_GRACE_MS) {
            failCurrentTarget(client,
                    "MetalDetector: walk interrupted",
                    PATH_RETRY_DELAY_MS);
        }
    }

    private static void tickRotating(Minecraft client) {
        if (automationTarget == null) {
            stopAutomation(client, true);
            return;
        }

        if (!isWithinAttackRange(client, automationTarget)) {
            failCurrentTarget(client, "MetalDetector: drifted out of range", PATH_RETRY_DELAY_MS);
            return;
        }

        faceTarget(client, automationTarget);
        if (isLookingAtTarget(client, automationTarget) && isCrosshairOnTarget(client, automationTarget)) {
            if (!ensureMetalDetectorSelected(client)) {
                failCurrentTarget(client, "MetalDetector: no hotbar metal detector found", RETRY_SAME_TARGET_AFTER_MS);
                return;
            }
            startOpenAttempt(client);
            return;
        }

        if (System.currentTimeMillis() - phaseStartedAt > ROTATE_TIMEOUT_MS) {
            failCurrentTarget(client, "MetalDetector: could not face target", PATH_RETRY_DELAY_MS);
        }
    }

    private static void tickOpening(Minecraft client) {
        if (automationTarget == null) {
            stopAutomation(client, true);
            return;
        }

        if (!isWithinAttackRange(client, automationTarget)) {
            failCurrentTarget(client, "MetalDetector: drifted out of range while opening", PATH_RETRY_DELAY_MS);
            return;
        }

        faceTarget(client, automationTarget);
        if (ClientUtils.isInventoryScreenOpen(client)) {
            return;
        }

        if (!openAttemptTriggered) {
            if (isLookingAtTarget(client, automationTarget) && isCrosshairOnTarget(client, automationTarget)) {
                startOpenAttempt(client);
            } else if (System.currentTimeMillis() - phaseStartedAt > ROTATE_TIMEOUT_MS) {
                failCurrentTarget(client, "MetalDetector: could not re-align for chest open", PATH_RETRY_DELAY_MS);
            }
            return;
        }

        if (System.currentTimeMillis() - phaseStartedAt > OPEN_TIMEOUT_MS) {
            failCurrentTarget(client, "MetalDetector: chest open attempt failed", RETRY_SAME_TARGET_AFTER_MS);
        }
    }

    private static boolean ensureMetalDetectorSelected(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }

        int slot = GearManager.findHotbarItemSlot(client, METAL_DETECTOR_ITEM_NAME);
        if (slot < 0) {
            return false;
        }

        if (FailsafeManager.getCurrentSelectedSlot(client) != slot) {
            FailsafeManager.selectHotbarSlot(client, slot);
        }
        return true;
    }

    private static void handleTreasureMessage(Minecraft client, String clean) {
        if (!lobbyInitialized) {
            tryInitializeAnchor(client, false, false);
        }

        if (anchor == null || absoluteChestCoords.isEmpty()) {
            return;
        }

        Vec3 playerPos = client.player.position();
        if (lastTreasureReadPos == null) {
            lastTreasureReadPos = playerPos;
            return;
        }

        if (playerPos.distanceToSqr(lastTreasureReadPos) > 1.0e-4) {
            lastTreasureReadPos = playerPos;
            return;
        }

        Matcher matcher = TREASURE_DISTANCE_PATTERN.matcher(clean);
        if (!matcher.find()) {
            return;
        }

        double treasureDistance;
        try {
            treasureDistance = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return;
        }

        for (BlockPos chest : absoluteChestCoords) {
            double dist = Math.sqrt(distanceSq(playerPos, chest.getX(), chest.getY() + 1.0, chest.getZ()));
            if (roundTenth(dist) != treasureDistance) {
                continue;
            }

            if (ignoreBlockPos != null && ignoreBlockPos.equals(chest)) {
                ignoreBlockPos = null;
                return;
            }

            addPredictedChest(client, chest);
        }
    }

    private static void handleTreasureFound(Minecraft client) {
        sessionChestsOpened++;
        if (automationTarget != null) {
            ignoreBlockPos = automationTarget.immutable();
            removePredictedChest(automationTarget);
        } else if (!predictedChestLocations.isEmpty()) {
            ignoreBlockPos = predictedChestLocations.get(0).immutable();
        }

        predictedChestLocations.clear();
        attemptedUntil.clear();
        stopAutomation(client, true);
    }

    private static void startTargetAutomation(Minecraft client, BlockPos target) {
        operationId++;
        automationTarget = target.immutable();
        automationGoal = null;
        activeGoals = List.of(automationTarget);
        activeGoalIndex = 0;
        phaseStartedAt = System.currentTimeMillis();
        lastAutomationStartAt = phaseStartedAt;
        pathCallbackTriggered = false;
        openAttemptTriggered = false;
        ClientUtils.setKeyMappingState(client.options.keyAttack, false);
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        RotationManager.cancelRotation();

        automationState = AutomationState.PATHING;
        startNextGoalPath(client, operationId);
    }

    private static void startNextGoalPath(Minecraft client, int opId) {
        if (opId != operationId || automationTarget == null || automationState != AutomationState.PATHING) {
            return;
        }

        if (activeGoalIndex >= activeGoals.size()) {
            failCurrentTarget(client, "MetalDetector: no walk path to predicted chest", RETRY_SAME_TARGET_AFTER_MS);
            return;
        }

        BlockPos goal = activeGoals.get(activeGoalIndex++);
        automationGoal = goal.immutable();
        phaseStartedAt = System.currentTimeMillis();
        pathCallbackTriggered = false;

        ClientUtils.sendDebugMessage("MetalDetector: walking to "
                        + automationTarget.getX() + " "
                        + automationTarget.getY() + " "
                        + automationTarget.getZ());
        PathfindingManager.startConfiguredWalk(client,
                goal.getX(), goal.getY(), goal.getZ(),
                () -> {
                    if (opId != operationId || automationState != AutomationState.PATHING) {
                        return;
                    }
                    pathCallbackTriggered = true;
                    automationState = AutomationState.ROTATING;
                    phaseStartedAt = System.currentTimeMillis();
                },
                () -> {
                    if (opId != operationId || automationState != AutomationState.PATHING) {
                        return;
                    }
                    pathCallbackTriggered = true;
                    startNextGoalPath(client, opId);
                },
                true,
                0.25);
    }

    private static void failCurrentTarget(Minecraft client, String debugReason, long retryDelayMs) {
        if (automationTarget != null) {
            markTargetAttempted(automationTarget, retryDelayMs);
        }
        ClientUtils.sendDebugMessage(debugReason);
        stopAutomation(client, true);
    }

    private static void stopAutomation(Minecraft client, boolean cancelPath) {
        operationId++;
        automationState = AutomationState.IDLE;
        automationTarget = null;
        automationGoal = null;
        activeGoals = List.of();
        activeGoalIndex = 0;
        phaseStartedAt = 0L;
        lastAutomationStartAt = System.currentTimeMillis();
        pathCallbackTriggered = false;
        openAttemptTriggered = false;

        if (client != null && client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyAttack, false);
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
        }
        RotationManager.cancelRotation();
        if (cancelPath) {
            PathfindingManager.stop(false);
        }
    }

    private static BlockPos chooseBestTarget(Minecraft client) {
        if (predictedChestLocations.isEmpty() || client.player == null) {
            return null;
        }

        Vec3 playerPos = client.player.position();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos chest : predictedChestLocations) {
            if (!canRetryTarget(chest)) {
                continue;
            }

            double score = distanceSq(playerPos, chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5);
            if (score < bestScore) {
                bestScore = score;
                best = chest;
            }
        }
        return best;
    }

    private static void startOpenAttempt(Minecraft client) {
        automationState = AutomationState.OPENING;
        openAttemptTriggered = true;
        phaseStartedAt = System.currentTimeMillis();
        ClientUtils.performAttackClick(client);
    }

    private static boolean isWithinAttackRange(Minecraft client, BlockPos target) {
        if (client.player == null) {
            return false;
        }
        Vec3 playerPos = client.player.position();
        return playerPos.distanceTo(new Vec3(
                target.getX() + 0.5,
                target.getY() + 0.5,
                target.getZ() + 0.5)) <= ATTACK_RANGE;
    }

    private static void faceTarget(Minecraft client, BlockPos target) {
        Vec3 targetCenter = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        RotationManager.forceRotation(client, targetCenter, 100L);
    }

    private static boolean isLookingAtTarget(Minecraft client, BlockPos target) {
        if (client.player == null) {
            return false;
        }
        return RotationUtils.isLookingAt(
                client.player.getYRot(),
                client.player.getXRot(),
                client.player.getEyePosition(),
                new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5),
                ROTATION_TOLERANCE);
    }

    private static boolean isCrosshairOnTarget(Minecraft client, BlockPos target) {
        if (client.player == null || client.level == null) {
            return false;
        }

        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(ATTACK_RANGE));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                client.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private static void addPredictedChest(Minecraft client, BlockPos chest) {
        for (BlockPos existing : predictedChestLocations) {
            if (existing.equals(chest)) {
                return;
            }
        }

        predictedChestLocations.add(chest.immutable());
        ClientUtils.sendDebugMessage("MetalDetector: predicted chest at "
                        + chest.getX() + " " + chest.getY() + " " + chest.getZ());
    }

    private static void removePredictedChest(BlockPos target) {
        predictedChestLocations.removeIf(existing -> existing.equals(target));
    }

    private static void markTargetAttempted(BlockPos target, long delayMs) {
        attemptedUntil.put(target.immutable(), System.currentTimeMillis() + delayMs);
    }

    private static void pruneAttemptedTargets() {
        long now = System.currentTimeMillis();
        attemptedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private static boolean canRetryTarget(BlockPos target) {
        Long retryAt = attemptedUntil.get(target);
        return retryAt == null || retryAt <= System.currentTimeMillis();
    }

    private static void applyAnchor(Minecraft client, BlockPos newAnchor, boolean announce) {
        anchor = newAnchor.immutable();
        lobbyInitialized = true;
        computeAbsoluteFromAnchor();
        if (announce) {
            ClientUtils.sendDebugMessage("MetalDetector: anchor at "
                            + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ());
        }
    }

    private static void computeAbsoluteFromAnchor() {
        absoluteChestCoords.clear();
        if (anchor == null) {
            return;
        }

        for (BlockPos rel : relativeChestCoords) {
            absoluteChestCoords.add(new BlockPos(
                    anchor.getX() - rel.getX(),
                    anchor.getY() - rel.getY(),
                    anchor.getZ() - rel.getZ()));
        }
    }

    private static BlockPos scanForNearbyAnchor(Minecraft client) {
        BlockPos scannedAnchor = scanForAnchor(client,
                AUTO_SCAN_HORIZONTAL_RADIUS, AUTO_SCAN_VERTICAL_RADIUS, AUTO_SCAN_HORIZONTAL_RADIUS);
        if (scannedAnchor == null) {
            scannedAnchor = scanForAnchor(client, 16, AUTO_SCAN_VERTICAL_RADIUS, 16);
        }
        return scannedAnchor;
    }

    private static boolean tryInitializeAnchor(Minecraft client, boolean force, boolean announce) {
        long now = System.currentTimeMillis();
        if (!force && now - lastScanAt < ANCHOR_SCAN_COOLDOWN_MS) {
            return anchor != null && !absoluteChestCoords.isEmpty();
        }

        lastScanAt = now;
        BlockPos scannedAnchor = scanForNearbyAnchor(client);
        if (scannedAnchor == null) {
            return false;
        }

        applyAnchor(client, scannedAnchor, announce);
        return true;
    }

    private static BlockPos scanForAnchor(Minecraft client, int radiusX, int radiusY, int radiusZ) {
        if (client.player == null || client.level == null) {
            return null;
        }

        BlockPos playerPos = client.player.blockPosition();
        for (int x = playerPos.getX() - radiusX; x <= playerPos.getX() + radiusX; x++) {
            for (int y = playerPos.getY() + radiusY; y >= playerPos.getY() - radiusY; y--) {
                for (int z = playerPos.getZ() - radiusZ; z <= playerPos.getZ() + radiusZ; z++) {
                    BlockState state = client.level.getBlockState(new BlockPos(x, y, z));
                    if (!isQuartzLike(state)) {
                        continue;
                    }

                    BlockPos barrierPos = new BlockPos(x, y + 13, z);
                    if (client.level.getBlockState(barrierPos).is(Blocks.BARRIER)) {
                        return verifyAnchor(client, barrierPos);
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos verifyAnchor(Minecraft client, BlockPos start) {
        if (client.level == null) {
            return start;
        }

        BlockPos.MutableBlockPos cursor = start.mutable();
        if (!client.level.getBlockState(cursor).is(Blocks.BARRIER)) {
            return cursor.immutable();
        }

        boolean moved;
        do {
            moved = false;
            if (client.level.getBlockState(cursor.offset(1, 0, 0)).is(Blocks.BARRIER)) {
                cursor.move(1, 0, 0);
                moved = true;
            }
            if (client.level.getBlockState(cursor.offset(0, -1, 0)).is(Blocks.BARRIER)) {
                cursor.move(0, -1, 0);
                moved = true;
            }
            if (client.level.getBlockState(cursor.offset(0, 0, 1)).is(Blocks.BARRIER)) {
                cursor.move(0, 0, 1);
                moved = true;
            }
        } while (moved);

        return cursor.immutable();
    }

    private static boolean isQuartzLike(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("quartz");
    }

    private static void ensureCoordinatesLoaded(Minecraft client) {
        if (coordsLoaded) {
            return;
        }

        coordsLoaded = true;
        relativeChestCoords.clear();
        relativeChestCoords.addAll(DEFAULT_RELATIVE_CHESTS);
        ClientUtils.sendDebugMessage("MetalDetector: using local chest coordinates");
    }

    private static void resetSessionState(boolean keepPredictions) {
        anchor = null;
        absoluteChestCoords.clear();
        ignoreBlockPos = null;
        lastTreasureReadPos = null;
        lobbyInitialized = false;
        lastScanAt = 0L;
        attemptedUntil.clear();
        openAttemptTriggered = false;
        if (!keepPredictions) {
            predictedChestLocations.clear();
        }
    }

    private static int getLevelIdentity(Minecraft client) {
        if (client.level == null) {
            return Integer.MIN_VALUE;
        }
        return System.identityHashCode(client.level);
    }

    private static double roundTenth(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double distanceSq(Vec3 pos, double x, double y, double z) {
        double dx = pos.x - x;
        double dy = pos.y - y;
        double dz = pos.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static String stripColors(String text) {
        return COLOR_PATTERN.matcher(text == null ? "" : text).replaceAll("");
    }

}
