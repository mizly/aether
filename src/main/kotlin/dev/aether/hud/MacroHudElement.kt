package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.farming.FastLaneSwitchManager
import dev.aether.modules.session.DynamicRestManager
import dev.aether.renderer.NVGRenderer
import dev.aether.telemetry.AetherAuthService
import dev.aether.ui.theme.Theme
import dev.aether.util.BpsTracker
import dev.aether.util.ClientUtils
import java.util.Locale

class MacroHudElement : HudElement() {
    override fun getX(): Float = AetherConfig.HUD_X.get().toFloat()
    override fun getY(): Float = AetherConfig.HUD_Y.get().toFloat()
    override fun setX(x: Float) { AetherConfig.HUD_X.set(x.toInt()) }
    override fun setY(y: Float) { AetherConfig.HUD_Y.set(y.toInt()) }
    override fun getScale(): Float = AetherConfig.HUD_SCALE.get()
    override fun setScale(scale: Float) { AetherConfig.HUD_SCALE.set(scale) }
    override fun getWidth(): Float = W
    override fun getHeight(): Float = computeHeight()
    override fun isEnabled(): Boolean = AetherConfig.SHOW_HUD.get()
    override fun isVisible(): Boolean {
        val inSupportedArea = ClientUtils.isSupportedHudArea()
        return isEnabled() && (inSupportedArea || AetherConfig.SHOW_HUD_OUTSIDE_GARDEN.get())
    }
    override fun getName(): String = "Macro HUD"
    override fun savePosition() { AetherConfig.save() }

    internal fun computeHeight(): Float = HudStyle.CONTENT_Y + 6 * ROW_H + 8f + HudStyle.PAD

    protected override fun renderElement(nvg: NVGRenderer, editMode: Boolean) {
        val state = MacroStateManager.getCurrentState()
        val stateLabel = state.name.lowercase(Locale.ROOT).replace('_', ' ')
        val sessionMs = MacroStateManager.getSessionRunningTime()
        val restTrigger = DynamicRestManager.getNextRestTriggerMs()
        val nextRest = if (restTrigger <= 0) "---" else formatTime(Math.max(0, restTrigger - System.currentTimeMillis()))

        HudStyle.panel(nvg, W, computeHeight())
        HudStyle.header(nvg, W, "Macro", "Aether")
        var y = HudStyle.CONTENT_Y
        row(nvg, y, "Status", stateLabel, HudStyle.stateColor(state)); y += ROW_H
        row(nvg, y, String.format("BPS (%d / %.1fs)", BpsTracker.getBreakCount(), BpsTracker.getActualWindowSeconds()),
            String.format("%.2f", BpsTracker.getBps()), Theme.HUD_VALUE); y += ROW_H
        row(nvg, y, "Next lane", FastLaneSwitchManager.getDisplayText(),
            if (FastLaneSwitchManager.hasEstimate()) Theme.HUD_VALUE else Theme.HUD_LABEL); y += ROW_H
        row(nvg, y, "Current session", formatTime(sessionMs), Theme.HUD_VALUE); y += ROW_H
        row(nvg, y, "Hours played", if (AetherAuthService.isAuthenticated())
            formatTime(AetherAuthService.getTotalSeconds() * 1000L) else "not linked", Theme.HUD_VALUE); y += ROW_H
        row(nvg, y, "Next rest", nextRest, Theme.HUD_VALUE); y += ROW_H

        val duration = DynamicRestManager.getScheduledDurationMs()
        val progress = if (duration > 0 && restTrigger > 0)
            (duration - Math.max(0, restTrigger - System.currentTimeMillis())).toFloat() / duration else 0f
        HudStyle.progress(nvg, HudStyle.PAD, y + 3f, W - HudStyle.PAD * 2, 4f, progress)
    }

    private fun row(nvg: NVGRenderer, y: Float, label: String, value: String, color: Int) {
        HudStyle.row(nvg, HudStyle.PAD, y, W - HudStyle.PAD * 2, label, value, LABEL_SZ, color)
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s % 60)
        else String.format("%02d:%02d", m, s % 60)
    }

    companion object {
        const val W = 240f
        private const val ROW_H = 18f
        private const val LABEL_SZ = 10f
    }
}
