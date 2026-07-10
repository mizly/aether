package dev.aether.modules.gear.helpers;

import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.session.RestartManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

public class LoadoutManager {
    public static volatile boolean isSwappingLoadout = false;
    public static volatile long loadoutInteractionTime = 0;
    public static volatile int loadoutInteractionStage = 0;
    public static volatile int loadoutCleanupTicks = 0;
    public static volatile int trackedLoadoutSlot = -1;
    public static volatile int targetLoadoutSlot = -1;
    public static volatile boolean shouldRestartFarmingAfterSwap = false;
    public static volatile long loadoutOpenPendingTime = 0;
    public static volatile boolean loadoutGuiDetected = false;
    public static volatile long loadoutTimelineStartTime = 0;

    public static void resetState() {
        isSwappingLoadout = false;
        shouldRestartFarmingAfterSwap = false;
        loadoutCleanupTicks = 0;
        trackedLoadoutSlot = -1;
        targetLoadoutSlot = -1;
        loadoutInteractionTime = 0;
        loadoutInteractionStage = 0;
        loadoutGuiDetected = false;
        loadoutOpenPendingTime = 0;
        loadoutTimelineStartTime = 0;
    }

    public static void triggerLoadoutSwap(Minecraft client, int slot) {
        if (trackedLoadoutSlot == slot) {
            ClientUtils.sendDebugMessage("Loadout already on target slot, restarting farming");
            client.execute(() -> FarmingMacroManager.disable(client));
            MacroWorkerThread.getInstance().submit("Wardrobe-AlreadyOnSlot-FastResume", () -> {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) {
                    return;
                }
                MacroWorkerThread.sleep(400);
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) {
                    return;
                }
                if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
                ClientUtils.sendDebugMessage("Loadout resume deferred because pest exchange has priority.");
                    return;
                }
                client.execute(() -> GearManager.swapToFarmingTool(client));
                MacroWorkerThread.sleep(250);
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) {
                    return;
                }
                if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
                ClientUtils.sendDebugMessage("Loadout resume deferred because pest exchange has priority.");
                    return;
                }
                ClientUtils.sendDebugMessage("Restarting farming macro after loadout swap");
                client.execute(() -> FarmingMacroManager.enable(client,
                        FarmingMacroManager.createMacroFromConfig()));
            });
            return;
        }

        targetLoadoutSlot = slot;
        isSwappingLoadout = true;
        loadoutGuiDetected = false;
        loadoutInteractionTime = 0;
        loadoutInteractionStage = 0;
        loadoutTimelineStartTime = 0;
        shouldRestartFarmingAfterSwap = true;
        MacroStateManager.setCurrentState(MacroState.State.WARDROBE);
        ClientUtils.sendDebugMessage("Triggering loadout swap to slot " + slot);
        client.execute(() -> FarmingMacroManager.disable(client));
        MacroWorkerThread.getInstance().submit("Loadout-OpenGui", () -> {
            if (MacroWorkerThread.shouldAbortTask(client)) {
                return;
            }
            MacroWorkerThread.sleep(375);
            if (MacroWorkerThread.shouldAbortTask(client)) {
                return;
            }
            client.execute(() -> ClientUtils.sendCommand("/loadout"));
            ClientUtils.waitForWardrobeGui();
        });
    }

    public static void ensureLoadoutSlot(Minecraft client, int slot) {
        if (trackedLoadoutSlot == slot) {
            return;
        }
        targetLoadoutSlot = slot;
        isSwappingLoadout = true;
        loadoutGuiDetected = false;
        loadoutInteractionTime = 0;
        loadoutInteractionStage = 0;
        loadoutTimelineStartTime = 0;
        ClientUtils.sendCommand("/loadout");
    }

    public static void abortSwapForPriorityTask(Minecraft client, String taskName) {
        if (!isSwappingLoadout) {
            return;
        }

        isSwappingLoadout = false;
        shouldRestartFarmingAfterSwap = false;
        loadoutGuiDetected = false;
        loadoutInteractionTime = 0;
        loadoutInteractionStage = 0;
        loadoutTimelineStartTime = 0;

        if (MacroStateManager.getCurrentState() == MacroState.State.WARDROBE) {
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
        }

        ClientUtils.sendDebugMessage("Aborted loadout swap because " + taskName + " has priority.");
        if (client.player != null) {
            client.execute(() -> client.player.closeContainer());
        }
    }

    public static void handleLoadoutMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!isSwappingLoadout || targetLoadoutSlot == -1) {
            return;
        }

        String title = screen.getTitle().getString().toLowerCase();
        if (!title.contains("loadouts")) {
            return;
        }

        long now = System.currentTimeMillis();

        if (!loadoutGuiDetected) {
            loadoutGuiDetected = true;
            loadoutTimelineStartTime = now;
            sendTimedDebug(client, "Loadout GUI opened", now);
        }

        int slotIdx = getLoadoutGuiSlot(targetLoadoutSlot);
        if (slotIdx < 0) {
            return;
        }
        if (slotIdx >= screen.getMenu().slots.size()) {
            return;
        }

        Slot slot = screen.getMenu().slots.get(slotIdx);

        if (loadoutInteractionStage == 1) {
            if (now - loadoutInteractionTime < ClientUtils.getGuiClickDelayMs(false)) {
                return;
            }
            finishLoadoutAfterClick(client, targetLoadoutSlot);
            return;
        }

        if (loadoutInteractionStage != 0) {
            return;
        }

        sendTimedDebug(client, "Clicked loadout slot " + targetLoadoutSlot, now);
        ClientUtils.performSlotClick(screen, slot.index, 0, ContainerInput.PICKUP);
        loadoutInteractionTime = now;
        loadoutInteractionStage = 1;
    }

    private static void finishLoadoutAfterClick(Minecraft client, int clickedLoadoutSlot) {
        if (!isSwappingLoadout || targetLoadoutSlot != clickedLoadoutSlot) {
            return;
        }

        long now = System.currentTimeMillis();
        trackedLoadoutSlot = clickedLoadoutSlot;
        isSwappingLoadout = false;
        loadoutGuiDetected = false;
        loadoutInteractionStage = 0;

        if (client.player != null) {
            sendTimedDebug(client, "Loadout GUI close requested", now);
            client.player.closeContainer();
        }

        sendTimedDebug(client, "Loadout swap complete. Active slot is now " + trackedLoadoutSlot, now);
        handleLoadoutCompletion(client);
    }

    private static void handleLoadoutCompletion(Minecraft client) {
        RestartManager.onWardrobeSwapCompleted(client);

        if (!shouldRestartFarmingAfterSwap) {
            return;
        }

        shouldRestartFarmingAfterSwap = false;

        if (MacroStateManager.getCurrentState() == MacroState.State.WARDROBE) {
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
        }

        if (PestManager.isCleaningInProgress) {
            ClientUtils.sendMessage("\u00A7aLoadout swap finished. Cleaning in progress, skipping restart.", true);
            return;
        }

        if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
            ClientUtils.sendDebugMessage("Loadout completion deferred because pest exchange has priority.");
            return;
        }

        ClientUtils.sendMessage("\u00A7aLoadout swap finished. Restarting farming...", true);
        client.execute(() -> GearManager.swapToFarmingTool(client));
        MacroWorkerThread.getInstance().submit("LoadoutCompletion-Resume", () -> {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) {
                return;
            }
            if (PestManager.isCleaningInProgress) {
                return;
            }
            if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
                return;
            }

            GearManager.finalResume(client);
        });
    }

    public static void forceLoadoutCompletionFailsafe(Minecraft client) {
        if (isSwappingLoadout && shouldRestartFarmingAfterSwap) {
            ClientUtils.sendDebugMessage("Loadout swap failsafe triggered. Forcing completion.");
            trackedLoadoutSlot = targetLoadoutSlot;
            isSwappingLoadout = false;
            loadoutGuiDetected = false;
            loadoutInteractionStage = 0;
            handleLoadoutCompletion(client);
        }
    }

    private static int getLoadoutGuiSlot(int loadoutSlot) {
        if (loadoutSlot < 1 || loadoutSlot > 12) {
            return -1;
        }
        int row = (loadoutSlot - 1) / 3;
        int column = (loadoutSlot - 1) % 3;
        return 14 + row * 9 + column;
    }

    private static void sendTimedDebug(Minecraft client, String action, long now) {
        ClientUtils.sendDebugMessage(action + " at " + ClientUtils.formatElapsedMs(loadoutTimelineStartTime, now) + ".");
    }
}
