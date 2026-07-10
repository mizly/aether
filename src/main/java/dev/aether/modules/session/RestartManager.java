package dev.aether.modules.session;

import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.ReconnectScheduler;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.PestPrepSwapManager;
import dev.aether.modules.pest.helpers.PestReturnManager;
import dev.aether.ui.DynamicRestScreen;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ThreadLocalRandom;

public class RestartManager {
    private static final long RESTART_COMMAND_GAP_MS = 2000L;
    private static final long RESTART_MIN_DELAY_SECONDS = 10L;
    private static final long RESTART_MAX_DELAY_SECONDS = 20L;
    private static final long PROXY_RESTART_MIN_DELAY_SECONDS = 15L;
    private static final long PROXY_RESTART_MAX_DELAY_SECONDS = 30L;

    private static boolean isRestartPending = false;
    private static long restartExecutionTime = 0;
    private static int restartSequenceStage = 0;
    private static long nextRestartActionTime = 0;
    private static long restartSetSpawnTime = 0;
    private static long restartDelaySeconds = 0;
    private static boolean isRestartAborted = false;
    private static boolean restartQueuedAfterWardrobe = false;
    private static CommandUtils.ChatWindow restartSetSpawnWindow = null;
    private static boolean isProxyRestartPending = false;
    private static int proxyRestartSequenceStage = 0;
    private static long nextProxyRestartActionTime = 0;
    private static long proxyRestartSetSpawnTime = 0;
    private static long proxyRestartDelaySeconds = 0;
    private static boolean proxyRestartQueuedAfterWardrobe = false;
    private static CommandUtils.ChatWindow proxyRestartSetSpawnWindow = null;

    public static void reset() {
        resetRestartState();
        resetProxyRestartState();
    }

    private static void resetRestartState() {
        isRestartPending = false;
        restartExecutionTime = 0;
        restartSequenceStage = 0;
        nextRestartActionTime = 0;
        restartSetSpawnTime = 0;
        restartDelaySeconds = 0;
        isRestartAborted = false;
        restartQueuedAfterWardrobe = false;
        restartSetSpawnWindow = null;
    }

    private static void resetProxyRestartState() {
        isProxyRestartPending = false;
        proxyRestartSequenceStage = 0;
        nextProxyRestartActionTime = 0;
        proxyRestartSetSpawnTime = 0;
        proxyRestartDelaySeconds = 0;
        proxyRestartQueuedAfterWardrobe = false;
        proxyRestartSetSpawnWindow = null;
    }

    private static boolean isSafeToRunRestartAbort(MacroState.State state) {
        if (state == MacroState.State.OFF || state == MacroState.State.RECOVERING) {
            return false;
        }

        // Never interrupt active pest/visitor flows; wait until they finish naturally.
        if (PestManager.isCleaningInProgress
                || PestPrepSwapManager.isPrepSwapping
                || PestReturnManager.isFinishingInProgress
                || PestReturnManager.isReturnToLocationActive
                || PestReturnManager.isReturningFromPestVisitor
                || state == MacroState.State.CLEANING
                || state == MacroState.State.SPRAYING
                || state == MacroState.State.VISITING) {
            return false;
        }

        return state == MacroState.State.FARMING;
    }

    public static void handleRestartMessage(Minecraft client, boolean isImmediate) {
        if (MacroStateManager.getCurrentState() != MacroState.State.OFF
                && MacroStateManager.getCurrentState() != MacroState.State.RECOVERING
                && !isRestartPending
                && !isProxyRestartPending) {
            restartDelaySeconds = ThreadLocalRandom.current()
                    .nextLong(RESTART_MIN_DELAY_SECONDS, RESTART_MAX_DELAY_SECONDS + 1);
            long contestMs = isImmediate ? 0 : ClientUtils.getJacobsContestRemainingMs();
            if (contestMs > 0) {
                ClientUtils.sendMessage("\u00A7c" + String.format(
                                dev.aether.util.AetherLang.localize(
                                        "Server restart detected. Delaying abort until Jacob's contest ends, then waiting %ds..."),
                                restartDelaySeconds),
                        false);
                restartExecutionTime = System.currentTimeMillis() + contestMs + (restartDelaySeconds * 1000L);
            } else {
                ClientUtils.sendMessage("\u00A7c" + String.format(
                                dev.aether.util.AetherLang.localize(
                                        "Server restart or evacuation detected. Waiting %ds before aborting..."),
                                restartDelaySeconds),
                        false);
                restartExecutionTime = System.currentTimeMillis() + (restartDelaySeconds * 1000L);
            }
            // Defer interruption to stage 0 so active pest/visitor flows can finish safely.
            isRestartPending = true;
            restartSequenceStage = 0;
        }
    }

