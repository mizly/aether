package dev.aether.ui.providers.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.hud.HudEditScreen;
import dev.aether.hud.ScoreboardHudElement;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class HudVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public HudVisualsRegistryProvider() {
        super(0);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();

        groups.add(SettingGroup.alwaysOn(
                        "HUD Settings",
                        "Configure overlay style and layout")
                .add(new MultiDropdownSetting("HUD Themes",
                        List.of("Main", "Watermark"),
                        () -> AetherConfig.HUD_THEME.get(),
                        i -> {
                            AetherConfig.HUD_THEME.set(i);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Frosted Panel Background",
                        () -> AetherConfig.HUD_PANEL_FROSTED.get(),
                        v -> {
                            AetherConfig.HUD_PANEL_FROSTED.set(v);
                            AetherConfig.save();
                        }))
                .add(new ActionSetting("Edit HUD Layout", () -> Minecraft.getInstance().setScreen(new HudEditScreen()))));

        groups.add(SettingGroup.alwaysOn(
                        "HUD Visibility",
                        "Controls when HUD overlays are shown")
                .add(new ToggleSetting("Only Show While Macro Running",
                        () -> AetherConfig.HUD_ONLY_WHILE_MACRO_RUNNING.get(),
                        v -> {
                            AetherConfig.HUD_ONLY_WHILE_MACRO_RUNNING.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Only Show HUDs In Garden",
                        () -> AetherConfig.GUI_ONLY_IN_GARDEN.get(),
                        v -> {
                            AetherConfig.GUI_ONLY_IN_GARDEN.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Show Task HUDs Outside Garden",
                        () -> AetherConfig.SHOW_HUD_OUTSIDE_GARDEN.get(),
                        v -> {
                            AetherConfig.SHOW_HUD_OUTSIDE_GARDEN.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> !AetherConfig.GUI_ONLY_IN_GARDEN.get())));

        groups.add(SettingGroup.of(
                        "Streamer Mode",
                        "Hides Aether chat, notifications, overlays, HUDs, and world visuals",
                        StreamerModeManager::isEnabled,
                        StreamerModeManager::setEnabled));

        groups.add(SettingGroup.alwaysOn(
                        "Main Status HUD",
                        "Settings for the all-in-one main status card")
                .add(new ToggleSetting("Gradient",
                        () -> AetherConfig.MAIN_STATUS_GRADIENT.get(),
                        v -> { AetherConfig.MAIN_STATUS_GRADIENT.set(v); AetherConfig.save(); }))
                .add(new ColorSetting("Gradient Left",
                        () -> AetherConfig.MAIN_STATUS_GRADIENT_LEFT.get(),
                        v -> { AetherConfig.MAIN_STATUS_GRADIENT_LEFT.set(v); AetherConfig.save(); })
                        .visibleWhen(() -> AetherConfig.MAIN_STATUS_GRADIENT.get()))
                .add(new ColorSetting("Gradient Right",
                        () -> AetherConfig.MAIN_STATUS_GRADIENT_RIGHT.get(),
                        v -> { AetherConfig.MAIN_STATUS_GRADIENT_RIGHT.set(v); AetherConfig.save(); })
                        .visibleWhen(() -> AetherConfig.MAIN_STATUS_GRADIENT.get())));

        groups.add(SettingGroup.alwaysOn(
                        "Debug HUD",
                        "Developer overlays for macro state and task tracking")
                .add(new ToggleSetting("Macro HUD",
                        () -> AetherConfig.SHOW_HUD.get(),
                        v -> {
                            AetherConfig.SHOW_HUD.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Intermediaries HUD",
                        () -> AetherConfig.SHOW_INTERMEDIARIES_HUD.get(),
                        v -> {
                            AetherConfig.SHOW_INTERMEDIARIES_HUD.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Mid Farming HUD",
                        () -> AetherConfig.SHOW_MID_FARMING_HUD.get(),
                        v -> {
                            AetherConfig.SHOW_MID_FARMING_HUD.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Failsafes HUD",
                        () -> AetherConfig.SHOW_FAILSAFES_HUD.get(),
                        v -> {
                            AetherConfig.SHOW_FAILSAFES_HUD.set(v);
                            AetherConfig.save();
                        })));

        groups.add(SettingGroup.of(
                        "Custom Scoreboard",
                        "Style the scoreboard to match your HUD",
                        () -> AetherConfig.CUSTOM_SCOREBOARD.get(),
                        ScoreboardHudElement::setEnabled)
                .add(new TextSetting("Scoreboard Title Text", "Leave empty to keep the server title",
                        AetherConfig.SCOREBOARD_TITLE_TEXT::get,
                        v -> { AetherConfig.SCOREBOARD_TITLE_TEXT.set(v); AetherConfig.save(); }))
                .add(new TextSetting("Scoreboard Server Line Text", "Leave empty to keep the server address",
                        AetherConfig.SCOREBOARD_SERVER_TEXT::get,
                        v -> { AetherConfig.SCOREBOARD_SERVER_TEXT.set(v); AetherConfig.save(); }))
                .add(new ActionSetting("Reset Scoreboard Layout", ScoreboardHudElement::resetLayout)));

        groups.add(SettingGroup.alwaysOn(
                        "Inventory HUD",
                        "Configure the inventory preview overlay")
                .add(new ToggleSetting("Inventory HUD",
                        () -> AetherConfig.SHOW_INVENTORY_HUD.get(),
                        v -> {
                            AetherConfig.SHOW_INVENTORY_HUD.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Show Player Model",
                        () -> AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.get(),
                        v -> {
                            AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Show Armor",
                        () -> AetherConfig.INVENTORY_HUD_SHOW_ARMOR.get(),
                        v -> {
                            AetherConfig.INVENTORY_HUD_SHOW_ARMOR.set(v);
                            AetherConfig.save();
                        })));

        groups.add(SettingGroup.of("Pest Target HUD", "Pest icons, health and hunting progress below the crosshair",
                AetherConfig.SHOW_PEST_TARGET_HUD::get,
                value -> { AetherConfig.SHOW_PEST_TARGET_HUD.set(value); AetherConfig.save(); })
                .add(new ActionSetting("Reset Target HUD Position", dev.aether.hud.PestTargetHudElement::resetLayout)));

        SettingGroup watermark = SettingGroup.alwaysOn(
                "Watermark",
                "Displays mod name, username, FPS, ping and time");
        watermark.add(new ToggleSetting("Show Macro Status",
                () -> AetherConfig.WATERMARK_SHOW_MACRO_STATUS.get(),
                v -> { AetherConfig.WATERMARK_SHOW_MACRO_STATUS.set(v); AetherConfig.save(); }));
        watermark.add(new ToggleSetting("Show Logo",
                () -> AetherConfig.WATERMARK_SHOW_LOGO.get(),
                v -> {
                    if (!v && !AetherConfig.WATERMARK_SHOW_NAME.get()) return;
                    AetherConfig.WATERMARK_SHOW_LOGO.set(v);
                    AetherConfig.save();
                }));
        watermark.add(new ToggleSetting("Show Name",
                () -> AetherConfig.WATERMARK_SHOW_NAME.get(),
                v -> {
                    if (!v && !AetherConfig.WATERMARK_SHOW_LOGO.get()) return;
                    AetherConfig.WATERMARK_SHOW_NAME.set(v);
                    AetherConfig.save();
                }));
        watermark.add(new TextSetting("Custom Username", "leave empty to use your real name",
                () -> AetherConfig.WATERMARK_CUSTOM_USERNAME.get(),
                v -> {
                    AetherConfig.WATERMARK_CUSTOM_USERNAME.set(v);
                    AetherConfig.save();
                }));
        watermark.add(new ToggleSetting("Show Username",
                () -> AetherConfig.WATERMARK_SHOW_USERNAME.get(),
                v -> { AetherConfig.WATERMARK_SHOW_USERNAME.set(v); AetherConfig.save(); }));
        watermark.add(new ToggleSetting("Show FPS",
                () -> AetherConfig.WATERMARK_SHOW_FPS.get(),
                v -> { AetherConfig.WATERMARK_SHOW_FPS.set(v); AetherConfig.save(); }));
        watermark.add(new ToggleSetting("Show Ping",
                () -> AetherConfig.WATERMARK_SHOW_PING.get(),
                v -> { AetherConfig.WATERMARK_SHOW_PING.set(v); AetherConfig.save(); }));
        watermark.add(new ToggleSetting("Show Time",
                () -> AetherConfig.WATERMARK_SHOW_TIME.get(),
                v -> { AetherConfig.WATERMARK_SHOW_TIME.set(v); AetherConfig.save(); }));
        watermark.add(new ToggleSetting("Gradient",
                () -> AetherConfig.WATERMARK_GRADIENT.get(),
                v -> { AetherConfig.WATERMARK_GRADIENT.set(v); AetherConfig.save(); }));
        watermark.add(new ColorSetting("Gradient Left",
                () -> AetherConfig.WATERMARK_GRADIENT_LEFT.get(),
                v -> { AetherConfig.WATERMARK_GRADIENT_LEFT.set(v); AetherConfig.save(); })
                .visibleWhen(() -> AetherConfig.WATERMARK_GRADIENT.get()));
        watermark.add(new ColorSetting("Gradient Right",
                () -> AetherConfig.WATERMARK_GRADIENT_COLOR.get(),
                v -> { AetherConfig.WATERMARK_GRADIENT_COLOR.set(v); AetherConfig.save(); })
                .visibleWhen(() -> AetherConfig.WATERMARK_GRADIENT.get()));
        groups.add(watermark);

        return MainGUIRegistry.subTab("HUD", "Controls which HUD overlays are visible", groups);
    }
}
