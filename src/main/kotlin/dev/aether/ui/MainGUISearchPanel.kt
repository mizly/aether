package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang
import java.util.ArrayList
import java.util.List

final class MainGUISearchPanel(private val owner: MainGUI) {
    private val searchResults: MutableList<SearchResult> = ArrayList()

    private class SearchResult(val mainTab: Int, val sourceLabel: String, val subtab: ModulesTab.SubTab, val group: SettingGroup?, val setting: Setting?)

    fun render(nvg: NVGRenderer, mx: Float, my: Float) {
        val gx = owner.contX + MainGUI.ITEM_PAD
        val gw = owner.contW - MainGUI.ITEM_PAD * 2f
        val resultsTop = owner.contY + MainGUI.TOP_BAR_H + 1f
        val resultsH = owner.contH - MainGUI.TOP_BAR_H - 1f
        buildResults(owner.searchQuery.lowercase())
        var y = resultsTop + 10f - owner.searchScrollY
        var total = 10f
        nvg.pushScissor(owner.contX, resultsTop, owner.contW, resultsH)
        var lastSubtab: ModulesTab.SubTab? = null
        for (result in searchResults) {
            if (result.subtab != lastSubtab) {
                if (lastSubtab != null) { y += MODULE_GAP; total += MODULE_GAP }
                renderModuleHeader(nvg, result, gx, y, gw)
                y += MainGUI.HEADER_H
                total += MainGUI.HEADER_H
                y += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                total += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                lastSubtab = result.subtab
            }
            if (y + RESULT_H > resultsTop && y < resultsTop + resultsH) {
                renderResult(nvg, result, gx, y, gw, mx, my)
                owner.addClickArea(gx, y, gw, RESULT_H) { owner.openSearchResult(result.mainTab, result.subtab, result.group, result.setting) }
            }
            y += RESULT_H + RESULT_GAP
            total += RESULT_H + RESULT_GAP
        }
        if (searchResults.isEmpty()) {
            nvg.textCentered(Fonts.REGULAR, AetherLang.localize("No results for") + " \"" + owner.searchQuery + "\"", owner.contX, y + 16f, owner.contW, 20f, 11f, Theme.TEXT_DIM)
        }
        owner.searchMaxScrollY = Math.max(0f, total - resultsH + 16f)
        nvg.popScissor()
        if (owner.searchMaxScrollY > 0f) {
            val ratio = resultsH / (total + 16f)
            val thumbH = Math.max(30f, resultsH * ratio)
            val trackT = resultsTop + MainGUI.RADIUS
            val trackH = resultsH - MainGUI.RADIUS * 2f
            owner.sbSrchTrackT = trackT
            owner.sbSrchTrackH = trackH
            owner.sbSrchThumbH = thumbH
            val thumbY = owner.scrollbarThumbY(trackT, trackH, thumbH, owner.searchScrollY, owner.searchMaxScrollY)
            nvg.roundedRect(owner.contX + owner.contW - 6f, thumbY, 4f, thumbH, 2f, Theme.withAlpha(Theme.TEXT_SECONDARY, 0.5f))
        }
    }

    fun handleClick(mx: Float, my: Float) {
        val gx = owner.contX + MainGUI.ITEM_PAD
        val gw = owner.contW - MainGUI.ITEM_PAD * 2f
        if (mx < gx || mx > gx + gw) return
        val resultsTop = owner.contY + MainGUI.TOP_BAR_H + 1f
        var y = resultsTop + 10f - owner.searchScrollY
        var lastSubtab: ModulesTab.SubTab? = null
        for (result in searchResults) {
            if (result.subtab != lastSubtab) {
                if (lastSubtab != null) y += MODULE_GAP
                y += MainGUI.HEADER_H + MainGUI.HEADER_TO_FIRST_SETTING_GAP
                lastSubtab = result.subtab
            }
            if (my >= y && my <= y + RESULT_H) { owner.openSearchResult(result.mainTab, result.subtab, result.group, result.setting); return }
            y += RESULT_H + RESULT_GAP
        }
    }

