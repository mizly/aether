package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.util.Fonts
import dev.aether.ui.theme.Theme
import dev.aether.util.AetherLang
import java.util.ArrayList

final class MainGUIModuleDetailRenderer(private val owner: MainGUI) {
    private val categorySelection = SelectionAnimation()
    private var categorySubTab: ModulesTab.SubTab? = null

    fun renderTopBar(nvg: NVGRenderer, mx: Float, my: Float) {
        val context = owner.context()
        val activeSubTab = owner.getActiveModuleSubTab() ?: return
        val moduleName = owner.moduleCardNameFor(activeSubTab)
        val totalHeight = MainGUI.TOP_BAR_H
        val backLabel = "\u2190 " + AetherLang.localize("Back")
        val backTextWidth = nvg.textWidth(Fonts.REGULAR, backLabel, 11f)
        val backX = context.layout.contX + 20f
        val textCenterY = context.layout.contY + (totalHeight - 11f) / 2f
        val backHovered = mx >= context.layout.contX + 14f && mx < context.layout.contX + 14f + backTextWidth + 16f && my >= context.layout.contY && my < context.layout.contY + totalHeight
        val buttonBg = if (backHovered) 0.08f else 0.04f
        nvg.roundedRect(context.layout.contX + 14f, context.layout.contY + (totalHeight - 26f) / 2f, backTextWidth + 16f, 26f, 5f, Theme.withAlpha(Theme.TEXT_PRIMARY, buttonBg))
        nvg.text(Fonts.REGULAR, backLabel, backX + 2f, textCenterY, 11f, if (backHovered) Theme.TEXT_PRIMARY else Theme.TEXT_VALUE)
        owner.addClickArea(context.layout.contX + 14f, context.layout.contY, backTextWidth + 16f, totalHeight) { owner.exitModuleDetailView() }
        val sep1X = context.layout.contX + 20f + backTextWidth + 14f
        nvg.rect(sep1X, context.layout.contY + 15f, 1f, totalHeight - 30f, Theme.SEPARATOR)
        val nameX = sep1X + 12f
        val nameWidth = nvg.textWidth(Fonts.BOLD, moduleName, 13f)
        nvg.text(Fonts.BOLD, moduleName, nameX, context.layout.contY + (totalHeight - 13f) / 2f, 13f, Theme.TEXT_PRIMARY)
        val sep2X = nameX + nameWidth + 12f
        nvg.rect(sep2X, context.layout.contY + 15f, 1f, totalHeight - 30f, Theme.SEPARATOR)
        val descY = context.layout.contY + (MainGUI.TOP_BAR_H - 12f) / 2f
        nvg.text(Fonts.REGULAR, AetherLang.localize("Module settings"), sep2X + 12f, descY, 12f, Theme.TEXT_MUTED)
        nvg.rect(context.layout.contX, context.layout.contY + totalHeight, context.layout.contW, 1f, Theme.SEPARATOR)
    }

    fun renderCategoryPanel(nvg: NVGRenderer, mx: Float, my: Float, panelTop: Float, panelH: Float) {
        var context = owner.context()
        val activeSubTab = owner.getActiveModuleSubTab() ?: return
        val categoryX = context.layout.contX
        val categoryW = MainGUI.MODULE_CAT_W
        val subtabEnabled = owner.isSubTabEnabledFor(activeSubTab)
        nvg.rect(categoryX + categoryW, panelTop, 1f, panelH, Theme.SEPARATOR)
        nvg.text(Fonts.BOLD, AetherLang.localize("Categories").uppercase(), categoryX + 16f, panelTop + 18f, 9f, Theme.withAlpha(Theme.TEXT_MUTED, 185))
        val itemHeight = 36f
        val itemsStart = panelTop + 44f
        val groups = activeSubTab.groups()
        val skipAll = groups.size == 1
        if (skipAll && owner.getActiveCategoryIndex() == 0) { owner.setActiveCategoryIndex(1); context = owner.context() }
        val totalItems = if (skipAll) groups.size else groups.size + 1
        val selectedRow = if (skipAll) Math.max(0, owner.getActiveCategoryIndex() - 1) else owner.getActiveCategoryIndex()
        val nowNanos = System.nanoTime()
        if (categorySubTab != activeSubTab) { categorySelection.reset(selectedRow * itemHeight, nowNanos); categorySubTab = activeSubTab }
        val highlightY = categorySelection.update(selectedRow * itemHeight, Theme.ANIM_TIME_MS, nowNanos)
        nvg.roundedRect(categoryX + 8f, itemsStart + highlightY + 3f, categoryW - 16f, 30f, 6f, Theme.withAlpha(Theme.ACCENT_PRIMARY, if (subtabEnabled) 0.12f else 0.06f))
        for (i in 0 until totalItems) {
            val isAll = !skipAll && i == 0
            val groupIndex = if (skipAll) i else i - 1
            val label = if (isAll) AetherLang.localize("All") else AetherLang.localize(groups[groupIndex].getName())
            val animKey: Any? = if (isAll) null else groups[groupIndex]
            val itemY = itemsStart + i * itemHeight
            val inItem = mx >= categoryX + 6f && mx < categoryX + categoryW - 6f && my >= itemY && my < itemY + itemHeight
            val selected = owner.getActiveCategoryIndex() == (if (skipAll) i + 1 else i)
            var hover = owner.moduleCategoryHoverProgress(animKey)
            hover += ((if (!selected && inItem) 1f else 0f) - hover) * Math.min(1f, Theme.animationFactor() * 7f)
            owner.setModuleCategoryHoverProgress(animKey, hover)
            if (selected) nvg.text(Fonts.BOLD, label, categoryX + 18f, itemY + 12f, 11f, if (subtabEnabled) Theme.TEXT_PRIMARY else Theme.withAlpha(Theme.TEXT_DIM, 190)) else {
                if (hover > 0.01f) nvg.roundedRect(categoryX + 8f, itemY + 3f, categoryW - 16f, 30f, 6f, Theme.withAlpha(0xFFFFFFFF.toInt(), hover * 0.05f))
                val textColor = if (subtabEnabled) { if (hover > 0.01f) Theme.blend(Theme.TEXT_MUTED, Theme.TEXT_VALUE, hover) else Theme.TEXT_MUTED } else Theme.withAlpha(Theme.TEXT_DIM, 165)
                nvg.text(Fonts.REGULAR, label, categoryX + 18f, itemY + 12f, 11f, textColor)
            }
            if (subtabEnabled) { val categoryIndex = if (skipAll) i + 1 else i; owner.addClickArea(categoryX + 6f, itemY, categoryW - 12f, itemHeight) { owner.selectModuleCategory(categoryIndex) } }
        }
    }

