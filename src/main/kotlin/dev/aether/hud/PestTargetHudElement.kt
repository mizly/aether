package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.modules.pest.helpers.PestDisplayTracker
import dev.aether.modules.pest.helpers.PestDisplayTracker.PestDisplay
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class PestTargetHudElement : HudElement() {
    private val bars = HashMap<Int, Float>()
    private var lastFrame = 0L
    private var lastLevel: Any? = null

    private fun pests(): List<PestDisplay> = PestDisplayTracker.getPests(Minecraft.getInstance())

    private fun layout(): PestHudLayout {
        val window = Minecraft.getInstance().window
        val savedX = AetherConfig.PEST_TARGET_HUD_X.get()
        val width = window.guiScaledWidth.toFloat() - (if (savedX >= 0) savedX + 8f else 16f)
        val height = window.guiScaledHeight.toFloat() - getY() - 8f
        return PestHudLayout.fit(pests().size, width, height, AetherConfig.PEST_TARGET_HUD_SCALE.get())
    }

    private fun rows(): Int = layout().rows()

    override fun getX(): Float {
        val saved = AetherConfig.PEST_TARGET_HUD_X.get()
        return if (saved >= 0) saved.toFloat() else maxOf(0f, (Minecraft.getInstance().window.guiScaledWidth.toFloat() - getWidth() * getScale()) / 2f)
    }
    override fun getY(): Float {
        val saved = AetherConfig.PEST_TARGET_HUD_Y.get()
        return if (saved >= 0) saved.toFloat() else Minecraft.getInstance().window.guiScaledHeight.toFloat() / 2f + 28f
    }
    override fun setX(x: Float) { AetherConfig.PEST_TARGET_HUD_X.set(Math.round(x)) }
    override fun setY(y: Float) { AetherConfig.PEST_TARGET_HUD_Y.set(Math.round(y)) }
    override fun getScale(): Float = layout().scale()
    override fun setScale(scale: Float) { AetherConfig.PEST_TARGET_HUD_SCALE.set(scale) }
    override fun getWidth(): Float = layout().width()
    override fun getHeight(): Float = layout().height()
    override fun isEnabled(): Boolean = AetherConfig.SHOW_PEST_TARGET_HUD.get()
    override fun isVisible(): Boolean = isEnabled() && Minecraft.getInstance().gui.screen() == null && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN && pests().isNotEmpty()
    override fun getName(): String = "Pest Target HUD"
    override fun savePosition() { AetherConfig.save() }
    override fun rendersBeforeMinecraft(): Boolean = true

    protected override fun renderElement(nvg: NVGRenderer, editMode: Boolean) {
        val entries = pests()
        HudStyle.panel(nvg, getWidth(), getHeight())
        HudStyle.accent(nvg, getWidth(), Theme.HUD_ACCENT, Theme.HUD_ACCENT)
        nvg.text(Fonts.BOLD, "Pests", 10f, 9f, 11f, Theme.HUD_TITLE)
        nvg.textRight(Fonts.MONO, entries.size.toString(), 10f, 10f, getWidth() - 20f, 9f, Theme.HUD_LABEL)
        val now = System.nanoTime()
        val blend = if (lastFrame == 0L) 1f else (1 - Math.exp(-(now - lastFrame) / 120_000_000.0)).toFloat()
        lastFrame = now
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) { bars.clear(); lastLevel = level }
        bars.keys.removeIf { id -> entries.none { it.entity().id == id } }
        if (entries.isEmpty() && editMode) { renderRow(nvg, 0, "Pest", "600 HP", 0.75f, false); return }
        entries.forEachIndexed { i, pest ->
            val target = if (pest.hunting()) pest.progress() else pest.healthFraction()
            var shown = target
            if (target >= 0) {
                shown = bars[pest.entity().id] ?: target
                shown += (target - shown) * blend
                bars[pest.entity().id] = shown
            } else bars.remove(pest.entity().id)
            val detail = if (pest.hunting()) when { target >= 1 -> "REEL"; target < 0 -> "Hunting…"; !pest.attached() && target == 0f -> "Ready"; else -> "${Math.round(target * 100)}% reel" } else if (pest.health() < 0) "Health unknown" else String.format(java.util.Locale.ROOT, "%,.0f HP", pest.health())
            renderRow(nvg, i, pest.name(), detail, shown, pest.hunting())
        }
    }

    private fun renderRow(nvg: NVGRenderer, index: Int, name: String, detail: String, fraction: Float, hunting: Boolean) {
        val x = (index / rows()) * PestHudLayout.COLUMN_WIDTH
        val y = PestHudLayout.HEADER_HEIGHT + (index % rows()) * PestHudLayout.ROW_HEIGHT
        val accent = if (hunting) Theme.HUD_ACCENT else Theme.blend(Theme.HUD_ERROR, Theme.HUD_SUCCESS, maxOf(0f, fraction))
        nvg.roundedRect(x + 8f, y, ICON_TILE, ICON_TILE, 6f, HudStyle.alpha(Theme.HUD_BORDER, 0.5f))
        val detailWidth = nvg.textWidth(Fonts.MONO, detail, 8f)
        HudStyle.text(nvg, Fonts.BOLD, name, x + 42f, y + 1f, PestHudLayout.COLUMN_WIDTH - 58f - detailWidth, 10f, Theme.HUD_TITLE)
        nvg.textRight(Fonts.MONO, detail, x + 42f, y + 2f, PestHudLayout.COLUMN_WIDTH - 52f, 8f, Theme.HUD_LABEL)
        val barWidth = PestHudLayout.COLUMN_WIDTH - 52f
        nvg.roundedRect(x + 42f, y + 18f, barWidth, 5f, 2.5f, Theme.HUD_BORDER)
        if (fraction >= 0) {
            if (fraction > 0) nvg.roundedRect(x + 42f, y + 18f, barWidth * fraction, 5f, 2.5f, accent)
        } else {
            val phase = (System.nanoTime() % 1_600_000_000L) / 1_600_000_000f
            val offset = (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)).toFloat() * barWidth * 0.75f
            nvg.roundedRect(x + 42f + offset, y + 18f, barWidth * 0.25f, 5f, 2.5f, Theme.HUD_LABEL)
        }
    }

    override fun renderMinecraft(graphics: GuiGraphicsExtractor, editMode: Boolean) {
        if (if (editMode) !isEnabled() else !isVisible()) return
        if (Minecraft.getInstance().level == null) return
        val entries = pests()
        graphics.pose().pushMatrix()
        graphics.pose().translate(getX(), getY())
        graphics.pose().scale(getScale())
        try {
            val inset = (ICON_TILE - ICON_SIZE) / 2f
            for (i in 0 until maxOf(if (editMode) 1 else 0, entries.size)) {
                val icon: ItemStack = if (entries.isEmpty()) ItemStack(Items.PLAYER_HEAD) else entries[i].icon()
                graphics.pose().pushMatrix()
                graphics.pose().translate((i / rows()) * PestHudLayout.COLUMN_WIDTH + 8f + inset, PestHudLayout.HEADER_HEIGHT + (i % rows()) * PestHudLayout.ROW_HEIGHT + inset)
                graphics.pose().scale(ICON_SIZE / 16f)
                graphics.item(icon, 0, 0)
                graphics.pose().popMatrix()
            }
        } finally { graphics.pose().popMatrix() }
    }

    companion object {
        private const val ICON_TILE = 27f
        private const val ICON_SIZE = 20f
        @JvmStatic fun resetLayout() {
            AetherConfig.PEST_TARGET_HUD_X.set(-1)
            AetherConfig.PEST_TARGET_HUD_Y.set(-1)
            AetherConfig.PEST_TARGET_HUD_SCALE.set(1f)
            AetherConfig.save()
        }
    }
}
