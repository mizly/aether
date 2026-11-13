package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.metaldetector.MetalDetectorSolver
import dev.aether.modules.farming.FastLaneSwitchManager
import dev.aether.modules.misc.AutoCarnivalManager
import dev.aether.modules.profit.ProfitManager
import dev.aether.modules.session.DailyFarmTimeTracker
import dev.aether.modules.session.DynamicRestManager
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.BpsTracker
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

open class MainStatusHudElement : HudElement() {
    private var macroLerp = -1f
    private var farmingLerp = -1f
    private var contentH = -1f

    override fun getX(): Float = AetherConfig.MAIN_STATUS_HUD_X.get().toFloat()
    override fun getY(): Float = AetherConfig.MAIN_STATUS_HUD_Y.get().toFloat()
    override fun setX(x: Float) { AetherConfig.MAIN_STATUS_HUD_X.set(x.toInt()) }
    override fun setY(y: Float) { AetherConfig.MAIN_STATUS_HUD_Y.set(y.toInt()) }
    override fun getScale(): Float = AetherConfig.MAIN_STATUS_HUD_SCALE.get()
    override fun setScale(s: Float) { AetherConfig.MAIN_STATUS_HUD_SCALE.set(s) }
    override fun getWidth(): Float = W
    override fun getHeight(): Float = HEADER_H + if (contentH < 0f) { if (MacroStateManager.isMacroRunning()) CONTENT_H else 0f } else contentH
    override fun isVisible(): Boolean {
        if (AetherConfig.HUD_THEME.get() and 0x1 == 0) return false
        val mc = try { Minecraft.getInstance() } catch (error: Throwable) { null }
        return mc == null || mc.gui.screen() == null || mc.gui.screen() is HudEditScreen
    }
    override fun getName(): String = "Main Status"
    override fun savePosition() { AetherConfig.save() }

