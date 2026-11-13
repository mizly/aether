package dev.aether.hud

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor

abstract class HudElement {
    private var dragging = false
    private var resizing = false
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var resizeStart = 0f
    private var resizeMouseX = 0f

    abstract fun getX(): Float
    abstract fun getY(): Float
    abstract fun setX(x: Float)
    abstract fun setY(y: Float)
    abstract fun getScale(): Float
    abstract fun setScale(s: Float)
    abstract fun getWidth(): Float
    abstract fun getHeight(): Float
    abstract fun isVisible(): Boolean
    open fun isEnabled(): Boolean = isVisible()
    abstract fun getName(): String
    abstract fun savePosition()

    protected abstract fun renderElement(nvg: NVGRenderer, editMode: Boolean)

    open fun renderMinecraft(graphics: GuiGraphicsExtractor, editMode: Boolean) {
    }
    open fun rendersBeforeMinecraft(): Boolean = false
    open fun rendersWithHud(): Boolean = true
    open fun renderOverlay(nvg: NVGRenderer, editMode: Boolean) {
    }

    fun render(nvg: NVGRenderer, editMode: Boolean) {
        if (if (editMode) !isEnabled() else !isVisible()) return
        nvg.save()
        nvg.translate(getX(), getY())
        nvg.scale(getScale(), getScale())
        renderElement(nvg, editMode)
        if (editMode) {
            val border = if (isDragging()) Theme.HUD_ACCENT else if (isResizing()) Theme.HUD_WARNING else Theme.HUD_BORDER
            nvg.rectOutline(0f, 0f, getWidth(), getHeight(), HudStyle.RADIUS, 1f, border)
        }
        nvg.restore()
    }

    fun isInteracting(): Boolean = dragging || resizing
    fun isDragging(): Boolean = dragging
    fun isResizing(): Boolean = resizing

    fun isHovered(mx: Double, my: Double): Boolean {
        val s = getScale()
        val lx = (mx - getX()) / s
        val ly = (my - getY()) / s
        return lx >= 0 && lx <= getWidth() && ly >= 0 && ly <= getHeight()
    }

    fun startDrag(mx: Double, my: Double, ctrl: Boolean) {
        if (ctrl) {
            resizing = true
            resizeStart = getScale()
            resizeMouseX = mx.toFloat()
        } else {
            dragging = true
            dragOffX = (mx - getX()).toFloat()
            dragOffY = (my - getY()).toFloat()
        }
    }

    fun drag(mx: Double, my: Double, screenW: Float, screenH: Float, snap: Int) {
        if (dragging) {
            var nx = (mx - dragOffX).toFloat()
            var ny = (my - dragOffY).toFloat()
            if (snap > 0) {
                nx = kotlin.math.round(nx / snap) * snap.toFloat()
                ny = kotlin.math.round(ny / snap) * snap.toFloat()
            }
            nx = maxOf(0f, minOf(screenW - getWidth() * getScale(), nx))
            ny = maxOf(0f, minOf(screenH - getHeight() * getScale(), ny))
            setX(nx)
            setY(ny)
        } else if (resizing) {
            val delta = (mx - resizeMouseX).toFloat()
            setScale(maxOf(0.5f, minOf(2.5f, resizeStart + delta * 0.005f)))
        }
    }

    fun endDrag() {
        if (dragging || resizing) {
            dragging = false
            resizing = false
            savePosition()
        }
    }
}
