package dev.aether.ui;

import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.theme.Theme;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class ThemeOptionsSettingsRegistryProvider extends AbstractSettingsRegistryProvider {
    public ThemeOptionsSettingsRegistryProvider() {
        super(0);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();
        groups.add(SettingGroup.alwaysOn(
                        "Theme Options",
                        "Animation speed and theme presets")
                .add(new SliderSetting("Animation Time", Theme.ANIM_TIME_MIN_MS, Theme.ANIM_TIME_MAX_MS,
                        () -> Theme.ANIM_TIME_MS,
                        value -> {
                            Theme.ANIM_TIME_MS = value;
                            Theme.saveTheme();
                        })
                        .withDecimals(0).withSuffix("ms"))
                .add(new ActionSetting("Export Theme (Copy)", () -> {
                    String json = Theme.exportJson();
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                }))
                .add(new ActionSetting("Import Theme (Paste)", () -> {
                    String json = Minecraft.getInstance().keyboardHandler.getClipboard();
                    if (json != null && !json.isBlank()) {
                        Theme.importJson(json);
                        Theme.saveTheme();
                    }
                })));
        return MainGUIRegistry.subTab("Theme Options", "Animation speed and theme presets", groups);
    }
}
