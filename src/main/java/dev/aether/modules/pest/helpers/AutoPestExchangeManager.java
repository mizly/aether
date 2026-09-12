package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import net.minecraft.client.Minecraft;

// watches the tab-list bonus line and runs the phillip exchange when it reads INACTIVE while farming
public final class AutoPestExchangeManager {

    private static final long RUN_COOLDOWN_MS = 30_000L;
    // a finished exchange is remembered this long while the tab still claims INACTIVE
    private static final long EXCHANGE_MEMORY_MS = 600_000L;

    private static volatile boolean running = false;
    private static volatile long bonusInactiveSinceMs = 0L;
    private static volatile long lastRunMs = 0L;
    private static volatile long pendingTriggerReadyAtMs = 0L;
    private static volatile long exchangeRememberedAtMs = 0L;
    private static volatile boolean sawBonusInactive = false;
    private static volatile boolean pendingTrigger = false;

    private AutoPestExchangeManager() {}

    public static boolean isRunning() {
        return running;
    }

    public static long getBonusInactiveElapsedMs() {
        if (!PestBonusManager.isBonusInactive() || bonusInactiveSinceMs == 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - bonusInactiveSinceMs);
    }

    public static long getRunCooldownRemainingMs() {
        return Math.max(0L, RUN_COOLDOWN_MS - (System.currentTimeMillis() - lastRunMs));
    }

    public static long getExchangeMemoryRemainingMs() {
        long now = System.currentTimeMillis();
        return isExchangeRemembered(now) ? EXCHANGE_MEMORY_MS - (now - exchangeRememberedAtMs) : 0L;
    }

    public static void reset() {
        running = false;
        bonusInactiveSinceMs = 0L;
        lastRunMs = 0L;
        pendingTriggerReadyAtMs = 0L;
        exchangeRememberedAtMs = 0L;
        sawBonusInactive = false;
        pendingTrigger = false;
    }

    // pests we already handed over, keyed to the run so late confirmations do not push the window
    static void rememberExchange() {
        exchangeRememberedAtMs = running ? lastRunMs : System.currentTimeMillis();
    }

