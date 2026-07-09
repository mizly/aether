package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.helpers.BudgetAutopetManager;
import dev.aether.modules.inventorymanager.AutoSellManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PestTrapManager {

    public enum Operation {
        NONE,
        CLEAR,
        REFILL
    }

    private static final Pattern FULL_TRAPS_PATTERN = Pattern.compile("(?i)Full Traps:\\s*([\\d\\s,#]+)");
    private static final Pattern NO_BAIT_PATTERN = Pattern.compile("(?i)No Bait:\\s*([\\d\\s,#]+)");
    public static volatile boolean isRunning = false;
    private static volatile boolean cancelRequested = false;
    public static volatile Operation currentOperation = Operation.NONE;

    private static void beginOperation(Operation operation) {
        cancelRequested = false;
        isRunning = true;
        currentOperation = operation;
    }

    private static void finishOperation() {
        isRunning = false;
        currentOperation = Operation.NONE;
        cancelRequested = false;
        PathfindingManager.stop();
    }

    public static void start(Minecraft client) {
        if (!PestManager.arePestTrapsEnabled()) {
            ClientUtils.sendMessage("\u00A7cPest traps are disabled.", false);
            return;
        }
        if (isBlockedByPestExchange()) {
            ClientUtils.sendDebugMessage("PestTrapManager: skipping clear while pest exchange is active.");
            return;
        }
        if (isRunning) {
            ClientUtils.sendMessage("\u00A7cPest traps sequence is already running.", false);
            return;
        }

        String plot = AetherConfig.PEST_TRAPS_PLOT.get();
        ClientUtils.sendMessage("\u00A7eStarting pest traps sequence for plot " + plot, false);

        beginOperation(Operation.CLEAR);
        MacroWorkerThread.getInstance().submit("PestTraps", () -> {
            try {
                runSequence(client, plot);
            } catch (Exception e) {
                ClientUtils.sendDebugMessage("PestTrapManager error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                final boolean cancelled = cancelRequested;
                finishOperation();
                client.execute(() -> {
                    if (client.player != null && !cancelled) {
                        ClientUtils.sendMessage("\u00A7aPest traps sequence finished.", false);
                    }
                });
            }
        });
    }

    public static void startRefill(Minecraft client) {
        if (!PestManager.arePestTrapsEnabled()) {
            ClientUtils.sendMessage("\u00A7cPest traps are disabled.", false);
            return;
        }
        if (isBlockedByPestExchange()) {
            ClientUtils.sendDebugMessage("PestTrapManager: skipping refill while pest exchange is active.");
            return;
        }
        if (isRunning) {
            ClientUtils.sendMessage("\u00A7cPest traps sequence is already running.", false);
            return;
        }

        String plot = AetherConfig.PEST_TRAPS_PLOT.get();
        ClientUtils.sendMessage("\u00A7eStarting pest traps refill sequence for plot " + plot, false);

        beginOperation(Operation.REFILL);
        MacroWorkerThread.getInstance().submit("PestRefill", () -> {
            try {
                runRefillSequence(client, plot);
            } catch (Exception e) {
                ClientUtils.sendDebugMessage("PestRefillManager error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                final boolean cancelled = cancelRequested;
                finishOperation();
                client.execute(() -> {
                    if (client.player != null && !cancelled) {
                        ClientUtils.sendMessage("\u00A7aPest traps refill sequence finished.", false);
                    }
                });
            }
        });
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
            List<Integer> fullTraps = getFullTrapsFromTab(client);
            if (fullTraps.isEmpty()) {
                ClientUtils.sendDebugMessage("No more full traps detected in tablist.");
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
                Entity trapMarker = findTrapEntity(client, trapId);
                if (trapMarker == null) {
                    ClientUtils.sendDebugMessage("Could not find armor stand for trap #" + trapId);
                    continue;
                }

                if (AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.get()) {
                    ensureMosquitoEquipped(client);
                    if (!isRunning || shouldAbort()) {
                        return;
                    }
                }

                Vec3 trapEyePos = trapMarker.position().add(0, trapMarker.getEyeHeight() - 0.5, 0);
                RotationManager.initiateRotation(client, trapEyePos, 200);
                MacroWorkerThread.sleep(250);

                ClientUtils.sendDebugMessage("Interacting with trap entity #" + trapId
                        + " (dist=" + String.format("%.2f", Math.sqrt(client.player.distanceToSqr(trapMarker))) + ")");

                boolean guiOpened = false;
                for (int attempt = 0; attempt < 3 && !guiOpened && isRunning && !shouldAbort(); attempt++) {
                    if (attempt > 0) {
                        Vec3 retryTarget = getTrapInteractTarget(trapEyePos, attempt);
                        ClientUtils.sendDebugMessage("Retry interact attempt " + (attempt + 1) + " using y offset "
                                        + String.format("%.1f", retryTarget.y - trapEyePos.y));
                        RotationManager.initiateRotation(client, retryTarget, 150);
                        MacroWorkerThread.sleep(200);
                    }

                    client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUse, true));
                    MacroWorkerThread.sleep(100);
                    client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUse, false));

                    long guiDeadline = System.currentTimeMillis() + 1500;
                    while (System.currentTimeMillis() < guiDeadline && isRunning && !shouldAbort()) {
                        if (client.screen instanceof AbstractContainerScreen<?> screen) {
                            String title = screen.getTitle().getString().toLowerCase();
                            if (title.contains("trap")) {
                                guiOpened = true;
                                break;
                            }
                        }
                        MacroWorkerThread.sleep(100);
                    }
                }

                if (shouldAbort()) {
                    return;
                }

                if (guiOpened && client.screen instanceof AbstractContainerScreen<?> screen) {
                    MacroWorkerThread.sleep(500);

                    final AbstractContainerScreen<?> finalScreen = screen;
                    client.execute(() -> {
                        int releaseSlot = findReleasePestsSlot(finalScreen);
                        if (releaseSlot != -1) {
                            ClientUtils.sendDebugMessage("Clicking 'Release All Pests' button at slot " + releaseSlot);
                            ClientUtils.performSlotClick(client, finalScreen, releaseSlot, 0, ContainerInput.PICKUP);
                        } else {
                            ClientUtils.sendDebugMessage("Could not find 'Release All Pests' button.");
                        }
                    });

                    MacroWorkerThread.sleep(200);
                    waitForTrapGuiClosed(client);
                    ensurePetEquippedAfterTrapOpen(client);
                    MacroWorkerThread.sleep(200);
                    clearedThisPass++;
                } else {
                    ClientUtils.sendDebugMessage("Failed to open trap GUI for #" + trapId
                            + " (dist=" + String.format("%.2f", Math.sqrt(client.player.distanceToSqr(trapMarker))) + ")");
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
                Entity trapMarker = findTrapEntity(client, trapId);
                if (trapMarker == null) {
                    ClientUtils.sendDebugMessage("Could not find armor stand for trap #" + trapId);
                    continue;
                }

                Vec3 trapEyePos = trapMarker.position().add(0, trapMarker.getEyeHeight() - 0.5, 0);
                RotationManager.initiateRotation(client, trapEyePos, 200);
                MacroWorkerThread.sleep(250);

                boolean guiOpened = false;
                for (int attempt = 0; attempt < 3 && !guiOpened && isRunning && !shouldAbort(); attempt++) {
                    ensureGuiClosed(client);
                    if (attempt > 0) {
                        Vec3 retryTarget = getTrapInteractTarget(trapEyePos, attempt);
                        ClientUtils.sendDebugMessage("Retry interact attempt " + (attempt + 1) + " using y offset "
                                        + String.format("%.1f", retryTarget.y - trapEyePos.y));
                        RotationManager.initiateRotation(client, retryTarget, 150);
                        MacroWorkerThread.sleep(200);
                    }

                    client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUse, true));
                    MacroWorkerThread.sleep(100);
                    client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyUse, false));

                    long guiDeadline = System.currentTimeMillis() + 1500;
                    while (System.currentTimeMillis() < guiDeadline && isRunning && !shouldAbort()) {
                        if (client.screen instanceof AbstractContainerScreen<?> screen) {
                            String title = screen.getTitle().getString().toLowerCase();
                            if (title.contains("trap")){
                                guiOpened = true;
                                break;
                            }
                        }
                        MacroWorkerThread.sleep(100);
                    }

                    if (!guiOpened) {
                        ensureGuiClosed(client);
                    }
                }

                if (shouldAbort()) {
                    return;
                }

                if (guiOpened && client.screen instanceof AbstractContainerScreen<?> screen) {
                    MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true));

                    final AbstractContainerScreen<?> finalScreen = screen;
                    boolean success = false;

                    int inventoryBaitSlot = findBaitInInventory(finalScreen, baitMaterial);
                    int trapBaitSlot = findBaitSlot(finalScreen);

                    if (inventoryBaitSlot != -1 && trapBaitSlot != -1) {
                        ClientUtils.sendDebugMessage("Refilling trap with " + baitMaterial + " from slot " + inventoryBaitSlot
                                        + " to bait slot " + trapBaitSlot);

                        client.execute(() -> ClientUtils.performSlotClick(client, finalScreen, inventoryBaitSlot, 0,
                                ContainerInput.PICKUP));
            MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));

                        client.execute(() -> ClientUtils.performSlotClick(client, finalScreen, trapBaitSlot, 0,
                                ContainerInput.PICKUP));
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
                            + " (dist=" + String.format("%.2f", Math.sqrt(client.player.distanceToSqr(trapMarker))) + ")");
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
        List<Integer> trapIds = new ArrayList<>();
        List<String> lines = TablistUtils.getRawTabLines(client);

        for (String line : lines) {
            Matcher m = NO_BAIT_PATTERN.matcher(line);
            if (m.find()) {
                String content = m.group(1);
                if (content.equalsIgnoreCase("None")) {
                    return trapIds;
                }

                Pattern idPattern = Pattern.compile("#(\\d+)");
                Matcher idMatcher = idPattern.matcher(content);
                while (idMatcher.find()) {
                    try {
                        trapIds.add(Integer.parseInt(idMatcher.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return trapIds;
    }

    private static void teleportToTrapPlotIfNeeded(Minecraft client, String plot) throws InterruptedException {
        String currentPlot = ClientUtils.getCurrentPlot(client);
        String freshChatPlot = CommandUtils.getFreshKnownPlotChat();
        boolean scoreboardMatch = plot != null && plot.equalsIgnoreCase(currentPlot);
        boolean chatMatch = plot != null && plot.equalsIgnoreCase(freshChatPlot);

        if (scoreboardMatch || chatMatch) {
            String source = scoreboardMatch ? "scoreboard" : "chat";
            ClientUtils.sendDebugMessage("Already on trap plot " + plot + " (via " + source + "), skipping plottp.");
            return;
        }

        CommandUtils.initiatePlotTp(client, plot);
        MacroWorkerThread.sleep(2000);
    }

    public static boolean isBlockedByPestExchange() {
        return PestExchangeManager.isExchanging || AutoPestExchangeManager.isRunning();
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
        List<Integer> trapIds = new ArrayList<>();
        List<String> lines = TablistUtils.getRawTabLines(client);

        for (String line : lines) {
            Matcher m = FULL_TRAPS_PATTERN.matcher(line);
            if (m.find()) {
                String content = m.group(1);
                if (content.equalsIgnoreCase("None")) {
                    return trapIds;
                }

                Pattern idPattern = Pattern.compile("#(\\d+)");
                Matcher idMatcher = idPattern.matcher(content);
                while (idMatcher.find()) {
                    try {
                        trapIds.add(Integer.parseInt(idMatcher.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return trapIds;
    }

    private static int findVacuumHotbarSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }

        ItemStack current = client.player.getMainHandItem();
        if (!current.isEmpty() && current.getHoverName().getString().toLowerCase().contains("vacuum")) {
            return ((AccessorInventory) client.player.getInventory()).getSelected();
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase().contains("vacuum")) {
                return i;
            }
        }
        return -1;
    }

    private static Vec3 getTrapInteractTarget(Vec3 baseTarget, int attempt) {
        double yOffset = switch (attempt) {
            case 1 -> -0.3D;
            case 2 -> 0.3D;
            default -> 0.0D;
        };
        return baseTarget.add(0.0D, yOffset, 0.0D);
    }

    private static Entity findTrapEntity(Minecraft client, int trapId) {
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
        return closest;
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
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
                return;
            }
            String title = screen.getTitle().getString().toLowerCase();
            if (!title.contains("trap")) {
                return;
            }
            MacroWorkerThread.sleep(25);
        }
        ensureGuiClosed(client);
    }

    private static void ensureGuiClosed(Minecraft client) {
        if (client == null || client.screen == null) {
            return;
        }
        client.execute(() -> {
            if (client.player != null) {
                client.player.closeContainer();
            }
        });
        MacroWorkerThread.sleep(200);
    }
}
