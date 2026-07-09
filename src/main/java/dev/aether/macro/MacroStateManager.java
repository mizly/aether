package dev.aether.macro;

import dev.aether.util.ClientUtils;
import dev.aether.config.AetherConfig;
import dev.aether.modules.ComposterManager;
import dev.aether.modules.GreenhouseManager;
import dev.aether.modules.SupercraftManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.farming.UngrabMouse;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.inventorymanager.AutoSellManager;
import dev.aether.modules.inventorymanager.BookCombineManager;
import dev.aether.modules.inventorymanager.GeorgeManager;
import dev.aether.modules.inventorymanager.JunkManager;
import dev.aether.modules.metaldetector.MetalDetectorSolver;
import dev.aether.modules.misc.AutoCarnivalManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.performance.MuteManager;
import dev.aether.modules.performance.PerformanceModeManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.pest.helpers.AutoSprayonatorManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.PestExchangeManager;
import dev.aether.modules.pest.helpers.PestTrapManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.modules.session.DailyFarmTimeTracker;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.modules.session.RecoveryManager;
import dev.aether.modules.session.RestartManager;
import dev.aether.modules.visitor.VisitorsMacro;
import dev.aether.util.BpsTracker;
import net.minecraft.client.Minecraft;

public class MacroStateManager {
    private static volatile MacroState.State currentState = MacroState.State.OFF;
    private static volatile boolean intentionalDisconnect = false;
    private static volatile long sessionAccumulated = 0;
    private static volatile long lifetimeAccumulated = 0;
    private static volatile long lastSessionStartTime = 0;
    private static long lastPeriodicSaveTime = 0;

    public static void resetSession() {
        if (isMacroRunning()) {
            lastSessionStartTime = System.currentTimeMillis();
        } else {
            lastSessionStartTime = 0;
        }
        sessionAccumulated = 0;
        ProfitManager.reset();
        AutoCarnivalManager.resetTokenSession();
        DynamicRestManager.reset();
        BpsTracker.reset();
    }

    public static void syncFromConfig() {
        lifetimeAccumulated = (long) (double) AetherConfig.LIFETIME_ACCUMULATED.get();
        DailyFarmTimeTracker.syncFromConfig();
    }

    public static void periodicUpdate() {
        if (currentState == MacroState.State.OFF || currentState == MacroState.State.RECOVERING)
            return;

        long now = System.currentTimeMillis();
        if (lastSessionStartTime <= 0) {
            lastPeriodicSaveTime = now;
            return;
        }
        if (now - lastPeriodicSaveTime > 60000) { // 1 minute
            lastPeriodicSaveTime = now;
            long diff = Math.max(0L, now - lastSessionStartTime);

            if (AetherConfig.PERSIST_SESSION_TIMER.get()) {
                // Keep session timer as is for pause/unpause if enabled
            } else {
                // Not actually hit here since we're periodic other than if someone pauses?
                // Wait, sessionAccumulated is only saved to disk if we want it to survive
                // RESTART
            }
            DailyFarmTimeTracker.periodicSave();
            AetherConfig.LIFETIME_ACCUMULATED.set((double) (lifetimeAccumulated + diff));
            AetherConfig.save();
        }
    }

    public static long getSessionRunningTime() {
        if (currentState != MacroState.State.OFF && currentState != MacroState.State.RECOVERING
                && lastSessionStartTime != 0) {
            return sessionAccumulated + (System.currentTimeMillis() - lastSessionStartTime);
        }
        return sessionAccumulated;
    }

    public static long getLifetimeRunningTime() {
        if (currentState != MacroState.State.OFF && currentState != MacroState.State.RECOVERING
                && lastSessionStartTime != 0) {
            return lifetimeAccumulated + (System.currentTimeMillis() - lastSessionStartTime);
        }
        return lifetimeAccumulated;
    }

    public static boolean isMacroRunning() {
        return currentState != MacroState.State.OFF;
    }

    public static boolean isIntentionalDisconnect() {
        return intentionalDisconnect;
    }

    public static void setIntentionalDisconnect(boolean intentional) {
        intentionalDisconnect = intentional;
    }

    public static MacroState.State getCurrentState() {
        return currentState;
    }

