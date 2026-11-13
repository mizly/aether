package dev.aether.ui.providers.modules

import java.util.function.Supplier
import dev.aether.config.AetherConfig
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.DropdownListSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup

class DynamicPestsRegistryProvider : AbstractModulesRegistryProvider(6) {
    override fun createSubTab(): ModulesTab.SubTab {
        val modeGroup = SettingGroup.alwaysOn(
            "Dynamic Pest Type",
            "Which event type drives Dynamic Pests behavior"
        )
            .add(DropdownSetting("Dynamic Pest Type", MODE_OPTIONS,
                { AetherConfig.DYNAMIC_PESTS_MODE.get() }, { value ->
                    AetherConfig.DYNAMIC_PESTS_MODE.set(value)
                    AetherConfig.save()
                })
                .visibleWhen { AetherConfig.DYNAMIC_PESTS_ENABLED.get() })

        val fallbackGroup = SettingGroup.alwaysOn(
            "Fallback Behavior",
            "Used when no Feast or Jacob's Contest is active"
        )
            .add(DropdownSetting("Fallback Spray", SPRAY_OPTIONS,
                { AetherConfig.DYNAMIC_PESTS_FALLBACK_SPRAY.get() }, { value ->
                    AetherConfig.DYNAMIC_PESTS_FALLBACK_SPRAY.set(value)
                    AetherConfig.save()
                })
                .visibleWhen { AetherConfig.DYNAMIC_PESTS_ENABLED.get() })
            .add(DropdownSetting("Fallback Vinyl", VINYL_OPTIONS,
                { AetherConfig.DYNAMIC_PESTS_FALLBACK_VINYL.get() }, { value ->
                    AetherConfig.DYNAMIC_PESTS_FALLBACK_VINYL.set(value)
                    AetherConfig.save()
                })
                .visibleWhen { AetherConfig.DYNAMIC_PESTS_ENABLED.get() })

        val feastGroup = SettingGroup.alwaysOn(
            "Harvest Feast Priority",
            "Crops to prioritize during a Harvest Feast (1 = highest)"
        )
            .add(DropdownListSetting("Feast Crop Priority", CROP_OPTIONS,
                { AetherConfig.DYNAMIC_PESTS_FEAST_PRIORITY.get() }, { value ->
                    AetherConfig.DYNAMIC_PESTS_FEAST_PRIORITY.set(value)
                    AetherConfig.save()
                })
                .visibleWhen { AetherConfig.DYNAMIC_PESTS_ENABLED.get() })

        val contestGroup = SettingGroup.alwaysOn(
            "Jacob's Contest Priority",
            "Crops to prioritize during Jacob's Contest (1 = highest)"
        )
            .add(DropdownListSetting("Contest Crop Priority", CROP_OPTIONS,
                { AetherConfig.DYNAMIC_PESTS_CONTEST_PRIORITY.get() }, { value ->
                    AetherConfig.DYNAMIC_PESTS_CONTEST_PRIORITY.set(value)
                    AetherConfig.save()
                })
                .visibleWhen { AetherConfig.DYNAMIC_PESTS_ENABLED.get() })

        return MainGUIRegistry.toggleSubTab(
            "Dynamic Pests",
            "Dynamically adjusts crop priorities based on active Feast and Jacob's Contests",
            { AetherConfig.DYNAMIC_PESTS_ENABLED.get() },
            { enabled ->
                AetherConfig.DYNAMIC_PESTS_ENABLED.set(enabled)
                AetherConfig.save()
            },
            listOf(modeGroup, fallbackGroup, feastGroup, contestGroup)
        )
    }

    companion object {
        @JvmField
        internal val CROP_OPTIONS: List<String> = java.util.ArrayList(java.util.List.of(
            "Wheat", "Carrot", "Potato", "Pumpkin", "Sugar Cane",
            "Melon Slice", "Cactus", "Cocoa Beans", "Mushrooms",
            "Nether Wart", "Moonflower", "Sunflower", "Wild Rose"
        ))

        private val MODE_OPTIONS: List<String> = listOf("Feast", "Jacob", "Jacob with Feast")
        private val SPRAY_OPTIONS: List<String> = listOf("Compost", "Honey Jar", "Dung", "Plant Matter", "Tasty Cheese", "Jelly")
        private val VINYL_OPTIONS: List<String> = listOf(
            "Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm",
            "Mite", "Moth", "Slug", "Beetle", "Firefly", "Dragonfly", "Praying Mantis"
        )
    }
}
