package dev.aether.ui;

import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.util.AetherLanguageManager;

import java.util.List;

public final class LanguageSettingsRegistryProvider extends AbstractSettingsRegistryProvider {
    public LanguageSettingsRegistryProvider() {
        super(1);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<String> languageOptions = AetherLanguageManager.getAvailableLanguageCodes();

        SettingGroup group = SettingGroup.alwaysOn(
                "Language",
                "Switch the Aether UI language and refetch language packs");
        group.add(new DropdownSetting("Language",
                languageOptions,
                () -> AetherLanguageManager.getSelectedLanguageIndex(languageOptions),
                index -> {
                    if (index < 0 || index >= languageOptions.size()) {
                        return;
                    }
                    AetherLanguageManager.selectLanguage(languageOptions.get(index));
                }));
        group.add(new ActionSetting("Refresh Language Packs",
                () -> AetherLanguageManager.refreshFromRemoteAsync(true)));

        return MainGUIRegistry.subTab("Language", "Switch the Aether UI language", List.of(group));
    }
}