    public static void setCurrentState(MacroState.State state) {
        MacroState.State prevState = currentState;
        currentState = state;
        Minecraft client = Minecraft.getInstance();

        if (state == MacroState.State.FARMING && prevState != MacroState.State.FARMING) {
            MacroWorkerThread.getInstance().clearPendingTasks();
            PathfindingManager.stop();
        }

        if (prevState == MacroState.State.OFF && state != MacroState.State.OFF
                && state != MacroState.State.RECOVERING) {
            lastSessionStartTime = System.currentTimeMillis();
            DailyFarmTimeTracker.onMacroStart();
            if (!AetherConfig.PERSIST_SESSION_TIMER.get()) {
                sessionAccumulated = 0;
                ProfitManager.reset();
                AutoCarnivalManager.resetTokenSession();
            }
            lastPeriodicSaveTime = System.currentTimeMillis();
        } else if (prevState == MacroState.State.RECOVERING && state != MacroState.State.OFF
                && state != MacroState.State.RECOVERING) {
            lastSessionStartTime = System.currentTimeMillis();
            DailyFarmTimeTracker.onMacroStart();
            FailsafeManager.syncExpectedRotationFromClient(client);
            FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        } else if (prevState != MacroState.State.OFF && prevState != MacroState.State.RECOVERING
                && (state == MacroState.State.OFF || state == MacroState.State.RECOVERING)) {
            if (lastSessionStartTime != 0) {
                long diff = System.currentTimeMillis() - lastSessionStartTime;
                sessionAccumulated += diff;
                lifetimeAccumulated += diff;
                lastSessionStartTime = 0;

                AetherConfig.LIFETIME_ACCUMULATED.set((double) lifetimeAccumulated);
                AetherConfig.save();
            }
            DailyFarmTimeTracker.onMacroStop();
        }

        // Mouse Grab/Ungrab Logic
        if (state != MacroState.State.OFF) {
            runOnClientThread(client, () -> {
                if (AetherConfig.MACRO_UNGRAB_MOUSE.get()) {
                    UngrabMouse.requestMacroUngrab();
                }
                PerformanceModeManager.start(client);
                MuteManager.start(client);
            });
        } else {
            runOnClientThread(client, () -> {
                UngrabMouse.clearMacroUngrab();
                PerformanceModeManager.stop(client);
                MuteManager.stop(client);
            });
        }
    }

    public static void stopMacro(Minecraft client) {
        stopMacro(client, "Macro stopped by user");
    }

    public static void stopMacro(Minecraft client, String debugReason) {
        stopMacro(client, debugReason, true);
    }

    public static void stopMacro(Minecraft client, String debugReason, boolean closeScreen) {
        MacroWorkerThread.getInstance().cancelCurrent();
        // Stop any active internal farming macro.
        runOnClientThread(client, () -> FarmingMacroManager.disable(client));
        MetalDetectorSolver.stopForMacro(client);
        AutoCarnivalManager.stopForMacro(client);
        FailsafeManager.reset();
        SqueakyMousematManager.clearReapplyAttempt();
        if (client != null) {
            client.execute(() -> {
                if (closeScreen && client.screen != null) {
                    client.setScreen(null);
                }
                UngrabMouse.clearMacroUngrab();
            });
        }
        setCurrentState(MacroState.State.OFF);
        ClientUtils.forceReleaseKeys(client);
        ClientUtils.sendDebugMessage(debugReason);
        PestManager.reset();
        PestExchangeManager.stop();
        PestDestroyer.stop(client);
        PestTrapManager.cancel(client);
        AutoSellManager.cancel(client);
        AutoSprayonatorManager.cancel();
        AutoSprayonatorManager.reset();
        AutoPestExchangeManager.reset();
        GreenhouseManager.reset();
        ComposterManager.reset();
        SupercraftManager.reset();
        GearManager.reset();
        GeorgeManager.reset();
        BookCombineManager.reset();
        JunkManager.reset();
        RecoveryManager.reset();
        RestartManager.reset();
        if (!AetherConfig.PERSIST_SESSION_TIMER.get()) {
            DynamicRestManager.reset();
            ProfitManager.reset();
            AutoCarnivalManager.resetTokenSession();
        }
        ReconnectScheduler.cancel();
        PathfindingManager.stop();
        VisitorsMacro.stop(client);
    }

    private static void runOnClientThread(Minecraft client, Runnable action) {
        if (client == null || action == null) {
            return;
        }
        if (client.isSameThread()) {
            action.run();
            return;
        }
        client.execute(action);
    }
}
