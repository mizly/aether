package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.util.Fonts;
import dev.aether.ui.theme.Theme;
import dev.aether.util.AetherLang;

final class MainGUIChromeRenderer {
    private final MainGUI owner;

    MainGUIChromeRenderer(MainGUI owner) {
        this.owner = owner;
    }

    void renderSidebar(NVGRenderer nvg, float mx, float my) {
        MainGUIContext context = owner.context();

        if (context.animation.computedSidebarExpanded < 1f) {
            float maxTW = 0f;
            for (String name : new String[]{AetherLang.localize("Modules"), AetherLang.localize("Colors"), AetherLang.localize("Config"), AetherLang.localize("Keybinds"), AetherLang.localize("Settings"), AetherLang.localize("HUD Positions")}) {
                maxTW = Math.max(maxTW, nvg.textWidth(Fonts.REGULAR, name, 12f));
            }
            owner.setComputedSidebarExpanded(MainGUI.SB_H_PAD + MainGUI.SB_PILL + 10f + maxTW + 14f);
            context = owner.context();
        }

        float sbW = MainGUI.SIDEBAR_W + context.animation.sidebarAnim
                * (context.animation.computedSidebarExpanded - MainGUI.SIDEBAR_W);
        float textX = context.layout.px + MainGUI.SB_H_PAD + MainGUI.SB_PILL + 10f;

        nvg.roundedRect(context.layout.px, context.layout.py, sbW, context.layout.ph, MainGUI.RADIUS, Theme.SIDEBAR_BG);
        nvg.rect(context.layout.px + MainGUI.RADIUS, context.layout.py, sbW - MainGUI.RADIUS, context.layout.ph, Theme.SIDEBAR_BG);
        nvg.rect(context.layout.px + sbW - 1f, context.layout.py, 1f, context.layout.ph, Theme.SEPARATOR);

        nvg.pushScissor(context.layout.px, context.layout.py, sbW, context.layout.ph);

        float logoSize = 34f;
        float logoX = context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - logoSize) / 2f;
        float logoY = context.layout.py + (MainGUI.SB_LOGO_H - logoSize) / 2f;
        nvg.renderSVG("/assets/aether/icons/logo.svg", logoX, logoY, logoSize, logoSize, Theme.ACCENT_PRIMARY);
        if (context.animation.sidebarAnim > 0.01f) {
            nvg.save();
            nvg.globalAlpha(context.animation.sidebarAnim);
            nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f);
            nvg.text(Fonts.BOLD, AetherLang.localize("Aether"), textX, logoY + (logoSize - 14f) / 2f, 18f, Theme.ACCENT_PRIMARY);
            nvg.restore();
        }

        float sepY = context.layout.py + MainGUI.SB_LOGO_H;
        nvg.rect(context.layout.px + MainGUI.SB_H_PAD, sepY, sbW - MainGUI.SB_H_PAD * 2f, 1f, Theme.SEPARATOR);

        final int sidebarHoverColor = Theme.TEXT_VALUE;
        String[] tabIds = { "modules", "colors", "config" };
        String[] tabLabels = { AetherLang.localize("Modules"), AetherLang.localize("Colors"), AetherLang.localize("Config") };
        float[] tabIconW = { 16f, 16f, 14f };
        float[] tabIconH = { 19f, 16f, 18f };
        float tabsY = sepY + MainGUI.SB_SEP_GAP;

        for (int i = 0; i < tabLabels.length; i++) {
            float tabY = tabsY + i * 44f;
            float pillY = tabY + MainGUI.SB_ROW_PAD;
            boolean selected = context.navigation.activeMain == i;
            boolean hovered = !selected && mx >= context.layout.px && mx < context.layout.px + sbW
                    && my >= tabY && my <= tabY + 44f;
            int color = selected ? Theme.ACCENT_PRIMARY : (hovered ? sidebarHoverColor : Theme.TEXT_MUTED);

            if (selected) {
                nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, pillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f,
                        Theme.withAlpha(Theme.ACCENT_PRIMARY, 0x55));
            } else if (hovered) {
                nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, pillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f,
                        Theme.withAlpha(Theme.TEXT_MUTED, 0x33));
            }

            float iconOffX = (MainGUI.SB_PILL - tabIconW[i]) / 2f;
            float iconOffY = (MainGUI.SB_PILL - tabIconH[i]) / 2f;
            nvg.renderSVG("/assets/aether/icons/" + tabIds[i] + ".svg",
                    context.layout.px + MainGUI.SB_H_PAD + iconOffX, pillY + iconOffY, tabIconW[i], tabIconH[i], color);

            if (context.animation.sidebarAnim > 0.01f) {
                nvg.save();
                nvg.globalAlpha(context.animation.sidebarAnim);
                nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f);
                nvg.text(Fonts.REGULAR, tabLabels[i], textX, pillY + (MainGUI.SB_PILL - 12f) / 2f, 12f, color);
                nvg.restore();
            }
        }

        float settingsTabY = context.layout.py + context.layout.ph - MainGUI.SB_BOT_PAD - 44f;
        float keybindsTabY = settingsTabY - 44f;
        float hudPositionsTabY = keybindsTabY - 44f;
        nvg.rect(context.layout.px + MainGUI.SB_H_PAD, hudPositionsTabY - MainGUI.SB_SEP_GAP,
                sbW - MainGUI.SB_H_PAD * 2f, 1f, Theme.SEPARATOR);

        float hudPillY = hudPositionsTabY + MainGUI.SB_ROW_PAD;
        boolean hudHovered = mx >= context.layout.px && mx < context.layout.px + sbW
                && my >= hudPositionsTabY && my <= hudPositionsTabY + 44f;
        int hudColor = hudHovered ? sidebarHoverColor : Theme.TEXT_MUTED;
        if (hudHovered) {
            nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, hudPillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f,
                    Theme.withAlpha(Theme.TEXT_MUTED, 0x33));
        }
        float hudIconSize = 18f;
        nvg.renderSVG("/assets/aether/icons/hud_positions.svg",
                context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - hudIconSize) / 2f,
                hudPillY + (MainGUI.SB_PILL - hudIconSize) / 2f,
                hudIconSize, hudIconSize, hudColor);
        if (context.animation.sidebarAnim > 0.01f) {
            nvg.save();
            nvg.globalAlpha(context.animation.sidebarAnim);
            nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f);
            nvg.text(Fonts.REGULAR, AetherLang.localize("HUD Positions"), textX, hudPillY + (MainGUI.SB_PILL - 12f) / 2f, 12f, hudColor);
            nvg.restore();
        }

        float keybindsPillY = keybindsTabY + MainGUI.SB_ROW_PAD;
        boolean keybindsSelected = context.navigation.activeMain == 3;
        boolean keybindsHovered = !keybindsSelected && mx >= context.layout.px && mx < context.layout.px + sbW
                && my >= keybindsTabY && my <= keybindsTabY + 44f;
        int keybindsColor = keybindsSelected ? Theme.ACCENT_PRIMARY
                : (keybindsHovered ? sidebarHoverColor : Theme.TEXT_MUTED);
        if (keybindsSelected) {
            nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, keybindsPillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f,
                    Theme.withAlpha(Theme.ACCENT_PRIMARY, 0x55));
        } else if (keybindsHovered) {
            nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, keybindsPillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f,
                    Theme.withAlpha(Theme.TEXT_MUTED, 0x33));
        }
        float keybindsIconSize = 18f;
        nvg.renderSVG("/assets/aether/icons/keybinds.svg",
                context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - keybindsIconSize) / 2f,
                keybindsPillY + (MainGUI.SB_PILL - keybindsIconSize) / 2f,
                keybindsIconSize, keybindsIconSize, keybindsColor);
        if (context.animation.sidebarAnim > 0.01f) {
            nvg.save();
            nvg.globalAlpha(context.animation.sidebarAnim);
            nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f);
            nvg.text(Fonts.REGULAR, AetherLang.localize("Keybinds"), textX, keybindsPillY + (MainGUI.SB_PILL - 12f) / 2f, 12f, keybindsColor);
            nvg.restore();
        }

        float settingsPillY = settingsTabY + MainGUI.SB_ROW_PAD;
        boolean settingsSelected = context.navigation.activeMain == 4;
        boolean settingsHovered = !settingsSelected && mx >= context.layout.px && mx < context.layout.px + sbW
                && my >= settingsTabY && my <= settingsTabY + 44f;
        int settingsColor = settingsSelected ? Theme.ACCENT_PRIMARY
                : (settingsHovered ? sidebarHoverColor : Theme.TEXT_MUTED);
        if (settingsSelected) {
            nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, settingsPillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f,
                    Theme.withAlpha(Theme.ACCENT_PRIMARY, 0x55));
        } else if (settingsHovered) {
            nvg.roundedRect(context.layout.px + MainGUI.SB_H_PAD, settingsPillY, MainGUI.SB_PILL, MainGUI.SB_PILL, 8f,
                    Theme.withAlpha(Theme.TEXT_MUTED, 0x33));
        }
        float settingsIconSize = 16f;
        nvg.renderSVG("/assets/aether/icons/settings.svg",
                context.layout.px + MainGUI.SB_H_PAD + (MainGUI.SB_PILL - settingsIconSize) / 2f,
                settingsPillY + (MainGUI.SB_PILL - settingsIconSize) / 2f,
                settingsIconSize, settingsIconSize, settingsColor);
        if (context.animation.sidebarAnim > 0.01f) {
            nvg.save();
            nvg.globalAlpha(context.animation.sidebarAnim);
            nvg.translate((1f - context.animation.sidebarAnim) * -6f, 0f);
            nvg.text(Fonts.REGULAR, AetherLang.localize("Settings"), textX, settingsPillY + (MainGUI.SB_PILL - 12f) / 2f, 12f, settingsColor);
            nvg.restore();
        }

        nvg.popScissor();
    }

    void renderContentTopBar(NVGRenderer nvg, float mx, float my) {
        MainGUIContext context = owner.context();
        String name = owner.currentTabName();
        String desc = owner.currentTabDescription();

        float nameY = context.layout.contY + (MainGUI.TOP_BAR_H - 15f) / 2f;
        nvg.text(Fonts.BOLD, name, context.layout.contX + 20f, nameY, 15f, Theme.TEXT_PRIMARY);

        float nameW = nvg.textWidth(Fonts.BOLD, name, 15f);
        float sepX = context.layout.contX + 20f + nameW + 10f;
        nvg.rect(sepX, context.layout.contY + 17f, 1f, 19f, Theme.SEPARATOR);

        float descY = context.layout.contY + (MainGUI.TOP_BAR_H - 12f) / 2f;
        nvg.text(Fonts.REGULAR, desc, sepX + 10f, descY, 12f, Theme.TEXT_MUTED);

        float searchW = 190f;
        float searchH = 30f;
        float searchX = context.layout.contX + context.layout.contW - 20f - searchW;
        float searchY = context.layout.contY + (MainGUI.TOP_BAR_H - searchH) / 2f;
        owner.setSearchBoxBounds(searchX, searchY, searchW, searchH);
        context = owner.context();

        int searchBg = Theme.BG_FIELD;
        int searchOutline = context.navigation.searchMode ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT;
        nvg.roundedRect(searchX, searchY, searchW, searchH, 7f, searchBg);
        nvg.rectOutlineSolid(searchX, searchY, searchW, searchH, 7f, 1f, searchOutline);
        nvg.renderSVG("/assets/aether/icons/search.svg",
                searchX + 8f, searchY + (searchH - 14f) / 2f, 14f, 14f,
                context.navigation.searchMode ? Theme.ACCENT_PRIMARY : Theme.TEXT_MUTED);

        if (context.navigation.searchQuery.isEmpty()) {
            nvg.text(Fonts.REGULAR, AetherLang.localize("Search settings..."),
                    searchX + 30f, searchY + (searchH - 11f) / 2f, 11f,
                    context.navigation.searchMode ? Theme.TEXT_SECONDARY : Theme.TEXT_MUTED);
        } else {
            String display = owner.isSearchEditorActiveState() ? owner.currentTextBuffer() : context.navigation.searchQuery;
            float textPad = 6f;
            float fontSize = 11f;
            float visibleTextW = searchW - 30f - textPad * 2f;
            float textOffset = owner.isSearchEditorActiveState()
                    ? owner.inlineTextScrollOffsetFor(nvg, Fonts.REGULAR, display, owner.currentTextCursor(), fontSize, visibleTextW)
                    : 0f;
            float drawX = searchX + 30f + textPad - textOffset;
            nvg.pushScissor(searchX + 30f + textPad, searchY + 1f, visibleTextW, searchH - 2f);
            if (owner.isSearchEditorActiveState() && owner.textHasSelection()) {
                int selStart = owner.currentTextSelectionStart();
                int selEnd = owner.currentTextSelectionEnd();
                float selX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, selStart), fontSize);
                float selW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, selStart, selEnd), fontSize);
                nvg.roundedRect(selX, searchY + 4f, Math.max(1f, selW), searchH - 8f, 3f,
                        Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f));
            }
            nvg.text(Fonts.REGULAR, display, drawX, searchY + (searchH - fontSize) / 2f, fontSize, Theme.TEXT_PRIMARY);
            if (owner.isSearchEditorActiveState() && (System.currentTimeMillis() / 530) % 2 == 0) {
                float caretX = drawX + nvg.textWidth(Fonts.REGULAR,
                        owner.safeTextSlice(display, 0, owner.currentTextCursor()), fontSize);
                nvg.rect(caretX, searchY + (searchH - 14f) / 2f, 1f, 14f, Theme.TEXT_PRIMARY);
            }
            nvg.popScissor();
        }

        owner.addClickArea(searchX, searchY, searchW, searchH, owner::activateSearchField);
        nvg.rect(context.layout.contX, context.layout.contY + MainGUI.TOP_BAR_H, context.layout.contW, 1f, Theme.SEPARATOR);
    }

    void renderFilterBar(NVGRenderer nvg, float mx, float my) {
        MainGUIContext context = owner.context();
        float animationStep = Math.min(1f, Theme.animationFactor() * 6f);
        String[] options = owner.currentTabFilters();
        int selectedIndex = Math.max(0, Math.min(context.navigation.activeFilter, options.length - 1));

        float barY = context.layout.contY + MainGUI.TOP_BAR_H + 1f;
        float textY = barY + (MainGUI.FILTER_BAR_H - 12f) / 2f;
        float optionPad = 14f;
        float x = context.layout.contX + 20f;

        float[] optionX = new float[options.length];
        float[] optionW = new float[options.length];
        for (int i = 0; i < options.length; i++) {
            float textW = nvg.textWidth(Fonts.REGULAR, options[i], 12f);
            optionX[i] = x;
            optionW[i] = textW + optionPad * 2f;
            x += optionW[i];
        }

        float targetX = optionX[selectedIndex] + optionPad;
        float targetW = nvg.textWidth(Fonts.REGULAR, options[selectedIndex], 12f);
        owner.setFilterBarTarget(targetX, targetW);

        if (!context.animation.filterBarInited) {
            owner.setFilterBarAnimation(targetX, targetW);
            owner.setFilterBarInitialized(true);
            context = owner.context();
        } else {
            owner.setFilterBarAnimation(
                    context.animation.filterBarAnimX + (targetX - context.animation.filterBarAnimX) * animationStep,
                    context.animation.filterBarAnimW + (targetW - context.animation.filterBarAnimW) * animationStep
            );
            context = owner.context();
        }

        for (int i = 0; i < options.length; i++) {
            boolean selected = context.navigation.activeFilter == i;
            nvg.text(Fonts.REGULAR, options[i], optionX[i] + optionPad, textY, 12f,
                    selected ? Theme.ACCENT_PRIMARY : Theme.TEXT_MUTED);

            final int filterIndex = i;
            owner.addClickArea(optionX[i], barY, optionW[i], MainGUI.FILTER_BAR_H,
                    () -> owner.onFilterSelected(filterIndex));
        }

        float sepY = barY + MainGUI.FILTER_BAR_H;
        nvg.rect(context.layout.contX, sepY, context.layout.contW, 1f, Theme.SEPARATOR);
        nvg.roundedRect(context.animation.filterBarAnimX, sepY - 1f, context.animation.filterBarAnimW, 2f, 1f,
                Theme.ACCENT_PRIMARY);
    }
}
