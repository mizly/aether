package dev.aether.ui.providers.visuals

import dev.aether.config.AetherConfig
import dev.aether.hud.HudEditScreen
import dev.aether.hud.PestTargetHudElement
import dev.aether.hud.ScoreboardHudElement
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.MultiDropdownSetting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting
import net.minecraft.client.Minecraft
import java.util.ArrayList

class HudVisualsRegistryProvider : AbstractVisualsRegistryProvider(0) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        groups.add(SettingGroup.alwaysOn("HUD Settings", "Configure overlay style and layout")
            .add(MultiDropdownSetting("HUD Themes", listOf("Main", "Watermark"), AetherConfig.HUD_THEME::get) { index ->
                AetherConfig.HUD_THEME.set(index)
                AetherConfig.save()
            })
            .add(ActionSetting("Edit HUD Layout") { Minecraft.getInstance().gui.setScreen(HudEditScreen()) }))
        groups.add(SettingGroup.alwaysOn("HUD Visibility", "Controls when HUD overlays are shown")
            .add(ToggleSetting("Only Show While Macro Running", AetherConfig.HUD_ONLY_WHILE_MACRO_RUNNING::get) { value ->
                AetherConfig.HUD_ONLY_WHILE_MACRO_RUNNING.set(value)
                AetherConfig.save()
            })
            .add(ToggleSetting("Only Show HUDs In Garden", AetherConfig.GUI_ONLY_IN_GARDEN::get) { value ->
                AetherConfig.GUI_ONLY_IN_GARDEN.set(value)
                AetherConfig.save()
            })
            .add(ToggleSetting("Show Task HUDs Outside Garden", AetherConfig.SHOW_HUD_OUTSIDE_GARDEN::get) { value ->
                AetherConfig.SHOW_HUD_OUTSIDE_GARDEN.set(value)
                AetherConfig.save()
            }.visibleWhen { !AetherConfig.GUI_ONLY_IN_GARDEN.get() }))
        groups.add(SettingGroup.of("Streamer Mode", "Hides Aether chat, notifications, overlays, HUDs, and world visuals",
            StreamerModeManager::isEnabled, StreamerModeManager::setEnabled))
        groups.add(SettingGroup.alwaysOn("Main Status HUD", "Settings for the all-in-one main status card")
            .add(ToggleSetting("Gradient", AetherConfig.MAIN_STATUS_GRADIENT::get) { value ->
                AetherConfig.MAIN_STATUS_GRADIENT.set(value); AetherConfig.save()
            })
            .add(ColorSetting("Gradient Left", AetherConfig.MAIN_STATUS_GRADIENT_LEFT::get) { value ->
                AetherConfig.MAIN_STATUS_GRADIENT_LEFT.set(value); AetherConfig.save()
            }.visibleWhen { AetherConfig.MAIN_STATUS_GRADIENT.get() })
            .add(ColorSetting("Gradient Right", AetherConfig.MAIN_STATUS_GRADIENT_RIGHT::get) { value ->
                AetherConfig.MAIN_STATUS_GRADIENT_RIGHT.set(value); AetherConfig.save()
            }.visibleWhen { AetherConfig.MAIN_STATUS_GRADIENT.get() }))
        groups.add(SettingGroup.alwaysOn("Debug HUD", "Developer overlays for macro state and task tracking")
            .add(ToggleSetting("Macro HUD", AetherConfig.SHOW_HUD::get) { value -> AetherConfig.SHOW_HUD.set(value); AetherConfig.save() })
            .add(ToggleSetting("Intermediaries HUD", AetherConfig.SHOW_INTERMEDIARIES_HUD::get) { value -> AetherConfig.SHOW_INTERMEDIARIES_HUD.set(value); AetherConfig.save() })
            .add(ToggleSetting("Mid Farming HUD", AetherConfig.SHOW_MID_FARMING_HUD::get) { value -> AetherConfig.SHOW_MID_FARMING_HUD.set(value); AetherConfig.save() })
            .add(ToggleSetting("Failsafes HUD", AetherConfig.SHOW_FAILSAFES_HUD::get) { value -> AetherConfig.SHOW_FAILSAFES_HUD.set(value); AetherConfig.save() }))
        groups.add(SettingGroup.of("Custom Scoreboard", "Style the scoreboard to match your HUD", AetherConfig.CUSTOM_SCOREBOARD::get,
            ScoreboardHudElement::setEnabled)
            .add(TextSetting("Scoreboard Title Text", "Leave empty to keep the server title", AetherConfig.SCOREBOARD_TITLE_TEXT::get) { value -> AetherConfig.SCOREBOARD_TITLE_TEXT.set(value); AetherConfig.save() })
            .add(TextSetting("Scoreboard Server Line Text", "Leave empty to keep the server address", AetherConfig.SCOREBOARD_SERVER_TEXT::get) { value -> AetherConfig.SCOREBOARD_SERVER_TEXT.set(value); AetherConfig.save() })
            .add(ActionSetting("Reset Scoreboard Layout", ScoreboardHudElement::resetLayout)))
        groups.add(SettingGroup.alwaysOn("Inventory HUD", "Configure the inventory preview overlay")
            .add(ToggleSetting("Inventory HUD", AetherConfig.SHOW_INVENTORY_HUD::get) { value -> AetherConfig.SHOW_INVENTORY_HUD.set(value); AetherConfig.save() })
            .add(ToggleSetting("Show Player Model", AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL::get) { value -> AetherConfig.INVENTORY_HUD_SHOW_PLAYER_MODEL.set(value); AetherConfig.save() })
            .add(ToggleSetting("Show Armor", AetherConfig.INVENTORY_HUD_SHOW_ARMOR::get) { value -> AetherConfig.INVENTORY_HUD_SHOW_ARMOR.set(value); AetherConfig.save() }))
        groups.add(SettingGroup.of("Pest Target HUD", "Pest icons, health and hunting progress below the crosshair",
            AetherConfig.SHOW_PEST_TARGET_HUD::get) { value -> AetherConfig.SHOW_PEST_TARGET_HUD.set(value); AetherConfig.save() }
            .add(ActionSetting("Reset Target HUD Position", PestTargetHudElement::resetLayout)))
        val watermark = SettingGroup.alwaysOn("Watermark", "Displays mod name, username, FPS, ping and time")
        watermark.add(ToggleSetting("Show Macro Status", AetherConfig.WATERMARK_SHOW_MACRO_STATUS::get) { value -> AetherConfig.WATERMARK_SHOW_MACRO_STATUS.set(value); AetherConfig.save() })
        watermark.add(ToggleSetting("Show Logo", AetherConfig.WATERMARK_SHOW_LOGO::get) { value ->
            if (!value && AetherConfig.WATERMARK_SHOW_NAME.get()) return@ToggleSetting
            AetherConfig.WATERMARK_SHOW_LOGO.set(value); AetherConfig.save()
        })
        watermark.add(ToggleSetting("Show Name", AetherConfig.WATERMARK_SHOW_NAME::get) { value ->
            if (!value && AetherConfig.WATERMARK_SHOW_LOGO.get()) return@ToggleSetting
            AetherConfig.WATERMARK_SHOW_NAME.set(value); AetherConfig.save()
        })
        watermark.add(TextSetting("Custom Username", "leave empty to use your real name", AetherConfig.WATERMARK_CUSTOM_USERNAME::get) { value -> AetherConfig.WATERMARK_CUSTOM_USERNAME.set(value); AetherConfig.save() })
        watermark.add(ToggleSetting("Show Username", AetherConfig.WATERMARK_SHOW_USERNAME::get) { value -> AetherConfig.WATERMARK_SHOW_USERNAME.set(value); AetherConfig.save() })
        watermark.add(ToggleSetting("Show FPS", AetherConfig.WATERMARK_SHOW_FPS::get) { value -> AetherConfig.WATERMARK_SHOW_FPS.set(value); AetherConfig.save() })
        watermark.add(ToggleSetting("Show Ping", AetherConfig.WATERMARK_SHOW_PING::get) { value -> AetherConfig.WATERMARK_SHOW_PING.set(value); AetherConfig.save() })
        watermark.add(ToggleSetting("Show Time", AetherConfig.WATERMARK_SHOW_TIME::get) { value -> AetherConfig.WATERMARK_SHOW_TIME.set(value); AetherConfig.save() })
        watermark.add(ToggleSetting("Gradient", AetherConfig.WATERMARK_GRADIENT::get) { value -> AetherConfig.WATERMARK_GRADIENT.set(value); AetherConfig.save() })
        watermark.add(ColorSetting("Gradient Left", AetherConfig.WATERMARK_GRADIENT_LEFT::get) { value -> AetherConfig.WATERMARK_GRADIENT_LEFT.set(value); AetherConfig.save() }.visibleWhen { AetherConfig.WATERMARK_GRADIENT.get() })
        watermark.add(ColorSetting("Gradient Right", AetherConfig.WATERMARK_GRADIENT_COLOR::get) { value -> AetherConfig.WATERMARK_GRADIENT_COLOR.set(value); AetherConfig.save() }.visibleWhen { AetherConfig.WATERMARK_GRADIENT.get() })
        groups.add(watermark)
        return MainGUIRegistry.subTab("HUD", "Controls which HUD overlays are visible", groups.toList())
    }
}
