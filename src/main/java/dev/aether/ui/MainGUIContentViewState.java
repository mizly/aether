package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

record MainGUIContentViewState(int activeMain, int activeSubtab, int activeFilter,
                               ModulesTab.SubTab activeSubTab, int activeCategoryIdx,
                               String searchQuery) {
}
