package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;

import java.util.List;

final class MainGUIActions {
    private final MainGUI owner;
    private final MainGUIContext context;

    MainGUIActions(MainGUI owner, MainGUIContext context) {
        this.owner = owner;
        this.context = context;
    }

    void resetActiveContentScroll() {
        owner.setActiveScroll(0f, 0f);
        syncActiveBodyScrollState();
        owner.refreshContext();
    }

    void syncActiveBodyScrollState() {
        if (owner.getActiveMainTab() != 0 || owner.isShowingSearchResults()) {
            return;
        }
        if (owner.getActiveModuleSubTab() != null) {
            owner.setModuleDetailScroll(owner.getActiveScrollY(), owner.getActiveTargetScrollY());
        } else {
            owner.setModulesOverviewScroll(owner.getActiveScrollY(), owner.getActiveTargetScrollY());
        }
        owner.refreshContext();
    }

    void enterModuleDetail(ModulesTab.SubTab subTab) {
        owner.setModulesOverviewScroll(owner.getActiveScrollY(), owner.getActiveTargetScrollY());
        owner.setActiveModuleSubTab(subTab);
        owner.setActiveCategoryIndex(0);
        owner.setActiveScroll(0f, 0f);
        owner.setModuleDetailScroll(0f, 0f);
        owner.refreshContext();
    }

    void exitModuleDetail() {
        owner.setModuleDetailScroll(owner.getActiveScrollY(), owner.getActiveTargetScrollY());
        owner.setActiveModuleSubTab(null);
        owner.setActiveCategoryIndex(0);
        owner.setActiveScroll(owner.getModulesOverviewScrollY(), owner.getModulesOverviewTargetScrollY());
        owner.refreshContext();
    }

    void switchMainTab(int mainTab) {
        if (owner.getActiveMainTab() == mainTab) {
            return;
        }
        owner.setActiveMainTab(mainTab);
        owner.setActiveSubtabIndex(0);
        owner.setActiveScroll(0f, 0f);
        owner.setProfileScroll(0f, 0f);
        clearSearch();
        owner.setActiveFilterIndex(0);
        owner.setFilterBarInitialized(false);
        owner.setActiveModuleSubTab(null);
        owner.setActiveCategoryIndex(0);
        owner.commitText();
        owner.refreshContext();
    }

    void clearSearch() {
        owner.setSearchMode(false);
        owner.setSearchQuery("");
        owner.setSearchScroll(0f, 0f);
        owner.clearInlineTextSelection();
        owner.refreshContext();
    }

    void openSearchResult(int mainTab, ModulesTab.SubTab subTab, SettingGroup group, Setting setting) {
        owner.commitText();
        owner.commitColor();
        owner.closeOpenDropdown();
        owner.setSearchMode(false);
        owner.setSearchQuery("");
        owner.setSearchScroll(0f, 0f);
        owner.clearInlineTextSelection();

        if (mainTab == 0) {
            openModuleSearchResult(subTab, group, setting);
        } else {
            openFlatSearchResult(mainTab, subTab, group, setting);
        }
        owner.refreshContext();
    }

    private void openModuleSearchResult(ModulesTab.SubTab subTab, SettingGroup group, Setting setting) {
        owner.setActiveMainTab(0);
        owner.setActiveFilterIndex(0);
        owner.setActiveSubtabIndex(indexOf(MainGUIRegistry.MODULE_SUBTABS, subTab));
        owner.setActiveModuleSubTab(subTab);
        owner.setActiveScroll(0f, 0f);

        int groupIndex = subTab.groups().indexOf(group);
        if (groupIndex >= 0) {
            owner.setActiveCategoryIndex(groupIndex + 1);
            owner.ensureSearchGroupVisible(group);
        } else {
            owner.setActiveCategoryIndex(0);
        }
        float targetScroll = moduleSettingScrollOffset(subTab, group, setting);
        owner.setActiveScroll(0f, targetScroll);
        owner.setModuleDetailScroll(0f, targetScroll);
    }

    private void openFlatSearchResult(int mainTab, ModulesTab.SubTab subTab, SettingGroup group, Setting setting) {
        owner.setActiveMainTab(mainTab);
        owner.setActiveFilterIndex(0);
        owner.setActiveSubtabIndex(indexOf(owner.flatSubtabsForCurrentFilter(), subTab));
        owner.setActiveModuleSubTab(null);
        owner.setActiveCategoryIndex(0);
        owner.ensureSearchGroupVisible(group);
        owner.setActiveScroll(0f, flatSettingScrollOffset(subTab, group, setting));
    }

    private float moduleSettingScrollOffset(ModulesTab.SubTab subTab, SettingGroup targetGroup, Setting targetSetting) {
        if (targetGroup == null) {
            return 0f;
        }

        float offset = 14f + MainGUI.HEADER_H + MainGUI.HEADER_TO_FIRST_SETTING_GAP;
        if (owner.isPetTrackerSettingsGroup(targetGroup)) {
            return Math.max(0f, offset - owner.moduleSettingsHeight() / 3f);
        }
        for (Setting setting : targetGroup.getSettings()) {
            if (!setting.isVisible()) {
                continue;
            }
            if (setting == targetSetting) {
                return Math.max(0f, offset - owner.moduleSettingsHeight() / 3f);
            }
            offset += owner.settingHeightFor(setting, owner.moduleSettingsWidth());
        }
        return Math.max(0f, offset - owner.moduleSettingsHeight() / 3f);
    }

    private float flatSettingScrollOffset(ModulesTab.SubTab targetSubtab, SettingGroup targetGroup, Setting targetSetting) {
        float offset = 0f;
        float groupW = owner.contentW() - MainGUI.ITEM_PAD * 2f;
        for (ModulesTab.SubTab subtab : owner.flatSubtabsForCurrentFilter()) {
            for (SettingGroup group : subtab.groups()) {
                offset += 14f + MainGUI.FLAT_LABEL_H;
                if (!owner.shouldShowChildren(group) || !group.hasSettings()) {
                    continue;
                }

                offset += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                for (Setting setting : group.getSettings()) {
                    if (!setting.isVisible()) {
                        continue;
                    }
                    if (subtab == targetSubtab && group == targetGroup && setting == targetSetting) {
                        return Math.max(0f, offset - owner.contentScrollHeight() / 3f);
                    }
                    offset += owner.settingHeightFor(setting, groupW);
                }
                if (subtab == targetSubtab && group == targetGroup) {
                    return Math.max(0f, offset - owner.contentScrollHeight() / 3f);
                }
                offset += 4f;
            }
        }
        return 0f;
    }

    private static int indexOf(List<ModulesTab.SubTab> subtabs, ModulesTab.SubTab target) {
        for (int i = 0; i < subtabs.size(); i++) {
            if (subtabs.get(i) == target) {
                return i;
            }
        }
        return 0;
    }

    MainGUIContext context() {
        owner.refreshContext();
        return context;
    }
}
