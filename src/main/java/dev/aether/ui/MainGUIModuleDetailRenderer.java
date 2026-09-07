package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.util.Fonts;
import dev.aether.ui.theme.Theme;
import dev.aether.util.AetherLang;

import java.util.List;

final class MainGUIModuleDetailRenderer {
    private final MainGUI owner;
    private final SelectionAnimation categorySelection = new SelectionAnimation();
    private ModulesTab.SubTab categorySubTab;

    MainGUIModuleDetailRenderer(MainGUI owner) {
        this.owner = owner;
    }

    void renderTopBar(NVGRenderer nvg, float mx, float my) {
        MainGUIContext context = owner.context();
        String moduleName = owner.moduleCardNameFor(owner.getActiveModuleSubTab());
        float totalHeight = MainGUI.TOP_BAR_H;

        String backLabel = "\u2190 " + AetherLang.localize("Back");
        float backTextWidth = nvg.textWidth(Fonts.REGULAR, backLabel, 11f);
        float backX = context.layout.contX + 20f;
        float textCenterY = context.layout.contY + (totalHeight - 11f) / 2f;
        boolean backHovered = mx >= context.layout.contX + 14f && mx < context.layout.contX + 14f + backTextWidth + 16f
                && my >= context.layout.contY && my < context.layout.contY + totalHeight;
        float buttonBg = backHovered ? 0.08f : 0.04f;
        nvg.roundedRect(context.layout.contX + 14f, context.layout.contY + (totalHeight - 26f) / 2f, backTextWidth + 16f, 26f, 5f,
                Theme.withAlpha(Theme.TEXT_PRIMARY, buttonBg));
        nvg.text(Fonts.REGULAR, backLabel, backX + 2f, textCenterY, 11f,
                backHovered ? Theme.TEXT_PRIMARY : Theme.TEXT_VALUE);
        owner.addClickArea(context.layout.contX + 14f, context.layout.contY, backTextWidth + 16f, totalHeight,
                owner::exitModuleDetailView);

        float sep1X = context.layout.contX + 20f + backTextWidth + 14f;
        nvg.rect(sep1X, context.layout.contY + 15f, 1f, totalHeight - 30f, Theme.SEPARATOR);

        float nameX = sep1X + 12f;
        float nameWidth = nvg.textWidth(Fonts.BOLD, moduleName, 13f);
        nvg.text(Fonts.BOLD, moduleName, nameX, context.layout.contY + (totalHeight - 13f) / 2f, 13f, Theme.TEXT_PRIMARY);

        float sep2X = nameX + nameWidth + 12f;
        nvg.rect(sep2X, context.layout.contY + 15f, 1f, totalHeight - 30f, Theme.SEPARATOR);

        float descY = context.layout.contY + (MainGUI.TOP_BAR_H - 12f) / 2f;
        nvg.text(Fonts.REGULAR, AetherLang.localize("Module settings"), sep2X + 12f, descY, 12f, Theme.TEXT_MUTED);
        nvg.rect(context.layout.contX, context.layout.contY + totalHeight, context.layout.contW, 1f, Theme.SEPARATOR);
    }

