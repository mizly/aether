package dev.aether.hud

import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NVGRenderer
import dev.aether.renderer.NanoVGManager
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class HudEditScreen : Screen(Component.literal("HUD Editor")) {
    private var snapToGrid = true
    private var activeElement: HudElement? = null
    private var mouseX = 0.0
    private var mouseY = 0.0
    private var btnSnapX = 0f
    private var btnDoneX = 0f
    private var btnBaseY = 0f

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        mouseX = mx.toDouble(); mouseY = my.toDouble()
        if (dev.aether.modules.visuals.StreamerModeManager.isEnabled()) return
        for (element in HudRegistry.ELEMENTS) if (element.isEnabled()) element.renderMinecraft(g, true)
        AetherRenderQueue.enqueueBeforeGui { renderQueued(true) }
        AetherRenderQueue.enqueue { renderQueued() }
    }

    private fun renderQueued() = renderQueued(false)

    private fun renderQueued(background: Boolean) {
        if (dev.aether.modules.visuals.StreamerModeManager.isEnabled()) return
        if (Minecraft.getInstance().gui.screen() !== this) return
        if (!NanoVGManager.isInitialized()) NanoVGManager.init()
        NanoVGManager.beginFrame(width.toFloat(), height.toFloat())
        val nvg = NanoVGManager.getRenderer() ?: return
        try { if (background) renderEditorBackground(nvg) else renderEditor(nvg) } finally { NanoVGManager.endFrame() }
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {}

    private fun renderEditorBackground(nvg: NVGRenderer) {
        nvg.rect(0f, 0f, width.toFloat(), height.toFloat(), Theme.withAlpha(0xFF000000.toInt(), 0x55))
        if (snapToGrid) renderGrid(nvg)
        HudRegistry.renderEditMode(nvg)
    }

    private fun renderEditor(nvg: NVGRenderer) {
        for (element in HudRegistry.ELEMENTS) {
            if (!element.isEnabled()) continue
            nvg.save(); nvg.translate(element.getX(), element.getY()); nvg.scale(element.getScale(), element.getScale())
            element.render(nvg, true)
            nvg.restore()
        }
        renderElementLabels(nvg)
        renderToolbar(nvg)
    }

    private fun renderGrid(nvg: NVGRenderer) {
        val minor = Theme.withAlpha(0xFFFFFFFF.toInt(), 0x0C)
        val major = Theme.withAlpha(0xFFFFFFFF.toInt(), 0x1A)
        var x = 0
        while (x < width) { nvg.rect(x.toFloat(), 0f, 1f, height.toFloat(), if (x % 50 == 0) major else minor); x += GRID_PX }
        var y = 0
        while (y < height - TOOLBAR_H) { nvg.rect(0f, y.toFloat(), width.toFloat(), 1f, if (y % 50 == 0) major else minor); y += GRID_PX }
    }

    private fun renderElementLabels(nvg: NVGRenderer) {
        for (e in HudRegistry.ELEMENTS) {
            if (!e.isEnabled()) continue
            val s = e.getScale(); val ex = e.getX(); val ey = e.getY(); val ew = e.getWidth() * s; val lbl = e.getName()
            val lw = nvg.textWidth(Fonts.REGULAR, lbl, 9f) + 10f
            val lx = ex + (ew - lw) / 2f
            var ly = ey - 18f
            if (ly < 4f) ly = ey + e.getHeight() * s + 4f
            val pillColor = if (e.isInteracting()) HudStyle.alpha(Theme.HUD_ACCENT, 0.8f) else HudStyle.alpha(Theme.HUD_BG, 0.8f)
            nvg.roundedRect(lx, ly, lw, 14f, 4f, pillColor)
            nvg.textCentered(Fonts.REGULAR, lbl, lx, ly, lw, 14f, 9f, Theme.HUD_TITLE)
        }
    }

    private fun renderToolbar(nvg: NVGRenderer) {
        val tbY = height - TOOLBAR_H.toFloat()
        nvg.rect(0f, tbY, width.toFloat(), TOOLBAR_H.toFloat(), Theme.withAlpha(Theme.BG_SECONDARY, 0xF0))
        nvg.rect(0f, tbY, width.toFloat(), 1f, Theme.BORDER_DEFAULT)
        nvg.textCentered(Fonts.REGULAR, "Drag to move  \u2022  Ctrl/Shift+Drag to scale  \u2022  Toggle Snap for grid alignment", 0f, tbY, width.toFloat(), TOOLBAR_H.toFloat(), 10f, Theme.TEXT_SECONDARY)
        val margin = 14f
        btnBaseY = tbY + (TOOLBAR_H - BTN_H) / 2f
        btnDoneX = width - margin - BTN_W
        btnSnapX = btnDoneX - margin - BTN_W
        val snapOn = snapToGrid
        nvg.roundedRect(btnSnapX, btnBaseY, BTN_W, BTN_H, 5f, if (hov(btnSnapX, btnBaseY, BTN_W, BTN_H)) Theme.BG_HOVER else Theme.BG_FIELD)
        nvg.rectOutline(btnSnapX, btnBaseY, BTN_W, BTN_H, 5f, 1f, if (snapOn) Theme.ACCENT_PRIMARY else Theme.BORDER_DEFAULT)
        nvg.textCentered(Fonts.REGULAR, "Snap: " + if (snapOn) "ON" else "OFF", btnSnapX, btnBaseY, BTN_W, BTN_H, 11f, if (snapOn) Theme.ACCENT_PRIMARY else Theme.TEXT_SECONDARY)
        val doneColor = if (hov(btnDoneX, btnBaseY, BTN_W, BTN_H)) Theme.ACCENT_SECONDARY else Theme.ACCENT_PRIMARY
        nvg.roundedRect(btnDoneX, btnBaseY, BTN_W, BTN_H, 5f, doneColor)
        nvg.textCentered(Fonts.BOLD, "Done", btnDoneX, btnBaseY, BTN_W, BTN_H, 11f, 0xFFFFFFFF.toInt())
    }

    private fun hov(x: Float, y: Float, w: Float, h: Float): Boolean = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = click.x(); val my = click.y(); mouseX = mx; mouseY = my
        val button = click.button()
        if (button == 0) {
            if (hov(btnDoneX, btnBaseY, BTN_W, BTN_H)) { onClose(); return true }
            if (hov(btnSnapX, btnBaseY, BTN_W, BTN_H)) { snapToGrid = !snapToGrid; return true }
        }
        val resizeMod = click.modifiers() and (InputConstants.MOD_CONTROL or InputConstants.MOD_SHIFT) != 0
        val move = button == 0 && !resizeMod
        val resize = resizeMod && (button == 0 || button == 1)
        if (move || resize) for (e in HudRegistry.ELEMENTS) {
            if (!e.isEnabled()) continue
            if (e.isHovered(mx, my)) { activeElement = e; e.startDrag(mx, my, resize); return true }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseDragged(click: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        mouseX = click.x(); mouseY = click.y()
        val element = activeElement ?: return false
        val win = Minecraft.getInstance().window
        element.drag(click.x(), click.y(), win.guiScaledWidth.toFloat(), win.guiScaledHeight.toFloat(), if (snapToGrid) GRID_PX else 0)
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val element = activeElement
        if (element != null) { element.endDrag(); activeElement = null }
        return false
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val key = input.key()
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_INSERT) { onClose(); return true }
        if (key == InputConstants.KEY_G) { snapToGrid = !snapToGrid; return true }
        return false
    }

    private companion object {
        private const val GRID_PX = 10
        private const val TOOLBAR_H = 40
        private const val BTN_W = 90f
        private const val BTN_H = 22f
    }
}
