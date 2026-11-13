package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.config.HumanizationPresetManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.ToggleSetting
import java.util.ArrayList
import java.util.List

class HumanizationRegistryProvider : AbstractModulesRegistryProvider(10) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        groups.add(SettingGroup.alwaysOn("Presets", "Apply editable humanization preset JSON files")
            .add(DropdownSetting("Preset", ArrayList(HumanizationPresetManager.getPresetOptions()),
                HumanizationPresetManager::getSelectedPresetIndex,
                HumanizationPresetManager::applyPresetByIndex)
                .addIconAction("/assets/aether/icons/folder.svg", HumanizationPresetManager::openPresetFolder)
                .addIconAction("/assets/aether/icons/refresh.svg", HumanizationPresetManager::reapplySelectedPreset)))
        groups.add(SettingGroup.alwaysOn("Delays", "Timing controls for macro and gear actions")
            .add(FarmingSettingsFactory.laneSwitchDelaySetting())
            .add(FarmingSettingsFactory.pestDestroyerTriggerDelaySetting())
            .add(FarmingSettingsFactory.ballsackShredderTriggerDelaySetting())
            .add(FarmingSettingsFactory.pestExchangeDelaySetting())
            .add(FarmingSettingsFactory.aotvBetweenPestsDelaySetting())
            .add(FarmingSettingsFactory.rodSwapDelaySetting())
            .add(FarmingSettingsFactory.guiFirstClickDelaySetting())
            .add(FarmingSettingsFactory.guiClickDelaySetting())
            .add(FarmingSettingsFactory.pickUpStashDelaySetting())
            .add(FarmingSettingsFactory.junkDropDelaySetting())
            .add(FarmingSettingsFactory.georgePostSellDelaySetting())
            .add(FarmingSettingsFactory.visitorDelaySetting())
            .add(FarmingSettingsFactory.bazaarGuiDelaySetting()))
        groups.add(SettingGroup.alwaysOn("Rotation Settings", "Configure how your character rotates while macroing")
            .add(SliderSetting("Rotation Time", 100.0f, 2000.0f,
                { AetherConfig.ROTATION_TIME.get().toFloat() },
                { v -> AetherConfig.ROTATION_TIME.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("ms"))
            .add(SliderSetting("Dynamic Duration", 0.0f, 20.0f,
                { AetherConfig.ROTATION_DYNAMIC_DURATION_MS_PER_DEGREE.get() },
                { v -> AetherConfig.ROTATION_DYNAMIC_DURATION_MS_PER_DEGREE.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix(" ms/°"))
            .add(ToggleSetting("Rotation Ease In", { AetherConfig.ROTATION_EASE_IN.get() },
                { v -> AetherConfig.ROTATION_EASE_IN.set(v); AetherConfig.save() }))
            .add(SliderSetting("Ease In Factor", 1.0f, 5.0f,
                { AetherConfig.ROTATION_EASE_IN_FACTOR.get() },
                { v -> AetherConfig.ROTATION_EASE_IN_FACTOR.set(v); AetherConfig.save() })
                .withDecimals(1).visibleWhen { AetherConfig.ROTATION_EASE_IN.get() })
            .add(ToggleSetting("Rotation Ease Out", { AetherConfig.ROTATION_EASE_OUT.get() },
                { v -> AetherConfig.ROTATION_EASE_OUT.set(v); AetherConfig.save() }))
            .add(SliderSetting("Ease Out Factor", 1.0f, 5.0f,
                { AetherConfig.ROTATION_EASE_OUT_FACTOR.get() },
                { v -> AetherConfig.ROTATION_EASE_OUT_FACTOR.set(v); AetherConfig.save() })
                .withDecimals(1).visibleWhen { AetherConfig.ROTATION_EASE_OUT.get() })
            .add(SliderSetting("Tracking Noise Min", 0.0f, 10.0f,
                { AetherConfig.ROTATION_TRACKING_NOISE_MIN.get() },
                { v ->
                    AetherConfig.ROTATION_TRACKING_NOISE_MIN.set(v)
                    if (AetherConfig.ROTATION_TRACKING_NOISE_MAX.get() < v) AetherConfig.ROTATION_TRACKING_NOISE_MAX.set(v)
                    AetherConfig.save()
                }).withDecimals(1).withSuffix("%"))
            .add(SliderSetting("Tracking Noise Max", 0.0f, 10.0f,
                { AetherConfig.ROTATION_TRACKING_NOISE_MAX.get() },
                { v ->
                    AetherConfig.ROTATION_TRACKING_NOISE_MAX.set(v)
                    if (AetherConfig.ROTATION_TRACKING_NOISE_MIN.get() > v) AetherConfig.ROTATION_TRACKING_NOISE_MIN.set(v)
                    AetherConfig.save()
                }).withDecimals(1).withSuffix("%")))
        groups.add(SettingGroup.alwaysOn("FOV/Rotation", "Angle ranges and target tolerance settings")
            .add(FarmingSettingsFactory.farmingPitchRangeSetting())
            .add(FarmingSettingsFactory.farmingYawRangeSetting())
            .add(FarmingSettingsFactory.pestFovRangeSetting())
            .add(FarmingSettingsFactory.pestAboveAimPitchRangeSetting())
            .add(FarmingSettingsFactory.pestMaxTurnSpeedSetting())
            .add(FarmingSettingsFactory.pestAimDriftSetting())
            .add(FarmingSettingsFactory.visitorFovRangeSetting())
            .add(FarmingSettingsFactory.pestExchangeFovRangeSetting()))
        groups.add(SettingGroup.alwaysOn("Miscellaneous", "Additional behavior toggles for humanization-sensitive actions")
            .add(FarmingSettingsFactory.farmWhileCallingGeorgeSetting()))
        return MainGUIRegistry.subTab("Humanization", "Humanization controls for pitch, yaw, and easing", groups.toList())
    }
}
