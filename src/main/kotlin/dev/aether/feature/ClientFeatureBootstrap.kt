package dev.aether.feature

import dev.aether.bootstrap.AetherChatEvents
import dev.aether.bootstrap.AetherCommandRegistrar
import dev.aether.bootstrap.AetherScreenHooks
import dev.aether.bootstrap.AetherTickHandlers
import dev.aether.config.AetherConfig
import dev.aether.config.ConfigManager
import dev.aether.hud.HudRegistry
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.ReconnectScheduler
import dev.aether.modules.failsafe.FailsafeSoundManager
import dev.aether.modules.irc.IrcManager
import dev.aether.modules.misc.AutoCarnivalManager
import dev.aether.modules.pathfinding.debug.PathVisualizer
import dev.aether.modules.pest.helpers.PestTrackerAbility
import dev.aether.modules.performance.MuteManager
import dev.aether.modules.performance.PerformanceModeManager
import dev.aether.modules.profit.ProfitManager
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.modules.visuals.PestEspManager
import dev.aether.notification.NotificationManager
import dev.aether.renderer.FunRenderer
import dev.aether.renderer.CosmeticWorldRenderer
import dev.aether.renderer.PositionHighlighter
import dev.aether.telemetry.AetherAuthService
import dev.aether.telemetry.AetherTelemetryService
import dev.aether.telemetry.ban.AetherBanService
import dev.aether.telemetry.ban.PlaySessionTracker
import dev.aether.ui.MainGUI
import dev.aether.ui.theme.Theme
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import java.io.File

class ClientFeatureBootstrap private constructor() {
    companion object {
        private var initialized = false

        @JvmStatic @Synchronized
        fun initialize() {
            if (initialized) return
            ConfigManager.init(); AetherConfig.init(); FailsafeSoundManager.init(); Theme.loadTheme()
            MainGUI.uiScale = Theme.UI_SCALE // apply persisted GUI scale (Theme is the source of truth)
            MainGUI.uiTextScale = Theme.TEXT_SCALE // apply persisted text scale before the first frame
            ProfitManager.loadLifetime(); ProfitManager.loadDaily(); MacroStateManager.syncFromConfig(); AutoCarnivalManager.syncFromConfig(Minecraft.getInstance()); ReconnectScheduler.clearState(); HudRegistry.register(); MacroWorkerThread.getInstance().start(); AetherAuthService.initialize(); IrcManager.initialize(); PlaySessionTracker.register(); PathVisualizer.register()
            LevelRenderEvents.END_MAIN.register { ctx ->
                if (StreamerModeManager.isEnabled()) return@register
                val drawPathVisualizer = PathVisualizer.shouldRender(); val drawPositionHighlights = PositionHighlighter.hasVisibleHighlights(); val drawPestEsp = PestEspManager.hasVisibleHighlights(); val drawPestTracker = PestTrackerAbility.hasVisibleArc(); val drawFunEffects = FunRenderer.hasVisibleEffects()
                if (!drawPathVisualizer && !drawPositionHighlights && !drawPestEsp && !drawPestTracker && !drawFunEffects) return@register
                if (drawPathVisualizer) PathVisualizer.renderWorld()
                if (drawPositionHighlights) PositionHighlighter.renderWorld(ctx)
                if (drawPestEsp) PestEspManager.renderWorld()
                if (drawPestTracker) PestTrackerAbility.renderWorld()
                if (drawFunEffects) FunRenderer.renderAfterMain()
            }
            // 26.2 only takes vertices and text while submits are being collected
            LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
                if (StreamerModeManager.isEnabled()) return@register
                if (PositionHighlighter.hasVisibleHighlights()) PositionHighlighter.collectLabels(ctx)
                FunRenderer.collectSubmits(ctx)
            }
            ClientLifecycleEvents.CLIENT_STOPPING.register(PerformanceModeManager::stop)
            ClientLifecycleEvents.CLIENT_STOPPING.register { CosmeticWorldRenderer.close() }
            ClientLifecycleEvents.CLIENT_STOPPING.register { dev.aether.renderer.SkyboxRenderer.close() }
            ClientLifecycleEvents.CLIENT_STOPPING.register { AetherConfig.flush() }
            AetherScreenHooks.register(); AetherChatEvents.register(); AetherCommandRegistrar.register(); AetherTickHandlers.register()
            initialized = true
        }

        @JvmStatic @Synchronized
        fun shutdown() {
            dev.aether.modules.session.DailyFarmTimeTracker.persistNow()
            dev.aether.modules.profit.ProfitManager.flush()
            AetherTelemetryService.shutdown(); AetherBanService.shutdown(); IrcManager.shutdown(); AetherAuthService.shutdown(); PlaySessionTracker.clear()
            PerformanceModeManager.stop(Minecraft.getInstance()); NotificationManager.clearAll(); HudRegistry.reset(); PathVisualizer.clear(); CosmeticWorldRenderer.close(); dev.aether.renderer.SkyboxRenderer.close(); ReconnectScheduler.clearState(); MacroWorkerThread.getInstance().cancelCurrent(); MacroWorkerThread.getInstance().clearPendingTasks()
            initialized = false
        }

        @JvmStatic @Synchronized
        fun onConfigProfileLoaded(profileFile: File) {
            FailsafeSoundManager.refresh(); MacroStateManager.syncFromConfig(); IrcManager.syncFromConfig(); AutoCarnivalManager.syncFromConfig(Minecraft.getInstance())
            val client = Minecraft.getInstance()
            PerformanceModeManager.stop(client); MuteManager.stop(client)
            if (MacroStateManager.isMacroRunning()) { PerformanceModeManager.start(client); MuteManager.start(client) }
        }
    }
}
