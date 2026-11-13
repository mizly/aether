package dev.aether.ui

import dev.aether.ui.settings.ModulesTab

class MainGUIContentViewState(
    private val activeMain: Int,
    private val activeSubtab: Int,
    private val activeFilter: Int,
    private val activeSubTab: ModulesTab.SubTab?,
    private val activeCategoryIdx: Int,
    private val searchQuery: String?
) {
    fun activeMain(): Int = activeMain
    fun activeSubtab(): Int = activeSubtab
    fun activeFilter(): Int = activeFilter
    fun activeSubTab(): ModulesTab.SubTab? = activeSubTab
    fun activeCategoryIdx(): Int = activeCategoryIdx
    fun searchQuery(): String? = searchQuery

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MainGUIContentViewState) return false
        return activeMain == other.activeMain && activeSubtab == other.activeSubtab && activeFilter == other.activeFilter &&
            activeSubTab == other.activeSubTab && activeCategoryIdx == other.activeCategoryIdx && searchQuery == other.searchQuery
    }

    override fun hashCode(): Int {
        var result = activeMain
        result = 31 * result + activeSubtab
        result = 31 * result + activeFilter
        result = 31 * result + (activeSubTab?.hashCode() ?: 0)
        result = 31 * result + activeCategoryIdx
        result = 31 * result + (searchQuery?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "MainGUIContentViewState[activeMain=$activeMain, activeSubtab=$activeSubtab, activeFilter=$activeFilter, activeSubTab=$activeSubTab, activeCategoryIdx=$activeCategoryIdx, searchQuery=$searchQuery]"
}