    protected override fun renderElement(nvg: NVGRenderer, editMode: Boolean) {
        val mc = Minecraft.getInstance()
        val st = MacroStateManager.getCurrentState()
        val loc = ClientUtils.getCurrentLocation()
        val macroing = editMode || st != MacroState.State.OFF
        val farming = editMode || st == MacroState.State.FARMING
        val metalDetecting = st == MacroState.State.METAL_DETECTING
        val autoCarnival = st == MacroState.State.AUTO_CARNIVAL
        val gradient = AetherConfig.MAIN_STATUS_GRADIENT.get()
        val gradLeft = if (gradient) AetherConfig.MAIN_STATUS_GRADIENT_LEFT.get() else Theme.HUD_ACCENT
        val gradRight = if (gradient) AetherConfig.MAIN_STATUS_GRADIENT_RIGHT.get() else Theme.HUD_ACCENT

        val speed = Theme.animationFactor(1f)
        if (macroLerp < 0f) macroLerp = if (macroing) 1f else 0f
        if (farmingLerp < 0f) farmingLerp = if (farming) 1f else 0f
        if (contentH < 0f) contentH = if (macroing) CONTENT_H else 0f
        macroLerp += ((if (macroing) 1f else 0f) - macroLerp) * speed
        farmingLerp += ((if (farming) 1f else 0f) - farmingLerp) * speed
        contentH += ((if (macroing) CONTENT_H else 0f) - contentH) * speed
        if (editMode) { macroLerp = 1f; farmingLerp = 1f; contentH = CONTENT_H }
        val fullH = HEADER_H + contentH
        val bw = W - PAD_H * 2
        HudStyle.panel(nvg, W, fullH)
        HudStyle.accent(nvg, W, gradLeft, gradRight)
        var ry = PAD_V

        val logoBoxColor = Theme.blend(Theme.HUD_SEP, gradLeft, macroLerp)
        nvg.roundedRect(PAD_H, ry, ICON_BOX, ICON_BOX, 6f, HudStyle.alpha(logoBoxColor, 0.14f))
        val logoSz = ICON_BOX * 0.55f
        val logoOff = (ICON_BOX - logoSz) / 2
        nvg.renderSVG(LOGO_ICON, PAD_H + logoOff, ry + logoOff, logoSz, logoSz, logoBoxColor)
        val textX = PAD_H + ICON_BOX + 8f
        val stateY = ry + ICON_BOX / 2 - STATE_SZ - 1
        val titleStr = if (!macroing) "Waiting" else if (metalDetecting) "Metal Detector" else if (autoCarnival) "Auto Carnival" else "Farming Macro"
        HudStyle.text(nvg, Fonts.BOLD, titleStr, textX, stateY, W - textX - PAD_H - 18f, STATE_SZ, Theme.HUD_TITLE)
        val locStr = locationLabel(loc)
        val stateStr = stateLabel(st)
        val subtitle = if (st == MacroState.State.OFF) locStr else "$locStr - $stateStr"
        HudStyle.text(nvg, Fonts.REGULAR, subtitle, textX, stateY + STATE_SZ + 2, W - textX - PAD_H, LOC_SZ, HudStyle.stateColor(st))
        ry += ICON_BOX + PAD_V
        if (contentH < 0.5f) return
        nvg.save()
        nvg.scissor(0f, HEADER_H, W, contentH)
        nvg.globalAlpha(macroLerp)
        nvg.rect(PAD_H, ry, bw, SEP_H, Theme.HUD_SEP)
        ry += SEP_H + 8
        val totalProfit = ProfitManager.getTotalProfit(false)
        val carnivalTokens = AutoCarnivalManager.getSessionTokensEarned()
        val carnivalTokensPerHour = AutoCarnivalManager.getSessionTokensPerHour()
        val scavengedTools = MetalDetectorSolver.getScavengedToolCount(mc)
        val backpackSummary = MetalDetectorSolver.getFilledBackpacksSummary()
        val primaryLabel = if (metalDetecting) "TOOLS IN INVENTORY" else if (autoCarnival) "CARNIVAL TOKENS" else "SESSION PROFIT"
        val primaryValue = if (metalDetecting) scavengedTools.toString() else if (autoCarnival) fmtCompact(carnivalTokens) else fmtCompact(totalProfit)
        nvg.text(Fonts.REGULAR, primaryLabel, PAD_H, ry, LABEL_SZ, Theme.HUD_LABEL)
        ry += LABEL_SZ + 3
        nvg.text(Fonts.BOLD, primaryValue, PAD_H, ry, VAL_SZ, Theme.HUD_VALUE)
        ry += VAL_SZ + 6
        val sesMs = MacroStateManager.getSessionRunningTime()
        val cph = if (sesMs > 0) (totalProfit / (sesMs / 3_600_000.0)).toLong() else 0L
        infoRow(nvg, ry, if (metalDetecting) "Filled Backpacks" else if (autoCarnival) "Tokens/hr" else "Coins/hr", if (metalDetecting) backpackSummary else if (autoCarnival) fmtCompact(carnivalTokensPerHour) else fmtCompact(cph), Theme.HUD_VALUE); ry += ROW_H
        infoRow(nvg, ry, "Next Lane", FastLaneSwitchManager.getDisplayText(), if (FastLaneSwitchManager.hasEstimate()) Theme.HUD_VALUE else Theme.HUD_LABEL); ry += ROW_H
        infoRow(nvg, ry, "Session", formatSessionTime(sesMs), Theme.HUD_VALUE); ry += ROW_H
        infoRow(nvg, ry, "Daily", formatSessionTime(DailyFarmTimeTracker.getTodayMs()), Theme.HUD_VALUE); ry += ROW_H
        val restTrigger = DynamicRestManager.getNextRestTriggerMs()
        val sched = DynamicRestManager.getScheduledDurationMs()
        val prog = if (sched > 0 && restTrigger > 0) (sched - maxOf(0, restTrigger - System.currentTimeMillis())).toFloat() / sched else 0f
        nvg.roundedRect(PAD_H, ry, bw, BAR_H, BAR_H / 2, Theme.HUD_BAR_BG)
        val fw = bw * prog.coerceIn(0f, 1f)
        if (fw > 0) {
            if (gradient) nvg.horizontalGradient(PAD_H, ry, fw, BAR_H, BAR_H / 2, gradLeft, gradRight)
            else nvg.roundedRect(PAD_H, ry, fw, BAR_H, BAR_H / 2, Theme.HUD_ACCENT)
        }
        ry += BAR_H + 8
        val gridH = BOX_PAD_V * 2 + LABEL_SZ + 4 + SES_SZ
        val halfW = (bw - 4) / 2
        val bpsBoxW = halfW * farmingLerp
        val restBoxW = bw - if (farmingLerp > 0.01f) bpsBoxW + 4 else 0f
        val restValStr = if (restTrigger <= 0) "---" else "In " + formatSessionTime(maxOf(0, restTrigger - System.currentTimeMillis()))
        nvg.roundedRect(PAD_H, ry, restBoxW, gridH, BOX_R, Theme.HUD_BAR_BG)
        nvg.text(Fonts.REGULAR, "NEXT REST", PAD_H + BOX_PAD_H, ry + BOX_PAD_V, LABEL_SZ, Theme.HUD_LABEL)
        HudStyle.text(nvg, Fonts.BOLD, restValStr, PAD_H + BOX_PAD_H, ry + BOX_PAD_V + LABEL_SZ + 4, restBoxW - BOX_PAD_H * 2, SES_SZ, Theme.HUD_VALUE)
        if (farmingLerp > 0.01f) {
            val bpsX = PAD_H + restBoxW + 4
            val bps = BpsTracker.getBps()
            val bpsClr = HudStyle.bpsColor(bps.toDouble())
            val bpsStr = String.format("%.1f", bps.toDouble())
            nvg.save()
            nvg.globalAlpha(macroLerp * farmingLerp)
            nvg.roundedRect(bpsX, ry, bpsBoxW, gridH, BOX_R, Theme.HUD_BAR_BG)
            nvg.text(Fonts.REGULAR, "BPS", bpsX + BOX_PAD_H, ry + BOX_PAD_V, LABEL_SZ, Theme.HUD_LABEL)
            nvg.text(Fonts.BOLD, bpsStr, bpsX + BOX_PAD_H, ry + BOX_PAD_V + LABEL_SZ + 4, SES_SZ, bpsClr)
            nvg.restore()
        }
        nvg.restore()
    }