    private fun renderModuleHeader(nvg: NVGRenderer, result: SearchResult, x: Float, y: Float, w: Float) {
        nvg.roundedRect(x, y, w, MainGUI.HEADER_H, 6f, Theme.BG_SECONDARY)
        nvg.roundedRect(x, y + 10f, 3f, MainGUI.HEADER_H - 20f, 2f, Theme.ACCENT_PRIMARY)
        nvg.text(Fonts.BOLD, result.subtab.name(), x + 12f, y + 9f, 13f, Theme.TEXT_PRIMARY)
        val context = AetherLang.localize(result.sourceLabel) + " > " + result.subtab.name()
        nvg.textRight(Fonts.REGULAR, context, x, y + 9f, w - 8f, 10f, Theme.TEXT_DIM)
    }

    private fun renderResult(nvg: NVGRenderer, result: SearchResult, x: Float, y: Float, w: Float, mx: Float, my: Float) {
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + RESULT_H
        val background = if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.12f) else Theme.BG_SECONDARY
        val border = if (hovered) Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.55f) else Theme.BORDER_DEFAULT
        nvg.roundedRect(x, y, w, RESULT_H, 7f, background)
        nvg.rectOutline(x, y, w, RESULT_H, 7f, 1f, border)
        val title = result.setting?.getName() ?: result.group?.getName() ?: result.subtab.name()
        owner.offerHoverHelp("search-result:" + result.mainTab + ":" + result.subtab.name() + ":" + title,
            AetherLang.localize(title), resultTooltip(result), x, y, w, RESULT_H, mx, my)
        nvg.text(Fonts.BOLD, AetherLang.localize(title), x + 14f, y + 10f, 12.5f, if (hovered) Theme.TEXT_PRIMARY else Theme.TEXT_VALUE)
        val context = if (result.setting == null) AetherLang.localize("Module settings") else result.group?.getName() ?: result.subtab.name()
        nvg.text(Fonts.REGULAR, AetherLang.localize(context), x + 14f, y + 31f, 10.5f, Theme.TEXT_MUTED)
        nvg.textRight(Fonts.REGULAR, "\u2192", x, y + 16f, w - 14f, 18f, if (hovered) Theme.ACCENT_PRIMARY else Theme.TEXT_DIM)
    }

    private fun buildResults(query: String) {
        searchResults.clear()
        for (section in MainGUIRegistry.MODULE_SECTIONS) addResults(searchResults, 0, section.displayName(), section.subtabs().toMutableList(), query)
        addResults(searchResults, 1, AetherLang.localize("Colors"), MainGUIRegistry.COLORS_SUBTABS, query)
        addResults(searchResults, 3, AetherLang.localize("Keybinds"), MainGUIRegistry.KEYBINDS_SUBTABS, query)
        addResults(searchResults, 4, AetherLang.localize("Settings"), MainGUIRegistry.SETTINGS_SUBTABS, query)
    }

    private fun addResults(out: MutableList<SearchResult>, mainTab: Int, sourceLabel: String, subtabs: MutableList<ModulesTab.SubTab>, query: String) {
        for (subtab in subtabs) {
            val subtabMatched = matchesQuery(subtab.name(), null, subtab.description(), query)
            var addedForSubtab = false
            for (group in subtab.groups()) {
                val groupMatched = matchesQuery(group.getName(), group.getRawName(), group.getDescription(), query)
                var addedForGroup = false
                for (setting in group.getSettings()) {
                    if (!setting.isVisible()) continue
                    if (subtabMatched || groupMatched || matchesQuery(setting.getName(), setting.getRawName(), setting.getDescription(), query)) {
                        out.add(SearchResult(mainTab, sourceLabel, subtab, group, setting))
                        addedForGroup = true
                        addedForSubtab = true
                    }
                }
                if (groupMatched && !addedForGroup) { out.add(SearchResult(mainTab, sourceLabel, subtab, group, null)); addedForSubtab = true }
            }
            if (subtabMatched && !addedForSubtab) out.add(SearchResult(mainTab, sourceLabel, subtab, null, null))
        }
    }

    private fun resultTooltip(result: SearchResult): String? = result.setting?.getDescription() ?: result.group?.getDescription() ?: result.subtab.description()
    private fun matchesQuery(localized: String?, raw: String?, tooltip: String?, query: String): Boolean = containsIgnoreCase(localized, query) || containsIgnoreCase(raw, query) || containsIgnoreCase(tooltip, query)
    private fun containsIgnoreCase(value: String?, query: String): Boolean = value != null && value.lowercase().contains(query)

    companion object {
        private const val RESULT_H = 54f
        private const val RESULT_GAP = 6f
        private const val MODULE_GAP = 10f
    }
}