    public static void handleProxyRestartMessage(Minecraft client) {
        if (client == null
                || MacroStateManager.getCurrentState() == MacroState.State.OFF
                || MacroStateManager.getCurrentState() == MacroState.State.RECOVERING
                || isRestartPending
                || isProxyRestartPending) {
            return;
        }

        proxyRestartDelaySeconds = ThreadLocalRandom.current()
                .nextLong(PROXY_RESTART_MIN_DELAY_SECONDS, PROXY_RESTART_MAX_DELAY_SECONDS + 1);

        ClientUtils.sendMessage("\u00A7eProxy restart detected. Waiting for farming to resume, then disconnecting for "
                        + proxyRestartDelaySeconds + "s...",
                false);
        isProxyRestartPending = true;
        proxyRestartSequenceStage = 0;
        nextProxyRestartActionTime = System.currentTimeMillis();
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        MacroState.State state = MacroStateManager.getCurrentState();

        if (isProxyRestartPending) {
            updateProxyRestart(client, state);
        }

        if (!isRestartPending) {
            return;
        }

        if (state == MacroState.State.OFF) {
            resetRestartState();
            return;
        }

        if (restartSequenceStage == 0 && System.currentTimeMillis() >= restartExecutionTime) {
            MacroState.Location loc = ClientUtils.getCurrentLocation();
            boolean alreadyDisplaced = loc != MacroState.Location.GARDEN && loc != MacroState.Location.UNKNOWN;

            if (!alreadyDisplaced && (LoadoutManager.isSwappingLoadout || state == MacroState.State.WARDROBE)) {
                if (!restartQueuedAfterWardrobe) {
                    restartQueuedAfterWardrobe = true;
                    ClientUtils.sendDebugMessage("Server restart abort queued until the wardrobe swap finishes.");
                }
                return;
            }

            if (!alreadyDisplaced && !isSafeToRunRestartAbort(state)) {
                return;
            }

            restartQueuedAfterWardrobe = false;

            if (alreadyDisplaced) {
                ClientUtils.sendDebugMessage("Displaced during restart delay (Location: " + loc + "). Aborting immediately.");
            }

            ClientUtils.sendMessage("\u00A7cExecuting delayed restart abort sequence...", false);
            ClientUtils.sendDebugMessage("Disabling farming macro: Server restart/evacuation detected");
            // Cancel worker tasks right before abort execution.
            MacroWorkerThread.getInstance().cancelCurrent();
            client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
            ClientUtils.forceReleaseKeys();
            restartSetSpawnWindow = CommandUtils.beginChatWindow();
            dev.aether.util.CommandUtils.initiateSetSpawn();
            restartSetSpawnTime = System.currentTimeMillis();
            restartSequenceStage = 1;
            nextRestartActionTime = System.currentTimeMillis() + 5000; // Fallback timeout
        } else if (restartSequenceStage == 1) {
            boolean spawnConfirmed = dev.aether.util.CommandUtils.hasSpawnBeenSet(restartSetSpawnWindow);
            long now = System.currentTimeMillis();
            if (!spawnConfirmed && now < nextRestartActionTime) {
                return;
            }

            if (restartSetSpawnTime > 0 && now - restartSetSpawnTime < RESTART_COMMAND_GAP_MS) {
                return;
            }

            ClientUtils.sendDebugMessage(CommandUtils.shouldSkipSetSpawn()
                            ? "Restart sequence: queueing /hub without /setspawn."
                            : "Restart sequence: queueing /hub after /setspawn.");
            ClientUtils.sendCommand("/hub");
            restartSequenceStage = 2;
            nextRestartActionTime = now + 10000;
        } else if (restartSequenceStage == 2 && System.currentTimeMillis() >= nextRestartActionTime) {
            ClientUtils.sendDebugMessage("Disabling farming macro: Entering recovery mode after server restart");
            client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
            MacroStateManager.setCurrentState(MacroState.State.RECOVERING);
            restartSequenceStage = 0;
            isRestartPending = false;
        }
    }

