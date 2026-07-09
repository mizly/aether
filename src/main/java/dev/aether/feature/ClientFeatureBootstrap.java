package dev.aether.feature;

import dev.aether.bootstrap.AetherChatEvents;
import dev.aether.bootstrap.AetherCommandRegistrar;
import dev.aether.bootstrap.AetherScreenHooks;
import dev.aether.bootstrap.AetherTickHandlers;
import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigManager;
import dev.aether.hud.HudRegistry;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.ReconnectScheduler;
import dev.aether.modules.failsafe.FailsafeSoundManager;
import dev.aether.modules.misc.AutoCarnivalManager;
import dev.aether.modules.pathfinding.debug.PathVisualizer;
import dev.aether.modules.performance.MuteManager;
import dev.aether.modules.performance.PerformanceModeManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.notification.NotificationManager;
import dev.aether.renderer.FunRenderer;
import dev.aether.renderer.PositionHighlighter;
import dev.aether.ui.theme.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;

import java.io.File;

public final class ClientFeatureBootstrap {
    private static boolean initialized;

    private ClientFeatureBootstrap() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        ConfigManager.init();
        AetherConfig.init();
        FailsafeSoundManager.init();
        Theme.loadTheme();
        ProfitManager.loadLifetime();
        ProfitManager.loadDaily();
        MacroStateManager.syncFromConfig();
        AutoCarnivalManager.syncFromConfig(Minecraft.getInstance());
        ReconnectScheduler.clearState();
        HudRegistry.register();
        MacroWorkerThread.getInstance().start();
        PathVisualizer.register();

        LevelRenderEvents.END_MAIN.register(ctx -> {
            if (StreamerModeManager.isEnabled()) {
                return;
            }

            boolean drawPathVisualizer = PathVisualizer.shouldRender();
            boolean drawPositionHighlights = PositionHighlighter.hasVisibleHighlights();
            boolean drawFunEffects = FunRenderer.hasVisibleEffects();
            if (!drawPathVisualizer && !drawPositionHighlights && !drawFunEffects) {
                return;
            }
            if (drawPathVisualizer) {
                PathVisualizer.renderWorld();
            }
            if (drawPositionHighlights) {
                PositionHighlighter.renderWorld(ctx);
            }
            if (drawFunEffects) {
                FunRenderer.renderWorld(ctx);
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(PerformanceModeManager::stop);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AetherConfig.flush());

        AetherScreenHooks.register();
        AetherChatEvents.register();
        AetherCommandRegistrar.register();
        AetherTickHandlers.register();

        initialized = true;
    }

    public static synchronized void shutdown() {
        // Commit any in-progress daily farming time before teardown: a macro left running
        // until the game closes never fires onMacroStop(), so this prevents the day's
        // un-committed time from being lost on exit.
        dev.aether.modules.session.DailyFarmTimeTracker.persistNow();
        PerformanceModeManager.stop(Minecraft.getInstance());
        NotificationManager.clearAll();
        HudRegistry.reset();
        PathVisualizer.clear();
        ReconnectScheduler.clearState();
        MacroWorkerThread.getInstance().cancelCurrent();
        MacroWorkerThread.getInstance().clearPendingTasks();
        initialized = false;
    }

    public static synchronized void onConfigProfileLoaded(File profileFile) {
        FailsafeSoundManager.refresh();
        MacroStateManager.syncFromConfig();
        AutoCarnivalManager.syncFromConfig(Minecraft.getInstance());

        Minecraft client = Minecraft.getInstance();
        PerformanceModeManager.stop(client);
        MuteManager.stop(client);
        if (MacroStateManager.isMacroRunning()) {
            PerformanceModeManager.start(client);
            MuteManager.start(client);
        }
    }

}

