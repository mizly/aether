package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroStateManager
import dev.aether.modules.profit.ProfitManager
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import net.minecraft.client.Minecraft
import java.util.Map

// One instance each for session, lifetime and daily.
class ProfitHudElement(private val mode: String) : HudElement() {
    private val graph = ProfitGraph()

    private fun isSession(): Boolean = mode == "session"
    private fun title(): String = when (mode) {
        "lifetime" -> "Lifetime Profit"
        "daily" -> "Daily Profit"
        else -> "Session Profit"
    }

    override fun getX(): Float = when (mode) {
        "lifetime" -> AetherConfig.LIFETIME_HUD_X.get().toFloat()
        "daily" -> AetherConfig.DAILY_HUD_X.get().toFloat()
        else -> AetherConfig.SESSION_PROFIT_HUD_X.get().toFloat()
    }

    override fun getY(): Float = when (mode) {
        "lifetime" -> AetherConfig.LIFETIME_HUD_Y.get().toFloat()
        "daily" -> AetherConfig.DAILY_HUD_Y.get().toFloat()
        else -> AetherConfig.SESSION_PROFIT_HUD_Y.get().toFloat()
    }

    override fun setX(x: Float) {
        when (mode) {
            "lifetime" -> AetherConfig.LIFETIME_HUD_X.set(x.toInt())
            "daily" -> AetherConfig.DAILY_HUD_X.set(x.toInt())
            else -> AetherConfig.SESSION_PROFIT_HUD_X.set(x.toInt())
        }
    }

    override fun setY(y: Float) {
        when (mode) {
            "lifetime" -> AetherConfig.LIFETIME_HUD_Y.set(y.toInt())
            "daily" -> AetherConfig.DAILY_HUD_Y.set(y.toInt())
            else -> AetherConfig.SESSION_PROFIT_HUD_Y.set(y.toInt())
        }
    }

    override fun getScale(): Float = when (mode) {
        "lifetime" -> AetherConfig.LIFETIME_HUD_SCALE.get()
        "daily" -> AetherConfig.DAILY_HUD_SCALE.get()
        else -> AetherConfig.SESSION_PROFIT_HUD_SCALE.get()
    }

    override fun setScale(s: Float) {
        when (mode) {
            "lifetime" -> AetherConfig.LIFETIME_HUD_SCALE.set(s)
            "daily" -> AetherConfig.DAILY_HUD_SCALE.set(s)
            else -> AetherConfig.SESSION_PROFIT_HUD_SCALE.set(s)
        }
    }

    override fun getWidth(): Float = W
    override fun getHeight(): Float = computeHeight()

