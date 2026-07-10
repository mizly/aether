package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

public class PestPrepSwapManager {
    public static volatile boolean prepSwappedForCurrentPestCycle = false;
    public static volatile boolean isPrepSwapping = false;

    public static void resetState() {
        prepSwappedForCurrentPestCycle = false;
        isPrepSwapping = false;
    }

    private static Minecraft client() {
        return Minecraft.getInstance();
    }

    public static void updatePrepSwapFlag(int cooldownSeconds, boolean isCleaningInProgress) {
        if (cooldownSeconds > getPrepSwapResetCooldownSeconds()
                && prepSwappedForCurrentPestCycle
                && !isCleaningInProgress) {
            prepSwappedForCurrentPestCycle = false;
        }
    }

    public static boolean shouldTriggerPrepSwap(MacroState.State currentState, int cooldownSeconds,
            boolean isCleaningInProgress, boolean isReturnToLocationActive) {
        if (currentState != MacroState.State.FARMING) {
            return false;
        }
        if (cooldownSeconds == -1 || cooldownSeconds < 0) {
            return false;
        }
        if (prepSwappedForCurrentPestCycle || isCleaningInProgress || isReturnToLocationActive) {
            return false;
        }
        if (!hasAnyPrepSwapTasksEnabled()) {
            return false;
        }

        return cooldownSeconds <= getPrepSwapTriggerCooldownSeconds();
    }

    public static void triggerPrepSwap() {
        prepSwappedForCurrentPestCycle = true;
        isPrepSwapping = true;
        ClientUtils.sendDebugMessage("Pest cooldown detected. Triggering prep-swap...");
        MacroWorkerThread.getInstance().submit("PrepSwap", () -> {
            try {
                Minecraft client = client();
                if (shouldAbortPrepSwap()) {
                    return;
                }
                ClientUtils.sendDebugMessage("Disabling farming macro: Triggering prep-swap");
                client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
                MacroWorkerThread.sleep(400);
                if (shouldAbortPrepSwap()) {
                    return;
                }

                if (!runPrepLoadoutSwap()) {
                    return;
                }

                if (!PestManager.isCleaningInProgress) {
                    GearManager.finalResume(client);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isPrepSwapping = false;
            }
        });
    }

    private static boolean hasAnyPrepSwapTasksEnabled() {
        return AetherConfig.LOADOUT_SLOT_PEST.get() != AetherConfig.LOADOUT_SLOT_FARMING.get();
    }

    private static int getPrepSwapTriggerCooldownSeconds() {
        return AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.get();
    }

    private static int getPrepSwapResetCooldownSeconds() {
        return AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.get();
    }

    private static boolean shouldAbortPrepSwap() {
        Minecraft client = client();
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING) || PestManager.isCleaningInProgress) {
            if (dev.aether.macro.MacroStateManager.getCurrentState() != MacroState.State.FARMING
                    || !dev.aether.macro.MacroStateManager.isMacroRunning()) {
                prepSwappedForCurrentPestCycle = false;
            }
            return true;
        }
        return false;
    }

    private static boolean runPrepLoadoutSwap() throws InterruptedException {
        Minecraft client = client();
        if (AetherConfig.LOADOUT_SLOT_PEST.get() <= 0) {
            return !shouldAbortPrepSwap();
        }

        ClientUtils.sendDebugMessage("Prep-swap: Initiating loadout swap to slot " + AetherConfig.LOADOUT_SLOT_PEST.get());
        GearManager.ensureLoadoutSlot(client, AetherConfig.LOADOUT_SLOT_PEST.get());
        if (!LoadoutManager.isSwappingLoadout) {
            ClientUtils.sendDebugMessage("Prep-swap: Loadout swap not needed (already on correct slot).");
            return !shouldAbortPrepSwap();
        }

        ClientUtils.sendDebugMessage("Prep-swap: Waiting for loadout GUI...");
        ClientUtils.waitForWardrobeGui();
        if (!LoadoutManager.loadoutGuiDetected) {
            ClientUtils.sendDebugMessage("\u00A7cPrep-swap: Loadout GUI not detected! Retrying in 1 second...");
            MacroWorkerThread.sleep(1000);
            if (shouldAbortPrepSwap()) {
                return false;
            }

            GearManager.ensureLoadoutSlot(client, AetherConfig.LOADOUT_SLOT_PEST.get());
            if (LoadoutManager.isSwappingLoadout) {
                ClientUtils.sendDebugMessage("Prep-swap: Retry - Waiting for loadout GUI...");
                ClientUtils.waitForWardrobeGui();
                if (!LoadoutManager.loadoutGuiDetected) {
                    ClientUtils.sendDebugMessage("\u00A7cPrep-swap: Loadout GUI still not detected after retry! Aborting prep-swap.");
                    prepSwappedForCurrentPestCycle = false;
                    return false;
                }
            }
        }

        while (LoadoutManager.isSwappingLoadout && !PestManager.isCleaningInProgress) {
            MacroWorkerThread.sleep(50);
        }
        MacroWorkerThread.sleep(250);
        if (shouldAbortPrepSwap()) {
            return false;
        }

        ClientUtils.sendDebugMessage("Prep-swap: Loadout swap completed.");
        return true;
    }
}
