package dev.aether.ui.providers.visuals


import dev.aether.config.AetherConfig
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting
import java.util.ArrayList
import java.util.function.Consumer
import java.util.function.IntConsumer
import java.util.function.Supplier

class NickHiderVisualsRegistryProvider : AbstractVisualsRegistryProvider(1) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        groups.add(SettingGroup.alwaysOn("Username", "Spoofs your username in chat and overlays")
            .add(ToggleSetting("Enable Username Spoof", AetherConfig.NICK_HIDER_ENABLED::get) { value ->
                AetherConfig.NICK_HIDER_ENABLED.set(value)
                AetherConfig.save()
            })
            .add(TextSetting("Custom Username", "AetherUser", AetherConfig.CUSTOM_USERNAME::get) { value ->
                AetherConfig.CUSTOM_USERNAME.set(value)
                AetherConfig.save()
            }.visibleWhen { AetherConfig.NICK_HIDER_ENABLED.get() })
            .add(ToggleSetting("Hide Skin", AetherConfig.HIDE_SKIN::get) { value ->
                AetherConfig.HIDE_SKIN.set(value)
                AetherConfig.save()
            }.visibleWhen { AetherConfig.NICK_HIDER_ENABLED.get() }))
        groups.add(SettingGroup.of("Hide Server ID", "Replaces the server identifier shown in tablist and scoreboard",
            AetherConfig.HIDE_SERVER_ID::get) { value ->
            AetherConfig.HIDE_SERVER_ID.set(value)
            AetherConfig.save()
        }.add(TextSetting("Custom Server ID", "aether.cat", AetherConfig.CUSTOM_SERVER_ID::get) { value ->
            AetherConfig.CUSTOM_SERVER_ID.set(value)
            AetherConfig.save()
        }))
        groups.add(SettingGroup.of("Coop Name Hider", "Obfuscates configured coop names", AetherConfig.COOP_HIDER_ENABLED::get) { value ->
            AetherConfig.COOP_HIDER_ENABLED.set(value)
            AetherConfig.save()
        }.add(ListSetting("Coop Names", "Add coop name", Supplier { java.util.ArrayList(AetherConfig.COOP_NAMES.get()) }) { value ->
            AetherConfig.COOP_NAMES.set(java.util.ArrayList(value))
            AetherConfig.save()
        }))
        val spoofValues = SettingGroup.of("Spoof Values", "Customise SkyBlock level and identifiable values", AetherConfig.SPOOF_VALUES_ENABLED::get) { value ->
            AetherConfig.SPOOF_VALUES_ENABLED.set(value)
            AetherConfig.save()
        }
        spoofValues.add(ToggleSetting("Custom Skyblock Level", AetherConfig.CUSTOM_SB_LEVEL_ENABLED::get) { value ->
            AetherConfig.CUSTOM_SB_LEVEL_ENABLED.set(value)
            AetherConfig.save()
        }.visibleWhen { AetherConfig.SPOOF_VALUES_ENABLED.get() })
        spoofValues.add(TextSetting("SkyBlock Level Override", "0", { AetherConfig.CUSTOM_SB_LEVEL.get().toString() },
            { value -> saveIntValue(value, AetherConfig.CUSTOM_SB_LEVEL::set) })
            .visibleWhen { AetherConfig.SPOOF_VALUES_ENABLED.get() && AetherConfig.CUSTOM_SB_LEVEL_ENABLED.get() })
        spoofValues.add(TextSetting("Purse Offset", "0", { formatDecimal(AetherConfig.PURSE_OFFSET.get()) },
            { value -> saveDoubleValue(value, AetherConfig.PURSE_OFFSET::set) }).visibleWhen { AetherConfig.SPOOF_VALUES_ENABLED.get() })
        spoofValues.add(TextSetting("Bits Offset", "0", { formatDecimal(AetherConfig.BITS_OFFSET.get()) },
            { value -> saveDoubleValue(value, AetherConfig.BITS_OFFSET::set) }).visibleWhen { AetherConfig.SPOOF_VALUES_ENABLED.get() })
        spoofValues.add(TextSetting("Copper Offset", "0", { formatDecimal(AetherConfig.COPPER_OFFSET.get()) },
            { value -> saveDoubleValue(value, AetherConfig.COPPER_OFFSET::set) }).visibleWhen { AetherConfig.SPOOF_VALUES_ENABLED.get() })
        spoofValues.add(TextSetting("Sawdust Offset", "0", { formatDecimal(AetherConfig.SAWDUST_OFFSET.get()) },
            { value -> saveDoubleValue(value, AetherConfig.SAWDUST_OFFSET::set) }).visibleWhen { AetherConfig.SPOOF_VALUES_ENABLED.get() })
        spoofValues.add(TextSetting("Farming XP Offset", "0", { formatDecimal(AetherConfig.FARMING_EXP_OFFSET.get()) },
            { value -> saveDoubleValue(value, AetherConfig.FARMING_EXP_OFFSET::set) }).visibleWhen { AetherConfig.SPOOF_VALUES_ENABLED.get() })
        groups.add(spoofValues)
        return MainGUIRegistry.toggleSubTab("Nick Hider", "Spoofs names, server IDs, coop names, and identifiable values",
            AetherConfig.NICK_HIDER_MASTER_ENABLED::get, { value ->
                AetherConfig.NICK_HIDER_MASTER_ENABLED.set(value)
                AetherConfig.save()
            }, groups)
    }

    companion object {
        @JvmStatic private fun saveIntValue(value: String, setter: IntConsumer) {
            try {
                setter.accept(Integer.parseInt(value.trim()))
                AetherConfig.save()
            } catch (_: NumberFormatException) { }
        }

        @JvmStatic private fun saveDoubleValue(value: String, setter: Consumer<Double>) {
            try {
                setter.accept(java.lang.Double.parseDouble(value.trim()))
                AetherConfig.save()
            } catch (_: NumberFormatException) { }
        }

        @JvmStatic private fun formatDecimal(value: Double): String {
            if (value == Math.rint(value)) return java.lang.Long.toString(Math.round(value))
            return java.lang.Double.toString(value)
        }
    }
}