    override fun isVisible(): Boolean {
        if (!AetherConfig.PROFIT_HUD_ENABLED.get()) return false
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null && mc.gui.screen() !is HudEditScreen) return false
        return when (mode) {
            "lifetime" -> AetherConfig.SHOW_LIFETIME_HUD.get()
            "daily" -> AetherConfig.SHOW_DAILY_HUD.get()
            else -> AetherConfig.SHOW_SESSION_PROFIT_HUD.get()
        }
    }

    override fun getName(): String = title()
    override fun savePosition() = AetherConfig.save()

    fun computeHeight(): Float {
        var h = HudStyle.CONTENT_Y + 50f
        if (showGraph()) h += ProfitGraph.HEIGHT
        val itemCount = if (AetherConfig.COMPACT_PROFIT_CALCULATOR.get())
            ProfitManager.getCompactDrops(mode).values.stream().filter { it != 0L }.count().toInt()
        else ProfitManager.getActiveDrops(mode).size
        h += if (itemCount > 0) itemCount * ROW_H else 24f
        if (showFarmingXp()) {
            var rows = 2
            if (AetherConfig.FARMING_HUD_XP_RATE.get()) rows++
            if (AetherConfig.FARMING_HUD_ETA_NEXT.get()) rows++
            if (AetherConfig.FARMING_HUD_ETA_MAX.get()) rows++
            h += 10f + rows * ROW_H + FARM_BAR_H + 4f
        }
        return h + 8f
    }

    private fun showFarmingXp(): Boolean = isSession() && AetherConfig.FARMING_XP_HUD.get() &&
        dev.aether.modules.profit.helpers.FarmingXpTracker.hasData()

    private fun showGraph(): Boolean = isSession() && AetherConfig.SESSION_PROFIT_GRAPH.get()

    override fun renderElement(nvg: NVGRenderer, editMode: Boolean) {
        val ph = computeHeight()
        HudStyle.panel(nvg, W, ph)
        HudStyle.header(nvg, W, title(), "COINS")
        var ry = HudStyle.CONTENT_Y
        val total = ProfitManager.getTotalProfit(mode)
        val contentWidth = W - PAD_H * 2
        nvg.roundedRect(PAD_H, ry, contentWidth, 41f, 5f, Theme.HUD_BAR_BG)
        nvg.text(Fonts.REGULAR, "Total Profit", PAD_H + 8f, ry + 6f, 9f, Theme.HUD_LABEL)
        val totalWidth = if (isSession()) contentWidth * 0.53f else contentWidth - 16f
        HudStyle.text(nvg, Fonts.BOLD, fmt(total), PAD_H + 8f, ry + 19f, totalWidth - 8f, 15f,
            if (total < 0L) Theme.HUD_ERROR else Theme.HUD_VALUE)
        if (isSession()) {
            val rateX = PAD_H + contentWidth * 0.57f
            nvg.rect(rateX - 8f, ry + 8f, 0.7f, 25f, Theme.HUD_SEP)
            val sessionMs = MacroStateManager.getSessionRunningTime()
            val cph = if (sessionMs > 0) (total / (sessionMs / 3_600_000.0)).toLong() else 0L
            nvg.text(Fonts.REGULAR, "Coins per Hour", rateX, ry + 6f, 9f, Theme.HUD_LABEL)
            HudStyle.text(nvg, Fonts.MONO, fmt(cph), rateX, ry + 21f, W - PAD_H - rateX - 8f, 12f, Theme.HUD_ACCENT)
        }
        ry += 50f
        if (showGraph()) {
            val now = System.nanoTime()
            graph.render(nvg, PAD_H, ry, contentWidth, ProfitManager.getSessionProfitHistory(now),
                AetherConfig.SESSION_PROFIT_GRAPH_MINUTES.get() * 60_000L, now)
            ry += ProfitGraph.HEIGHT
        }

        val startRy = ry
        if (AetherConfig.COMPACT_PROFIT_CALCULATOR.get()) {
            for (entry in ProfitManager.getCompactDrops(mode).entries) {
                if (entry.value != 0L) {
                    val color = if (entry.key == "Costs") Theme.HUD_ERROR else Theme.HUD_VALUE
                    row(nvg, ry, ProfitManager.getCompactCategoryLabel(entry.key), fmt(entry.value), color)
                    ry += ROW_H
                }
            }
        } else {
            for (entry in ProfitManager.getActiveDrops(mode).entries) {
                val item = entry.key
                val count = entry.value
                val lineVal = ProfitManager.getItemValue(item, count).toLong()
                val cName = ProfitManager.getCategorizedName(item)
                val cDisp = when {
                    item == "[Spray] Sprayonator" -> "x${String.format("%,d", ProfitManager.getSprayQuantity(mode))}"
                    item.startsWith("Pet XP (") -> String.format("%,d XP", count)
                    else -> "x${String.format("%,d", count)}"
                }
                val color = if (item == "[Visitor] Visitor Cost" || item == "[Spray] Sprayonator") Theme.HUD_ERROR else Theme.HUD_VALUE
                row(nvg, ry, "$cName ($cDisp)", fmt(lineVal), color)
                ry += ROW_H
            }
        }
        if (ry == startRy) {
            nvg.text(Fonts.REGULAR, "No tracked drops yet", PAD_H, ry + 4f, LABEL_SZ, Theme.HUD_LABEL)
            ry += 24f
        }

        if (showFarmingXp()) {
            nvg.rect(PAD_H, ry + 1f, W - PAD_H * 2f, 1f, Theme.HUD_SEP)
            ry += 10f
            val maxed = dev.aether.modules.profit.helpers.FarmingXpTracker.isMaxed()
            val level = dev.aether.modules.profit.helpers.FarmingXpTracker.getLevel()
            val prog = dev.aether.modules.profit.helpers.FarmingXpTracker.getProgressToMax()
            row(nvg, ry, "Farming XP earned", fmt(dev.aether.modules.profit.helpers.FarmingXpTracker.getSessionXpGained()), Theme.HUD_SUCCESS)
            ry += ROW_H
            row(nvg, ry, "Farming $level → 60", String.format("%.2f%%", prog * 100f), Theme.HUD_VALUE)
            ry += ROW_H
            if (AetherConfig.FARMING_HUD_XP_RATE.get()) {
                row(nvg, ry, "Farming XP/hr", fmt(dev.aether.modules.profit.helpers.FarmingXpTracker.getXpPerHour()), Theme.HUD_SUCCESS)
                ry += ROW_H
            }
            if (AetherConfig.FARMING_HUD_ETA_NEXT.get()) {
                val etaNext = dev.aether.modules.profit.helpers.FarmingXpTracker.getEtaToNextLevelMs()
                val value = if (maxed) "done" else if (etaNext < 0) "---" else formatEta(etaNext)
                row(nvg, ry, "Next level (${level + 1})", value, Theme.HUD_VALUE)
                ry += ROW_H
            }
            if (AetherConfig.FARMING_HUD_ETA_MAX.get()) {
                val etaMax = dev.aether.modules.profit.helpers.FarmingXpTracker.getEtaToMaxMs()
                val value = if (maxed) "done" else if (etaMax < 0) "---" else formatEta(etaMax)
                row(nvg, ry, "Time to 60", value, Theme.HUD_VALUE)
                ry += ROW_H
            }
            val bw = W - PAD_H * 2f
            nvg.roundedRect(PAD_H, ry, bw, FARM_BAR_H, FARM_BAR_H / 2f, Theme.HUD_BAR_BG)
            val fw = bw * maxOf(0f, minOf(1f, prog))
            if (fw > 0) nvg.roundedRect(PAD_H, ry, fw, FARM_BAR_H, FARM_BAR_H / 2f, Theme.HUD_ACCENT)
            ry += FARM_BAR_H + 4f
        }
    }

    private fun row(nvg: NVGRenderer, y: Float, label: String, value: String, valueColor: Int) {
        val width = W - PAD_H * 2
        val fittedValue = HudStyle.fit(nvg, Fonts.MONO, value, LABEL_SZ, width * 0.45f)
        val labelWidth = width - nvg.textWidth(Fonts.MONO, fittedValue, LABEL_SZ) - 10f
        if (label.startsWith("[") && label.indexOf(']') > 0) {
            val close = label.indexOf(']')
            val tag = label.substring(1, close)
            val tagWidth = nvg.textWidth(Fonts.BOLD, LONGEST_CATEGORY_TAG, 8f) + 8f
            nvg.roundedRect(PAD_H, y - 1f, tagWidth, 13f, 3f, HudStyle.alpha(Theme.HUD_ACCENT, 0.12f))
            nvg.textCentered(Fonts.BOLD, tag, PAD_H, y - 1f, tagWidth, 13f, 8f, Theme.HUD_ACCENT)
            HudStyle.text(nvg, Fonts.REGULAR, label.substring(close + 1).trimStart(), PAD_H + tagWidth + CATEGORY_TAG_GAP,
                y, labelWidth - tagWidth - CATEGORY_TAG_GAP, LABEL_SZ, Theme.HUD_LABEL)
        } else HudStyle.text(nvg, Fonts.REGULAR, label, PAD_H, y, labelWidth, LABEL_SZ, Theme.HUD_LABEL)
        nvg.textRight(Fonts.MONO, fittedValue, PAD_H, y, width, LABEL_SZ, valueColor)
    }

    companion object {
        private const val W = 260f
        private val PAD_H: Float = HudStyle.PAD
        private const val LABEL_SZ = 10f
        private const val ROW_H = 18f
        private const val LONGEST_CATEGORY_TAG = "[VISITOR]"
        private const val CATEGORY_TAG_GAP = 3f
        private const val FARM_BAR_H = 4f

        private fun fmt(amount: Long) = String.format("%,d", amount)
        private fun formatEta(ms: Long): String {
            val totalSec = ms / 1_000L
            val days = totalSec / 86_400L
            val hours = (totalSec % 86_400L) / 3600L
            val mins = (totalSec % 3600L) / 60L
            val secs = totalSec % 60L
            return when {
                days > 0 -> String.format("%dd %dh", days, hours)
                hours > 0 -> String.format("%dh %dm", hours, mins)
                mins > 0 -> String.format("%dm %ds", mins, secs)
                else -> String.format("%ds", secs)
            }
        }
    }
}