    private fun infoRow(nvg: NVGRenderer, y: Float, label: String, value: String, valColor: Int) {
        val cy = y + ROW_H / 2
        val textY = cy - ROW_SZ / 2 + 0.5f
        HudStyle.row(nvg, PAD_H, textY, W - PAD_H * 2, label, value, ROW_SZ, valColor)
    }

    companion object {
        @JvmField val W = 240f
        private const val PAD_H = 12f
        private const val PAD_V = 10f
        private const val ICON_BOX = 30f
        private const val HEADER_H = PAD_V + ICON_BOX + PAD_V
        private const val STATE_SZ = 12f
        private const val LOC_SZ = 9f
        private const val LABEL_SZ = 9f
        private const val VAL_SZ = 20f
        private const val SES_SZ = 10f
        private const val ROW_SZ = 10f
        private const val ROW_H = 16f
        private const val SEP_H = 1f
        private const val BOX_R = 4f
        private const val BOX_PAD_H = 8f
        private const val BOX_PAD_V = 6f
        private const val BAR_H = 3f
        private const val CONTENT_H = SEP_H + 8f + LABEL_SZ + 3f + VAL_SZ + 6f + ROW_H * 4 + 6f + BAR_H + 8f + (BOX_PAD_V * 2 + LABEL_SZ + 4f + SES_SZ) + PAD_V
        private const val LOGO_ICON = "/assets/aether/icons/logo.svg"
        @JvmStatic private fun stateLabel(st: MacroState.State): String = when (st) {
            MacroState.State.FARMING -> "Farming"
            MacroState.State.METAL_DETECTING -> "Metal Detecting"
            MacroState.State.AUTO_CARNIVAL -> "Auto Carnival"
            MacroState.State.CLEANING -> "Cleaning"
            MacroState.State.RECOVERING -> "Recovering"
            MacroState.State.VISITING -> "Visitor"
            MacroState.State.AUTOSELLING -> "Autoselling"
            MacroState.State.WARDROBE -> "Wardrobe"
            MacroState.State.EQUIPMENT -> "Equipment"
            MacroState.State.GEORGE -> "George"
            MacroState.State.DROPPING_JUNK -> "Dropping Junk"
            else -> "Idle"
        }
        @JvmStatic private fun locationLabel(loc: MacroState.Location): String = when (loc) {
            MacroState.Location.GARDEN -> "Garden"
            MacroState.Location.CRYSTAL_HOLLOWS -> "Crystal Hollows"
            MacroState.Location.HUB -> "Hub"
            MacroState.Location.LOBBY -> "Lobby"
            MacroState.Location.LIMBO -> "Limbo"
            else -> "Unknown"
        }
        @JvmStatic private fun fmtCompact(v: Long): String = when {
            v < 0 -> "-" + fmtCompact(-v)
            v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000.0)
            v >= 1_000 -> String.format("%.1fk", v / 1_000.0)
            else -> v.toString()
        }
        @JvmStatic private fun formatSessionTime(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = s % 3600 / 60
            val sec = s % 60
            return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
        }
    }
}
