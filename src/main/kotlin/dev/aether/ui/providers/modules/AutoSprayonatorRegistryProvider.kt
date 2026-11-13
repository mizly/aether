package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.ToggleSetting

class AutoSprayonatorRegistryProvider : AbstractModulesRegistryProvider(5) {
    override fun createSubTab(): ModulesTab.SubTab {
        val sprayMaterials = FarmingSettingsFactory.sprayMaterials()
        val group = SettingGroup.alwaysOn(
            "Sprayonator Settings",
            "Configure Auto Sprayonator behavior"
        )
            .add(DropdownSetting("Spray Material", sprayMaterials,
                {
                    val current = AetherConfig.AUTO_SPRAYONATOR_MATERIAL.get()
                    val index = sprayMaterials.indexOf(current)
                    if (index >= 0) index else 0
                }, { index ->
                    if (index >= 0 && index < sprayMaterials.size) {
                        AetherConfig.AUTO_SPRAYONATOR_MATERIAL.set(sprayMaterials[index])
                        AetherConfig.save()
                    }
                })
                .visibleWhen { AetherConfig.AUTO_SPRAYONATOR.get() })
            .add(ToggleSetting("Auto Buy Material",
                { AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY.get() }, { value ->
                    AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY.set(value)
                    AetherConfig.save()
                })
                .visibleWhen { AetherConfig.AUTO_SPRAYONATOR.get() })
            .add(SliderSetting("Auto Buy Amount", 1.0f, 64.0f,
                { AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY_AMOUNT.get().toFloat() }, { value ->
                    AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY_AMOUNT.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0)
                .visibleWhen { AetherConfig.AUTO_SPRAYONATOR.get() && AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY.get() })
            .add(SliderSetting("Unsprayed Plot Detect Time", 5.0f, 30.0f,
                { AetherConfig.AUTO_SPRAYONATOR_DETECT_TIME.get().toFloat() }, { value ->
                    AetherConfig.AUTO_SPRAYONATOR_DETECT_TIME.set(Math.round(value).toInt())
                    AetherConfig.save()
                })
                .withDecimals(0).withSuffix("s")
                .visibleWhen { AetherConfig.AUTO_SPRAYONATOR.get() })

        return MainGUIRegistry.toggleSubTab(
            "Auto Sprayonator",
            "Automatically detects and sprays unsprayed plots",
            { AetherConfig.AUTO_SPRAYONATOR.get() }, { value ->
                AetherConfig.AUTO_SPRAYONATOR.set(value)
                AetherConfig.save()
            },
            listOf(group)
        )
    }
}
