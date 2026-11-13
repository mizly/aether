package dev.aether.ui.providers.visuals

import dev.aether.config.AetherConfig
import dev.aether.config.PetInfo
import dev.aether.config.PetRarity
import dev.aether.modules.profit.ProfitManager
import dev.aether.modules.profit.ProfitPriceSource
import dev.aether.modules.profit.ShardPriceSource
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting
import java.util.ArrayList
import java.util.function.Consumer

class ProfitCalculatorRegistryProvider : AbstractVisualsRegistryProvider(0) {
    override fun createSubTab(): ModulesTab.SubTab = MainGUIRegistry.toggleSubTab(
        "Profit Tracker", "Tracks and displays farming earnings",
        AetherConfig.PROFIT_HUD_ENABLED::get, { value -> AetherConfig.PROFIT_HUD_ENABLED.set(value); AetherConfig.save() }, buildGroups())

    companion object {
        private val PROFIT_GROUPS = ArrayList<SettingGroup>()
        private val PET_TRACKER_GROUPS = ArrayList<SettingGroup>()

        @JvmStatic private fun buildGroups(): List<SettingGroup> {
            val profitGroups = PROFIT_GROUPS
            profitGroups.clear()
            profitGroups.add(SettingGroup.of("Profit HUD", "Displays session earnings", AetherConfig.PROFIT_HUD_ENABLED::get) { value ->
                AetherConfig.PROFIT_HUD_ENABLED.set(value); AetherConfig.save()
            }
                .add(ToggleSetting("Session Profit HUD", AetherConfig.SHOW_SESSION_PROFIT_HUD::get) { value -> AetherConfig.SHOW_SESSION_PROFIT_HUD.set(value); AetherConfig.save() })
                .add(ToggleSetting("Rolling Profit Graph", AetherConfig.SESSION_PROFIT_GRAPH::get) { value -> AetherConfig.SESSION_PROFIT_GRAPH.set(value); AetherConfig.save() }.visibleWhen(AetherConfig.SHOW_SESSION_PROFIT_HUD::get))
                .add(SliderSetting("Graph Window", 1.0f, 15.0f, { AetherConfig.SESSION_PROFIT_GRAPH_MINUTES.get().toFloat() }, { value -> AetherConfig.SESSION_PROFIT_GRAPH_MINUTES.set(Math.round(value).toInt()); AetherConfig.save() })
                    .withDecimals(0).withSuffix(" min").visibleWhen { AetherConfig.SHOW_SESSION_PROFIT_HUD.get() && AetherConfig.SESSION_PROFIT_GRAPH.get() })
                .add(ToggleSetting("Lifetime HUD", AetherConfig.SHOW_LIFETIME_HUD::get) { value -> AetherConfig.SHOW_LIFETIME_HUD.set(value); AetherConfig.save() })
                .add(ToggleSetting("Daily HUD", AetherConfig.SHOW_DAILY_HUD::get) { value -> AetherConfig.SHOW_DAILY_HUD.set(value); AetherConfig.save() })
                .add(ToggleSetting("Compact Profit Calculator", AetherConfig.COMPACT_PROFIT_CALCULATOR::get) { value -> AetherConfig.COMPACT_PROFIT_CALCULATOR.set(value); AetherConfig.save() })
                .add(DropdownSetting("Price Source", ProfitPriceSource.values().map { it.label },
                    { ProfitPriceSource.fromConfig(AetherConfig.PROFIT_PRICE_SOURCE.get()).ordinal }) { index ->
                    val values = ProfitPriceSource.values()
                    if (index < 0 || index >= values.size) return@DropdownSetting
                    AetherConfig.PROFIT_PRICE_SOURCE.set(values[index].name)
                    AetherConfig.save()
                    ProfitManager.handlePriceSourceChanged()
                })
                .add(DropdownSetting("Shard Price Source", ShardPriceSource.values().map { it.label },
                    { ShardPriceSource.fromConfig(AetherConfig.SHARD_PRICE_SOURCE.get()).ordinal }) { index ->
                    val values = ShardPriceSource.values()
                    if (index < 0 || index >= values.size) return@DropdownSetting
                    AetherConfig.SHARD_PRICE_SOURCE.set(values[index].name)
                    AetherConfig.save()
                    ProfitManager.handleShardPriceSourceChanged()
                })
                .add(ActionSetting("Reset Session", dev.aether.macro.MacroStateManager::resetSession))
                .add(ActionSetting("Reset Lifetime Profit", ProfitManager::resetLifetime)))
            profitGroups.add(SettingGroup.alwaysOn("Farming XP", "Farming XP stats shown on the Session Profit HUD")
                .add(ToggleSetting("Show Farming XP", AetherConfig.FARMING_XP_HUD::get) { value -> AetherConfig.FARMING_XP_HUD.set(value); AetherConfig.save() })
                .add(ToggleSetting("Farming XP / hr", AetherConfig.FARMING_HUD_XP_RATE::get) { value -> AetherConfig.FARMING_HUD_XP_RATE.set(value); AetherConfig.save() }.visibleWhen { AetherConfig.FARMING_XP_HUD.get() })
                .add(ToggleSetting("Time to Next Level", AetherConfig.FARMING_HUD_ETA_NEXT::get) { value -> AetherConfig.FARMING_HUD_ETA_NEXT.set(value); AetherConfig.save() }.visibleWhen { AetherConfig.FARMING_XP_HUD.get() })
                .add(ToggleSetting("Time to Farming 60", AetherConfig.FARMING_HUD_ETA_MAX::get) { value -> AetherConfig.FARMING_HUD_ETA_MAX.set(value); AetherConfig.save() }.visibleWhen { AetherConfig.FARMING_XP_HUD.get() }))
            rebuildPetTrackerGroups()
            return profitGroups
        }

        @JvmStatic private fun defaultPetTrackerEntry(): String = "Rose Dragon:200:650000000:1250000000:LEGENDARY"

        @JvmStatic private fun getPetTrackerInfo(index: Int): PetInfo {
            val entries = AetherConfig.PET_TRACKER_LIST.get()
            if (entries.isEmpty()) return PetInfo(defaultPetTrackerEntry())
            val safeIndex = Math.max(0, Math.min(index, entries.size - 1))
            return PetInfo(entries[safeIndex])
        }

        @JvmStatic private fun updatePetTrackerInfo(index: Int, updater: Consumer<PetInfo>) {
            val entries = ArrayList(AetherConfig.PET_TRACKER_LIST.get())
            while (entries.size <= index) entries.add(defaultPetTrackerEntry())
            val info = PetInfo(entries[index])
            updater.accept(info)
            entries[index] = info.toString()
            AetherConfig.PET_TRACKER_LIST.set(entries)
            AetherConfig.save()
            ProfitManager.reloadConfiguredPetXpPrices()
        }

        @JvmStatic private fun addPetTrackerEntry() {
            val entries = ArrayList(AetherConfig.PET_TRACKER_LIST.get())
            entries.add(defaultPetTrackerEntry())
            AetherConfig.PET_TRACKER_LIST.set(entries)
            AetherConfig.save()
            ProfitManager.reloadConfiguredPetXpPrices()
            rebuildPetTrackerGroups()
        }

        @JvmStatic private fun removePetTrackerEntry(index: Int) {
            val entries = ArrayList(AetherConfig.PET_TRACKER_LIST.get())
            if (entries.size <= 1 || index < 0 || index >= entries.size) return
            entries.removeAt(index)
            AetherConfig.PET_TRACKER_LIST.set(entries)
            AetherConfig.save()
            ProfitManager.reloadConfiguredPetXpPrices()
            rebuildPetTrackerGroups()
        }

        @JvmStatic private fun buildPetTrackerGroup(petIndex: Int, petLevelOptions: List<String>, petRarityOptions: List<String>): SettingGroup {
            val groupName = if (petIndex == 0) "Pet XP Tracker" else "Pet XP Tracker " + (petIndex + 1)
            val petXpTracker = SettingGroup.alwaysOn(groupName, "Configure user-defined Pet XP pricing")
            petXpTracker.add(ActionSetting("Remove Pet") { removePetTrackerEntry(petIndex) }.visibleWhen { false })
            petXpTracker.add(TextSetting("Pet Name", "e.g. Rose Dragon", { getPetTrackerInfo(petIndex).name }, { value -> updatePetTrackerInfo(petIndex) { info -> info.name = value.trim() } }))
            petXpTracker.add(DropdownSetting("Max Level", petLevelOptions, { if (getPetTrackerInfo(petIndex).maxLevel >= 200) 1 else 0 }) { index ->
                updatePetTrackerInfo(petIndex) { info -> info.maxLevel = if (index == 1) 200 else 100 }
            })
            petXpTracker.add(TextSetting("Level 1 Price", "e.g. 650000000", { java.lang.String.valueOf(getPetTrackerInfo(petIndex).level1Price) }, { value ->
                updatePetTrackerInfo(petIndex) { info ->
                    try { info.level1Price = java.lang.Long.parseLong(value.replace(Regex("[^\\d]"), "")) } catch (_: Exception) { }
                }
            }))
            petXpTracker.add(TextSetting("Max Level Price", "e.g. 1250000000", { java.lang.String.valueOf(getPetTrackerInfo(petIndex).maxLevelPrice) }, { value ->
                updatePetTrackerInfo(petIndex) { info ->
                    try { info.maxLevelPrice = java.lang.Long.parseLong(value.replace(Regex("[^\\d]"), "")) } catch (_: Exception) { }
                }
            }))
            petXpTracker.add(DropdownSetting("Rarity", petRarityOptions, { getPetTrackerInfo(petIndex).rarity.ordinal }) { index ->
                updatePetTrackerInfo(petIndex) { info -> info.rarity = PetRarity.values()[index] }
            })
            petXpTracker.add(ActionSetting("Add Pet", ::addPetTrackerEntry).visibleWhen { false })
            return petXpTracker
        }

        @JvmStatic private fun rebuildPetTrackerGroups() {
            PROFIT_GROUPS.removeAll(PET_TRACKER_GROUPS)
            PET_TRACKER_GROUPS.clear()
            val petLevelOptions = listOf("100", "200")
            val petRarityOptions = PetRarity.values().map { it.name }
            val petTrackerCount = Math.max(1, AetherConfig.PET_TRACKER_LIST.get().size)
            for (index in 0 until petTrackerCount) PET_TRACKER_GROUPS.add(buildPetTrackerGroup(index, petLevelOptions, petRarityOptions))
            PROFIT_GROUPS.addAll(PET_TRACKER_GROUPS)
        }
    }
}
