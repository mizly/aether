package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;

public class PestCleaningSequencer {

    public static void startCleaningSequence(Minecraft client, String plot, String currentInfestedPlot,
            int currentPestSessionId) {
        if (PestManager.isCleaningInProgress || LoadoutManager.isSwappingLoadout) {
            PestManager.clearCleaningTriggerPending();
            return;
        }

        ClientUtils.sendDebugMessage("Disabling farming macro: Pest threshold reached, starting cleaning sequence for plot " + plot);
        client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
        PestManager.isCleaningInProgress = true;
        PestManager.clearCleaningTriggerPending();
        LoadoutManager.shouldRestartFarmingAfterSwap = false;
        dev.aether.macro.MacroStateManager.setCurrentState(dev.aether.macro.MacroState.State.CLEANING);
        final int sessionId = currentPestSessionId;

        MacroWorkerThread.getInstance().submit("CleaningSequence-" + plot, () -> {
            try {
                // Set spawn with 10s timeout (increased in CommandUtils)
                if (MacroWorkerThread.shouldAbortTask(client))
                    return;
                if (!dev.aether.util.CommandUtils.setSpawn(client)) {
                    ClientUtils.sendMessage("§c[Aether] /setspawn timed out - aborting pest cleaning to prevent roof spawn.", false);
                    PestManager.isCleaningInProgress = false;
                    dev.aether.macro.MacroStateManager.setCurrentState(MacroState.State.FARMING);
                    return;
                }
                if (MacroWorkerThread.shouldAbortTask(client))
                    return;
                if (sessionId != PestManager.currentPestSessionId)
                    return;



                boolean deferLoadoutUntilAfterDiscoTeleport = PestDiscoDestinationManager.matchesPlot(currentInfestedPlot);

                if (!deferLoadoutUntilAfterDiscoTeleport && !restoreGearForCleaning(client))
                    return;

                PestPrepSwapManager.prepSwappedForCurrentPestCycle = false;
                ClientUtils.sendMessage("§6Starting Pest Cleaner script (" + currentInfestedPlot + ")...", true);
                if (MacroWorkerThread.shouldAbortTask(client))
                    return;

                if (PestDiscoDestinationManager.isUsablePlot(currentInfestedPlot)) {
                    String currentPlot = ClientUtils.getCurrentPlot(client);
                    boolean scribePlotMatch = currentPlot != null && currentPlot.equalsIgnoreCase(currentInfestedPlot);
                    String freshChatPlot = dev.aether.util.CommandUtils.getFreshKnownPlotChat();
                    boolean chatPlotMatch = freshChatPlot != null && freshChatPlot.equalsIgnoreCase(currentInfestedPlot);
                    boolean alreadyOnPlot = scribePlotMatch;
                    if (!alreadyOnPlot && (currentPlot == null || currentPlot.equalsIgnoreCase("Unknown"))) {
                        alreadyOnPlot = chatPlotMatch;
                    }

                    boolean forcePlotTpForCurrentPlot = alreadyOnPlot
                            && AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get();
                    if (alreadyOnPlot && PestDiscoDestinationManager.matchesPlot(currentInfestedPlot)) {
                        forcePlotTpForCurrentPlot = true;
                    }

                    if (alreadyOnPlot && !forcePlotTpForCurrentPlot) {
                        String source = chatPlotMatch ? "chat" : "scoreboard";
                        ClientUtils.sendDebugMessage("Already on plot " + currentInfestedPlot + " (via " + source + "), skipping plottp.");
                    } else if (forcePlotTpForCurrentPlot) {
                        ClientUtils.sendDebugMessage("Already on plot " + currentInfestedPlot + ", but forcing plottp before starting destroyer.");
                    } else {
                        ClientUtils.sendDebugMessage("Arriving at plot " + currentInfestedPlot + " before starting destroyer. (Score: " + currentPlot + ", FreshChat: " + (freshChatPlot != null ? freshChatPlot : "stale") + ")");
                    }

                    if (!alreadyOnPlot || forcePlotTpForCurrentPlot) {
                        dev.aether.util.CommandUtils.plotTp(client, currentInfestedPlot);
                        MacroWorkerThread.sleep(200); // Wait for world load / stable position
                    }
                }

                if (deferLoadoutUntilAfterDiscoTeleport) {
                    ClientUtils.sendDebugMessage("Disco destination active: restoring pest loadout after plot teleport.");
                    if (!restoreGearForCleaning(client))
                        return;
                }

                if (PestBonusManager.isBonusInactive) {
                    ClientUtils.sendMessage("§dBonus is INACTIVE! Triggering Phillip reactivation...", true);
                    PestBonusManager.isReactivatingBonus = true;
                    ClientUtils.sendDebugMessage("Using native Pest Destroyer for reactivation.");
                    client.execute(() -> PestDestroyer.start(client, plot));
                    return;
                }

                startPestCleanerScript(client, currentInfestedPlot);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static boolean restoreGearForCleaning(Minecraft client) throws InterruptedException {
        if (AetherConfig.AUTO_LOADOUT_PEST.get()) {
            int targetSlot = AetherConfig.LOADOUT_SLOT_FARMING.get();
            if ((PestPrepSwapManager.prepSwappedForCurrentPestCycle
                    || LoadoutManager.trackedLoadoutSlot != targetSlot)
                    && targetSlot > 0) {
                ClientUtils.sendMessage("§eRestoring farming loadout (slot " + targetSlot + ") for vacuuming...", true);
                client.execute(() -> GearManager.ensureLoadoutSlot(client, targetSlot));

                // client.execute is async; wait for swap state to actually start so later waits
                // are not skipped.
                long wardrobeStartWait = System.currentTimeMillis();
                while (!LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - wardrobeStartWait < 2000) {
                    if (MacroWorkerThread.shouldAbortTask(client))
                        return false;
                    MacroWorkerThread.sleep(25);
                }

                ClientUtils.waitForWardrobeGui(client);
                long wardrobeFinishWait = System.currentTimeMillis();
                while (LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - wardrobeFinishWait < 7000)
                    MacroWorkerThread.sleep(50);

                if (LoadoutManager.isSwappingLoadout) {
                    ClientUtils.sendDebugMessage("§eLoadout swap wait timeout in cleaning sequence. Triggering failsafe completion.");
                    LoadoutManager.forceLoadoutCompletionFailsafe(client);
                }

                while (LoadoutManager.loadoutCleanupTicks > 0)
                    MacroWorkerThread.sleep(50);
                MacroWorkerThread.sleep(250);
                if (MacroWorkerThread.shouldAbortTask(client))
                    return false;
            }
        }

        return true;
    }

    private static void startPestCleanerScript(Minecraft client, String currentInfestedPlot) {
        ClientUtils.sendDebugMessage("Ready to start pest cleaner");

        ClientUtils.sendDebugMessage("Starting pest cleaner for plot " + currentInfestedPlot);

        ClientUtils.sendDebugMessage("Using native Pest Destroyer.");
        client.execute(() -> PestDestroyer.start(client, currentInfestedPlot));
    }
}


