package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;

final class MainGUIFlatPanelRenderer {
    private final MainGUI owner;

    MainGUIFlatPanelRenderer(MainGUI owner) {
        this.owner = owner;
    }

    void render(NVGRenderer nvg, float mx, float my) {
        MainGUIContext context = owner.context();
        float scrollTop = owner.contentScrollTop();
        float scrollH = owner.contentScrollHeight();
        float groupX = context.layout.contX + MainGUI.ITEM_PAD;
        float groupW = context.layout.contW - MainGUI.ITEM_PAD * 2f;

        boolean clipped = owner.pushContentLocalScissor(nvg, context.layout.contX, scrollTop, context.layout.contW, scrollH);
        float y = scrollTop - context.scroll.scrollY;
        float total = 0f;

        for (ModulesTab.SubTab subTab : owner.flatSubtabsForCurrentFilter()) {
            for (SettingGroup group : subTab.groups()) {
                y += 14f;
                total += 14f;
                owner.renderFlatGroupLabelPanel(nvg, group, groupX, y, groupW, mx, my);
                y += MainGUI.FLAT_LABEL_H;
                total += MainGUI.FLAT_LABEL_H;

                if (owner.shouldShowChildren(group) && group.hasSettings()) {
                    y += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                    total += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                    if (owner.isPetTrackerSettingsGroup(group)) {
                        float groupHeight = owner.petTrackerGroupHeightValue();
                        if (y + groupHeight > scrollTop && y < scrollTop + scrollH) {
                            owner.renderPetTrackerGroupPanel(nvg, group, groupX, y, groupW, mx, my);
                        }
                        y += groupHeight;
                        total += groupHeight;
                    } else {
                        for (Setting setting : group.getSettings()) {
                            if (!setting.isVisible()) {
                                continue;
                            }
                            float settingHeight = owner.settingHeightFor(setting, groupW);
                            if (y + settingHeight > scrollTop && y < scrollTop + scrollH) {
                                owner.renderSettingRow(nvg, setting, groupX, y, groupW, settingHeight, mx, my);
                            }
                            y += settingHeight;
                            total += settingHeight;
                        }
                    }
                    y += 4f;
                    total += 4f;
                }
            }
        }

        owner.popContentLocalScissor(nvg, clipped);
        owner.renderMainScrollbar(nvg, total, scrollTop, scrollH, context.layout.contX + context.layout.contW - 6f);
    }
}
