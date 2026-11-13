package dev.aether.ui

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingGroup

class MainGUIFlatPanelRenderer(private val owner: MainGUI) {
    fun render(nvg: NVGRenderer, mx: Float, my: Float) {
        val context = owner.context()
        val scrollTop = owner.contentScrollTop()
        val scrollH = owner.contentScrollHeight()
        val groupX = context.layout.contX + MainGUI.ITEM_PAD
        val groupW = context.layout.contW - MainGUI.ITEM_PAD * 2f
        val clipped = owner.pushContentLocalScissor(nvg, context.layout.contX, scrollTop, context.layout.contW, scrollH)
        var y = scrollTop - context.scroll.scrollY
        var total = 0f
        for (subTab in owner.flatSubtabsForCurrentFilter()) {
            for (group in subTab.groups()) {
                y += 14f
                total += 14f
                owner.renderFlatGroupLabelPanel(nvg, group, groupX, y, groupW, mx, my)
                y += MainGUI.FLAT_LABEL_H
                total += MainGUI.FLAT_LABEL_H
                if (owner.shouldShowChildren(group) && group.hasSettings()) {
                    y += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                    total += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                    if (owner.isPetTrackerSettingsGroup(group)) {
                        val groupHeight = owner.petTrackerGroupHeightValue()
                        if (y + groupHeight > scrollTop && y < scrollTop + scrollH) owner.renderPetTrackerGroupPanel(nvg, group, groupX, y, groupW, mx, my)
                        y += groupHeight
                        total += groupHeight
                    } else {
                        for (setting: Setting in group.getSettings()) {
                            if (!setting.isVisible()) continue
                            val settingHeight = owner.settingHeightFor(setting, groupW)
                            if (y + settingHeight > scrollTop && y < scrollTop + scrollH) owner.renderSettingRow(nvg, setting, groupX, y, groupW, settingHeight, mx, my)
                            y += settingHeight
                            total += settingHeight
                        }
                    }
                    y += 4f
                    total += 4f
                }
            }
        }
        owner.popContentLocalScissor(nvg, clipped)
        owner.renderMainScrollbar(nvg, total, scrollTop, scrollH, context.layout.contX + context.layout.contW - 6f)
    }
}
