package dev.aether.ui.providers.settings

import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractSettingsRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.util.AetherLanguageManager

class LanguageSettingsRegistryProvider : AbstractSettingsRegistryProvider(1) {
    override fun createSubTab(): ModulesTab.SubTab {
        val languageOptions: java.util.ArrayList<String> = java.util.ArrayList<String>(AetherLanguageManager.getAvailableLanguageCodes())
        val group = SettingGroup.alwaysOn("Language", "Switch the Aether UI language and refetch language packs")
        group.add(DropdownSetting("Language", languageOptions,
            { AetherLanguageManager.getSelectedLanguageIndex(languageOptions as java.util.List<String>) }, { index ->
                if (index < 0 || index >= languageOptions.size) return@DropdownSetting
                AetherLanguageManager.selectLanguage(languageOptions[index])
            }))
        group.add(ActionSetting("Refresh Language Packs") { AetherLanguageManager.refreshFromRemoteAsync(true) })
        return MainGUIRegistry.subTab("Language", "Switch the Aether UI language", listOf(group))
    }
}
