package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

final class MainGUISearchPanel {
    private static final float RESULT_H = 54f;
    private static final float RESULT_GAP = 6f;
    private static final float MODULE_GAP = 10f;

    private final MainGUI owner;
    private final List<SearchResult> searchResults = new ArrayList<>();

    private record SearchResult(int mainTab, String sourceLabel, ModulesTab.SubTab subtab,
                                SettingGroup group, Setting setting) {}

    MainGUISearchPanel(MainGUI owner) {
        this.owner = owner;
    }

    void render(NVGRenderer nvg, float mx, float my) {
        float gx = owner.contX + MainGUI.ITEM_PAD;
        float gw = owner.contW - MainGUI.ITEM_PAD * 2f;
        float resultsTop = owner.contY + MainGUI.TOP_BAR_H + 1f;
        float resultsH = owner.contH - MainGUI.TOP_BAR_H - 1f;

        buildResults(owner.searchQuery.toLowerCase());

        float y = resultsTop + 10f - owner.searchScrollY;
        float total = 10f;

        nvg.pushScissor(owner.contX, resultsTop, owner.contW, resultsH);

        ModulesTab.SubTab lastSubtab = null;
        for (SearchResult result : searchResults) {
            if (result.subtab() != lastSubtab) {
                if (lastSubtab != null) {
                    y += MODULE_GAP;
                    total += MODULE_GAP;
                }
                renderModuleHeader(nvg, result, gx, y, gw);
                y += MainGUI.HEADER_H;
                total += MainGUI.HEADER_H;
                y += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                total += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                lastSubtab = result.subtab();
            }

            if (y + RESULT_H > resultsTop && y < resultsTop + resultsH) {
                renderResult(nvg, result, gx, y, gw, mx, my);
                owner.addClickArea(gx, y, gw, RESULT_H,
                        () -> owner.openSearchResult(result.mainTab(), result.subtab(), result.group(), result.setting()));
            }
            y += RESULT_H + RESULT_GAP;
            total += RESULT_H + RESULT_GAP;
        }

        if (searchResults.isEmpty()) {
            nvg.textCentered(
                    Fonts.REGULAR,
                    AetherLang.localize("No results for") + " \"" + owner.searchQuery + "\"",
                    owner.contX,
                    y + 16f,
                    owner.contW,
                    20f,
                    11f,
                    Theme.TEXT_DIM
            );
        }

        owner.searchMaxScrollY = Math.max(0f, total - resultsH + 16f);
        nvg.popScissor();

        if (owner.searchMaxScrollY > 0f) {
            float ratio = resultsH / (total + 16f);
            float thumbH = Math.max(30f, resultsH * ratio);
            float trackT = resultsTop + MainGUI.RADIUS;
            float trackH = resultsH - MainGUI.RADIUS * 2f;
            owner.sbSrchTrackT = trackT;
            owner.sbSrchTrackH = trackH;
            owner.sbSrchThumbH = thumbH;
            float thumbY = MainGUI.scrollbarThumbY(trackT, trackH, thumbH, owner.searchScrollY, owner.searchMaxScrollY);
            nvg.roundedRect(owner.contX + owner.contW - 6f, thumbY, 4f, thumbH, 2f, Theme.withAlpha(Theme.TEXT_SECONDARY, 0.5f));
        }
    }

    void handleClick(float mx, float my) {
        float gx = owner.contX + MainGUI.ITEM_PAD;
        float gw = owner.contW - MainGUI.ITEM_PAD * 2f;
        if (mx < gx || mx > gx + gw) {
            return;
        }

        float resultsTop = owner.contY + MainGUI.TOP_BAR_H + 1f;
        float y = resultsTop + 10f - owner.searchScrollY;
        ModulesTab.SubTab lastSubtab = null;
        for (SearchResult result : searchResults) {
            if (result.subtab() != lastSubtab) {
                if (lastSubtab != null) {
                    y += MODULE_GAP;
                }
                y += MainGUI.HEADER_H + MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                lastSubtab = result.subtab();
            }
            if (my >= y && my <= y + RESULT_H) {
                owner.openSearchResult(result.mainTab(), result.subtab(), result.group(), result.setting());
                return;
            }
            y += RESULT_H + RESULT_GAP;
        }
    }

