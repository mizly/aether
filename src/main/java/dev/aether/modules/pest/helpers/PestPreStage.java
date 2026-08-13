package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import net.minecraft.client.Minecraft;

/**
 * Shared preparation for automatic and manual pest cleaning.
 */
final class PestPreStage {

    record Result(boolean successful, PestBallsackShredder.Result ballsackResult) {
        static Result success() {
            return new Result(true, null);
        }

        static Result failure() {
            return new Result(false, null);
        }
    }

    private PestPreStage() {
    }

    static Result run(Minecraft client, String plot, int pestCount, int sessionId) throws InterruptedException {
        if (shouldAbort(client, sessionId)) {
            return Result.failure();
        }

        if (AetherConfig.SUNSET_PESTS.get()) {
            if (!PestLifecycleManager.prepareSunsetPestsDaytime(client)) {
                return Result.failure();
            }
        }

        if (!swapToPestLoadout(client, sessionId)) {
            return Result.failure();
        }

        PestPrepSwapManager.clearCycleState();
        if (!moveToTargetPlot(client, plot, sessionId)) {
            return Result.failure();
        }

        boolean ballsackOnPlot = PestManager.isBallsackShredderActiveForCurrentCycle();
        if (ballsackOnPlot) {
            PestBallsackShredder.Result result =
                    PestBallsackShredder.run(client, sessionId, pestCount);
            return new Result(result.successful(), result);
        }
        return moveToRoofIfNeeded(client, plot, sessionId)
                ? Result.success()
                : Result.failure();
    }

    private static boolean moveToTargetPlot(Minecraft client, String plot, int sessionId) {
        if (!PestPlotId.isUsable(plot)) {
            return !shouldAbort(client, sessionId);
        }

        String currentPlot = ClientUtils.getCurrentPlot();
        boolean scoreboardMatch = currentPlot != null && currentPlot.equalsIgnoreCase(plot);
        String freshChatPlot = CommandUtils.getFreshKnownPlotChat();
        boolean chatMatch = freshChatPlot != null && freshChatPlot.equalsIgnoreCase(plot);
        boolean alreadyOnPlot = scoreboardMatch;
        if (!alreadyOnPlot && (currentPlot == null || currentPlot.equalsIgnoreCase("Unknown"))) {
            alreadyOnPlot = chatMatch;
        }

        boolean forcePlotTp =
                alreadyOnPlot && (AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get()
                        || (AetherConfig.TELEPORT_TO_PLOT_WHEN_START.get() && AetherConfig.MANUAL_PEST_MODE.get())
                        || PestManager.isBallsackShredderActiveForCurrentCycle());

        if (alreadyOnPlot && !forcePlotTp) {
            String source = chatMatch ? "chat" : "scoreboard";
            ClientUtils.sendDebugMessage("Already on plot " + plot + " (via " + source + "), skipping plottp.");
            return !shouldAbort(client, sessionId);
        }

        ClientUtils.sendDebugMessage((forcePlotTp ? "Forcing" : "Running")
                + " plottp to " + plot + " during pest PRE stage.");
        CommandUtils.plotTp(plot);
        MacroWorkerThread.sleep(200);
        return !shouldAbort(client, sessionId);
    }

    private static boolean moveToRoofIfNeeded(Minecraft client, String plot, int sessionId)
            throws InterruptedException {
        if (!PestAotvManager.shouldDoAotvOnCurrentPlot(client, plot, true)
                || !PestAotvManager.hasRoofAbove(client)) {
            return !shouldAbort(client, sessionId);
        }

        ClientUtils.sendDebugMessage("Pest PRE stage: using AOTV to reach the roof on plot " + plot + ".");
        PestAotvManager.startPreparationAotv(client);
        while (PestAotvManager.isPreparationAotvActive()) {
            if (shouldAbort(client, sessionId)) {
                PestAotvManager.cancelPreparationAotv(client);
                return false;
            }
            MacroWorkerThread.sleep(25);
        }

        if (PestAotvManager.consumePreparationPlotRecovery()) {
            ClientUtils.sendDebugMessage("Pest PRE stage: AOTV timed out; using plottp to recover.");
            CommandUtils.initiatePlotTp(plot);
            MacroWorkerThread.sleep(2500);
        } else {
            PestAotvManager.rotateDownAfterAotv(client, PestManager.isBallsackShredderActiveForCurrentCycle());
        }
        return !shouldAbort(client, sessionId);
    }

    private static boolean swapToPestLoadout(Minecraft client, int sessionId) throws InterruptedException {
        int targetSlot = AetherConfig.LOADOUT_SLOT_PEST_KILL.get();
        if (targetSlot <= 0 || LoadoutManager.trackedLoadoutSlot == targetSlot) {
            return !shouldAbort(client, sessionId);
        }

        ClientUtils.sendMessage("\u00A7eSwapping to pest kill loadout (slot " + targetSlot + ")...", true);
        client.execute(() -> GearManager.ensureLoadoutSlot(client, targetSlot));

        long startWait = System.currentTimeMillis();
        while (!LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - startWait < 2000) {
            if (shouldAbort(client, sessionId)) {
                return false;
            }
            MacroWorkerThread.sleep(25);
        }

        ClientUtils.waitForWardrobeGui();
        long finishWait = System.currentTimeMillis();
        while ((LoadoutManager.isSwappingLoadout || !LoadoutManager.loadoutGuiCloseComplete)
                && System.currentTimeMillis() - finishWait < 7000) {
            MacroWorkerThread.sleep(50);
        }
        if (LoadoutManager.isSwappingLoadout || !LoadoutManager.loadoutGuiCloseComplete) {
            ClientUtils.sendDebugMessage("Loadout swap timed out in pest PRE stage; triggering completion failsafe.");
            LoadoutManager.forceLoadoutCompletionFailsafe(client);
            long failsafeWait = System.currentTimeMillis();
            while (!LoadoutManager.loadoutGuiCloseComplete
                    && System.currentTimeMillis() - failsafeWait < 2000) {
                MacroWorkerThread.sleep(25);
            }
        }

        while (LoadoutManager.loadoutCleanupTicks > 0) {
            MacroWorkerThread.sleep(50);
        }
        MacroWorkerThread.sleep(250);
        return !shouldAbort(client, sessionId);
    }

    private static boolean shouldAbort(Minecraft client, int sessionId) {
        return MacroWorkerThread.shouldAbortTask(client)
                || sessionId != PestManager.getCurrentPestSessionId()
                || !PestManager.isCleaningInProgress();
    }
}
