package dev.aether.ui.components

import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import dev.aether.renderer.NVGRenderer
import java.util.function.Consumer

// label on the left, sliding pill track on the right
class Toggle(label: String, initialValue: Boolean, onChange: Consumer<Boolean>?) : Component() {
    private var slideAmt: Float = if (initialValue) 1f else 0f
    private var label: String = label
    private var value: Boolean = initialValue
    private var onChange: Consumer<Boolean>? = onChange

    init { height = TRACK_H }

    override fun render(nvg: NVGRenderer) {
        if (!visible) return
        val target = if (value) 1f else 0f
        slideAmt += (target - slideAmt) * (1f - Math.pow((1f - ANIM_SPEED).toDouble(), 1.0).toFloat())
        slideAmt = slideAmt + (target - slideAmt) * ANIM_SPEED * 3f
        if (Math.abs(slideAmt - target) < 0.002f) slideAmt = target
        val trackX = x + width - TRACK_W
        val trackY = y + (height - TRACK_H) / 2f
        var trackColor = Colors.lerp(Colors.BORDER, Colors.ACCENT, slideAmt)
        if (!enabled) trackColor = Colors.withAlpha(trackColor, 0.5f)
        nvg.roundedRect(trackX, trackY, TRACK_W, TRACK_H, TRACK_H / 2f, trackColor)
        val thumbD = TRACK_H - THUMB_PAD * 2
        val thumbTravel = TRACK_W - THUMB_PAD * 2 - thumbD
        val thumbX = trackX + THUMB_PAD + slideAmt * thumbTravel
        val thumbY = trackY + THUMB_PAD
        val thumbColor = if (enabled) Colors.WHITE else Colors.TEXT_DIM
        nvg.circle(thumbX + thumbD / 2f, thumbY + thumbD / 2f, thumbD / 2f, thumbColor)
        val labelColor = if (enabled) Colors.TEXT else Colors.TEXT_OFF
        val labelY = y + (height - FONT_SIZE) / 2f
        nvg.text(Fonts.REGULAR, label, x, labelY, FONT_SIZE, labelColor)
    }

    override fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && enabled && contains(mouseX, mouseY)) { setValue(!value); return true }
        return false
    }

    fun getValue(): Boolean = value
    fun setValue(v: Boolean): Toggle { value = v; onChange?.accept(v); return this }
    fun setLabel(label: String): Toggle { this.label = label; return this }
    fun setOnChange(h: Consumer<Boolean>?): Toggle { onChange = h; return this }

    companion object {
        private const val TRACK_W = 38f
        private const val TRACK_H = 20f
        private const val THUMB_PAD = 3f
        private const val FONT_SIZE = 13f
        private const val ANIM_SPEED = 0.12f
    }
}
