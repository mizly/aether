package dev.aether.ui.components

import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import dev.aether.renderer.NVGRenderer
import java.util.function.Consumer

// one row: label left, formatted value right, track filling the width below
class Slider(label: String, min: Double, max: Double, step: Double, initial: Double, onChange: Consumer<Double>?) : Component() {
    private var label: String = label
    private val min: Double = min
    private val max: Double = max
    private val step: Double = step
    private var value: Double = 0.0
    private var onChange: Consumer<Double>? = onChange
    private var dragging = false
    private var format = "%.1f"

    init { value = clamp(initial); height = LABEL_H + 4f + THUMB_R * 2 }

    override fun render(nvg: NVGRenderer) {
        if (!visible) return
        val labelY = y + (LABEL_H - FONT_SIZE) / 2f
        nvg.text(Fonts.REGULAR, label, x, labelY, FONT_SIZE, if (enabled) Colors.TEXT else Colors.TEXT_OFF)
        val valueStr = String.format(format, value)
        nvg.text(Fonts.MONO, valueStr, x + width - nvg.textWidth(Fonts.MONO, valueStr, FONT_SIZE), labelY, FONT_SIZE, Colors.TEXT_DIM)
        val trackY = y + LABEL_H + 4f + THUMB_R - TRACK_H / 2f
        val trackX0 = x + THUMB_R
        val trackW = width - THUMB_R * 2
        nvg.roundedRect(trackX0, trackY, trackW, TRACK_H, TRACK_H / 2f, Colors.BORDER)
        val t = ((value - min) / (max - min)).toFloat()
        val fillW = trackW * t
        if (fillW > 0) {
            val fillColor = if (enabled) Colors.ACCENT else Colors.withAlpha(Colors.ACCENT, 0.4f)
            nvg.roundedRect(trackX0, trackY, fillW, TRACK_H, TRACK_H / 2f, fillColor)
        }
        val thumbCX = trackX0 + fillW
        val thumbCY = trackY + TRACK_H / 2f
        val thumbColor = if (enabled) { if (dragging) Colors.ACCENT else Colors.WHITE } else Colors.TEXT_OFF
        nvg.circle(thumbCX, thumbCY, THUMB_R, thumbColor)
        if (enabled) nvg.circle(thumbCX, thumbCY, THUMB_R - 2f, if (dragging) Colors.ACCENT_DARK else Colors.SURFACE)
    }

    override fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && enabled && contains(mouseX, mouseY)) { dragging = true; setValueFromMouse(mouseX); return true }
        return false
    }
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && dragging) { dragging = false; return true }
        return false
    }
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (button == 0 && dragging) { setValueFromMouse(mouseX); return true }
        return false
    }
    private fun setValueFromMouse(mouseX: Double) {
        val trackX0 = x + THUMB_R
        val trackW = width - THUMB_R * 2
        val t = (mouseX - trackX0) / trackW
        setValue(min + t * (max - min))
    }

    fun getValue(): Double = value
    fun setValue(v: Double): Slider {
        val clamped = clamp(v)
        if (clamped != value) { value = clamped; onChange?.accept(value) }
        return this
    }
    fun setFormat(fmt: String): Slider { format = fmt; return this }
    fun setLabel(label: String): Slider { this.label = label; return this }
    private fun clamp(v0: Double): Double {
        var v = Math.max(min, Math.min(max, v0))
        if (step > 0) v = Math.round(v / step) * step
        return v
    }

    companion object {
        private const val TRACK_H = 4f
        private const val THUMB_R = 7f
        private const val LABEL_H = 16f
        private const val FONT_SIZE = 13f
    }
}