    void renderCategoryPanel(NVGRenderer nvg, float mx, float my, float panelTop, float panelH) {
        MainGUIContext context = owner.context();
        ModulesTab.SubTab activeSubTab = owner.getActiveModuleSubTab();
        float categoryX = context.layout.contX;
        float categoryW = MainGUI.MODULE_CAT_W;
        boolean subtabEnabled = owner.isSubTabEnabledFor(activeSubTab);

        nvg.rect(categoryX + categoryW, panelTop, 1f, panelH, Theme.SEPARATOR);
        nvg.text(Fonts.BOLD, AetherLang.localize("Categories").toUpperCase(), categoryX + 16f, panelTop + 18f, 9f,
                Theme.withAlpha(Theme.TEXT_MUTED, 185));

        float itemHeight = 36f;
        float itemsStart = panelTop + 44f;
        List<SettingGroup> groups = activeSubTab.groups();
        boolean skipAll = groups.size() == 1;
        if (skipAll && owner.getActiveCategoryIndex() == 0) {
            owner.setActiveCategoryIndex(1);
            context = owner.context();
        }

        int totalItems = skipAll ? groups.size() : groups.size() + 1;
        int selectedRow = skipAll ? Math.max(0, owner.getActiveCategoryIndex() - 1) : owner.getActiveCategoryIndex();
        long nowNanos = System.nanoTime();
        if (categorySubTab != activeSubTab) {
            categorySelection.reset(selectedRow * itemHeight, nowNanos);
            categorySubTab = activeSubTab;
        }
        float highlightY = categorySelection.update(selectedRow * itemHeight, Theme.ANIM_TIME_MS, nowNanos);
        nvg.roundedRect(categoryX + 8f, itemsStart + highlightY + 3f, categoryW - 16f, 30f, 6f,
                Theme.withAlpha(Theme.ACCENT_PRIMARY, subtabEnabled ? 0.12f : 0.06f));

        for (int i = 0; i < totalItems; i++) {
            boolean isAll = !skipAll && i == 0;
            int groupIndex = skipAll ? i : i - 1;
            String label = isAll ? AetherLang.localize("All") : AetherLang.localize(groups.get(groupIndex).getName());
            Object animKey = owner.moduleCategoryAnimationKey(isAll ? null : groups.get(groupIndex), isAll);
            float itemY = itemsStart + i * itemHeight;
            boolean selected = owner.getActiveCategoryIndex() == (skipAll ? i + 1 : i);

            float hover = owner.moduleCategoryHoverProgress(animKey);
            boolean inItem = mx >= categoryX + 6f && mx < categoryX + categoryW - 6f
                    && my >= itemY && my < itemY + itemHeight;
            hover += ((!selected && inItem ? 1f : 0f) - hover) * Math.min(1f, Theme.animationFactor() * 7f);
            owner.setModuleCategoryHoverProgress(animKey, hover);

            if (selected) {
                nvg.text(Fonts.BOLD, label, categoryX + 18f, itemY + 12f, 11f,
                        subtabEnabled ? Theme.TEXT_PRIMARY : Theme.withAlpha(Theme.TEXT_DIM, 190));
            } else {
                if (hover > 0.01f) {
                    nvg.roundedRect(categoryX + 8f, itemY + 3f, categoryW - 16f, 30f, 6f,
                            Theme.withAlpha(0xFFFFFFFF, hover * 0.05f));
                }
                int textColor = subtabEnabled
                        ? (hover > 0.01f ? Theme.blend(Theme.TEXT_MUTED, Theme.TEXT_VALUE, hover) : Theme.TEXT_MUTED)
                        : Theme.withAlpha(Theme.TEXT_DIM, 165);
                nvg.text(Fonts.REGULAR, label, categoryX + 18f, itemY + 12f, 11f, textColor);
            }

            if (subtabEnabled) {
                final int categoryIndex = skipAll ? i + 1 : i;
                owner.addClickArea(categoryX + 6f, itemY, categoryW - 12f, itemHeight,
                        () -> owner.selectModuleCategory(categoryIndex));
            }
        }

    }

