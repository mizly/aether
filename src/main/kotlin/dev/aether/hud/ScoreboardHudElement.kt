package dev.aether.hud

import dev.aether.Aether
import dev.aether.config.AetherConfig
import dev.aether.mixin.GuiAccessor
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NVGRenderer
import dev.aether.renderer.NanoVGManager
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.function.Consumer

class ScoreboardHudElement : HudElement() {
    private var drawList = ScoreboardDrawList()
    private var renderingFailed = false

    override fun getX(): Float {
        val screenWidth = Minecraft.getInstance().window.guiScaledWidth.toFloat()
        val x = if (AetherConfig.SCOREBOARD_HUD_X.get() < 0) screenWidth - getWidth() * getScale() - 8 else AetherConfig.SCOREBOARD_HUD_X.get().toFloat()
        return Math.max(0f, Math.min(screenWidth - getWidth() * getScale(), x))
    }
    override fun getY(): Float {
        val screenHeight = Minecraft.getInstance().window.guiScaledHeight.toFloat()
        val y = if (AetherConfig.SCOREBOARD_HUD_Y.get() < 0) drawList.top() - ScoreboardDrawList.PADDING else AetherConfig.SCOREBOARD_HUD_Y.get().toFloat()
        return Math.max(0f, Math.min(screenHeight - getHeight() * getScale(), y))
    }
    override fun setX(x: Float) { AetherConfig.SCOREBOARD_HUD_X.set(Math.round(x)) }
    override fun setY(y: Float) { AetherConfig.SCOREBOARD_HUD_Y.set(Math.round(y)) }
    override fun getScale(): Float = AetherConfig.SCOREBOARD_HUD_SCALE.get()
    override fun setScale(scale: Float) { AetherConfig.SCOREBOARD_HUD_SCALE.set(scale) }
    override fun getWidth(): Float = drawList.panelWidth()
    override fun getHeight(): Float = drawList.panelHeight()
    override fun getName(): String = "Custom Scoreboard"
    override fun savePosition() { AetherConfig.save() }
    override fun isEnabled(): Boolean = AetherConfig.CUSTOM_SCOREBOARD.get()
    override fun rendersWithHud(): Boolean = false

    override fun isVisible(): Boolean {
        val mc = Minecraft.getInstance()
        return isEnabled() && !renderingFailed && mc.player != null && mc.level != null && !mc.gui.hud.isHidden() && !StreamerModeManager.isEnabled() && HudRegistry.canRenderInGameplay(mc)
    }

    fun extract(graphics: GuiGraphicsExtractor, vanilla: Consumer<GuiGraphicsExtractor>) {
        if (graphics is ScoreboardGraphicsExtractor || !isVisible()) { vanilla.accept(graphics); return }
        val mc = Minecraft.getInstance()
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val capture: ScoreboardGraphicsExtractor
        try {
            capture = ScoreboardGraphicsExtractor(mc, width, height)
            vanilla.accept(capture)
        } catch (error: RuntimeException) { fail(error); vanilla.accept(graphics); return }
        catch (error: LinkageError) { fail(error); vanilla.accept(graphics); return }
        drawList = capture.drawList()
        val level = mc.level
        // Keep the sidebar below screens and tooltips, independently of the other HUDs' fade.
        AetherRenderQueue.enqueueBeforeGui {
            if (!isVisible() || mc.level !== level) return@enqueueBeforeGui
            renderFrame(capture.drawList(), width, height)
        }
    }

    private fun renderFrame(snapshot: ScoreboardDrawList, width: Int, height: Int) {
        try {
            if (!NanoVGManager.isInitialized()) NanoVGManager.init()
            NanoVGManager.beginFrame(width.toFloat(), height.toFloat())
            try { drawList = snapshot; NanoVGManager.getRenderer()?.let { render(it, false) } }
            finally { NanoVGManager.endFrame() }
        } catch (error: RuntimeException) { fail(error) }
        catch (error: LinkageError) { fail(error) }
    }

    private fun fail(error: Throwable) {
        if (!renderingFailed) Aether.LOGGER.warn("Custom scoreboard unavailable; restoring vanilla rendering", error)
        renderingFailed = true
    }

    override fun renderMinecraft(graphics: GuiGraphicsExtractor, editMode: Boolean) {
        if (!editMode || !isEnabled()) return
        val mc = Minecraft.getInstance()
        val capture = ScoreboardGraphicsExtractor(mc, graphics.guiWidth(), graphics.guiHeight())
        if (mc.level != null && mc.player != null) (mc.gui.hud as GuiAccessor).`aether$extractScoreboardSidebar`(capture, mc.deltaTracker)
        if (capture.drawList().isEmpty()) {
            val title = Component.literal("Scoreboard").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
            val row = Component.literal("Player").append(Component.literal("  \u2764").withStyle(ChatFormatting.RED))
            val width = Math.max(mc.font.width(title), mc.font.width(row) + mc.font.width(": 1"))
            val left = graphics.guiWidth() - width - 3
            val top = graphics.guiHeight() / 2 - 16
            capture.fill(left - 2, top, left + width + 2, top + 9, mc.options.getBackgroundColor(0.4f))
            capture.fill(left - 2, top + 9, left + width + 2, top + 19, mc.options.getBackgroundColor(0.3f))
            capture.text(mc.font, title, left + width / 2 - mc.font.width(title) / 2, top + 1, -1, false)
            capture.text(mc.font, row, left, top + 10, -1, false)
            capture.text(mc.font, Component.literal("1").withStyle(ChatFormatting.RED), left + width + 2 - mc.font.width("1"), top + 10, -1, false)
        }
        drawList = capture.drawList()
    }

    override fun renderElement(nvg: NVGRenderer, editMode: Boolean) {
        if (renderingFailed) return
        try { drawList.render(nvg) }
        catch (error: RuntimeException) { fail(error) }
        catch (error: LinkageError) { fail(error) }
    }

    companion object {
        @JvmStatic
        fun setEnabled(enabled: Boolean) {
            val hud = HudRegistry.scoreboardHud
            if (hud != null) hud.renderingFailed = false
            AetherConfig.CUSTOM_SCOREBOARD.set(enabled)
            AetherConfig.save()
        }

        @JvmStatic
        fun resetLayout() {
            AetherConfig.SCOREBOARD_HUD_X.set(-1)
            AetherConfig.SCOREBOARD_HUD_Y.set(-1)
            AetherConfig.SCOREBOARD_HUD_SCALE.set(1f)
            AetherConfig.save()
        }
    }
}
