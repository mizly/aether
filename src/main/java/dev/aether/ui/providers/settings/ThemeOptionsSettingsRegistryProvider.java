package dev.aether.ui.providers.settings;

import dev.aether.ui.MainGUI;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractSettingsRegistryProvider;
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
                .add(new SliderSetting("UI Scale", Theme.UI_SCALE_MIN, Theme.UI_SCALE_MAX,
                        () -> Theme.UI_SCALE,
                        value -> {
                            // Only update the persisted value; MainGUI applies it to uiScale each
                            // frame (paused during the drag) so the slider can't feed back to max.
                            Theme.UI_SCALE = value;
                            Theme.saveTheme();
                        })
                        .withDecimals(2).withSuffix("x"))
                .add(new SliderSetting("Text Scale", Theme.TEXT_SCALE_MIN, Theme.TEXT_SCALE_MAX,
                        () -> Theme.TEXT_SCALE,
                        value -> {
                            Theme.TEXT_SCALE = value;
                            MainGUI.uiTextScale = value;
                            Theme.saveTheme();
                        })
                        .withDecimals(2).withSuffix("x"))
                .add(new ActionSetting("Reset Theme", () -> {
                    Theme.resetToDefaults();
                    MainGUI.uiScale = Theme.UI_SCALE;
                    MainGUI.uiTextScale = Theme.TEXT_SCALE;
                    Theme.saveTheme();
                }))
                .add(new ActionSetting("Export Theme (Copy)", () -> {
                    String json = Theme.exportJson();
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                }))
                .add(new ActionSetting("Import Theme (Paste)", () -> {
                    String json = Minecraft.getInstance().keyboardHandler.getClipboard();
                    if (json != null && !json.isBlank()) {
                        Theme.importJson(json);
                        MainGUI.uiScale = Theme.UI_SCALE; // apply imported scale to the live panel
                        MainGUI.uiTextScale = Theme.TEXT_SCALE; // apply imported text scale immediately
                        Theme.saveTheme();
                    }
                })));
        return MainGUIRegistry.subTab("Theme Options", "Animation speed and theme presets", groups);
    }
}
