package dev.aether.ui

import dev.aether.macro.ReconnectScheduler
import dev.aether.renderer.NVGRenderer
import dev.aether.renderer.NVGScreen
import dev.aether.ui.components.Button
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import net.minecraft.client.gui.screens.TitleScreen

// rest countdown, progress bar, and a cancel button back to the title screen
class DynamicRestScreen : NVGScreen {
    private val restEndTimeMs: Long
    private val totalDurationMs: Long
    private val panelTitle: String
    private val statusLabel: String
    private val cancelButtonLabel: String
    private var cancelButton: Button? = null

    constructor(restEndTimeMs: Long, totalDurationMs: Long) : this(DEFAULT_SCREEN_TITLE, DEFAULT_PANEL_TITLE, DEFAULT_STATUS_LABEL, DEFAULT_CANCEL_LABEL, restEndTimeMs, totalDurationMs)

    constructor(screenTitle: String, panelTitle: String, statusLabel: String, cancelButtonLabel: String, restEndTimeMs: Long, totalDurationMs: Long) : super(screenTitle) {
        this.restEndTimeMs = restEndTimeMs
        this.totalDurationMs = totalDurationMs
        this.panelTitle = panelTitle
        this.statusLabel = statusLabel
        this.cancelButtonLabel = cancelButtonLabel
    }

    override fun initNVG() {
        val btnW = 200f
        val btnH = 24f
        val btnX = (width - btnW) / 2f
        val btnY = (height + PANEL_H) / 2f + 20f
        cancelButton = addComponent(Button.builder(cancelButtonLabel, btnW, btnH).accent(true).onPress {
            ReconnectScheduler.cancel()
            if (minecraft != null) minecraft.gui.setScreen(TitleScreen())
        }.build())
        cancelButton?.setPosition(btnX, btnY)
    }

    override fun renderNVG(nvg: NVGRenderer) {
        nvg.rect(0f, 0f, width.toFloat(), height.toFloat(), 0xFF0A0A14.toInt())
        val now = System.currentTimeMillis()
        val remainingMs = Math.max(0L, restEndTimeMs - now)
        val progress = if (totalDurationMs > 0L) (totalDurationMs - remainingMs).toFloat() / totalDurationMs.toFloat() else 1.0f
        val panelX = (width - PANEL_W) / 2f
        val panelY = (height - PANEL_H) / 2f - 20f
        nvg.shadow(panelX, panelY, PANEL_W, PANEL_H, RADIUS, 12f, 0x40000000)
        nvg.roundedRect(panelX, panelY, PANEL_W, PANEL_H, RADIUS, BG_COLOR)
        val titleSize = 16f
        nvg.text(Fonts.BOLD, panelTitle, panelX + (PANEL_W - nvg.textWidth(Fonts.BOLD, panelTitle, titleSize)) / 2f, panelY + 12f, titleSize, Colors.WHITE)
        nvg.rect(panelX + 10f, panelY + 34f, PANEL_W - 20f, 1f, SEPARATOR_COLOR)
        val totalSecs = remainingMs / 1000L
        val timeStr = String.format("%02d:%02d", (totalSecs / 60L) % 60L, totalSecs % 60L)
        val labelSize = 13f
        val timeSize = 13f
        nvg.text(Fonts.REGULAR, statusLabel, panelX + 12f, panelY + 48f, labelSize, Colors.TEXT_DIM)
        nvg.text(Fonts.MONO, timeStr, panelX + PANEL_W - 12f - nvg.textWidth(Fonts.MONO, timeStr, timeSize), panelY + 48f, timeSize, Colors.WHITE)
        val barX = panelX + 12f
        val barY = panelY + 72f
        val barW = PANEL_W - 24f
        nvg.roundedRect(barX, barY, barW, BAR_H, BAR_RADIUS, BAR_BG_COLOR)
        val fillW = barW * Math.max(0f, Math.min(1f, progress))
        if (fillW > 0f) nvg.roundedRect(barX, barY, fillW, BAR_H, BAR_RADIUS, BAR_FILL_COLOR)
        if (remainingMs <= 0L) {
            val reconnecting = "Reconnecting now..."
            val reconnectSize = 14f
            nvg.text(Fonts.REGULAR, reconnecting, (width - nvg.textWidth(Fonts.REGULAR, reconnecting, reconnectSize)) / 2f, panelY + PANEL_H + 12f, reconnectSize, RECONNECT_COLOR)
        }
    }

    override fun shouldCloseOnEsc(): Boolean = false

    companion object {
        private const val DEFAULT_SCREEN_TITLE = "Dynamic Rest"
        private const val DEFAULT_PANEL_TITLE = "Dynamic Rest"
        private const val DEFAULT_STATUS_LABEL = "reconnecting in"
        private const val DEFAULT_CANCEL_LABEL = "Cancel Rest & Exit to Menu"
        private const val PANEL_W = 260f
        private const val PANEL_H = 120f
        private const val RADIUS = 8f
        private const val BAR_H = 8f
        private const val BAR_RADIUS = 4f
        private const val BG_COLOR = 0xFF141424.toInt()
        private const val BAR_BG_COLOR = 0xFF1A1A32.toInt()
        private val BAR_FILL_COLOR = Theme.ACCENT_PRIMARY
        private val SEPARATOR_COLOR = Theme.ACCENT_PRIMARY
        private const val RECONNECT_COLOR = 0xFFAA00
    }
}
