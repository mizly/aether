package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class MainGUISearchPanel {
    private final MainGUI owner;
    private final List<SearchResult> searchResults = new ArrayList<>();
    private final Map<SettingGroup, ToggleSetting> searchGroupToggles = new IdentityHashMap<>();

    private record SearchResult(String tabLabel, String subtabLabel, SettingGroup group, Setting setting) {}

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
        float tot = 10f;

        nvg.pushScissor(owner.contX, resultsTop, owner.contW, resultsH);

        SettingGroup lastGroup = null;
        for (SearchResult result : searchResults) {
            if (result.group() != lastGroup) {
                if (lastGroup != null) {
                    y += 4f;
                    tot += 4f;
                }
                nvg.roundedRect(gx, y, gw, MainGUI.HEADER_H, 6f, Theme.BG_SECONDARY);
                int stripe = result.group().isAlwaysOn()
                        ? Theme.withAlpha(Theme.TEXT_SECONDARY, 160)
                        : result.group().isEnabled() ? Theme.ACCENT_PRIMARY
                        : Theme.withAlpha(Theme.TEXT_DIM, 180);
                nvg.roundedRect(gx, y + 10f, 3f, MainGUI.HEADER_H - 20f, 2f, stripe);
                nvg.text(Fonts.BOLD, AetherLang.localize(result.group().getName()), gx + 12f, y + 9f, 13f, Theme.TEXT_PRIMARY);
                String ctx = AetherLang.localize(result.tabLabel()) + " > " + AetherLang.localize(result.subtabLabel());
                nvg.textRight(Fonts.REGULAR, ctx, gx, y + 9f, gw - 8f, 10f, Theme.TEXT_DIM);
                y += MainGUI.HEADER_H;
                tot += MainGUI.HEADER_H;
                y += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                tot += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                lastGroup = result.group();
            }
            float settingH = owner.settingH(result.setting(), gw);
            if (result.setting().isVisible()) {
                if (y + settingH > resultsTop && y < resultsTop + resultsH) {
                    owner.renderSettingRow(nvg, result.setting(), gx, y, gw, settingH, mx, my);
                }
                y += settingH;
                tot += settingH;
            }
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

        owner.searchMaxScrollY = Math.max(0f, tot - resultsH + 16f);
        nvg.popScissor();

        if (owner.searchMaxScrollY > 0f) {
            float ratio = resultsH / (tot + 16f);
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
        float y = owner.contY + MainGUI.TOP_BAR_H + 10f - owner.searchScrollY;
        SettingGroup lastGroup = null;
        for (SearchResult result : searchResults) {
            if (result.group() != lastGroup) {
                if (lastGroup != null) {
                    y += 4f;
                }
                y += MainGUI.HEADER_H;
                y += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                lastGroup = result.group();
            }
            if (!result.setting().isVisible()) {
                continue;
            }
            float settingH = owner.settingH(result.setting(), gw);
            if (my >= y && my <= y + settingH) {
                owner.handleSettingClick(result.setting(), mx, my, gx, y, gw, settingH);
                return;
            }
            y += settingH;
        }
    }

    private void buildResults(String query) {
        searchResults.clear();
        searchGroupToggles.clear();
        for (MainGUIRegistry.ModuleSection section : MainGUIRegistry.MODULE_SECTIONS) {
            addResults(searchResults, query, AetherLang.localize(section.displayName()), section.subtabs());
        }
        addResults(searchResults, query, AetherLang.localize("Colors"), MainGUIRegistry.COLORS_SUBTABS);
        addResults(searchResults, query, AetherLang.localize("Keybinds"), MainGUIRegistry.KEYBINDS_SUBTABS);
        addResults(searchResults, query, AetherLang.localize("Settings"), MainGUIRegistry.SETTINGS_SUBTABS);
    }

    private void addResults(List<SearchResult> out, String query, String tab, List<ModulesTab.SubTab> subtabs) {
        for (ModulesTab.SubTab subtab : subtabs) {
            for (SettingGroup group : subtab.groups()) {
                boolean groupMatched = matchesQuery(group.getName(), group.getRawName(), query);
                boolean addedGroupToggle = false;
                for (Setting setting : group.getSettings()) {
                    if (groupMatched || matchesQuery(setting.getName(), setting.getRawName(), query)) {
                        if (!group.isAlwaysOn() && !addedGroupToggle) {
                            out.add(new SearchResult(tab, subtab.name(), group, searchToggleFor(group)));
                            addedGroupToggle = true;
                        }
                        out.add(new SearchResult(tab, subtab.name(), group, setting));
                    }
                }
                if (groupMatched && !group.isAlwaysOn() && !addedGroupToggle) {
                    out.add(new SearchResult(tab, subtab.name(), group, searchToggleFor(group)));
                }
            }
        }
    }

    private ToggleSetting searchToggleFor(SettingGroup group) {
        return searchGroupToggles.computeIfAbsent(group,
                key -> new ToggleSetting("Enabled", key::isEnabled, key::setEnabled));
    }

    private static boolean matchesQuery(String localized, String raw, String query) {
        return containsIgnoreCase(localized, query) || containsIgnoreCase(raw, query);
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }
}
