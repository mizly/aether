package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroStateManager
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.notification.NotificationManager
import dev.aether.notification.NotificationRenderer
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NVGRenderer
import dev.aether.renderer.NanoVGManager
import dev.aether.ui.MainGUI
import dev.aether.ui.theme.Theme
import dev.aether.util.ClientUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.util.ArrayList

class HudRegistry private constructor() {
    companion object {
        private const val FADE_EPSILON = 0.01f

        @JvmField val ELEMENTS: MutableList<HudElement> = ArrayList()
        private var hudAlpha = 1f

        @JvmField var macroHud: MacroHudElement? = null
        @JvmField var sessionHud: ProfitHudElement? = null
        @JvmField var lifetimeHud: ProfitHudElement? = null
        @JvmField var dailyHud: ProfitHudElement? = null
        @JvmField var intermediariesHud: TaskGroupHudElement? = null
        @JvmField var midFarmingHud: TaskGroupHudElement? = null
        @JvmField var failsafesHud: TaskGroupHudElement? = null
        @JvmField var inventoryHud: InventoryHudElement? = null
        @JvmField var watermarkHud: WatermarkHudElement? = null
        @JvmField var mainStatusHud: MainStatusHudElement? = null
        @JvmField var scoreboardHud: ScoreboardHudElement? = null

        @JvmStatic fun register() {
            val macro = MacroHudElement()
            val session = ProfitHudElement("session")
            val lifetime = ProfitHudElement("lifetime")
            val daily = ProfitHudElement("daily")
            val intermediaries = TaskGroupHudElement(TaskGroupHudElement.Group.INTERMEDIARIES)
            val midFarming = TaskGroupHudElement(TaskGroupHudElement.Group.MID_FARMING_TASKS)
            val failsafes = TaskGroupHudElement(TaskGroupHudElement.Group.FAILSAFES)
            val inventory = InventoryHudElement()
            val watermark = WatermarkHudElement()
            val mainStatus = MainStatusHudElement()
            val scoreboard = ScoreboardHudElement()
            macroHud = macro
            sessionHud = session
            lifetimeHud = lifetime
            dailyHud = daily
            intermediariesHud = intermediaries
            midFarmingHud = midFarming
            failsafesHud = failsafes
            inventoryHud = inventory
            watermarkHud = watermark
            mainStatusHud = mainStatus
            scoreboardHud = scoreboard
            ELEMENTS.add(macro)
            ELEMENTS.add(session)
            ELEMENTS.add(lifetime)
            ELEMENTS.add(daily)
            ELEMENTS.add(intermediaries)
            ELEMENTS.add(midFarming)
            ELEMENTS.add(failsafes)
            ELEMENTS.add(inventory)
            ELEMENTS.add(PestTargetHudElement())
            ELEMENTS.add(watermark)
            ELEMENTS.add(mainStatus)
            ELEMENTS.add(scoreboard)

            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("aether", "hud")) { guiGraphics, _ ->
                val mc = Minecraft.getInstance()
                if (mc.player == null) {
                    hudAlpha = 0f
                    return@addLast
                }
                if (mc.gui.screen() is HudEditScreen) return@addLast
                if (mc.gui.screen() is MainGUI) return@addLast
                if (StreamerModeManager.isEnabled()) {
                    hudAlpha = 0f
                    return@addLast
                }
                val anyVisible = hasVisibleElements()
                val hasNotifications = NotificationManager.getCount() > 0
                val alpha = tickHudAlpha(canRenderInGameplay(mc) && anyVisible)
                if (alpha <= FADE_EPSILON && !hasNotifications) return@addLast
                val win = mc.window
                val sw = win.guiScaledWidth.toFloat()
                val sh = win.guiScaledHeight.toFloat()
                if (alpha > FADE_EPSILON) {
                    guiGraphics.nextStratum()
                    renderMcElements(guiGraphics)
                    if (ELEMENTS.stream().anyMatch { it.rendersBeforeMinecraft() && it.isVisible() }) {
                        AetherRenderQueue.enqueueBeforeGui { renderBackgroundFrame(sw, sh, alpha) }
                    }
                }
                AetherRenderQueue.enqueue { renderGameplayFrame(sw, sh, alpha) }
            }
        }