    private static void updateProxyRestart(Minecraft client, MacroState.State state) {
        long now = System.currentTimeMillis();
        if (now < nextProxyRestartActionTime) {
            return;
        }

        if (proxyRestartSequenceStage == 0) {
            if (LoadoutManager.isSwappingLoadout || state == MacroState.State.WARDROBE) {
                if (!proxyRestartQueuedAfterWardrobe) {
                    proxyRestartQueuedAfterWardrobe = true;
                    ClientUtils.sendDebugMessage("Proxy restart queued until the wardrobe swap finishes.");
                }
                return;
            }

            if (!isSafeToRunRestartAbort(state)) {
                return;
            }

            proxyRestartQueuedAfterWardrobe = false;
            ClientUtils.sendMessage(CommandUtils.shouldSkipSetSpawn()
                            ? "\u00A7eProxy restart recovery: preparing disconnect..."
                            : "\u00A7eProxy restart recovery: running /setspawn before disconnecting...",
                    false);
            ClientUtils.sendDebugMessage("Disabling farming macro: Proxy restart recovery");
            MacroWorkerThread.getInstance().cancelCurrent();
            client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
            ClientUtils.forceReleaseKeys();
            proxyRestartSetSpawnWindow = CommandUtils.beginChatWindow();
            CommandUtils.initiateSetSpawn();
            proxyRestartSetSpawnTime = now;
            MacroStateManager.setCurrentState(MacroState.State.OFF);
            proxyRestartSequenceStage = 1;
            nextProxyRestartActionTime = now + 5000;
            return;
        }

        if (proxyRestartSequenceStage != 1) {
            return;
        }

        boolean spawnConfirmed = CommandUtils.hasSpawnBeenSet(proxyRestartSetSpawnWindow);
        if (!spawnConfirmed && now < nextProxyRestartActionTime) {
            return;
        }

        if (proxyRestartSetSpawnTime > 0 && now - proxyRestartSetSpawnTime < RESTART_COMMAND_GAP_MS) {
            return;
        }

        long reconnectDelayMs = proxyRestartDelaySeconds * 1000L;
        long reconnectAtMs = now + reconnectDelayMs;
        ClientUtils.sendMessage("\u00A7eProxy restart recovery: disconnecting from Hypixel for "
                        + proxyRestartDelaySeconds + "s...",
                false);
        MacroStateManager.setIntentionalDisconnect(true);
        ReconnectScheduler.scheduleReconnect(
                proxyRestartDelaySeconds,
                true,
                ReconnectScheduler.ReconnectMode.PROXY_RESTART);
        ClientUtils.disconnectWithScreen(new DynamicRestScreen(
                "Proxy Restart Recovery",
                "Proxy Restart Recovery",
                "reconnecting to Hypixel in",
                "Cancel Reconnect & Exit to Menu",
                reconnectAtMs,
                reconnectDelayMs), net.minecraft.network.chat.Component.literal("Proxy restart recovery"));
        resetProxyRestartState();
    }

    public static boolean isRestartPending() {
        return isRestartPending;
    }

    public static boolean isRestartSequenceActive() {
        return isRestartPending && restartSequenceStage > 0;
    }

    public static void onWardrobeSwapCompleted(Minecraft client) {
        if (isRestartPending && restartQueuedAfterWardrobe && restartSequenceStage == 0) {
            ClientUtils.sendDebugMessage("Wardrobe swap finished. Resuming queued server restart abort.");
            restartQueuedAfterWardrobe = false;
            restartExecutionTime = Math.min(restartExecutionTime, System.currentTimeMillis());
        }

        if (isProxyRestartPending && proxyRestartQueuedAfterWardrobe && proxyRestartSequenceStage == 0) {
            ClientUtils.sendDebugMessage("Wardrobe swap finished. Resuming queued proxy restart recovery.");
            proxyRestartQueuedAfterWardrobe = false;
            nextProxyRestartActionTime = Math.min(nextProxyRestartActionTime, System.currentTimeMillis());
        }
    }
}


