package dev.aether.ui

import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.Setting
import dev.aether.ui.settings.SettingGroup

class MainGUIActions(private val owner: MainGUI, private val context: MainGUIContext) {
    fun resetActiveContentScroll() {
        owner.setActiveScroll(0f, 0f)
        syncActiveBodyScrollState()
        owner.refreshContext()
    }

    fun syncActiveBodyScrollState() {
        if (owner.getActiveMainTab() != 0 || owner.isShowingSearchResults()) return
        if (owner.getActiveModuleSubTab() != null) {
            owner.setModuleDetailScroll(owner.getActiveScrollY(), owner.getActiveTargetScrollY())
        } else {
            owner.setModulesOverviewScroll(owner.getActiveScrollY(), owner.getActiveTargetScrollY())
        }
        owner.refreshContext()
    }

    fun enterModuleDetail(subTab: ModulesTab.SubTab) {
        owner.setModulesOverviewScroll(owner.getActiveScrollY(), owner.getActiveTargetScrollY())
        owner.setActiveModuleSubTab(subTab)
        owner.setActiveCategoryIndex(0)
        owner.setActiveScroll(0f, 0f)
        owner.setModuleDetailScroll(0f, 0f)
        owner.refreshContext()
    }

    fun exitModuleDetail() {
        owner.setModuleDetailScroll(owner.getActiveScrollY(), owner.getActiveTargetScrollY())
        owner.setActiveModuleSubTab(null)
        owner.setActiveCategoryIndex(0)
        owner.setActiveScroll(owner.getModulesOverviewScrollY(), owner.getModulesOverviewTargetScrollY())
        owner.refreshContext()
    }

    fun switchMainTab(mainTab: Int) {
        if (owner.getActiveMainTab() == mainTab) return
        owner.setActiveMainTab(mainTab)
        owner.setActiveSubtabIndex(0)
        owner.setActiveScroll(0f, 0f)
        owner.setProfileScroll(0f, 0f)
        clearSearch()
        owner.setActiveFilterIndex(0)
        owner.setFilterBarInitialized(false)
        owner.setActiveModuleSubTab(null)
        owner.setActiveCategoryIndex(0)
        owner.commitText()
        owner.refreshContext()
    }

    fun clearSearch() {
        owner.setSearchMode(false)
        owner.setSearchQuery("")
        owner.setSearchScroll(0f, 0f)
        owner.clearInlineTextSelection()
        owner.refreshContext()
    }

    fun openSearchResult(mainTab: Int, subTab: ModulesTab.SubTab, group: SettingGroup?, setting: Setting?) {
        owner.commitText()
        owner.commitColor()
        owner.closeOpenDropdown()
        owner.setSearchMode(false)
        owner.setSearchQuery("")
        owner.setSearchScroll(0f, 0f)
        owner.clearInlineTextSelection()
        if (mainTab == 0) openModuleSearchResult(subTab, group ?: return, setting ?: return) else openFlatSearchResult(mainTab, subTab, group ?: return, setting ?: return)
        owner.refreshContext()
    }

    private fun openModuleSearchResult(subTab: ModulesTab.SubTab, group: SettingGroup, setting: Setting) {
        owner.setActiveMainTab(0)
        owner.setActiveFilterIndex(0)
        owner.setActiveSubtabIndex(indexOf(MainGUIRegistry.MODULE_SUBTABS, subTab))
        owner.setActiveModuleSubTab(subTab)
        owner.setActiveScroll(0f, 0f)
        val groupIndex = subTab.groups().indexOf(group)
        if (groupIndex >= 0) {
            owner.setActiveCategoryIndex(groupIndex + 1)
            owner.ensureSearchGroupVisible(group)
        } else owner.setActiveCategoryIndex(0)
        val targetScroll = moduleSettingScrollOffset(subTab, group, setting)
        owner.setActiveScroll(0f, targetScroll)
        owner.setModuleDetailScroll(0f, targetScroll)
    }

    private fun openFlatSearchResult(mainTab: Int, subTab: ModulesTab.SubTab, group: SettingGroup, setting: Setting) {
        owner.setActiveMainTab(mainTab)
        owner.setActiveFilterIndex(0)
        owner.setActiveSubtabIndex(indexOf(owner.flatSubtabsForCurrentFilter(), subTab))
        owner.setActiveModuleSubTab(null)
        owner.setActiveCategoryIndex(0)
        owner.ensureSearchGroupVisible(group)
        owner.setActiveScroll(0f, flatSettingScrollOffset(subTab, group, setting))
    }

    private fun moduleSettingScrollOffset(subTab: ModulesTab.SubTab, targetGroup: SettingGroup?, targetSetting: Setting): Float {
        if (targetGroup == null) return 0f
        var offset = 14f + MainGUI.HEADER_H + MainGUI.HEADER_TO_FIRST_SETTING_GAP
        if (owner.isPetTrackerSettingsGroup(targetGroup)) return Math.max(0f, offset - owner.moduleSettingsHeight() / 3f)
        for (setting in targetGroup.getSettings()) {
            if (!setting.isVisible()) continue
            if (setting === targetSetting) return Math.max(0f, offset - owner.moduleSettingsHeight() / 3f)
            offset += owner.settingHeightFor(setting, owner.moduleSettingsWidth())
        }
        return Math.max(0f, offset - owner.moduleSettingsHeight() / 3f)
    }

    private fun flatSettingScrollOffset(targetSubtab: ModulesTab.SubTab, targetGroup: SettingGroup, targetSetting: Setting): Float {
        var offset = 0f
        val groupW = owner.contentW() - MainGUI.ITEM_PAD * 2f
        for (subtab in owner.flatSubtabsForCurrentFilter()) {
            for (group in subtab.groups()) {
                offset += 14f + MainGUI.FLAT_LABEL_H
                if (!owner.shouldShowChildren(group) || !group.hasSettings()) continue
                offset += MainGUI.HEADER_TO_FIRST_SETTING_GAP
                for (setting in group.getSettings()) {
                    if (!setting.isVisible()) continue
                    if (subtab === targetSubtab && group === targetGroup && setting === targetSetting) {
                        return Math.max(0f, offset - owner.contentScrollHeight() / 3f)
                    }
                    offset += owner.settingHeightFor(setting, groupW)
                }
                if (subtab === targetSubtab && group === targetGroup) return Math.max(0f, offset - owner.contentScrollHeight() / 3f)
                offset += 4f
            }
        }
        return 0f
    }

    private fun indexOf(subtabs: List<ModulesTab.SubTab>, target: ModulesTab.SubTab): Int {
        for (i in subtabs.indices) if (subtabs[i] === target) return i
        return 0
    }

    fun context(): MainGUIContext {
        owner.refreshContext()
        return context
    }
}