    fun renderSettingsPanel(nvg: NVGRenderer, mx: Float, my: Float, panelTop: Float, panelH: Float) {
        var context = owner.context()
        val activeSubTab = owner.getActiveModuleSubTab() ?: return
        val rightX = context.layout.contX + MainGUI.MODULE_CAT_W + 1f
        val rightW = context.layout.contW - MainGUI.MODULE_CAT_W - 1f
        val subtabEnabled = owner.isSubTabEnabledFor(activeSubTab)
        val moduleName = owner.moduleCardNameFor(activeSubTab)
        val moduleDescription = AetherLang.localize(activeSubTab.description())
        val groupX = rightX + MainGUI.ITEM_PAD
        val groupW = rightW - MainGUI.ITEM_PAD * 2f
        nvg.text(Fonts.BOLD, moduleName, rightX + 20f, panelTop + 16f, 15f, Theme.TEXT_PRIMARY)
        nvg.text(Fonts.REGULAR, moduleDescription, rightX + 20f, panelTop + 37f, 10f, Theme.TEXT_MUTED)
        if (activeSubTab.hasToggle()) { val pillX = groupX + groupW - 52f; val pillY = panelTop + (MainGUI.MOD_HEADER_H - MainGUI.PILL_H) / 2f; owner.renderPillControl(nvg, pillX, pillY, subtabEnabled, activeSubTab); owner.addClickArea(pillX - 6f, pillY - 6f, 48f, MainGUI.PILL_H + 12f) { activeSubTab.toggle() } }
        nvg.rect(rightX, panelTop + MainGUI.MOD_HEADER_H, rightW, 1f, Theme.SEPARATOR)
        val maxCategoryIndex = activeSubTab.groups().size
        val showAll = owner.getActiveCategoryIndex() == 0
        val renderGroups: List<SettingGroup> = if (showAll) activeSubTab.groups() else listOf(activeSubTab.groups()[owner.getActiveCategoryIndex() - 1])
        val settingsTop = panelTop + MainGUI.MOD_HEADER_H + 1f
        val settingsH = panelH - MainGUI.MOD_HEADER_H - 1f
        val clipped = owner.pushContentLocalScissor(nvg, rightX, settingsTop, rightW, settingsH)
        context = owner.context()
        var y = settingsTop - context.scroll.scrollY
        var total = 0f
        for (group in renderGroups) {
            y += 14f; total += 14f
            if (y + MainGUI.HEADER_H > settingsTop && y < settingsTop + settingsH) owner.renderGroupHeaderPanel(nvg, group, groupX, y, groupW, mx, my, subtabEnabled)
            y += MainGUI.HEADER_H; total += MainGUI.HEADER_H
            if (owner.shouldShowChildren(group) && group.hasSettings()) {
                y += MainGUI.HEADER_TO_FIRST_SETTING_GAP; total += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                if (owner.isPetTrackerSettingsGroup(group)) {
                    val groupHeight = owner.petTrackerGroupHeightValue()
                    if (y + groupHeight > settingsTop && y < settingsTop + settingsH) { owner.renderPetTrackerGroupPanel(nvg, group, groupX, y, groupW, mx, my); if (!subtabEnabled) { val cardH = groupHeight - 5f; nvg.roundedRect(groupX, y, groupW, cardH, 7f, Theme.withAlpha(Theme.PANEL_BG, 0.42f)) } }
                    y += groupHeight; total += groupHeight
                } else for (setting in group.getSettings()) {
                    if (!setting.isVisible()) continue
                    val settingHeight = owner.settingHeightFor(setting, groupW)
                    if (y + settingHeight > settingsTop && y < settingsTop + settingsH) { owner.renderSettingRow(nvg, setting, groupX, y, groupW, settingHeight, mx, my); if (!subtabEnabled) { val cardH = settingHeight - 5f; nvg.roundedRect(groupX, y, groupW, cardH, 7f, Theme.withAlpha(Theme.PANEL_BG, 0.42f)) } }
                    y += settingHeight; total += settingHeight
                }
                y += 4f; total += 4f
            }
        }
        owner.popContentLocalScissor(nvg, clipped)
        owner.renderMainScrollbar(nvg, total, settingsTop, settingsH, rightX + rightW - 6f)
    }
}
