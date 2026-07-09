package dev.aether.ui;

import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

public final class MenuColorsRegistryProvider extends AbstractColorsRegistryProvider {
    public MenuColorsRegistryProvider() {
        super(1);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();
        SettingGroup menuColors = SettingGroup.alwaysOn(
                "Menu Colors",
                "Customize config menu colors");
        for (Theme.ThemeEntry entry : Theme.ENTRIES) {
            menuColors.add(new ColorSetting(entry.label, entry.getter,
                    value -> {
                        entry.setter.accept(value);
                        Theme.saveTheme();
                    }));
        }
        groups.add(menuColors);
        return MainGUIRegistry.subTab("Menu Colors", "Customize config menu colors", groups);
    }
}
