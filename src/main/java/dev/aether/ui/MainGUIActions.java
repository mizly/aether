package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

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
        owner.clearInlineTextSelection();
        owner.refreshContext();
    }

    MainGUIContext context() {
        owner.refreshContext();
        return context;
    }
}
