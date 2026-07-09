package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;

final class MainGUIContentRenderer {
    private final MainGUI owner;
    private final MainGUIChromeRenderer chromeRenderer;

    MainGUIContentRenderer(MainGUI owner, MainGUIChromeRenderer chromeRenderer) {
        this.owner = owner;
        this.chromeRenderer = chromeRenderer;
    }

    void renderContent(NVGRenderer nvg, float mx, float my) {
        MainGUIContext context = owner.context();
        if (context.navigation.activeMain == 0 && context.navigation.activeSubTab != null) {
            renderModuleDetailView(nvg, mx, my);
            return;
        }

        chromeRenderer.renderContentTopBar(nvg, mx, my);

        if (owner.searchResultsVisible()) {
            owner.renderSearchContentPanel(nvg, mx, my);
            if (owner.hasOpenDropdown()) {
                owner.renderDropdownOverlayPanel(nvg, mx, my);
            }
            if (owner.hasActiveColorOverlay()) {
                owner.renderColorOverlayPanel(nvg);
            }
            return;
        }

        if (context.navigation.activeMain == 0) {
            chromeRenderer.renderFilterBar(nvg, mx, my);
            float scrollTop = context.layout.contY + MainGUI.TOP_BAR_H + 1f + MainGUI.FILTER_BAR_H + 1f;
            float scrollH = context.layout.contH - MainGUI.TOP_BAR_H - 1f - MainGUI.FILTER_BAR_H - 1f;
            owner.renderModuleGridPanel(nvg, mx, my, scrollTop, scrollH);
            return;
        }

        if (context.navigation.activeMain == 1) {
            chromeRenderer.renderFilterBar(nvg, mx, my);
            owner.renderFlatContentPanel(nvg, mx, my);
            if (owner.hasOpenDropdown()) {
                owner.renderDropdownOverlayPanel(nvg, mx, my);
            }
            if (owner.hasActiveColorOverlay()) {
                owner.renderColorOverlayPanel(nvg);
            }
            return;
        }

        if (context.navigation.activeMain == 2) {
            chromeRenderer.renderFilterBar(nvg, mx, my);
            owner.renderProfileContentPanel(nvg, mx, my);
            return;
        }

        owner.renderFlatContentPanel(nvg, mx, my);
        if (owner.hasOpenDropdown()) {
            owner.renderDropdownOverlayPanel(nvg, mx, my);
        }
        if (owner.hasActiveColorOverlay()) {
            owner.renderColorOverlayPanel(nvg);
        }
    }

    void renderModuleDetailView(NVGRenderer nvg, float mx, float my) {
        owner.renderModuleTopBarPanel(nvg, mx, my);
        renderModuleDetailBody(nvg, mx, my);
    }

    void renderModuleDetailBody(NVGRenderer nvg, float mx, float my) {
        MainGUIContext context = owner.context();
        float panelTop = context.layout.contY + MainGUI.TOP_BAR_H + 1f;
        float panelH = context.layout.contH - MainGUI.TOP_BAR_H - 1f;
        owner.renderModuleCategoryPanelSection(nvg, mx, my, panelTop, panelH);
        owner.renderModuleSettingsPanelSection(nvg, mx, my, panelTop, panelH);
        if (owner.hasOpenDropdown()) {
            owner.renderDropdownOverlayPanel(nvg, mx, my);
        }
        if (owner.hasActiveColorOverlay()) {
            owner.renderColorOverlayPanel(nvg);
        }
    }
}
