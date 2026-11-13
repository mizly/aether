package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.util.ServerIdHider
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.util.FormattedCharSequence

internal class ScoreboardGraphicsExtractor(minecraft: Minecraft, private val width: Int, private val height: Int) :
    GuiGraphicsExtractor(minecraft, GuiRenderState(), 0, 0) {
    private val drawList = ScoreboardDrawList()

    override fun guiWidth(): Int = width
    override fun guiHeight(): Int = height
    override fun nextStratum() = Unit

    override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        drawList.fill(x1, y1, x2, y2, color)
    }

    override fun text(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, shadow: Boolean) {
        if ((color ushr 24) == 0) return
        val heading = !drawList.hasText() || ScoreboardText.isServerAddress(text)
        var display = ScoreboardText.customize(text, !drawList.hasText(),
            AetherConfig.SCOREBOARD_TITLE_TEXT.get(), AetherConfig.SCOREBOARD_SERVER_TEXT.get())
        if (AetherConfig.NICK_HIDER_MASTER_ENABLED.get() && AetherConfig.HIDE_SERVER_ID.get()) {
            display = ServerIdHider.replace(display, AetherConfig.CUSTOM_SERVER_ID.get())
        }
        val displayWidth = if (heading) kotlin.math.ceil(font.width(display) * ScoreboardText.HEADING_SIZE / ScoreboardText.SIZE).toInt()
            else if (display !== text) font.width(display) else 0
        drawList.text(ScoreboardText.prepare(font, display, color, shadow, heading), x, y, font.width(text),
            minOf(displayWidth, maxOf(0, width - 32)))
    }

    fun drawList(): ScoreboardDrawList = drawList
}