    public static boolean isExchangePending() {
        if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) {
            return false;
        }
        if (running || PestExchangeManager.isExchanging()) {
            return true;
        }
        return pendingTrigger
                && PestBonusManager.isBonusInactive()
                && !isExchangeRemembered(System.currentTimeMillis());
    }

    public static boolean shouldBlockFarmingResume() {
        if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) {
            return false;
        }
        if (running || PestExchangeManager.isExchanging()) {
            return true;
        }
        if (!PestBonusManager.isBonusInactive() || !pendingTrigger) {
            return false;
        }
        long now = System.currentTimeMillis();
        return !isExchangeRemembered(now) && isPendingTriggerReady(now);
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getConnection() == null) return;
        if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) {
            clearPendingTrigger();
            exchangeRememberedAtMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (isExchangeRemembered(now)) {
            clearPendingTrigger();
            return;
        }
        exchangeRememberedAtMs = 0L;

        if (!PestBonusManager.isBonusInactive()) {
            clearPendingTrigger();
            return;
        }

        if (bonusInactiveSinceMs == 0L) {
            bonusInactiveSinceMs = now;
        }
        if (!sawBonusInactive) {
            sawBonusInactive = true;
            pendingTrigger = true;
            pendingTriggerReadyAtMs = now + ConfigHelpers.getRandomizedDelay(
                    AetherConfig.PEST_EXCHANGE_DELAY_MIN.get(),
                    AetherConfig.PEST_EXCHANGE_DELAY_MAX.get());
        }

        tryTriggerPending(client, now);
    }

    public static boolean tryTriggerPending(Minecraft client) {
        return tryTriggerPending(client, System.currentTimeMillis());
    }

    private static boolean tryTriggerPending(Minecraft client, long now) {
        if (client == null || client.player == null || client.getConnection() == null) {
            return false;
        }
        if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) {
            return false;
        }
        if (running || PestExchangeManager.isExchanging()) {
            return true;
        }
        if (isExchangeRemembered(now)) {
            return false;
        }
        if (PestManager.isCleaningInProgress()) {
            return false;
        }

        MacroState.State state = MacroStateManager.getCurrentState();
        boolean stateAllowsPriority = state == MacroState.State.FARMING
                || state == MacroState.State.WARDROBE;
        if (!stateAllowsPriority || !pendingTrigger || !isPendingTriggerReady(now)) {
            return false;
        }

        if (LoadoutManager.isSwappingLoadout) {
            LoadoutManager.abortSwapForPriorityTask(client, "pest exchange");
        }
        MacroStateManager.setCurrentState(MacroState.State.CLEANING);
        PestManager.setCleaningInProgress(true);
        running = true;
        lastRunMs = now;
        // the trigger stays armed so a run that never reaches phillip retries after the cooldown
        MacroWorkerThread worker = MacroWorkerThread.getInstance();
        if (worker.isBusy()) {
            worker.replaceCurrent("AutoPestExchange", () -> runSequence(client));
            ClientUtils.sendDebugMessage("AutoPestExchange: replaced queued worker tasks for priority.");
        } else {
            worker.submit("AutoPestExchange", () -> runSequence(client));
        }
        return true;
    }

    private static void runSequence(Minecraft client) {
        long guiDelay = Math.max(50L, dev.aether.util.ClientUtils.getGuiClickDelayMs(false));
        boolean canResumeFarming = true;

        try {
            if (MacroWorkerThread.shouldAbortTask(client))
                return;

            MacroStateManager.setCurrentState(MacroState.State.CLEANING);
            PestManager.setCleaningInProgress(true);

            msg(client, "\u00A7eBonus inactive detected. Running pest exchange...");
            client.execute(() -> FarmingMacroManager.disable(client));
            MacroWorkerThread.sleep(guiDelay);

            if (MacroWorkerThread.shouldAbortTask(client))
                return;

            if (!AetherConfig.AUTO_PEST_USE_ABIPHONE.get()) {
                if (!CommandUtils.setSpawn()) {
                    msg(client, "\u00A7c/setspawn failed before pest exchange. Returning to farm.");
                    return;
                }
            }

            if (MacroWorkerThread.shouldAbortTask(client))
                return;

            if (PestExchangeManager.runExchangeBlocking(client)) {
                rememberExchange();
            }

            if (MacroWorkerThread.shouldAbortTask(client))
                return;

            if (!AetherConfig.AUTO_PEST_USE_ABIPHONE.get()) {
                if (!CommandUtils.warpGarden()) {
                    msg(client, "\u00A7c/warp garden failed after pest exchange.");
                    canResumeFarming = false;
                    return;
                }
            }

            MacroWorkerThread.sleep(guiDelay);
        } catch (Exception e) {
            e.printStackTrace();
            msg(client, "\u00A7cAuto pest exchange error: " + e.getMessage());
        } finally {
            running = false;
            if (MacroStateManager.isMacroRunning() && canResumeFarming) {
                if (!AetherConfig.AUTO_PEST_USE_ABIPHONE.get()) {
                    SqueakyMousematManager.armReapplyAttempt();
                }
                client.execute(() -> FarmingMacroManager.enable(client,
                        FarmingMacroManager.createMacroFromConfig()));
                MacroStateManager.setCurrentState(MacroState.State.FARMING);
            } else if (MacroStateManager.isMacroRunning() && !canResumeFarming) {
                msg(client, "\u00A7cAuto pest exchange did not resume farming because garden warp failed.");
            }
            PestManager.setCleaningInProgress(false);
            if (canResumeFarming && MacroStateManager.isMacroRunning()) {
                msg(client, "\u00A7aAuto pest exchange finished. Resuming farming.");
            }
        }
    }

    private static boolean isExchangeRemembered(long now) {
        if (exchangeRememberedAtMs == 0L) {
            return false;
        }
        if (PestBonusManager.hasSeenBonusActiveSince(exchangeRememberedAtMs)) {
            return false;
        }
        return now - exchangeRememberedAtMs < EXCHANGE_MEMORY_MS;
    }

    private static void clearPendingTrigger() {
        bonusInactiveSinceMs = 0L;
        pendingTriggerReadyAtMs = 0L;
        sawBonusInactive = false;
        pendingTrigger = false;
    }

    private static boolean isPendingTriggerReady(long now) {
        return pendingTriggerReadyAtMs > 0L
                && now >= pendingTriggerReadyAtMs
                && now - lastRunMs >= RUN_COOLDOWN_MS;
    }

    private static void msg(Minecraft client, String text) {
        ClientUtils.sendMessage(text);
    }
}