    void renderSettingsPanel(NVGRenderer nvg, float mx, float my, float panelTop, float panelH) {
        MainGUIContext context = owner.context();
        ModulesTab.SubTab activeSubTab = owner.getActiveModuleSubTab();
        float rightX = context.layout.contX + MainGUI.MODULE_CAT_W + 1f;
        float rightW = context.layout.contW - MainGUI.MODULE_CAT_W - 1f;

        boolean subtabEnabled = owner.isSubTabEnabledFor(activeSubTab);
        String moduleName = owner.moduleCardNameFor(activeSubTab);
        String moduleDescription = AetherLang.localize(activeSubTab.description());
        float groupX = rightX + MainGUI.ITEM_PAD;
        float groupW = rightW - MainGUI.ITEM_PAD * 2f;

        nvg.text(Fonts.BOLD, moduleName, rightX + 20f, panelTop + 16f, 15f, Theme.TEXT_PRIMARY);
        nvg.text(Fonts.REGULAR, moduleDescription, rightX + 20f, panelTop + 37f, 10f, Theme.TEXT_MUTED);

        if (activeSubTab.hasToggle()) {
            float pillX = groupX + groupW - 52f;
            float pillY = panelTop + (MainGUI.MOD_HEADER_H - MainGUI.PILL_H) / 2f;
            owner.renderPillControl(nvg, pillX, pillY, subtabEnabled, activeSubTab);
            owner.addClickArea(pillX - 6f, pillY - 6f, 48f, MainGUI.PILL_H + 12f, activeSubTab::toggle);
        }

        nvg.rect(rightX, panelTop + MainGUI.MOD_HEADER_H, rightW, 1f, Theme.SEPARATOR);

        int maxCategoryIndex = activeSubTab.groups().size();
        if (owner.getActiveCategoryIndex() > maxCategoryIndex) {
            owner.setActiveCategoryIndex(0);
        }

        boolean showAll = owner.getActiveCategoryIndex() == 0;
        List<SettingGroup> renderGroups = showAll
                ? activeSubTab.groups()
                : List.of(activeSubTab.groups().get(owner.getActiveCategoryIndex() - 1));

        float settingsTop = panelTop + MainGUI.MOD_HEADER_H + 1f;
        float settingsH = panelH - MainGUI.MOD_HEADER_H - 1f;

        boolean clipped = owner.pushContentLocalScissor(nvg, rightX, settingsTop, rightW, settingsH);
        context = owner.context();
        float y = settingsTop - context.scroll.scrollY;
        float total = 0f;

        for (SettingGroup group : renderGroups) {
            y += 14f;
            total += 14f;
            if (y + MainGUI.HEADER_H > settingsTop && y < settingsTop + settingsH) {
                owner.renderGroupHeaderPanel(nvg, group, groupX, y, groupW, mx, my, subtabEnabled);
            }
            y += MainGUI.HEADER_H;
            total += MainGUI.HEADER_H;

            if (owner.shouldShowChildren(group) && group.hasSettings()) {
                y += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                total += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                if (owner.isPetTrackerSettingsGroup(group)) {
                    float groupHeight = owner.petTrackerGroupHeightValue();
                    if (y + groupHeight > settingsTop && y < settingsTop + settingsH) {
                        owner.renderPetTrackerGroupPanel(nvg, group, groupX, y, groupW, mx, my);
                        if (!subtabEnabled) {
                            float cardH = groupHeight - 5f;
                            nvg.roundedRect(groupX, y, groupW, cardH, 7f, Theme.withAlpha(Theme.PANEL_BG, 0.42f));
                        }
                    }
                    y += groupHeight;
                    total += groupHeight;
                } else {
                    for (Setting setting : group.getSettings()) {
                        if (!setting.isVisible()) {
                            continue;
                        }
                        float settingHeight = owner.settingHeightFor(setting, groupW);
                        if (y + settingHeight > settingsTop && y < settingsTop + settingsH) {
                            owner.renderSettingRow(nvg, setting, groupX, y, groupW, settingHeight, mx, my);
                            if (!subtabEnabled) {
                                float cardH = settingHeight - 5f;
                                nvg.roundedRect(groupX, y, groupW, cardH, 7f, Theme.withAlpha(Theme.PANEL_BG, 0.42f));
                            }
                        }
                        y += settingHeight;
                        total += settingHeight;
                    }
                }
                y += 4f;
                total += 4f;
            }
        }

        owner.popContentLocalScissor(nvg, clipped);
        owner.renderMainScrollbar(nvg, total, settingsTop, settingsH, rightX + rightW - 6f);
    }
}
