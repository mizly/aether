package dev.aether.ui

class MainGUILayout {
    fun computeFrameLayout(width: Int, height: Int, pixelRatio: Float, uiScale: Float, sidebarWidth: Float): MainGUIContext.LayoutState {
        val layout = MainGUIContext.LayoutState()
        layout.pr = pixelRatio
        layout.physW = width * pixelRatio
        layout.physH = height * pixelRatio
        val canvasW = layout.physW / uiScale
        val canvasH = layout.physH / uiScale
        layout.pw = Math.min(MAX_PANEL_W, canvasW - PANEL_MARGIN)
        layout.ph = Math.min(MAX_PANEL_H, canvasH - PANEL_MARGIN)
        layout.px = (canvasW - layout.pw) / 2f
        layout.py = (canvasH - layout.ph) / 2f
        layout.contX = layout.px + sidebarWidth
        layout.contY = layout.py
        layout.contW = layout.pw - sidebarWidth
        layout.contH = layout.ph
        return layout
    }

    fun contentScrollTop(contY: Float, hasTopFilterBar: Boolean, topBarHeight: Float, filterBarHeight: Float): Float =
        contY + topBarHeight + 1f + (if (hasTopFilterBar) filterBarHeight + 1f else 0f)

    fun contentScrollHeight(contY: Float, contH: Float, hasTopFilterBar: Boolean, topBarHeight: Float, filterBarHeight: Float): Float =
        contH - (contentScrollTop(contY, hasTopFilterBar, topBarHeight, filterBarHeight) - contY)

    companion object {
        private const val PANEL_MARGIN = 40f
        private const val MAX_PANEL_W = 1060f
        private const val MAX_PANEL_H = 660f
    }
}
