package dev.aether.ui

import dev.aether.renderer.NVGRenderer

internal class MainGUIContentRenderer(private val owner: MainGUI, private val chromeRenderer: MainGUIChromeRenderer) {
    fun renderContent(nvg: NVGRenderer, mx: Float, my: Float) {
        val context = owner.context()
        if (context.navigation.activeMain == 0 && context.navigation.activeSubTab != null) {
            renderModuleDetailView(nvg, mx, my)
            return
        }
        chromeRenderer.renderContentTopBar(nvg, mx, my)
        if (owner.searchResultsVisible()) {
            owner.renderSearchContentPanel(nvg, mx, my)
            if (owner.hasOpenDropdown()) owner.renderDropdownOverlayPanel(nvg, mx, my)
            if (owner.hasActiveColorOverlay()) owner.renderColorOverlayPanel(nvg)
            return
        }
        if (context.navigation.activeMain == 0) {
            chromeRenderer.renderFilterBar(nvg, mx, my)
            val scrollTop = context.layout.contY + MainGUI.TOP_BAR_H + 1f + MainGUI.FILTER_BAR_H + 1f
            val scrollH = context.layout.contH - MainGUI.TOP_BAR_H - 1f - MainGUI.FILTER_BAR_H - 1f
            owner.renderModuleGridPanel(nvg, mx, my, scrollTop, scrollH)
            return
        }
        if (context.navigation.activeMain == 1) {
            chromeRenderer.renderFilterBar(nvg, mx, my)
            owner.renderFlatContentPanel(nvg, mx, my)
            if (owner.hasOpenDropdown()) owner.renderDropdownOverlayPanel(nvg, mx, my)
            if (owner.hasActiveColorOverlay()) owner.renderColorOverlayPanel(nvg)
            return
        }
        if (context.navigation.activeMain == 2) {
            chromeRenderer.renderFilterBar(nvg, mx, my)
            owner.renderProfileContentPanel(nvg, mx, my)
            return
        }
        owner.renderFlatContentPanel(nvg, mx, my)
        if (owner.hasOpenDropdown()) owner.renderDropdownOverlayPanel(nvg, mx, my)
        if (owner.hasActiveColorOverlay()) owner.renderColorOverlayPanel(nvg)
    }

    fun renderModuleDetailView(nvg: NVGRenderer, mx: Float, my: Float) {
        owner.renderModuleTopBarPanel(nvg, mx, my)
        renderModuleDetailBody(nvg, mx, my)
    }

    fun renderModuleDetailBody(nvg: NVGRenderer, mx: Float, my: Float) {
        val context = owner.context()
        val panelTop = context.layout.contY + MainGUI.TOP_BAR_H + 1f
        val panelH = context.layout.contH - MainGUI.TOP_BAR_H - 1f
        owner.renderModuleCategoryPanelSection(nvg, mx, my, panelTop, panelH)
        owner.renderModuleSettingsPanelSection(nvg, mx, my, panelTop, panelH)
        if (owner.hasOpenDropdown()) owner.renderDropdownOverlayPanel(nvg, mx, my)
        if (owner.hasActiveColorOverlay()) owner.renderColorOverlayPanel(nvg)
    }
}
