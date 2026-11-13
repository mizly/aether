package dev.aether.ui

import dev.aether.renderer.NVGRenderer

internal object DropdownChevron {
    private const val HALF_WIDTH = 3.5f
    private const val HALF_HEIGHT = 2.25f
    private const val STROKE_WIDTH = 1.5f

    @JvmStatic
    fun renderDown(nvg: NVGRenderer, x: Float, y: Float, width: Float, height: Float, color: Int) {
        val centerX = x + width / 2f
        val centerY = y + height / 2f
        nvg.line(centerX - HALF_WIDTH, centerY - HALF_HEIGHT, centerX, centerY + HALF_HEIGHT, STROKE_WIDTH, color)
        nvg.line(centerX, centerY + HALF_HEIGHT, centerX + HALF_WIDTH, centerY - HALF_HEIGHT, STROKE_WIDTH, color)
    }
}
