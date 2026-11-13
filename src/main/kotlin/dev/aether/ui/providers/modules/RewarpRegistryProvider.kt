package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.config.RewarpMode
import dev.aether.config.RewarpPointPair
import dev.aether.config.RewarpPointPairs
import dev.aether.notification.NotificationManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting
import dev.aether.util.AetherLang
import net.minecraft.client.Minecraft

class RewarpRegistryProvider : AbstractModulesRegistryProvider(1) {
    override fun createSubTab(): ModulesTab.SubTab = MainGUIRegistry.toggleSubTab(
        "Rewarp",
        "Brings you back to the start of your farm after reaching the end",
        { AetherConfig.ENABLE_REWARP.get() },
        { v ->
            AetherConfig.ENABLE_REWARP.set(v)
            AetherConfig.save()
        },
        java.util.ArrayList<SettingGroup>(buildGroups())
    )

    companion object {
        private val rewarpGroups: java.util.ArrayList<SettingGroup> = java.util.ArrayList()
        private val rewarpModeOptions: java.util.ArrayList<String> = java.util.ArrayList<String>(java.util.List.of(
            RewarpMode.PLOT_TP.displayName(),
            RewarpMode.FLY.displayName(),
            RewarpMode.WARP_GARDEN.displayName()
        ))

        private fun buildGroups(): java.util.List<SettingGroup> {
            rewarpGroups.clear()
            rewarpGroups.add(SettingGroup.alwaysOn("Rewarp Delay", "Timing controls for rewarp")
                .add(FarmingSettingsFactory.rewarpDelaySetting()))
            val count = Math.max(1, RewarpPointPairs.get().size)
            for (i in 0 until count) rewarpGroups.add(buildPointGroup(i))
            return java.util.ArrayList(rewarpGroups) as java.util.List<SettingGroup>
        }

        private fun addRewarpPair() {
            RewarpPointPairs.add()
            buildGroups()
        }

        private fun removeRewarpPair(index: Int) {
            RewarpPointPairs.remove(index)
            buildGroups()
        }

        private fun buildPointGroup(index: Int): SettingGroup {
            val pair = RewarpPointPairs.get(index)
            val group = SettingGroup.alwaysOn(pair.displayName(), "Configure a named rewarp start and end pair")
            group.add(TextSetting("Rewarp Name", "e.g. Wheat",
                { RewarpPointPairs.get(index).displayName() },
                { v -> RewarpPointPairs.update(index) { p -> p.name = sanitizeName(v, index) } }))
            group.add(DropdownSetting("Rewarp Mode", rewarpModeOptions,
                { (RewarpPointPairs.get(index).rewarpMode ?: RewarpMode.FLY).ordinal },
                { v -> RewarpPointPairs.update(index) { p -> p.rewarpMode = RewarpMode.values()[v] } }))
            group.add(TextSetting("Plot Number", "e.g. 5",
                { RewarpPointPairs.get(index).plotTpNumber ?: "" },
                { v -> RewarpPointPairs.update(index) { p -> p.plotTpNumber = sanitizePlotNumber(v) } })
                .visibleWhen { RewarpPointPairs.get(index).rewarpMode == RewarpMode.PLOT_TP })
            group.add(ToggleSetting("Hold W Until Wall",
                { RewarpPointPairs.get(index).holdWUntilWall },
                { v -> RewarpPointPairs.update(index) { p -> p.holdWUntilWall = v } }))
            group.add(ToggleSetting("Reverse Direction After Rewarp",
                { RewarpPointPairs.get(index).reverseDirection },
                { v -> RewarpPointPairs.update(index) { p -> p.reverseDirection = v } }))
            group.add(ToggleSetting("AOTV/AOTE Align",
                { RewarpPointPairs.get(index).aotvAlign },
                { v -> RewarpPointPairs.update(index) { p -> p.aotvAlign = v } })
                .visibleWhen { RewarpPointPairs.get(index).rewarpMode == RewarpMode.FLY })
            group.add(buildRewarpStartSetting(index))
            group.add(buildRewarpEndSetting(index))
            group.add(ActionSetting("Add Rewarp", ::addRewarpPair))
            group.add(ActionSetting("Remove Rewarp") { removeRewarpPair(index) }
                .visibleWhen { RewarpPointPairs.get().size > 1 })
            return group
        }

        private fun sanitizeName(value: String?, index: Int): String =
            if (value == null || value.trim().isEmpty()) "Rewarp ${index + 1}" else value.trim()

        private fun sanitizePlotNumber(value: String?): String =
            if (value == null || value.trim().isEmpty()) "0" else value.trim().replace(":", "")

        private fun buildRewarpStartSetting(index: Int) = PositionSetting("Rewarp Start",
            { RewarpPointPairs.get(index).startX },
            { v -> RewarpPointPairs.update(index) { pair -> pair.startX = RewarpPointPair.snapToBlockCenter(v) } },
            { RewarpPointPairs.get(index).startY },
            { v -> RewarpPointPairs.update(index) { pair -> pair.startY = v } },
            { RewarpPointPairs.get(index).startZ },
            { v -> RewarpPointPairs.update(index) { pair -> pair.startZ = RewarpPointPair.snapToBlockCenter(v) } },
            { RewarpPointPairs.get(index).highlightStart },
            { v -> RewarpPointPairs.update(index) { pair -> pair.highlightStart = v } },
            { captureStart(index) })

        private fun buildRewarpEndSetting(index: Int) = PositionSetting("Rewarp End",
            { RewarpPointPairs.get(index).endX },
            { v -> RewarpPointPairs.update(index) { pair -> pair.endX = RewarpPointPair.snapToBlockCenter(v) } },
            { RewarpPointPairs.get(index).endY },
            { v -> RewarpPointPairs.update(index) { pair -> pair.endY = v } },
            { RewarpPointPairs.get(index).endZ },
            { v -> RewarpPointPairs.update(index) { pair -> pair.endZ = RewarpPointPair.snapToBlockCenter(v) } },
            { RewarpPointPairs.get(index).highlightEnd },
            { v -> RewarpPointPairs.update(index) { pair -> pair.highlightEnd = v } },
            { captureEnd(index) })

        private fun captureStart(index: Int) {
            val player = Minecraft.getInstance().player ?: return
            RewarpPointPairs.update(index) { pair ->
                pair.startX = RewarpPointPair.snapToBlockCenter(player.getX())
                pair.startY = player.getY()
                pair.startZ = RewarpPointPair.snapToBlockCenter(player.getZ())
                pair.startSet = true
            }
            val pair = RewarpPointPairs.get(index)
            NotificationManager.success(AetherLang.localize("Rewarp Start Set"),
                String.format("%s - X: %.1f, Y: %.1f, Z: %.1f", pair.displayName(), pair.startX, pair.startY, pair.startZ))
        }

        private fun captureEnd(index: Int) {
            val player = Minecraft.getInstance().player ?: return
            RewarpPointPairs.update(index) { pair ->
                pair.endX = RewarpPointPair.snapToBlockCenter(player.getX())
                pair.endY = player.getY()
                pair.endZ = RewarpPointPair.snapToBlockCenter(player.getZ())
                pair.endSet = true
            }
            val pair = RewarpPointPairs.get(index)
            NotificationManager.success(AetherLang.localize("Rewarp End Set"),
                String.format("%s - X: %.1f, Y: %.1f, Z: %.1f", pair.displayName(), pair.endX, pair.endY, pair.endZ))
        }
    }
}
