package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.theme.Theme;

final class MainGUIModuleOverviewRenderer {
    private final MainGUI owner;

    MainGUIModuleOverviewRenderer(MainGUI owner) {
        this.owner = owner;
    }

    void render(NVGRenderer nvg, float mx, float my, float scrollTop, float scrollH) {
        MainGUIContext context = owner.context();
        float gridX = context.layout.contX + MainGUI.ITEM_PAD;
        float gridW = context.layout.contW - MainGUI.ITEM_PAD * 2f;
        float cardW = (gridW - MainGUI.CARD_HGAP * (MainGUI.CARD_COLS - 1)) / MainGUI.CARD_COLS;

        boolean clipped = owner.pushContentLocalScissor(nvg, context.layout.contX, scrollTop, context.layout.contW, scrollH);
        float y = scrollTop - context.scroll.scrollY;
        float total = 0f;

        for (MainGUIModuleSection section : owner.moduleSectionsForFilter()) {
            if (section.subtabs().isEmpty()) {
                continue;
            }

            float gap = total == 0f ? MainGUI.SECT_GAP * 0.6f : MainGUI.SECT_GAP;
            y += gap;
            total += gap;

            owner.renderSectionHeader(nvg, section.name(), gridX, y, gridW);
            y += MainGUI.SECT_H;
            total += MainGUI.SECT_H;
            y += MainGUI.SECT_SEP;
            total += MainGUI.SECT_SEP;

            var subtabs = section.subtabs();
            for (int i = 0; i < subtabs.size(); i += MainGUI.CARD_COLS) {
                for (int col = 0; col < MainGUI.CARD_COLS; col++) {
                    int idx = i + col;
                    if (idx >= subtabs.size()) {
                        break;
                    }
                    ModulesTab.SubTab subTab = subtabs.get(idx);
                    float cardX = gridX + col * (cardW + MainGUI.CARD_HGAP);
                    float hover = owner.moduleCardHoverProgress(subTab);
                    boolean inCard = mx >= cardX && mx < cardX + cardW && my >= y && my < y + MainGUI.CARD_H;
                    hover += ((inCard ? 1f : 0f) - hover) * Math.min(1f, Theme.animationFactor() * 7f);
                    owner.setModuleCardHoverProgress(subTab, hover);
                    if (y + MainGUI.CARD_H > scrollTop && y < scrollTop + scrollH) {
                        owner.renderModuleCardPanel(nvg, subTab, cardX, y, cardW, hover, mx, my);
                    }
                }
                y += MainGUI.CARD_H + MainGUI.CARD_VGAP;
                total += MainGUI.CARD_H + MainGUI.CARD_VGAP;
            }
            total -= MainGUI.CARD_VGAP;
            y -= MainGUI.CARD_VGAP;
            y += 14f;
            total += 14f;
        }

        owner.popContentLocalScissor(nvg, clipped);
        owner.renderMainScrollbar(nvg, total, scrollTop, scrollH, context.layout.contX + context.layout.contW - 6f);
    }
}
