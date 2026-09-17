package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.helpers.BudgetAutopetManager;
import dev.aether.modules.inventorymanager.AutoSellManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import dev.aether.util.CommandUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class PestTrapManager {

    public enum Operation {
        NONE,
        CLEAR,
        REFILL
    }

    private static final Pattern FULL_TRAPS_PATTERN = Pattern.compile("(?i)Full Traps:\\s*(.*)");
    private static final Pattern NO_BAIT_PATTERN = Pattern.compile("(?i)No Bait:\\s*(.*)");
    private static final float TRAP_AIM_TOLERANCE_DEGREES = 3.0f;
    private static final long RELEASED_PEST_KILL_TIMEOUT_MS = 5_000L;
    private static volatile boolean isRunning = false;
    private static volatile boolean cancelRequested = false;
    private static volatile Operation currentOperation = Operation.NONE;

    public static boolean isRunning() {
        return isRunning;
    }

    public static Operation getCurrentOperation() {
        return currentOperation;
    }

    private static synchronized boolean beginOperation(Operation operation) {
        if (isRunning) {
            return false;
        }
        cancelRequested = false;
        isRunning = true;
        currentOperation = operation;
        return true;
    }

    private static void finishOperation() {
        isRunning = false;
        currentOperation = Operation.NONE;
        cancelRequested = false;
        PathfindingManager.stop();
    }

    public static void start(Minecraft client) {
        startAsync(client, Operation.CLEAR);
    }

    public static void startRefill(Minecraft client) {
        startAsync(client, Operation.REFILL);
    }

    private static void startAsync(Minecraft client, Operation operation) {
        if (!PestManager.arePestTrapsEnabled()) {
            ClientUtils.sendMessage("\u00A7cPest traps are disabled.", false);
            return;
        }
        if (isBlockedByPestExchange()) {
            ClientUtils.sendDebugMessage("PestTrapManager: skipping " + operationName(operation)
                    + " while pest exchange is active.");
            return;
        }
        if (!beginOperation(operation)) {
            ClientUtils.sendMessage("\u00A7cPest traps sequence is already running.", false);
            return;
        }

        String plot = AetherConfig.PEST_TRAPS_PLOT.get();
        ClientUtils.sendMessage("\u00A7eStarting pest traps " + operationName(operation)
                + " sequence for plot " + plot, false);
        MacroWorkerThread.getInstance().submit("PestTraps-" + operationName(operation),
                () -> runClaimedOperation(client, plot, operation, true));
    }

    static boolean runBlocking(Minecraft client, String plot, Operation operation) {
        if (!PestManager.arePestTrapsEnabled() || isBlockedByPestExchange() || !beginOperation(operation)) {
            return false;
        }
        runClaimedOperation(client, plot, operation, false);
        return true;
    }

    private static void runClaimedOperation(
            Minecraft client,
            String plot,
            Operation operation,
            boolean notifyCompletion
    ) {
        try {
            if (operation == Operation.CLEAR) {
                runSequence(client, plot);
            } else if (operation == Operation.REFILL) {
                runRefillSequence(client, plot);
            }
        } catch (Exception error) {
            ClientUtils.sendDebugMessage("PestTrapManager " + operationName(operation)
                    + " error: " + error.getMessage());
            error.printStackTrace();
        } finally {
            boolean cancelled = cancelRequested;
            finishOperation();
            if (notifyCompletion && !cancelled) {
                PestClientThread.run(client, () -> {
                    if (client.player != null) {
                        ClientUtils.sendMessage("\u00A7aPest traps " + operationName(operation)
                                + " sequence finished.", false);
                    }
                });
            }
        }
    }

    private static String operationName(Operation operation) {
        return operation.name().toLowerCase();
    }

    public static void cancel(Minecraft client) {
        cancelRequested = true;
        isRunning = false;
        currentOperation = Operation.NONE;
        ensureGuiClosed(client);
        PathfindingManager.stop();
        RotationManager.cancelRotation();
    }

    public static void runSequence(Minecraft client, String plot) throws InterruptedException {
        currentOperation = Operation.CLEAR;
        Set<Integer> clearedTrapIds = new HashSet<>();
        if (shouldAbort() || abortForPestExchange(client, "clear")) {
            return;
        }

        AutoSellManager.checkBeforePestTraps(client, true, false);
        if (shouldAbort()) {
            return;
        }

        teleportToTrapPlotIfNeeded(client, plot);
        if (shouldAbort()) {
            return;
        }

        walkToTrapsIfConfigured(client);
        if (shouldAbort()) {
            return;
        }

        client.execute(() -> {
            int slot = findVacuumHotbarSlot(client);
            if (slot != -1) {
                FailsafeManager.selectHotbarSlot(client, slot);
            }
        });

        while (isRunning && !shouldAbort()) {
            if (abortForPestExchange(client, "clear")) {
                return;
            }
            ensureGuiClosed(client);
            List<Integer> fullTraps = getFullTrapsFromTab(client).stream()
                    .filter(trapId -> !clearedTrapIds.contains(trapId))
                    .toList();
            if (fullTraps.isEmpty()) {
                ClientUtils.sendDebugMessage(clearedTrapIds.isEmpty()
                        ? "No more full traps detected in tablist."
                        : "No uncleared full traps detected in tablist.");
                break;
            }

            ClientUtils.sendMessage("Found " + fullTraps.size() + " full traps: " + fullTraps);
            int clearedThisPass = 0;

            for (int trapId : fullTraps) {
                if (!isRunning || shouldAbort() || abortForPestExchange(client, "clear")) {
                    return;
                }

                ensureGuiClosed(client);
                ClientUtils.sendDebugMessage("Looking for trap #" + trapId);
                TrapTarget trapTarget = PestClientThread.call(client, () -> findTrapTarget(client, trapId), null);
                if (trapTarget == null) {
                    ClientUtils.sendDebugMessage("Could not find armor stand for trap #" + trapId);
                    continue;
                }

                if (AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.get()) {
                    ensureMosquitoEquipped(client);
                    if (!isRunning || shouldAbort()) {
                        return;
                    }
                }

                Vec3 trapEyePos = trapTarget.eyePosition();
                ClientUtils.sendDebugMessage("Interacting with trap entity #" + trapId
                        + " (dist=" + String.format("%.2f", trapTarget.distance()) + ")");

                boolean guiOpened = openTrapGui(client, trapId, trapEyePos);

                if (shouldAbort()) {
                    return;
                }

                if (guiOpened) {
                    MacroWorkerThread.sleep(500);
                    int releaseSlot = PestClientThread.call(client, () -> {
                        if (client.screen instanceof AbstractContainerScreen<?> screen) {
                            return findReleasePestsSlot(screen);
                        }
                        return -1;
                    }, -1);
                    boolean released = false;
                    Set<Integer> preReleasePestIds = Set.of();
                    if (releaseSlot != -1) {
                        ClientUtils.sendDebugMessage("Clicking 'Release All Pests' button at slot " + releaseSlot);
                        preReleasePestIds = snapshotLivePestIds(client);
                        clickCurrentScreenSlot(client, releaseSlot);
                        clearedTrapIds.add(trapId);
                        clearedThisPass++;
                        released = true;
                    } else {
                        ClientUtils.sendDebugMessage("Could not find 'Release All Pests' button.");
                    }

                    MacroWorkerThread.sleep(200);
                    waitForTrapGuiClosed(client);
                    ensurePetEquippedAfterTrapOpen(client);
                    if (released) {
                        awaitReleasedPestKill(client, preReleasePestIds);
                    } else {
                        MacroWorkerThread.sleep(200);
                    }
                } else {
                    ClientUtils.sendDebugMessage("Failed to open trap GUI for #" + trapId
                            + " (dist=" + String.format("%.2f", trapTarget.distance()) + ")");
                }
            }

            if (clearedThisPass == 0) {
                ClientUtils.sendDebugMessage("No traps cleared this pass - stopping.");
                break;
            }

            MacroWorkerThread.sleep(500);
        }
    }

    public static void runRefillSequence(Minecraft client, String plot) throws InterruptedException {
        currentOperation = Operation.REFILL;
        if (shouldAbort() || abortForPestExchange(client, "refill")) {
            return;
        }

        List<Integer> emptyTraps = getNoBaitTrapsFromTab(client);
        if (emptyTraps.isEmpty()) {
            ClientUtils.sendDebugMessage("No empty traps found (no bait).");
            return;
        }

        String baitMaterial = AetherConfig.PEST_TRAPS_BAIT_MATERIAL.get();
        int baitAmount = Math.max(1, AetherConfig.PEST_TRAPS_BAIT_AMOUNT.get());
        int baitNeeded = emptyTraps.size() * baitAmount;
        ClientUtils.sendDebugMessage("Buying " + baitNeeded + " " + baitMaterial + "...");
        boolean bought = dev.aether.util.BazaarUtils.executeBuy(client, baitMaterial, baitNeeded);
        if (!bought || shouldAbort()) {
            if (!shouldAbort()) {
                ClientUtils.sendDebugMessage("Failed to buy " + baitMaterial + ". Aborting.");
            }
            return;
        }

        teleportToTrapPlotIfNeeded(client, plot);
        if (shouldAbort()) {
            return;
        }
        walkToTrapsIfConfigured(client);

        while (isRunning && !shouldAbort()) {
            if (abortForPestExchange(client, "refill")) {
                return;
            }
            ensureGuiClosed(client);
            List<Integer> targets = getNoBaitTrapsFromTab(client);
            if (targets.isEmpty()) {
                ClientUtils.sendDebugMessage("No more empty traps (no bait) detected in tablist.");
                break;
            }

            int clearedThisPass = 0;

            for (int trapId : targets) {
                if (!isRunning || shouldAbort() || abortForPestExchange(client, "refill")) {
                    return;
                }

                ensureGuiClosed(client);
                ClientUtils.sendDebugMessage("Looking for empty trap #" + trapId);
                TrapTarget trapTarget = PestClientThread.call(client, () -> findTrapTarget(client, trapId), null);
                if (trapTarget == null) {
                    ClientUtils.sendDebugMessage("Could not find armor stand for trap #" + trapId);
                    continue;
                }

                Vec3 trapEyePos = trapTarget.eyePosition();
                boolean guiOpened = openTrapGui(client, trapId, trapEyePos);

                if (shouldAbort()) {
                    return;
                }

                if (guiOpened) {
                    MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true));
                    boolean success = false;
                    int[] baitSlots = PestClientThread.call(client, () -> {
                        if (client.screen instanceof AbstractContainerScreen<?> screen) {
                            return new int[] { findBaitInInventory(screen, baitMaterial), findBaitSlot(screen) };
                        }
                        return new int[] { -1, -1 };
                    }, new int[] { -1, -1 });
                    int inventoryBaitSlot = baitSlots[0];
                    int trapBaitSlot = baitSlots[1];

                    if (inventoryBaitSlot != -1 && trapBaitSlot != -1) {
                        ClientUtils.sendDebugMessage("Refilling trap with " + baitMaterial + " from slot " + inventoryBaitSlot
                                        + " to bait slot " + trapBaitSlot);
                        clickCurrentScreenSlot(client, inventoryBaitSlot);
                        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));
                        clickCurrentScreenSlot(client, trapBaitSlot);
                        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));

                        client.execute(() -> client.player.closeContainer());
                        success = true;
                    } else {
                        ClientUtils.sendDebugMessage("Could not find bait item or bait slot. Item=" + inventoryBaitSlot
                                        + ", BaitSlot=" + trapBaitSlot);
                        client.execute(() -> client.player.closeContainer());
                    }

                    if (success) {
                        clearedThisPass++;
                    }
                    MacroWorkerThread.sleep(1000);
                } else {
                    ClientUtils.sendDebugMessage("Failed to open trap GUI for #" + trapId
                            + " (dist=" + String.format("%.2f", trapTarget.distance()) + ")");
                    ensureGuiClosed(client);
                }
            }

            if (clearedThisPass == 0) {
                break;
            }
            MacroWorkerThread.sleep(500);
        }
    }

    public static List<Integer> getNoBaitTrapsFromTab(Minecraft client) {
        if (client != null && !client.isSameThread()) {
            return PestClientThread.call(client, () -> getNoBaitTrapsFromTab(client), List.of());
        }
        return PestTrapTabParser.parseTrapIds(TablistUtils.getRawTabLines(client), NO_BAIT_PATTERN);
    }

    private static void walkToTrapsIfConfigured(Minecraft client) throws InterruptedException {
        if (!AetherConfig.PEST_TRAPS_PATHFIND.get()) {
            return;
        }

        int x = AetherConfig.PEST_TRAPS_X.get();
        int y = AetherConfig.PEST_TRAPS_Y.get();
        int z = AetherConfig.PEST_TRAPS_Z.get();
        if (y == 0) {
            ClientUtils.sendDebugMessage("PestTrapManager: trap position not set, skipping pathfind.");
            return;
        }
        boolean alreadyNear = PestClientThread.call(client,
                () -> client.player != null
                        && client.player.position().distanceToSqr(x + 0.5, y + 0.5, z + 0.5) <= 9.0,
                false);
        if (alreadyNear) {
            ClientUtils.sendDebugMessage("PestTrapManager: already near the trap position.");
            return;
        }

        ClientUtils.sendDebugMessage("PestTrapManager: walking to traps at " + x + ", " + y + ", " + z);
        client.execute(() -> PathfindingManager.startPathfind(client, x, y, z, false));

        MacroWorkerThread.sleep(1000);
        long deadline = System.currentTimeMillis() + 30_000L;
        while (PathfindingManager.isNavigating() && System.currentTimeMillis() < deadline
                && isRunning && !shouldAbort()) {
            MacroWorkerThread.sleep(200);
        }

        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop();
            ClientUtils.sendDebugMessage("PestTrapManager: pathfind to traps timed out, continuing anyway.");
        }
        MacroWorkerThread.sleep(300);
    }

    private static void teleportToTrapPlotIfNeeded(Minecraft client, String plot) throws InterruptedException {
        String currentPlot = ClientUtils.getCurrentPlot();
        String freshChatPlot = CommandUtils.getFreshKnownPlotChat();
        boolean scoreboardMatch = plot != null && plot.equalsIgnoreCase(currentPlot);
        boolean chatMatch = plot != null && plot.equalsIgnoreCase(freshChatPlot);

        if (scoreboardMatch || chatMatch) {
            String source = scoreboardMatch ? "scoreboard" : "chat";
            ClientUtils.sendDebugMessage("Already on trap plot " + plot + " (via " + source + "), skipping plottp.");
            return;
        }

        Vec3 posBefore = PestClientThread.call(
                client, () -> client.player != null ? client.player.position() : null, null);
        CommandUtils.initiatePlotTp(plot);
        MacroWorkerThread.sleep(600);

        long tpDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < tpDeadline && isRunning && !shouldAbort()) {
            boolean teleported = posBefore == null || PestClientThread.call(client,
                    () -> client.player != null && client.player.position().distanceTo(posBefore) > 5,
                    false);
            if (teleported) {
                break;
            }
            MacroWorkerThread.sleep(200);
        }
        MacroWorkerThread.sleep(400);
    }

    public static boolean isBlockedByPestExchange() {
        return PestExchangeManager.isExchanging() || AutoPestExchangeManager.isRunning();
    }

    private static boolean abortForPestExchange(Minecraft client, String operation) {
        if (!isBlockedByPestExchange()) {
            return false;
        }

        ClientUtils.sendDebugMessage("PestTrapManager: aborting trap " + operation + " because pest exchange is active.");
        isRunning = false;
        ensureGuiClosed(client);
        PathfindingManager.stop();
        return true;
    }

    private static boolean shouldAbort() {
        return cancelRequested || MacroWorkerThread.getInstance().isCancelled();
    }

    public static List<Integer> getFullTrapsFromTab(Minecraft client) {
        if (client != null && !client.isSameThread()) {
            return PestClientThread.call(client, () -> getFullTrapsFromTab(client), List.of());
        }
        return PestTrapTabParser.parseTrapIds(TablistUtils.getRawTabLines(client), FULL_TRAPS_PATTERN);
    }

    private static int findVacuumHotbarSlot(Minecraft client) {
        return PestLoadoutHelper.findVacuumHotbarSlot(client);
    }

    private static boolean openTrapGui(Minecraft client, int trapId, Vec3 trapEyePos) throws InterruptedException {
        for (int attempt = 0; attempt < 3 && isRunning && !shouldAbort(); attempt++) {
            ensureGuiClosed(client);
            Vec3 target = getTrapInteractTarget(trapEyePos, attempt);
            if (attempt > 0) {
                ClientUtils.sendDebugMessage("Retry interact attempt " + (attempt + 1) + " using y offset "
                        + String.format("%.1f", target.y - trapEyePos.y));
            }

            int rotationTime = attempt == 0 ? 200 : 150;
            if (!aimAtTrap(client, target, rotationTime)) {
                ClientUtils.sendDebugMessage("Could not aim at trap #" + trapId + ", retrying.");
                continue;
            }
            PestClientThread.run(client, () -> ClientUtils.setKeyMappingState(client.options.keyUse, true));
            MacroWorkerThread.sleep(100);
            PestClientThread.run(client, () -> ClientUtils.setKeyMappingState(client.options.keyUse, false));

            long deadline = System.currentTimeMillis() + 1_500L;
            while (System.currentTimeMillis() < deadline && isRunning && !shouldAbort()) {
                boolean open = PestClientThread.call(client,
                        () -> client.screen instanceof AbstractContainerScreen<?> screen
                                && screen.getTitle().getString().toLowerCase().contains("trap"),
                        false);
                if (open) {
                    return true;
                }
                MacroWorkerThread.sleep(100);
            }
        }
        ensureGuiClosed(client);
        return false;
    }

    // initiateRotation silently no-ops when another rotation holds the lock or the failsafe suppresses
    // it, so aim is verified against the player's real angles rather than trusting the call landed
    private static boolean aimAtTrap(Minecraft client, Vec3 target, int rotationTime)
            throws InterruptedException {
        long freeDeadline = System.currentTimeMillis() + 1_000L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < freeDeadline
                && isRunning && !shouldAbort()) {
            MacroWorkerThread.sleep(50);
        }
        if (RotationManager.isRotating()) {
            return false;
        }

        PestClientThread.run(client, () -> RotationManager.initiateRotation(client, target, rotationTime));
        long settleDeadline = System.currentTimeMillis() + rotationTime + 800L;
        while (System.currentTimeMillis() < settleDeadline && isRunning && !shouldAbort()) {
            if (!RotationManager.isRotating() && isAimedAtTrap(client, target)) {
                MacroWorkerThread.sleep(150);
                return isAimedAtTrap(client, target);
            }
            MacroWorkerThread.sleep(25);
        }

        RotationManager.cancelRotation();
        return false;
    }

    private static boolean isAimedAtTrap(Minecraft client, Vec3 target) {
        return PestClientThread.call(client, () -> client.player != null
                && RotationUtils.isLookingAt(
                        client.player.getYRot(),
                        client.player.getXRot(),
                        client.player.getEyePosition(),
                        target,
                        TRAP_AIM_TOLERANCE_DEGREES), false);
    }

    private static void clickCurrentScreenSlot(Minecraft client, int slot) {
        PestClientThread.run(client, () -> {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                ClientUtils.performSlotClick(screen, slot, 0, ContainerInput.PICKUP);
            }
        });
    }

    private static Vec3 getTrapInteractTarget(Vec3 baseTarget, int attempt) {
        double yOffset = switch (attempt) {
            case 1 -> -0.3D;
            case 2 -> 0.3D;
            default -> 0.0D;
        };
        return baseTarget.add(0.0D, yOffset, 0.0D);
    }

    private static TrapTarget findTrapTarget(Minecraft client, int trapId) {
        if (client.level == null) {
            return null;
        }
        String target = "#" + trapId;
        Entity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand)) {
                continue;
            }

            String name = entity.getName().getString();
            String cleanName = name.replaceAll("(?i)\u00A7.", "").trim().toLowerCase();

            if (cleanName.endsWith(target) || cleanName.contains("trap " + target)) {
                double dist = entity.distanceToSqr(client.player);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = entity;
                }
            }
        }
        if (closest == null) {
            return null;
        }
        Vec3 eyePosition = closest.position().add(0, closest.getEyeHeight() - 0.5, 0);
        return new TrapTarget(eyePosition, Math.sqrt(closestDist));
    }

    private static int findReleasePestsSlot(AbstractContainerScreen<?> screen) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            String name = stack.getHoverName().getString().toLowerCase();

            if (name.contains("release all pests")) {
                String itemId = stack.getItem().toString().toLowerCase();
                if (itemId.contains("lime") || itemId.contains("green") || itemId.contains("emerald")
                        || itemId.contains("terracotta")) {
                    return i;
                }
                return i;
            }
        }
        return -1;
    }

    private static int findBaitSlot(AbstractContainerScreen<?> screen) {
        int containerSize = screen.getMenu().slots.size() - 36;
        for (int i = 0; i < containerSize; i++) {
            Slot slot = screen.getMenu().slots.get(i);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase().contains("trap bait")) {
                return i;
            }
        }
        return -1;
    }

    private static int findBaitInInventory(AbstractContainerScreen<?> screen, String baitMaterial) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            String name = stack.getHoverName().getString().toLowerCase();
            if (name.contains(baitMaterial.toLowerCase())) {
                return i;
            }
        }
        return -1;
    }

    private static void ensureMosquitoEquipped(Minecraft client) throws InterruptedException {
        if (!AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.get()) {
            return;
        }
        BudgetAutopetManager.equipPetByName(client, "Mosquito", "pest traps");
    }

    private static void ensurePetEquippedAfterTrapOpen(Minecraft client) throws InterruptedException {
        if (!AetherConfig.AUTO_PET_AFTER_TRAP_OPEN.get()) {
            return;
        }
        BudgetAutopetManager.equipPetByName(client, AetherConfig.AUTO_PET_AFTER_TRAP_OPEN_PET.get(),
                "pest trap release");
    }

    private static void waitForTrapGuiClosed(Minecraft client) {
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline && isRunning && !shouldAbort()) {
            boolean trapScreenOpen = PestClientThread.call(client,
                    () -> client.screen instanceof AbstractContainerScreen<?> screen
                            && screen.getTitle().getString().toLowerCase().contains("trap"),
                    false);
            if (!trapScreenOpen) {
                return;
            }
            MacroWorkerThread.sleep(25);
        }
        ensureGuiClosed(client);
    }

    private static void ensureGuiClosed(Minecraft client) {
        if (client == null) {
            return;
        }
        boolean closed = PestClientThread.call(client, () -> {
            if (client.player != null && client.screen != null) {
                client.player.closeContainer();
                return true;
            }
            return false;
        }, false);
        if (closed && !client.isSameThread()) {
            MacroWorkerThread.sleep(200);
        }
    }

    private static Set<Integer> snapshotLivePestIds(Minecraft client) {
        Set<Integer> ids = new HashSet<>();
        ids.addAll(PestClientThread.call(client,
                () -> PestTargetTracker.getLoadedPests(client).stream()
                        .map(Entity::getId)
                        .toList(),
                List.of()));
        return ids;
    }

    // the interact right-click that opens a trap also vacuums the stack that trap releases, so one
    // confirmed kill means the release landed and the next trap's interact ray is clear again
    private static void awaitReleasedPestKill(Minecraft client, Set<Integer> preReleasePestIds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + RELEASED_PEST_KILL_TIMEOUT_MS;
        List<Entity> released = List.of();
        while (System.currentTimeMillis() < deadline && isRunning && !shouldAbort()) {
            released = PestClientThread.call(client,
                    () -> PestTargetTracker.getLoadedPests(client).stream()
                            .filter(pest -> !preReleasePestIds.contains(pest.getId()))
                            .toList(),
                    List.of());
            if (!released.isEmpty()) {
                break;
            }
            MacroWorkerThread.sleep(50);
        }

        if (released.isEmpty()) {
            ClientUtils.sendDebugMessage("No released pests appeared after the trap release, continuing.");
            return;
        }

        List<Entity> tracked = released;
        ClientUtils.sendDebugMessage("Waiting for one of " + tracked.size() + " released pests to die.");
        while (System.currentTimeMillis() < deadline && isRunning && !shouldAbort()) {
            boolean killed = PestClientThread.call(client,
                    () -> tracked.stream().anyMatch(PestTrapManager::isPestDead), false);
            if (killed) {
                ClientUtils.sendDebugMessage("Released pest confirmed dead, moving to the next trap.");
                return;
            }
            MacroWorkerThread.sleep(50);
        }
        ClientUtils.sendDebugMessage("Timed out waiting for a released pest to die, moving on anyway.");
    }

    private static boolean isPestDead(Entity entity) {
        return entity.isRemoved() || entity instanceof LivingEntity living && living.isDeadOrDying();
    }

    private record TrapTarget(Vec3 eyePosition, double distance) {
    }
}