        private fun renderMcElements(graphics: net.minecraft.client.gui.GuiGraphicsExtractor) {
            for (e in ELEMENTS) if (e.rendersWithHud() && e.isVisible()) e.renderMinecraft(graphics, false)
        }

        @JvmStatic fun renderConfigTransition(nvg: NVGRenderer) {
            if (StreamerModeManager.isEnabled()) {
                hudAlpha = 0f
                return
            }
            val mc = Minecraft.getInstance()
            if (mc.player == null) {
                hudAlpha = 0f
                return
            }
            val alpha = tickHudAlpha(false)
            if (alpha <= FADE_EPSILON || !canRenderInGameplay(mc)) return
            renderHudElements(nvg, alpha)
        }

        @JvmStatic fun renderEditMode(nvg: NVGRenderer) {
            if (StreamerModeManager.isEnabled()) return
            for (e in ELEMENTS) {
                if (!e.isEnabled()) continue
                e.render(nvg, true)
            }
        }

        private fun hasVisibleElements(): Boolean = ELEMENTS.stream().anyMatch { it.rendersWithHud() && it.isVisible() }

        @JvmStatic fun canRenderInGameplay(mc: Minecraft): Boolean {
            if (AetherConfig.HUD_ONLY_WHILE_MACRO_RUNNING.get() && !MacroStateManager.isMacroRunning()) return false
            return !AetherConfig.GUI_ONLY_IN_GARDEN.get() || ClientUtils.isSupportedHudArea()
        }

        private fun tickHudAlpha(visible: Boolean): Float {
            val target = if (visible) 1f else 0f
            val speed = maxOf(0.08f, minOf(0.35f, Theme.animationFactor(0.625f)))
            hudAlpha += (target - hudAlpha) * speed
            if (kotlin.math.abs(target - hudAlpha) < FADE_EPSILON) hudAlpha = target
            return hudAlpha
        }

        private fun renderHudElements(nvg: NVGRenderer, alpha: Float) {
            if (alpha <= FADE_EPSILON) return
            nvg.save()
            nvg.globalAlpha(alpha)
            for (e in ELEMENTS) if (e.rendersWithHud() && !e.rendersBeforeMinecraft()) e.render(nvg, false)
            nvg.restore()
        }

        private fun renderBackgroundFrame(width: Float, height: Float, alpha: Float) {
            if (StreamerModeManager.isEnabled() || NanoVGManager.isDrawing()) return
            if (!NanoVGManager.isInitialized()) NanoVGManager.init()
            NanoVGManager.beginFrame(width, height)
            val nvg = NanoVGManager.getRenderer() ?: return
            try {
                nvg.globalAlpha(alpha)
                for (element in ELEMENTS) if (element.rendersBeforeMinecraft()) element.render(nvg, false)
            } finally {
                NanoVGManager.endFrame()
            }
        }

        @JvmStatic fun onGuiGraphicsClosed() { }

        private fun renderGameplayFrame(width: Float, height: Float, alpha: Float) {
            if (StreamerModeManager.isEnabled()) return
            if (NanoVGManager.isDrawing()) return
            if (!NanoVGManager.isInitialized()) NanoVGManager.init()
            NanoVGManager.beginFrame(width, height)
            val nvg = NanoVGManager.getRenderer() ?: return
            try {
                renderHudElements(nvg, alpha)
                NotificationRenderer.render(nvg, width, height)
                if (alpha > FADE_EPSILON) renderOverlayElements(nvg, alpha)
            } finally {
                NanoVGManager.endFrame()
            }
        }

        private fun renderOverlayElements(nvg: NVGRenderer, alpha: Float) {
            nvg.save()
            nvg.globalAlpha(alpha)
            for (e in ELEMENTS) {
                if (!e.rendersWithHud() || !e.isVisible()) continue
                nvg.save()
                nvg.translate(e.getX(), e.getY())
                nvg.scale(e.getScale(), e.getScale())
                e.renderOverlay(nvg, false)
                nvg.restore()
            }
            nvg.restore()
        }

        @JvmStatic fun reset() {
            ELEMENTS.clear()
            macroHud = null
            sessionHud = null
            lifetimeHud = null
            dailyHud = null
            intermediariesHud = null
            midFarmingHud = null
            failsafesHud = null
            inventoryHud = null
            watermarkHud = null
            mainStatusHud = null
            scoreboardHud = null
            hudAlpha = 0f
        }
    }
}
