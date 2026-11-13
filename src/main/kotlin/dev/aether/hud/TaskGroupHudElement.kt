package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

class TaskGroupHudElement(private val group: Group) : HudElement() {
    enum class Group { INTERMEDIARIES, MID_FARMING_TASKS, FAILSAFES }

    override fun getX(): Float {
        val configured = when (group) {
            Group.INTERMEDIARIES -> AetherConfig.INTERMEDIARIES_HUD_X.get().toFloat()
            Group.MID_FARMING_TASKS -> AetherConfig.MID_FARMING_HUD_X.get().toFloat()
            Group.FAILSAFES -> AetherConfig.FAILSAFES_HUD_X.get().toFloat()
        }
        val mc = Minecraft.getInstance()
        if (mc == null || mc.window == null) return configured
        val maxX = Math.max(0f, mc.window.guiScaledWidth.toFloat() - getWidth() * getScale())
        val fallback = if (group == Group.INTERMEDIARIES) 10f else Math.max(10f, maxX - 10f)
        return if (configured < 0f || configured > maxX) fallback else Math.max(0f, Math.min(maxX, configured))
    }

    override fun getY(): Float {
        val configured = when (group) {
            Group.INTERMEDIARIES -> AetherConfig.INTERMEDIARIES_HUD_Y.get().toFloat()
            Group.MID_FARMING_TASKS -> AetherConfig.MID_FARMING_HUD_Y.get().toFloat()
            Group.FAILSAFES -> AetherConfig.FAILSAFES_HUD_Y.get().toFloat()
        }
        val mc = Minecraft.getInstance()
        if (mc == null || mc.window == null) return configured
        val maxY = Math.max(0f, mc.window.guiScaledHeight.toFloat() - getHeight() * getScale())
        val fallback = Math.min(150f, maxY)
        return if (configured < 0f || configured > maxY) fallback else Math.max(0f, Math.min(maxY, configured))
    }

    override fun setX(x: Float) {
        if (group == Group.INTERMEDIARIES) AetherConfig.INTERMEDIARIES_HUD_X.set(x.toInt())
        else if (group == Group.MID_FARMING_TASKS) AetherConfig.MID_FARMING_HUD_X.set(x.toInt())
        else AetherConfig.FAILSAFES_HUD_X.set(x.toInt())
    }
    override fun setY(y: Float) {
        if (group == Group.INTERMEDIARIES) AetherConfig.INTERMEDIARIES_HUD_Y.set(y.toInt())
        else if (group == Group.MID_FARMING_TASKS) AetherConfig.MID_FARMING_HUD_Y.set(y.toInt())
        else AetherConfig.FAILSAFES_HUD_Y.set(y.toInt())
    }
    override fun getScale(): Float = when (group) {
        Group.INTERMEDIARIES -> AetherConfig.INTERMEDIARIES_HUD_SCALE.get()
        Group.MID_FARMING_TASKS -> AetherConfig.MID_FARMING_HUD_SCALE.get()
        Group.FAILSAFES -> AetherConfig.FAILSAFES_HUD_SCALE.get()
    }
    override fun setScale(s: Float) {
        if (group == Group.INTERMEDIARIES) AetherConfig.INTERMEDIARIES_HUD_SCALE.set(s)
        else if (group == Group.MID_FARMING_TASKS) AetherConfig.MID_FARMING_HUD_SCALE.set(s)
        else AetherConfig.FAILSAFES_HUD_SCALE.set(s)
    }
    override fun getWidth(): Float = W
    override fun getHeight(): Float = computeHeight(getRows(Minecraft.getInstance()))
    override fun isEnabled(): Boolean = when (group) {
        Group.INTERMEDIARIES -> AetherConfig.SHOW_INTERMEDIARIES_HUD.get()
        Group.MID_FARMING_TASKS -> AetherConfig.SHOW_MID_FARMING_HUD.get()
        Group.FAILSAFES -> AetherConfig.SHOW_FAILSAFES_HUD.get()
    }
    override fun isVisible(): Boolean = (ClientUtils.isSupportedHudArea() || AetherConfig.SHOW_HUD_OUTSIDE_GARDEN.get()) && isEnabled()
    override fun getName(): String = when (group) {
        Group.INTERMEDIARIES -> "Intermediaries HUD"
        Group.MID_FARMING_TASKS -> "Mid-Farming HUD"
        Group.FAILSAFES -> "Failsafes HUD"
    }
    override fun savePosition() { AetherConfig.save() }
    override fun renderElement(nvg: NVGRenderer, editMode: Boolean) { renderRows(nvg, getRows(Minecraft.getInstance())) }

    internal fun renderRows(nvg: NVGRenderer, rows: List<TaskHudStatusProvider.TaskStatusRow>) {
        val ph = computeHeight(rows)
        HudStyle.panel(nvg, W, ph)
        val title = when (group) {
            Group.INTERMEDIARIES -> "Intermediaries"
            Group.MID_FARMING_TASKS -> "Mid-Farming Tasks"
            Group.FAILSAFES -> "Failsafes"
        }
        val enabled = rows.stream().filter { row -> row.badge != "OFF" }.count()
        HudStyle.header(nvg, W, title, "$enabled/${rows.size}")
        var y = HudStyle.CONTENT_Y
        for (row in rows) {
            val rowHeight = computeRowHeight(row)
            val color = row.color()
            val badgeWidth = nvg.textWidth(Fonts.BOLD, row.badge, DETAIL_SZ) + 12f
            val badgeX = W - HudStyle.PAD - badgeWidth
            nvg.roundedRect(HudStyle.PAD, y - 2f, W - HudStyle.PAD * 2, rowHeight - 5f, 5f, HudStyle.alpha(Theme.HUD_BAR_BG, 0.45f))
            HudStyle.text(nvg, Fonts.BOLD, row.name, HudStyle.PAD + 8f, y + 1f, badgeX - HudStyle.PAD - 14f, LABEL_SZ, Theme.HUD_VALUE)
            nvg.roundedRect(badgeX, y - 1f, badgeWidth, 15f, 4f, HudStyle.alpha(color, 0.14f))
            nvg.textCentered(Fonts.BOLD, row.badge, badgeX, y - 1f, badgeWidth, 15f, DETAIL_SZ, color)
            for (i in row.detailLines.indices) HudStyle.text(nvg, Fonts.REGULAR, row.detailLines[i], HudStyle.PAD + 8f, y + 16f + i * DETAIL_LINE_H, W - HudStyle.PAD * 2 - 14f, DETAIL_SZ, Theme.HUD_LABEL)
            y += rowHeight
        }
    }

    private fun getRows(client: Minecraft): List<TaskHudStatusProvider.TaskStatusRow> = TaskHudStatusProvider.getRows(group, client)
    private fun computeHeight(rows: List<TaskHudStatusProvider.TaskStatusRow>): Float {
        var height = HudStyle.CONTENT_Y
        for (row in rows) height += computeRowHeight(row)
        return height + PAD_V
    }
    private fun computeRowHeight(row: TaskHudStatusProvider.TaskStatusRow): Float = ROW_H + (Math.max(1, row.detailLines.size) - 1).toFloat() * DETAIL_LINE_H

    companion object {
        private const val W = 300f
        private const val PAD_V = 8f
        private const val LABEL_SZ = 10f
        private const val DETAIL_SZ = 9f
        private const val ROW_H = 36f
        private const val DETAIL_LINE_H = 10f
    }
}
