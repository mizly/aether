package dev.aether.ui.providers.failsafes

import java.util.ArrayList
import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.ToggleSetting

class RotationFailsafeRegistryProvider : AbstractFailsafesRegistryProvider(6) {
    override fun createSubTab(): ModulesTab.SubTab {
        val rotationGroup = SettingGroup.alwaysOn(
            "Rotation Failsafe",
            "Triggers when player rotation deviates beyond the configured thresholds"
        )
            .add(FailsafeActionSettings.createActionDropdown("Rotation Action",
                { AetherConfig.FAILSAFE_ROTATION_ACTION.get() },
                { value -> AetherConfig.FAILSAFE_ROTATION_ACTION.set(value) }))
            .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeCustomReplayManager.FailsafeReplayType.ROTATION,
                { AetherConfig.FAILSAFE_ROTATION_ACTION.get() }))
            .add(SliderSetting("Pitch Threshold", 5.0f, 30.0f,
                { AetherConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.get().toFloat() },
                { v -> AetherConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("\u00B0"))
            .add(SliderSetting("Yaw Threshold", 5.0f, 30.0f,
                { AetherConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.get().toFloat() },
                { v -> AetherConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("\u00B0"))
            .add(SliderSetting("Trigger Delay", 0.0f, 5.0f,
                { AetherConfig.FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s"))
            .add(SliderSetting("Warp Grace Period", 1000.0f, 5000.0f,
                { AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toFloat() },
                { v -> AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("ms"))

        val pestRotationGroup = SettingGroup.alwaysOn(
            "Pest Cleaner Rotation",
            "Controls how rotation failsafes behave while the pest cleaner is rotating"
        )
            .add(ToggleSetting("Trigger During Pest Cleaner",
                { AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.get() },
                { v -> AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.set(v); AetherConfig.save() }))
            .add(FailsafeActionSettings.createActionDropdown("Pest Rotation Action",
                { AetherConfig.FAILSAFE_PEST_ROTATION_ACTION.get() },
                { value -> AetherConfig.FAILSAFE_PEST_ROTATION_ACTION.set(value) }))
            .add(FailsafeActionSettings.createCustomReplayDropdown(FailsafeCustomReplayManager.FailsafeReplayType.PEST_ROTATION,
                { AetherConfig.FAILSAFE_PEST_ROTATION_ACTION.get() }))
            .add(SliderSetting("Pest Cleaner Rotation Failsafe Delay", 0.0f, 5000.0f,
                { AetherConfig.FAILSAFE_ROTATION_PEST_CLEANER_DELAY_MS.get().toFloat() },
                { v -> AetherConfig.FAILSAFE_ROTATION_PEST_CLEANER_DELAY_MS.set(Math.round(v).toInt()); AetherConfig.save() })
                .withDecimals(0).withSuffix("ms")
                .visibleWhen { AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.get() })

        return MainGUIRegistry.toggleSubTab(
            "Rotation",
            "Triggers when player rotation deviates beyond the configured thresholds",
            { AetherConfig.FAILSAFE_ROTATION.get() },
            { v -> AetherConfig.FAILSAFE_ROTATION.set(v); AetherConfig.save() },
            listOf(rotationGroup, pestRotationGroup)
        )
    }
}