    private void renderModuleHeader(NVGRenderer nvg, SearchResult result, float x, float y, float w) {
        nvg.roundedRect(x, y, w, MainGUI.HEADER_H, 6f, Theme.BG_SECONDARY);
        nvg.roundedRect(x, y + 10f, 3f, MainGUI.HEADER_H - 20f, 2f, Theme.ACCENT_PRIMARY);
        nvg.text(Fonts.BOLD, result.subtab().name(), x + 12f, y + 9f, 13f, Theme.TEXT_PRIMARY);
        String context = AetherLang.localize(result.sourceLabel()) + " > " + result.subtab().name();
        nvg.textRight(Fonts.REGULAR, context, x, y + 9f, w - 8f, 10f, Theme.TEXT_DIM);
    }

    private void renderResult(NVGRenderer nvg, SearchResult result, float x, float y, float w, float mx, float my) {
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + RESULT_H;
        int background = hovered
                ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.12f)
                : Theme.BG_SECONDARY;
        int border = hovered
                ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.55f)
                : Theme.BORDER_DEFAULT;
        nvg.roundedRect(x, y, w, RESULT_H, 7f, background);
        nvg.rectOutline(x, y, w, RESULT_H, 7f, 1f, border);

        String title = result.setting() == null
                ? result.group() == null ? result.subtab().name() : result.group().getName()
                : result.setting().getName();
        owner.offerHoverHelp(
                "search-result:" + result.mainTab() + ":" + result.subtab().name() + ":" + title,
                AetherLang.localize(title),
                resultTooltip(result),
                x, y, w, RESULT_H, mx, my);
        nvg.text(Fonts.BOLD, AetherLang.localize(title), x + 14f, y + 10f, 12.5f,
                hovered ? Theme.TEXT_PRIMARY : Theme.TEXT_VALUE);
        String context = result.setting() == null
                ? AetherLang.localize("Module settings")
                : result.group() == null ? result.subtab().name() : result.group().getName();
        nvg.text(Fonts.REGULAR, AetherLang.localize(context), x + 14f, y + 31f, 10.5f, Theme.TEXT_MUTED);
        nvg.textRight(Fonts.REGULAR, "\u2192", x, y + 16f, w - 14f, 18f, hovered ? Theme.ACCENT_PRIMARY : Theme.TEXT_DIM);
    }

    private void buildResults(String query) {
        searchResults.clear();
        for (MainGUIRegistry.ModuleSection section : MainGUIRegistry.MODULE_SECTIONS) {
            addResults(searchResults, 0, section.displayName(), section.subtabs(), query);
        }
        addResults(searchResults, 1, AetherLang.localize("Colors"), MainGUIRegistry.COLORS_SUBTABS, query);
        addResults(searchResults, 3, AetherLang.localize("Keybinds"), MainGUIRegistry.KEYBINDS_SUBTABS, query);
        addResults(searchResults, 4, AetherLang.localize("Settings"), MainGUIRegistry.SETTINGS_SUBTABS, query);
    }

    private void addResults(List<SearchResult> out, int mainTab, String sourceLabel,
                            List<ModulesTab.SubTab> subtabs, String query) {
        for (ModulesTab.SubTab subtab : subtabs) {
            boolean subtabMatched = matchesQuery(subtab.name(), null, subtab.description(), query);
            boolean addedForSubtab = false;
            for (SettingGroup group : subtab.groups()) {
                boolean groupMatched = matchesQuery(group.getName(), group.getRawName(), group.getDescription(), query);
                boolean addedForGroup = false;
                for (Setting setting : group.getSettings()) {
                    if (!setting.isVisible()) {
                        continue;
                    }
                    if (subtabMatched || groupMatched
                            || matchesQuery(setting.getName(), setting.getRawName(), setting.getDescription(), query)) {
                        out.add(new SearchResult(mainTab, sourceLabel, subtab, group, setting));
                        addedForGroup = true;
                        addedForSubtab = true;
                    }
                }
                if (groupMatched && !addedForGroup) {
                    out.add(new SearchResult(mainTab, sourceLabel, subtab, group, null));
                    addedForSubtab = true;
                }
            }
            if (subtabMatched && !addedForSubtab) {
                out.add(new SearchResult(mainTab, sourceLabel, subtab, null, null));
            }
        }
    }

    private static String resultTooltip(SearchResult result) {
        if (result.setting() != null) {
            return result.setting().getDescription();
        }
        if (result.group() != null) {
            return result.group().getDescription();
        }
        return result.subtab().description();
    }

    private static boolean matchesQuery(String localized, String raw, String tooltip, String query) {
        return containsIgnoreCase(localized, query)
                || containsIgnoreCase(raw, query)
                || containsIgnoreCase(tooltip, query);
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }
}
