package dev.aether.modules.inventorymanager;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.PestPrepSwapManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class AutoSellManager {
    public static volatile boolean isSelling = false;
    public static volatile boolean isPreparingToSell = false;
    public static volatile boolean isTriggeredBeforeVisitors = false;
    private static volatile boolean cancelRequested = false;
    private static boolean visitorResumeIntent = true;
    private static boolean wasRunningBeforeSell = false;
    private static boolean wasTriggeredManually = false;

    private static long thresholdMetStartTime = 0;
    private static volatile long interactionTime = 0;
    private static volatile boolean awaitingFirstGuiClick = false;
    private static long lastSellTime = 0;
    private static final long SELL_COOLDOWN_MS = 30000;

    public static void reset() {
        cancelRequested = false;
        isSelling = false;
        isPreparingToSell = false;
        isTriggeredBeforeVisitors = false;
        wasRunningBeforeSell = false;
        wasTriggeredManually = false;
        thresholdMetStartTime = 0;
        interactionTime = 0;
        awaitingFirstGuiClick = false;
        lastSellTime = 0;
    }

    public static void cancel(Minecraft client) {
        cancelRequested = true;
        isSelling = false;
        isPreparingToSell = false;
        isTriggeredBeforeVisitors = false;
        thresholdMetStartTime = 0;
        awaitingFirstGuiClick = false;
        interactionTime = 0;
        if (client != null) {
            client.execute(() -> {
                if (client.player != null && client.screen != null) {
                    client.player.closeContainer();
                }
            });
        }
    }

    public static void handleNpcCoinLimit(Minecraft client) {
        boolean resumeFarming = MacroStateManager.getCurrentState() == MacroState.State.AUTOSELLING
                && wasRunningBeforeSell
                && !wasTriggeredManually;

        cancel(client);
        AetherConfig.AUTO_SELL.set(false);
        AetherConfig.save();

        if (MacroStateManager.getCurrentState() == MacroState.State.AUTOSELLING) {
            if (resumeFarming) {
                MacroStateManager.setCurrentState(MacroState.State.FARMING);
                if (client != null) {
                    client.execute(() -> dev.aether.macro.FarmingMacroManager.enable(client,
                            dev.aether.macro.FarmingMacroManager.createMacroFromConfig()));
                }
            } else {
                MacroStateManager.setCurrentState(MacroState.State.OFF);
            }
        }

        ClientUtils.sendMessage("\u00A7cNPC shop coin limit reached. AutoSell disabled.", false);
    }

    private static boolean shouldAbort() {
        return cancelRequested || MacroWorkerThread.getInstance().isCancelled();
    }

    public static double getInventoryFullnessRatio(Minecraft client) {
        if (client.player == null) {
            return 0.0;
        }
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) {
                emptySlots++;
            }
        }
        return ((36 - emptySlots) / 36.0) * 100.0;
    }

    public static long getThresholdMetElapsedMs() {
        if (thresholdMetStartTime == 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - thresholdMetStartTime);
    }

    public static long getSellCooldownRemainingMs() {
        return Math.max(0L, SELL_COOLDOWN_MS - (System.currentTimeMillis() - lastSellTime));
    }

    public static boolean checkBeforeVisitors(Minecraft client, boolean force, boolean resumeAfter) {
        if (!AetherConfig.AUTO_SELL_BEFORE_VISITORS.get()) {
            return false;
        }

        visitorResumeIntent = resumeAfter;

        if (System.currentTimeMillis() - lastSellTime < SELL_COOLDOWN_MS) {
            if (AetherConfig.SHOW_DEBUG.get()) {
                ClientUtils.sendDebugMessage("checkBeforeVisitors: Cooldown active ("
                        + (SELL_COOLDOWN_MS - (System.currentTimeMillis() - lastSellTime)) + "ms left)");
            }
            return false;
        }

        if (AetherConfig.SHOW_DEBUG.get()) {
            ClientUtils.sendDebugMessage("Checking inventory for AutoSell before visitors...");
        }

        if (LoadoutManager.isSwappingLoadout
                || GeorgeManager.isSelling
                || JunkManager.isDropping) {
            return false;
        }

        double ratio = getInventoryFullnessRatio(client);
        if (force || ratio >= AetherConfig.AUTO_SELL_THRESHOLD.get()) {
            ClientUtils.sendDebugMessage((force ? "Forced AutoSell" : "AutoSell threshold met (" + String.format("%.1f", ratio) + "%)")
                            + ", triggering before visitors...");

            if (Thread.currentThread().getName().equals("aether-worker")) {
                runSellSequenceSync(client, true, false, true);
            } else {
                isTriggeredBeforeVisitors = true;
                triggerSell(client, true, false);
            }
            return true;
        } else if (AetherConfig.SHOW_DEBUG.get()) {
            ClientUtils.sendDebugMessage("checkBeforeVisitors: Threshold not met ("
                    + String.format("%.1f", ratio) + "% < " + AetherConfig.AUTO_SELL_THRESHOLD.get() + "%)");
        }
        return false;
    }

    public static boolean checkBeforePestTraps(Minecraft client, boolean force, boolean resumeAfter) {
        if (!AetherConfig.AUTO_SELL_BEFORE_PEST_TRAPS.get()) {
            return false;
        }

        visitorResumeIntent = resumeAfter;

        if (System.currentTimeMillis() - lastSellTime < SELL_COOLDOWN_MS) {
            if (AetherConfig.SHOW_DEBUG.get()) {
                ClientUtils.sendDebugMessage("checkBeforePestTraps: Cooldown active ("
                        + (SELL_COOLDOWN_MS - (System.currentTimeMillis() - lastSellTime)) + "ms left)");
            }
            return false;
        }

        if (AetherConfig.SHOW_DEBUG.get()) {
            ClientUtils.sendDebugMessage("Checking inventory for AutoSell before pest traps...");
        }

        if (LoadoutManager.isSwappingLoadout
                || GeorgeManager.isSelling
                || JunkManager.isDropping) {
            return false;
        }

        double ratio = getInventoryFullnessRatio(client);
        if (force || ratio >= AetherConfig.AUTO_SELL_THRESHOLD.get()) {
            ClientUtils.sendDebugMessage((force ? "Forced AutoSell" : "AutoSell threshold met (" + String.format("%.1f", ratio) + "%)")
                            + ", triggering before pest traps...");

            if (!client.isSameThread()) {
                runSellSequenceSync(client, true, false, true);
            } else {
                isTriggeredBeforeVisitors = true;
                triggerSell(client, true, false);
            }
            return true;
        } else if (AetherConfig.SHOW_DEBUG.get()) {
            ClientUtils.sendDebugMessage("checkBeforePestTraps: Threshold not met ("
                    + String.format("%.1f", ratio) + "% < " + AetherConfig.AUTO_SELL_THRESHOLD.get() + "%)");
        }
        return false;
    }

    private static boolean isPriorityEventActive(Minecraft client, boolean ignoreVisitors) {
        return LoadoutManager.isSwappingLoadout
                || PestManager.isCleaningInProgress
                || PestPrepSwapManager.isPrepSwapping
                || (!ignoreVisitors && dev.aether.modules.visitor.VisitorsMacro.isRunning)
                || BookCombineManager.isCombining
                || BookCombineManager.isPreparingToCombine
                || GeorgeManager.isSelling
                || GeorgeManager.isPreparingToSell
                || JunkManager.isDropping
                || JunkManager.isPreparingToDrop;
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (!AetherConfig.AUTO_SELL.get() || client.player == null) {
            thresholdMetStartTime = 0;
            return;
        }

        if (System.currentTimeMillis() - lastSellTime < SELL_COOLDOWN_MS) {
            thresholdMetStartTime = 0;
            return;
        }

        boolean isBazaarSelling = dev.aether.util.BazaarUtils.isSellingBazaar;
        if (isPreparingToSell || isSelling || isBazaarSelling) {
            if (!isTriggeredBeforeVisitors && isPriorityEventActive(client, true)) {
                isPreparingToSell = false;
                isSelling = false;
                lastSellTime = System.currentTimeMillis();
                ClientUtils.sendMessage("\u00A7cAborting AutoSell due to priority event.", false);
                return;
            }

            thresholdMetStartTime = 0;

            if (isSelling && client.screen == null) {
                long now = System.currentTimeMillis();
                long elapsed = now - interactionTime;
                if (elapsed > 2500 && elapsed < 2700) {
                    ClientUtils.sendCommand("/boostercookie");
                } else if (elapsed > 5000) {
                    ClientUtils.sendDebugMessage("AutoSell GUI failed to open after 5s. Finishing...");
                    finishSelling(client);
                }
            }
            return;
        }

        if (isPriorityEventActive(client, false)) {
            thresholdMetStartTime = 0;
            return;
        }

        if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            thresholdMetStartTime = 0;
            return;
        }

        double ratio = getInventoryFullnessRatio(client);
        if (ratio >= AetherConfig.AUTO_SELL_THRESHOLD.get()) {
            if (thresholdMetStartTime == 0) {
                thresholdMetStartTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - thresholdMetStartTime >= AetherConfig.AUTO_SELL_TIME.get() * 1000L) {
                triggerSell(client, false, false);
                thresholdMetStartTime = 0;
            }
        } else {
            thresholdMetStartTime = 0;
        }
    }

    public static void runSellSequenceSync(Minecraft client, boolean isBeforeVisitor, boolean isManual, boolean isNested) {
        cancelRequested = false;
        wasRunningBeforeSell = MacroStateManager.isMacroRunning();
        wasTriggeredManually = isManual;
        ClientUtils.sendMessage("\u00A7eAuto-selling inventory contents...", false);

        ClientUtils.forceReleaseKeys();

        isPreparingToSell = true;
        isSelling = false;

        try {
            if (shouldAbort()) {
                return;
            }

            if (MacroStateManager.getCurrentState() == MacroState.State.FARMING && !isNested) {
                ClientUtils.sendDebugMessage("Disabling farming macro: Preparing AutoSell");
                client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
            }

            MacroWorkerThread.sleep(500);
            if (shouldAbort()) {
                return;
            }

            isPreparingToSell = false;
            MacroState.State previousState = MacroStateManager.getCurrentState();

            if (!isNested && (wasRunningBeforeSell || (isBeforeVisitor && visitorResumeIntent)) && !isManual) {
                MacroStateManager.setCurrentState(MacroState.State.AUTOSELLING);
            }

            if (AetherConfig.AUTO_SELL_NPC.get()) {
                isSelling = true;
                interactionTime = System.currentTimeMillis();
                awaitingFirstGuiClick = true;
                client.execute(() -> {
                    if (client.player != null && client.screen != null) {
                        client.player.closeContainer();
                    }
                });
                MacroWorkerThread.sleep(200);
                ClientUtils.sendCommand("/boostercookie");

                while (isSelling && !shouldAbort()) {
                    MacroWorkerThread.sleep(100);
                    if (System.currentTimeMillis() - interactionTime > 30000) {
                        ClientUtils.sendDebugMessage("NPC Autosell timed out.");
                        isSelling = false;
                    }
                }
            }

            if (!shouldAbort() && AetherConfig.AUTO_SELL_BAZAAR.get()) {
                ClientUtils.sendDebugMessage("Bazaar phase safety delay...");
                MacroWorkerThread.sleep(1000);
                ClientUtils.sendDebugMessage("Starting Bazaar Autosell phase...");
                try {
                    dev.aether.util.BazaarUtils.executeInstantSell(client);
                } catch (Exception e) {
                    ClientUtils.sendDebugMessage("Bazaar autosell failed.");
                    e.printStackTrace();
                }
            }

            lastSellTime = System.currentTimeMillis();
            if (shouldAbort()) {
                return;
            }

            if (isNested) {
                MacroStateManager.setCurrentState(previousState);
                ClientUtils.sendMessage("\u00A7aAuto-Sell finished. Resuming macro...", true);
            } else {
                resumeAfterAutoSell(client);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (!isNested && !shouldAbort()) {
                resumeAfterAutoSell(client);
            }
        } finally {
            isSelling = false;
            isPreparingToSell = false;
        }
    }

    private static void triggerSell(Minecraft client, boolean isBeforeVisitor, boolean isManual) {
        cancelRequested = false;
        wasRunningBeforeSell = MacroStateManager.isMacroRunning();
        wasTriggeredManually = isManual;
        ClientUtils.sendMessage("\u00A7eAuto-selling inventory contents...", false);

        ClientUtils.forceReleaseKeys();

        isPreparingToSell = true;
        isSelling = false;

        MacroWorkerThread.getInstance().submit("AutoSell-Trigger", () ->
                runSellSequenceSync(client, isBeforeVisitor, isManual, false));
    }

    private static void resumeAfterAutoSell(Minecraft client) {
        MacroWorkerThread.getInstance().submit("AutoSell-Finish", () -> {
            MacroWorkerThread.sleep(500);
            if (shouldAbort()) {
                return;
            }

            if (MacroStateManager.getCurrentState() == MacroState.State.AUTOSELLING || isTriggeredBeforeVisitors) {
                if (isTriggeredBeforeVisitors) {
                    isTriggeredBeforeVisitors = false;
                    if (wasRunningBeforeSell && visitorResumeIntent && !wasTriggeredManually) {
                        MacroStateManager.setCurrentState(MacroState.State.FARMING);
                    } else if (!wasTriggeredManually) {
                        MacroStateManager.setCurrentState(MacroState.State.OFF);
                    }

                    if (!shouldAbort()) {
                        if (dev.aether.modules.visitor.VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) {
                            if (wasRunningBeforeSell && visitorResumeIntent && !wasTriggeredManually) {
                                client.execute(() -> dev.aether.macro.FarmingMacroManager.enable(client,
                                        dev.aether.macro.FarmingMacroManager.createMacroFromConfig()));
                            }
                        } else {
                            client.execute(() -> dev.aether.modules.visitor.VisitorsMacro.start(client,
                                    !visitorResumeIntent || wasTriggeredManually));
                        }
                    }
                } else if (wasRunningBeforeSell && !wasTriggeredManually) {
                    MacroStateManager.setCurrentState(MacroState.State.FARMING);
                    client.execute(() -> dev.aether.macro.FarmingMacroManager.enable(client,
                            dev.aether.macro.FarmingMacroManager.createMacroFromConfig()));
                } else if (!wasTriggeredManually) {
                    MacroStateManager.setCurrentState(MacroState.State.OFF);
                }
            }
        });

        if (!shouldAbort() && client.player != null) {
            ClientUtils.sendMessage("\u00A7aAuto-Sell finished. Resuming tasks...", true);
        }
    }

    public static void handleMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!isSelling || screen == null || screen.getMenu() == null || screen.getTitle() == null
                || client.player == null || client.gameMode == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - interactionTime < ClientUtils.getGuiClickDelayMs(awaitingFirstGuiClick)) {
            return;
        }

        String rawTitle = screen.getTitle().getString();
        String title = rawTitle.replaceAll("(?i)\u00A7.", "").toLowerCase();

        if (!title.contains("booster cookie")) {
            return;
        }

        int foundSlotIdx = -1;
        int totalSlots = screen.getMenu().slots.size();
        int inventoryStart = totalSlots - 36;

        for (int i = inventoryStart; i < totalSlots; i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }

            ItemStack stack = slot.getItem();
            String name = stack.getHoverName().getString().replaceAll("(?i)\u00A7.", "").toLowerCase();

            for (String target : AetherConfig.AUTO_SELL_ITEMS.get()) {
                if (target.isBlank()) {
                    continue;
                }
                if (name.contains(target.toLowerCase())) {
                    foundSlotIdx = i;
                    break;
                }
            }
            if (foundSlotIdx != -1) {
                break;
            }
        }

        if (foundSlotIdx != -1) {
            interactionTime = now;
            awaitingFirstGuiClick = false;
            ClientUtils.performSlotClick(screen, foundSlotIdx, 0, ContainerInput.QUICK_MOVE);
        } else {
            finishSelling(client);
        }
    }

    public static void manualTrigger(Minecraft client) {
        if (client.player != null) {
            ClientUtils.sendMessage("\u00A7eManually triggering AutoSell...", false);
        }
        triggerSell(client, false, true);
    }

    public static void finishSelling(Minecraft client) {
        if (client.player != null && client.screen != null) {
            client.player.closeContainer();
        }
        isSelling = false;
    }
}
