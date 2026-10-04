package dev.aether.modules.session;

import dev.aether.config.AetherConfig;
import dev.aether.config.RewarpPointPair;
import dev.aether.config.RewarpPointPairs;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.farming.AbstractFarmingMacro;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.inventorymanager.AutoSellManager;
import dev.aether.modules.inventorymanager.BookCombineManager;
import dev.aether.modules.inventorymanager.GeorgeManager;
import dev.aether.modules.inventorymanager.JunkManager;
import dev.aether.modules.pest.ManualPestManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.pest.helpers.AutoSprayonatorManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.PestLifecycleManager;
import dev.aether.modules.pest.helpers.PestOnTheTrackManager;
import dev.aether.modules.rotation.HumanFlick;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.concurrent.ThreadLocalRandom;

// a short break in place, like checking a phone: keys released, camera still, the state stays FARMING
// a resume never rotates, so any drift during the pause is left to the rotation failsafe
public final class MicropauseManager {
    private static final long HOLD_TASKS_MIN_MS = 1_500L;
    private static final long HOLD_TASKS_MAX_MS = 4_000L;
    // the rewarp trigger radius is 1.5 blocks, plus about a block of coast once the keys are released
    private static final double REWARP_END_MARGIN = 3.0;

    private static final MicropauseScheduler SCHEDULER = new MicropauseScheduler();
    // stopMacro can run off the client thread, so it only flips flags and update() applies them
    private static volatile boolean paused;
    private static volatile boolean resetRequested;
    private static volatile boolean farming;
    private static volatile long holdTasksUntilMs = Long.MIN_VALUE;
    private static AbstractFarmingMacro pausedMacro;

    private MicropauseManager() {
    }

    // client thread only, right after DynamicRestManager so pest, failsafe and farming ticks see the same state
    public static void update(Minecraft client) {
        long now = now();
        if (resetRequested) {
            resetRequested = false;
            SCHEDULER.reset();
            pausedMacro = null;
        }

        AbstractFarmingMacro macro = FarmingMacroManager.getActiveMacro();
        farming = isFarming(client, macro);
        MicropauseScheduler.Action action = SCHEDULER.update(now, farming, () -> canBeginPause(client, macro),
                MicropauseScheduler.Settings.configured(), ThreadLocalRandom.current());
        if (action == MicropauseScheduler.Action.BEGIN_PAUSE) {
            beginPause(client, macro, now);
        } else if (action == MicropauseScheduler.Action.END_PAUSE) {
            endPause(now);
        }
        paused = SCHEDULER.isPaused();
    }

    public static boolean isPaused() {
        return paused;
    }

    // the pest trigger, rests, rewarps and inventory tasks also wait a moment after a resume instead of firing at once
    public static boolean isHoldingTasks() {
        return paused || now() < holdTasksUntilMs;
    }

    // -1 while not farming
    public static long getFarmingMsUntilNextPause() {
        return farming && !paused ? SCHEDULER.farmingMsUntilDue() : -1L;
    }

    public static long getPauseRemainingMs() {
        return SCHEDULER.pauseRemainingMs(now());
    }

    public static void reset() {
        paused = false;
        holdTasksUntilMs = Long.MIN_VALUE;
        resetRequested = true;
    }

    // the scheduler reads its settings every tick, so only turning the feature off needs anything here
    public static void syncFromConfig() {
        if (!AetherConfig.MICROPAUSE_ENABLED.get()) {
            reset();
        }
    }

    private static boolean isFarming(Minecraft client, AbstractFarmingMacro macro) {
        return client.player != null
                && MacroStateManager.getCurrentState() == MacroState.State.FARMING
                && FarmingMacroManager.isStarted()
                && !PestOnTheTrackManager.getInstance().isBlockingFarming()
                && (!SCHEDULER.isPaused() || macro == pausedMacro);
    }

    private static boolean canBeginPause(Minecraft client, AbstractFarmingMacro macro) {
        LocalPlayer player = client.player;
        return player != null
                && macro != null
                && macro.canBeginMicropause()
                && client.screen == null
                && player.onGround()
                && !player.getAbilities().flying
                && !RotationManager.isRotating()
                && !HumanFlick.isActive()
                && isPestFlowIdle()
                && areTasksIdle()
                && !FailsafeManager.isAnyFailsafePending(client)
                && !isNearRewarpEnd(player);
    }

    private static boolean isPestFlowIdle() {
        return !PestDestroyer.isActive()
                && !ManualPestManager.isActive()
                && PestLifecycleManager.getStage() == PestLifecycleManager.Stage.IDLE
                && !PestManager.isCleaningInProgress()
                && !PestManager.isTriggerPending()
                && !PestOnTheTrackManager.getInstance().isNotIdle()
                && !AutoSprayonatorManager.isRunning()
                && !AutoPestExchangeManager.isRunning();
    }

    private static boolean areTasksIdle() {
        return !DynamicRestManager.isRestPending()
                && !RestartManager.isRestartPending()
                && !MacroWorkerThread.getInstance().hasActiveWork()
                && !GeorgeManager.isSelling
                && !GeorgeManager.isPreparingToSell
                && !AutoSellManager.isSelling
                && !AutoSellManager.isPreparingToSell
                && !BookCombineManager.isCombining
                && !BookCombineManager.isPreparingToCombine
                && !JunkManager.isDropping
                && !JunkManager.isPreparingToDrop
                && !LoadoutManager.isSwappingLoadout
                && LoadoutManager.loadoutCleanupTicks <= 0
                && LoadoutManager.loadoutGuiCloseComplete;
    }

    private static boolean isNearRewarpEnd(LocalPlayer player) {
        if (!AetherConfig.ENABLE_REWARP.get()) {
            return false;
        }
        for (RewarpPointPair pair : RewarpPointPairs.get()) {
            if (pair.hasEnd()
                    && player.distanceToSqr(pair.endX, pair.endY, pair.endZ) <= REWARP_END_MARGIN * REWARP_END_MARGIN) {
                return true;
            }
        }
        return false;
    }

    private static void beginPause(Minecraft client, AbstractFarmingMacro macro, long now) {
        pausedMacro = macro;
        // not forceReleaseKeys: that leaves the macro's attack edge set, and the resume would never click again
        FarmingMacroManager.releaseInputs(client);
        ClientUtils.discardQueuedClicks(client.options.keyAttack);
        ClientUtils.sendDebugMessage("Micropause: pausing for " + SCHEDULER.pauseRemainingMs(now) / 1000L + "s.");
    }

    private static void endPause(long now) {
        pausedMacro = null;
        holdTasksUntilMs = now + ThreadLocalRandom.current().nextLong(HOLD_TASKS_MIN_MS, HOLD_TASKS_MAX_MS + 1L);
        ClientUtils.sendDebugMessage("Micropause: over.");
    }

    // monotonic, so a wall clock jump can never stretch a pause
    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
