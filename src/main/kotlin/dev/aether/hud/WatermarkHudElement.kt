package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.misc.AutoCarnivalManager
import dev.aether.modules.profit.ProfitManager
import dev.aether.modules.session.DailyFarmTimeTracker
import dev.aether.modules.session.DynamicRestManager
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.BpsTracker
import dev.aether.util.PingTracker
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class WatermarkHudElement : HudElement() {
    private var animW = -1f
    private var renderMacro = false
    private var animStep = 0

    override fun getX(): Float = AetherConfig.WATERMARK_HUD_X.get().toFloat()
    override fun getY(): Float = AetherConfig.WATERMARK_HUD_Y.get().toFloat()
    override fun setX(x: Float) { AetherConfig.WATERMARK_HUD_X.set(x.toInt()) }
    override fun setY(y: Float) { AetherConfig.WATERMARK_HUD_Y.set(y.toInt()) }
    override fun getScale(): Float = AetherConfig.WATERMARK_HUD_SCALE.get()
    override fun setScale(s: Float) { AetherConfig.WATERMARK_HUD_SCALE.set(s) }
    override fun getWidth(): Float = if (animW < 0f) PAD_H * 2f + 200f else animW
    override fun getHeight(): Float = PAD_V * 2f + NAME_SZ
    override fun isVisible(): Boolean = (AetherConfig.HUD_THEME.get() and 0x2) != 0
    override fun getName(): String = "Watermark"
    override fun savePosition() { AetherConfig.save() }

    override fun renderElement(nvg: NVGRenderer, editMode: Boolean) {
        val mc = Minecraft.getInstance()
        val showLogo = AetherConfig.WATERMARK_SHOW_LOGO.get()
        val showName = AetherConfig.WATERMARK_SHOW_NAME.get()
        val gradient = AetherConfig.WATERMARK_GRADIENT.get()
        val gradLeft = if (gradient) AetherConfig.WATERMARK_GRADIENT_LEFT.get() else Theme.HUD_ACCENT
        val gradRight = if (gradient) AetherConfig.WATERMARK_GRADIENT_COLOR.get() else Theme.HUD_ACCENT
        val wantMacro = AetherConfig.WATERMARK_SHOW_MACRO_STATUS.get() && MacroStateManager.isMacroRunning()
        val h = getHeight()
        val nameW = nvg.textWidth(Fonts.BOLD, NAME, NAME_SZ)
        val verW = nvg.textWidth(Fonts.BOLD, VERSION, VER_SZ)
        val brandW = PAD_H + (if (showLogo) ICON_SZ + 4f else 0f) + (if (showName) nameW + 2f else 0f) + verW + 1f + PAD_H
        val showUser = AetherConfig.WATERMARK_SHOW_USERNAME.get()
        val showFps = AetherConfig.WATERMARK_SHOW_FPS.get()
        val showPing = AetherConfig.WATERMARK_SHOW_PING.get()
        val showTime = AetherConfig.WATERMARK_SHOW_TIME.get()
        val custom = AetherConfig.WATERMARK_CUSTOM_USERNAME.get()
        var username = if (custom.isNotEmpty()) custom else if (mc.player != null) mc.player!!.name.string else "---"
        username = HudStyle.fit(nvg, Fonts.REGULAR, username, INFO_SZ, 110f)
        val fps = mc.fps.toString() + " fps"
        val ping = getPing(mc)
        val time = LocalTime.now().format(TIME_FMT)
        val userW = if (showUser) nvg.textWidth(Fonts.REGULAR, username, INFO_SZ) else 0f
        val fpsW = if (showFps) nvg.textWidth(Fonts.REGULAR, fps, INFO_SZ) else 0f
        val pingW = if (showPing) nvg.textWidth(Fonts.REGULAR, ping, INFO_SZ) else 0f
        val timeW = if (showTime) nvg.textWidth(Fonts.REGULAR, time, INFO_SZ) else 0f
        val st = MacroStateManager.getCurrentState()
        val stateStr = st.name.lowercase(java.util.Locale.ROOT).replace('_', ' ')
        val stateColor = HudStyle.stateColor(st)
        val farming = st == MacroState.State.FARMING
        val autoCarnival = st == MacroState.State.AUTO_CARNIVAL
        val bps = BpsTracker.getBps()
        val bpsClr = HudStyle.bpsColor(bps.toDouble())
        val bpsStr = String.format("%.1f bps", bps.toDouble())
        val totalProfit = ProfitManager.getTotalProfit(false)
        val sesMs = MacroStateManager.getSessionRunningTime()
        val dayMs = DailyFarmTimeTracker.getTodayMs()
        val cph = if (sesMs > 0) (totalProfit / (sesMs / 3_600_000.0)).toLong() else 0L
        val tokenPh = AutoCarnivalManager.getSessionTokensPerHour()
        val cphStr = if (autoCarnival) fmtCompact(tokenPh) + " tok/h" else fmtCompact(cph) + "/h"
        val restTrigger = DynamicRestManager.getNextRestTriggerMs()
        val restStr = if (restTrigger <= 0) "---" else formatTime(Math.max(0L, restTrigger - System.currentTimeMillis()))
        val sessStr = formatTime(sesMs)
        val dayStr = formatTime(dayMs)
        val badgeTextW = nvg.textWidth(Fonts.BOLD, stateStr, INFO_SZ)
        val badgeW = BADGE_PAD * 2f + badgeTextW
        val bpsW = if (farming) nvg.textWidth(Fonts.MONO, bpsStr, INFO_SZ) else 0f
        val cphW = nvg.textWidth(Fonts.MONO, cphStr, INFO_SZ)
        val restW = nvg.textWidth(Fonts.MONO, restStr, INFO_SZ)
        val sessW = nvg.textWidth(Fonts.MONO, sessStr, INFO_SZ)
        val dayW = nvg.textWidth(Fonts.MONO, dayStr, INFO_SZ)
        var fullW = computeFullW(if (animW < 0f) wantMacro else renderMacro, brandW, farming, badgeW, bpsW, cphW, restW, sessW, dayW, showUser, userW, showFps, fpsW, showPing, pingW, showTime, timeW)
        if (animW < 0f) {
            renderMacro = wantMacro
            animW = fullW
            animStep = 0
        }
        if (animStep == 0) {
            if (wantMacro != renderMacro) animStep = 1
        } else if (animStep == 1) {
            if (Math.abs(animW - brandW) < 1.5f) {
                animW = brandW
                renderMacro = wantMacro
                fullW = computeFullW(renderMacro, brandW, farming, badgeW, bpsW, cphW, restW, sessW, dayW, showUser, userW, showFps, fpsW, showPing, pingW, showTime, timeW)
                animStep = 2
            }
        } else {
            if (Math.abs(animW - fullW) < 1.5f) { animW = fullW; animStep = 0 }
            if (wantMacro != renderMacro) animStep = 1
        }
        val targetW = if (animStep == 1) brandW else fullW
        val speed = Math.min(1f, Theme.animationFactor() * (if (animStep == 1) SHRINK_SPEED_MULT else GROW_SPEED_MULT))
        animW += (targetW - animW) * speed
        val w = animW
        HudStyle.panel(nvg, w, h)
        HudStyle.accent(nvg, w, gradLeft, gradRight)
        nvg.save()
        nvg.scissor(0f, 0f, w, h)
        val cy = h / 2f
        val iconY = cy - ICON_SZ / 2f
        val textY = cy - NAME_SZ / 2f
        val infoY = cy - INFO_SZ / 2f + 0.5f
        var cx = PAD_H
        if (showLogo) {
            nvg.renderSVG(LOGO_ICON, cx, iconY, ICON_SZ, ICON_SZ, accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight))
            cx += ICON_SZ + 4f
        }
        if (showName) {
            nvg.text(Fonts.BOLD, NAME, cx, textY, NAME_SZ, Theme.HUD_TITLE)
            cx += nameW + 2f
        }
        nvg.text(Fonts.BOLD, VERSION, cx, textY + VER_SZ / 2f + 0.5f, VER_SZ, Theme.HUD_LABEL)
        cx += verW
        if (renderMacro) {
            cx += SEP
            val badgeH = h - PAD_V * 2f + 2f
            val badgeY = cy - badgeH / 2f
            nvg.roundedRect(cx, badgeY, badgeW, badgeH, BADGE_R, HudStyle.alpha(stateColor, 0.14f))
            nvg.rectOutline(cx, badgeY, badgeW, badgeH, BADGE_R, 1f, HudStyle.alpha(stateColor, 0.5f))
            nvg.text(Fonts.BOLD, stateStr, cx + BADGE_PAD, infoY, INFO_SZ, stateColor)
            cx += badgeW
            if (farming) {
                separator(nvg, cx, h); cx += SEP
                nvg.renderSVG(PERFORMANCE_ICON, cx, iconY, ICON_SZ, ICON_SZ, bpsClr); cx += ICON_SZ + ICON_GAP
                nvg.text(Fonts.MONO, bpsStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE); cx += bpsW
            }
            cx += SEP; nvg.renderSVG(CASH_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL); cx += ICON_SZ + ICON_GAP
            nvg.text(Fonts.MONO, cphStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE); cx += cphW
            cx += SEP; nvg.renderSVG(REFRESH_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL); cx += ICON_SZ + ICON_GAP
            nvg.text(Fonts.MONO, restStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE); cx += restW
            cx += SEP; nvg.renderSVG(CLOCK_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL); cx += ICON_SZ + ICON_GAP
            nvg.text(Fonts.MONO, sessStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE); cx += sessW
            cx += SEP; nvg.renderSVG(CALENDAR_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL); cx += ICON_SZ + ICON_GAP
            nvg.text(Fonts.MONO, dayStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE)
        } else {
            if (showUser) {
                separator(nvg, cx, h); cx += SEP
                nvg.renderSVG(PERSON_ICON, cx, iconY, ICON_SZ, ICON_SZ, accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight)); cx += ICON_SZ + ICON_GAP
                nvg.text(Fonts.REGULAR, username, cx, infoY, INFO_SZ, Theme.HUD_VALUE); cx += userW
            }
            if (showFps) {
                separator(nvg, cx, h); cx += SEP
                nvg.renderSVG(PERFORMANCE_ICON, cx, iconY, ICON_SZ, ICON_SZ, accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight)); cx += ICON_SZ + ICON_GAP
                nvg.text(Fonts.REGULAR, fps, cx, infoY, INFO_SZ, Theme.HUD_VALUE); cx += fpsW
            }
            if (showPing) {
                separator(nvg, cx, h); cx += SEP
                nvg.renderSVG(SIGNAL_ICON, cx, iconY, ICON_SZ, ICON_SZ, accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight)); cx += ICON_SZ + ICON_GAP
                nvg.text(Fonts.REGULAR, ping, cx, infoY, INFO_SZ, Theme.HUD_VALUE); cx += pingW
            }
            if (showTime) {
                separator(nvg, cx, h); cx += SEP
                nvg.renderSVG(CLOCK_ICON, cx, iconY, ICON_SZ, ICON_SZ, accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight)); cx += ICON_SZ + ICON_GAP
                nvg.text(Fonts.REGULAR, time, cx, infoY, INFO_SZ, Theme.HUD_VALUE)
            }
        }
        nvg.restore()
    }

    companion object {
        private const val NAME = "Aether"
        private val VERSION = FabricLoader.getInstance().getModContainer("aether").map { container -> stripReleaseSuffix(container.metadata.version.friendlyString) }.orElse("dev")
        private const val PAD_H = 12f
        private const val PAD_V = 9f
        private const val NAME_SZ = 14f
        private const val VER_SZ = 8f
        private const val INFO_SZ = 10f
        private const val ICON_SZ = 12f
        private const val ICON_GAP = 3f
        private const val SEP = 14f
        private const val BADGE_PAD = 5f
        private const val BADGE_R = 3f
        private const val SHRINK_SPEED_MULT = 0.5f
        private const val GROW_SPEED_MULT = 0.3f
        private const val LOGO_ICON = "/assets/aether/icons/logo.svg"
        private const val PERSON_ICON = "/assets/aether/icons/person.svg"
        private const val PERFORMANCE_ICON = "/assets/aether/icons/performance.svg"
        private const val SIGNAL_ICON = "/assets/aether/icons/signal.svg"
        private const val CLOCK_ICON = "/assets/aether/icons/clock.svg"
        private const val REFRESH_ICON = "/assets/aether/icons/refresh.svg"
        private const val CASH_ICON = "/assets/aether/icons/cash.svg"
        private const val CALENDAR_ICON = "/assets/aether/icons/calendar.svg"
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
        private fun computeFullW(macro: Boolean, brandW: Float, farming: Boolean, badgeW: Float, bpsW: Float, cphW: Float, restW: Float, sessW: Float, dayW: Float, showUser: Boolean, userW: Float, showFps: Boolean, fpsW: Float, showPing: Boolean, pingW: Float, showTime: Boolean, timeW: Float): Float {
            return if (macro) brandW + SEP + badgeW + (if (farming) SEP + ICON_SZ + ICON_GAP + bpsW else 0f) + SEP + ICON_SZ + ICON_GAP + cphW + SEP + ICON_SZ + ICON_GAP + restW + SEP + ICON_SZ + ICON_GAP + sessW + SEP + ICON_SZ + ICON_GAP + dayW
            else brandW + (if (showUser) SEP + ICON_SZ + ICON_GAP + userW else 0f) + (if (showFps) SEP + ICON_SZ + ICON_GAP + fpsW else 0f) + (if (showPing) SEP + ICON_SZ + ICON_GAP + pingW else 0f) + (if (showTime) SEP + ICON_SZ + ICON_GAP + timeW else 0f)
        }
        private fun accent(cx: Float, totalW: Float, gradient: Boolean, gradLeft: Int, gradRight: Int): Int {
            if (!gradient) return Theme.HUD_ACCENT
            val t = if (totalW > 0f) Math.max(0f, Math.min(1f, cx / totalW)) else 0f
            return Theme.blend(gradLeft, gradRight, t)
        }
        private fun separator(nvg: NVGRenderer, x: Float, height: Float) { nvg.rect(x + SEP / 2f, 10f, 0.7f, height - 20f, Theme.HUD_SEP) }
        private fun fmtCompact(v: Long): String {
            if (v < 0) return "-" + fmtCompact(-v)
            if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0)
            if (v >= 1_000) return String.format("%.1fk", v / 1_000.0)
            return v.toString()
        }
        private fun formatTime(ms: Long): String {
            val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s % 60) else String.format("%02d:%02d", m, s % 60)
        }
        private fun stripReleaseSuffix(version: String): String = version.replaceFirst("-r\\d+$".toRegex(), "")
        private fun getPing(mc: Minecraft): String = if (mc.player == null || mc.connection == null) "---" else PingTracker.getFormattedPing()
    }
}
