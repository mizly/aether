package dev.aether.ui.providers.settings;

import dev.aether.ui.MainGUI;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractSettingsRegistryProvider;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.bootstrap.AetherUiActions;
import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.theme.ThemePreset;
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
        List<String> themes = new ArrayList<>();
        for (ThemePreset preset : ThemePreset.values()) themes.add(preset.label());
        themes.add("Custom");
        groups.add(SettingGroup.alwaysOn("Look", "Colour theme and which menu opens")
                .add(new DropdownSetting("Theme", themes,
                        () -> {
                            ThemePreset current = ThemePreset.current();
                            return current == null ? ThemePreset.values().length : current.ordinal();
                        },
                        index -> {
                            if (index < 0 || index >= ThemePreset.values().length) return;
                            ThemePreset.values()[index].apply();
                            Theme.saveTheme();
                        })
                        .describe("Aether is the original red on black"))
                .add(new ToggleSetting("Traditional GUI", AetherConfig.TRADITIONAL_GUI::get,
                        value -> {
                            AetherConfig.TRADITIONAL_GUI.set(value);
                            AetherConfig.save();
                            // swap straight over to the other menu
                            AetherUiActions.openMainGui();
                        })
                        .describe("Use the classic flat settings window instead of the 3D farm menu"))
                .add(new ToggleSetting("Record Garden Plots", AetherConfig.RECORD_GARDEN_PLOTS::get,
                        value -> {
                            AetherConfig.RECORD_GARDEN_PLOTS.set(value);
                            AetherConfig.save();
                        })
                        .describe("Photograph your plots and copy your Barn for the 3D menu's plot map")));
        groups.add(SettingGroup.alwaysOn(
                        "Theme Options",
                        "Animation speed and interface scale")
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
                        Theme.saveTheme();
                    }
                })));
        return MainGUIRegistry.subTab("Theme Options", "Animation speed and interface scale", groups);
    }
}
