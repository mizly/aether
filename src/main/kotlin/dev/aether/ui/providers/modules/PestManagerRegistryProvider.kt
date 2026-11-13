package dev.aether.ui.providers.modules

import dev.aether.bootstrap.AetherKeybindRegistry
import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeSoundManager
import dev.aether.notification.NotificationManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractModulesRegistryProvider
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.KeybindSetting
import dev.aether.ui.settings.ListSetting
import dev.aether.ui.settings.MultiDropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.PositionSetting
import dev.aether.ui.settings.SectionSetting
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.TextSetting
import dev.aether.ui.settings.ToggleSetting
import dev.aether.util.AetherLang
import java.util.function.Supplier
import kotlin.collections.MutableList as KMutableList
import net.minecraft.client.Minecraft
import java.util.Arrays
import kotlin.collections.List as KList
import kotlin.collections.MutableList
import java.util.function.Consumer
import java.util.ArrayList
import java.util.List

class PestManagerRegistryProvider : AbstractModulesRegistryProvider(2) {
    override fun createSubTab(): ModulesTab.SubTab {
        val sprayMaterials: KList<String> = ArrayList<String>(FarmingSettingsFactory.sprayMaterials())
        val manualPestSoundOptions: MutableList<String> = ArrayList<String>(getSoundOptions())
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        groups.add(SettingGroup.of("Pest ESP", "Highlights and traces pests in the Garden",
            java.util.function.BooleanSupplier { AetherConfig.PEST_ESP_ENABLED.get() }, Consumer<Boolean> { value -> AetherConfig.PEST_ESP_ENABLED.set(value); AetherConfig.save() })
            .add(DropdownSetting("ESP Mode", ArrayList(java.util.Arrays.asList("Box", "Glow Outline")),
                Supplier { if ("GLOW".equals(AetherConfig.PEST_ESP_MODE.get(), ignoreCase = true)) 1 else 0 },
                Consumer<Int> { value -> AetherConfig.PEST_ESP_MODE.set(if (value == 1) "GLOW" else "BOX"); AetherConfig.save() })
                .visibleWhen { AetherConfig.PEST_ESP_HIGHLIGHT.get() })
            .add(ColorSetting("Highlight Color", { AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.get() },
                { value -> AetherConfig.PEST_ESP_HIGHLIGHT_COLOR.set(value); AetherConfig.save() })
                .visibleWhen { AetherConfig.PEST_ESP_HIGHLIGHT.get() })
            .add(ToggleSetting("Tracer", { AetherConfig.PEST_ESP_TRACER.get() },
                { value -> AetherConfig.PEST_ESP_TRACER.set(value); AetherConfig.save() }))
            .add(ColorSetting("Tracer Color", { AetherConfig.PEST_ESP_TRACER_COLOR.get() },
                { value -> AetherConfig.PEST_ESP_TRACER_COLOR.set(value); AetherConfig.save() })
                .visibleWhen { AetherConfig.PEST_ESP_TRACER.get() })
            .add(ToggleSetting("Optimized Route ESP", { AetherConfig.PEST_ESP_OPTIMIZED_ROUTE.get() },
                { value -> AetherConfig.PEST_ESP_OPTIMIZED_ROUTE.set(value); AetherConfig.save() }))
            .add(ColorSetting("Optimized Route Color", { AetherConfig.PEST_ESP_OPTIMIZED_ROUTE_COLOR.get() },
                { value -> AetherConfig.PEST_ESP_OPTIMIZED_ROUTE_COLOR.set(value); AetherConfig.save() })
                .visibleWhen { AetherConfig.PEST_ESP_OPTIMIZED_ROUTE.get() }))

        groups.add(SettingGroup.of("Pest Destroyer", "Cleans pests once past the threshold",
            java.util.function.BooleanSupplier { AetherConfig.TRIGGER_PEST_ON_CHAT.get() }, Consumer<Boolean> { v -> AetherConfig.TRIGGER_PEST_ON_CHAT.set(v); AetherConfig.save() })
            .add(SectionSetting("General", "When Pest Destroyer starts and how a run completes"))
            .add(SliderSetting("Pest Threshold", 1.0f, 8.0f, { AetherConfig.PEST_THRESHOLD.get().toFloat() },
                { v -> AetherConfig.PEST_THRESHOLD.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0))
            .add(FarmingSettingsFactory.pestDestroyerTriggerDelaySetting())
            .add(ToggleSetting("Walk Mode", { AetherConfig.PEST_DESTROYER_WALK_MODE.get() },
                { v -> AetherConfig.PEST_DESTROYER_WALK_MODE.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Use Pest Tracker Ability", { AetherConfig.USE_PEST_TRACKER_ABILITY.get() },
                { v -> AetherConfig.USE_PEST_TRACKER_ABILITY.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Draw Arc", { AetherConfig.PEST_TRACKER_DRAW_ARC.get() },
                { v -> AetherConfig.PEST_TRACKER_DRAW_ARC.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Estimate Pest Destroyer Completion", { AetherConfig.ESTIMATE_PEST_DESTROYER_COMPLETION.get() },
                { v -> AetherConfig.ESTIMATE_PEST_DESTROYER_COMPLETION.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Skip while Crop Fever Active", { AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() },
                { v -> AetherConfig.DELAY_PEST_FOR_CROP_FEVER.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Trigger Only After Rewarp", { AetherConfig.PEST_TRIGGER_ONLY_AFTER_REWARP.get() },
                { v -> AetherConfig.PEST_TRIGGER_ONLY_AFTER_REWARP.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Plot TP for Current Plot", { AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get() },
                { v -> AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.set(v); AetherConfig.save() }))
            .add(SectionSetting("Targeting", "How pests are selected and reserved during a run"))
            .add(ToggleSetting("Leave One Pest Alive", { AetherConfig.LEAVE_ONE_PEST_ALIVE.get() },
                { v -> AetherConfig.LEAVE_ONE_PEST_ALIVE.set(v); AetherConfig.save() }))
            .add(ListSetting("Leave One Pest Plots", "Add plot number", Supplier { java.util.ArrayList(AetherConfig.LEAVE_ONE_PEST_PLOTS.get()) },
                Consumer { v -> AetherConfig.LEAVE_ONE_PEST_PLOTS.set(java.util.ArrayList(v)); AetherConfig.save() })
                .visibleWhen { AetherConfig.LEAVE_ONE_PEST_ALIVE.get() })
            .add(ToggleSetting("Sunset Pests", { AetherConfig.SUNSET_PESTS.get() },
                { v -> AetherConfig.SUNSET_PESTS.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Change Time Directly Before Pest Spawn", { AetherConfig.SUNSET_PESTS_NIGHT_BEFORE_SPAWN.get() },
                { v -> AetherConfig.SUNSET_PESTS_NIGHT_BEFORE_SPAWN.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.SUNSET_PESTS.get() })
            .add(SectionSetting("Navigation", "Movement between pests and plot routing"))
            .add(ToggleSetting("AOTV Between Distant Pests", { AetherConfig.PEST_AOTV_BETWEEN.get() },
                { v -> AetherConfig.PEST_AOTV_BETWEEN.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Smart AOTV Routing", { AetherConfig.PEST_SMART_AOTV_ROUTING.get() },
                { v -> AetherConfig.PEST_SMART_AOTV_ROUTING.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.PEST_AOTV_BETWEEN.get() })
            .add(ToggleSetting("Etherwarp Directly Near Pests", { AetherConfig.PEST_ETHERWARP_TO_PEST.get() },
                { v -> AetherConfig.PEST_ETHERWARP_TO_PEST.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.PEST_AOTV_BETWEEN.get() })
            .add(FarmingSettingsFactory.pestEtherwarpMinDistanceSetting().visibleWhen { AetherConfig.PEST_AOTV_BETWEEN.get() && AetherConfig.PEST_ETHERWARP_TO_PEST.get() })
            .add(FarmingSettingsFactory.pestAotvStartDistanceSetting().visibleWhen { AetherConfig.PEST_AOTV_BETWEEN.get() && AetherConfig.PEST_SMART_AOTV_ROUTING.get() })
            .add(FarmingSettingsFactory.pestAotvStopDistanceSetting().visibleWhen { AetherConfig.PEST_AOTV_BETWEEN.get() && AetherConfig.PEST_SMART_AOTV_ROUTING.get() })
            .add(ToggleSetting("Confirm AOTV Between Pests", { AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get() },
                { v -> AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.PEST_AOTV_BETWEEN.get() })
            .add(FarmingSettingsFactory.aotvBetweenPestsDelaySetting().visibleWhen { AetherConfig.PEST_AOTV_BETWEEN.get() })
            .add(SectionSetting("Combat", "Aiming and vacuum engagement behavior"))
            .add(FarmingSettingsFactory.pestFovRangeSetting())
            .add(FarmingSettingsFactory.pestAboveAimPitchRangeSetting())
            .add(FarmingSettingsFactory.pestMaxTurnSpeedSetting())
            .add(FarmingSettingsFactory.pestNextTargetTurnSpeedSetting())
            .add(ToggleSetting("Respect Vacuum True Range", { AetherConfig.RESPECT_VACUUM_TRUE_RANGE.get() },
                { v -> AetherConfig.RESPECT_VACUUM_TRUE_RANGE.set(v); AetherConfig.save() }))
            .add(SliderSetting("Vacuum Follow Distance", 2.0f, 7.0f, { AetherConfig.PEST_VACUUM_FOLLOW_DISTANCE.get() },
                { v -> AetherConfig.PEST_VACUUM_FOLLOW_DISTANCE.set(v); AetherConfig.save() }).withDecimals(1).withSuffix(" blocks"))
            .add(SliderSetting("Pest Approach Speed", 0.15f, 0.8f, { AetherConfig.PEST_APPROACH_SPEED.get() },
                { v -> AetherConfig.PEST_APPROACH_SPEED.set(v); AetherConfig.save() }).withDecimals(2).withSuffix(" blocks/tick"))
            .add(SliderSetting("Pest Tracking Smoothing", 100.0f, 500.0f, { AetherConfig.PEST_TRACKING_SMOOTHING_MS.get() },
                { v -> AetherConfig.PEST_TRACKING_SMOOTHING_MS.set(v); AetherConfig.save() }).withDecimals(0).withSuffix("ms"))
            .add(FarmingSettingsFactory.pestAimDriftSetting()))

        groups.add(SettingGroup.of("Pest Hunting", "Lassos pests for guaranteed shards instead of vacuuming them (needs a lasso in your hotbar)",
            java.util.function.BooleanSupplier { AetherConfig.PEST_HUNTING.get() }, Consumer<Boolean> { v -> AetherConfig.PEST_HUNTING.set(v); AetherConfig.save() })
            .add(ToggleSetting("Vacuum Stun Before Lasso", { AetherConfig.PEST_HUNTING_VACUUM_STUN.get() },
                { v -> AetherConfig.PEST_HUNTING_VACUUM_STUN.set(v); AetherConfig.save() }))
            .add(MultiDropdownSetting("Vacuum Pest Blacklist", listOf("Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm", "Mite", "Moth", "Slug", "Beetle", "Firefly", "Dragonfly", "Praying Mantis"),
                Supplier { AetherConfig.PEST_HUNTING_VACUUM_PEST_MASK.get() },
                Consumer<Int> { v -> AetherConfig.PEST_HUNTING_VACUUM_PEST_MASK.set(v); AetherConfig.save() }))
            .add(SliderSetting("Follow Distance", 1.0f, 8.0f, { AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE.get() },
                { v -> AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE.set(v); AetherConfig.save() }).withDecimals(1))
            .add(SliderSetting("Max Leash Distance", 4.0f, 10.0f, { AetherConfig.PEST_HUNTING_MAX_DISTANCE.get() },
                { v -> AetherConfig.PEST_HUNTING_MAX_DISTANCE.set(v); AetherConfig.save() }).withDecimals(1))
            .add(SliderSetting("Hunting Tracking Smoothing", 75.0f, 300.0f, { AetherConfig.PEST_HUNTING_TRACKING_SMOOTHING_MS.get() },
                { value -> AetherConfig.PEST_HUNTING_TRACKING_SMOOTHING_MS.set(value); AetherConfig.save() }).withDecimals(0).withSuffix("ms"))
            .add(SliderSetting("Hunting Max Turn Speed", 180.0f, 900.0f, { AetherConfig.PEST_HUNTING_MAX_TURN_SPEED.get() },
                { value -> AetherConfig.PEST_HUNTING_MAX_TURN_SPEED.set(value); AetherConfig.save() }).withDecimals(0).withSuffix("°/s"))
            .add(SliderSetting("Max Lasso Throws", 1.0f, 15.0f, { AetherConfig.PEST_HUNTING_MAX_THROWS.get().toFloat() },
                { v -> AetherConfig.PEST_HUNTING_MAX_THROWS.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0))
            .add(SliderSetting("Catch Timeout", 10000.0f, 120000.0f, { AetherConfig.PEST_HUNTING_TIMEOUT_MS.get().toFloat() },
                { v -> AetherConfig.PEST_HUNTING_TIMEOUT_MS.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0).withSuffix("ms")))

        groups.add(SettingGroup.of("Ballsack Shredder", "Uses a dedicated AOTV sequence before pest cleaning",
            java.util.function.BooleanSupplier { AetherConfig.BALLSACK_SHREDDER.get() }, Consumer<Boolean> { v -> AetherConfig.BALLSACK_SHREDDER.set(v); AetherConfig.save() })
            .add(ListSetting("Ballsack Shredder Plots", "Add plot number", Supplier { java.util.ArrayList(AetherConfig.BALLSACK_SHREDDER_PLOTS.get()) },
                Consumer { v -> AetherConfig.BALLSACK_SHREDDER_PLOTS.set(java.util.ArrayList(v)); AetherConfig.save() }).visibleWhen { AetherConfig.BALLSACK_SHREDDER.get() })
            .add(SliderSetting("AOTV Warps", 1.0f, 5.0f, { AetherConfig.BALLSACK_WARPS.get().toFloat() },
                { v -> AetherConfig.BALLSACK_WARPS.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0))
            .add(FarmingSettingsFactory.ballsackShredderTriggerDelaySetting())
            .add(ToggleSetting("Look Down After Warps", { AetherConfig.BALLSACK_LOOK_DOWN.get() },
                { v -> AetherConfig.BALLSACK_LOOK_DOWN.set(v); AetherConfig.save() }))
            .add(SliderSetting("Look Down Time", 0.0f, 3000.0f, { AetherConfig.BALLSACK_LOOK_DOWN_TIME_MS.get().toFloat() },
                { v -> AetherConfig.BALLSACK_LOOK_DOWN_TIME_MS.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0).withSuffix("ms").visibleWhen { AetherConfig.BALLSACK_LOOK_DOWN.get() }))

        groups.add(SettingGroup.of("AOTV to Roof", "Teleports to the roof before cleaning pests on selected plots",
            java.util.function.BooleanSupplier { AetherConfig.AOTV_TO_ROOF.get() }, Consumer<Boolean> { v -> AetherConfig.AOTV_TO_ROOF.set(v); AetherConfig.save() })
            .add(ListSetting("AOTV Roof Plots", "Add plot number", Supplier { java.util.ArrayList(AetherConfig.AOTV_ROOF_PLOTS.get()) },
                Consumer { v -> AetherConfig.AOTV_ROOF_PLOTS.set(java.util.ArrayList(v)); AetherConfig.save() }))
            .add(FarmingSettingsFactory.aotvToRoofPitchSetting())
            .add(ToggleSetting("Break Blocks Before AOTV", { AetherConfig.BREAK_BLOCKS_BEFORE_AOTV.get() },
                { v -> AetherConfig.BREAK_BLOCKS_BEFORE_AOTV.set(v); AetherConfig.save() })))

        groups.add(SettingGroup.of("On-The-Track Pest", "Pauses farming briefly to vacuum pests already within reach",
            java.util.function.BooleanSupplier { AetherConfig.PEST_ON_TRACK_ENABLED.get() }, Consumer<Boolean> { v -> AetherConfig.PEST_ON_TRACK_ENABLED.set(v); AetherConfig.save() })
            .add(SliderSetting("Pest Detection FOV", 1.0f, 360.0f, { AetherConfig.PEST_ON_THE_TRACK_FOV.get().toFloat() },
                { v -> AetherConfig.PEST_ON_THE_TRACK_FOV.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0).withSuffix("\u00B0"))
            .add(SliderSetting("Pest Detection Delay Time", 0.0f, 3500.0f, { AetherConfig.PEST_ON_THE_TRACK_ACQUIRE_DELAY_MS.get().toFloat() },
                { v -> AetherConfig.PEST_ON_THE_TRACK_ACQUIRE_DELAY_MS.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0).withSuffix("ms"))
            .add(SliderSetting("Stuck Timeout", 4000.0f, 25000.0f, { AetherConfig.PEST_ON_THE_TRACK_STUCK_TIMEOUT_MS.get().toFloat() },
                { v -> AetherConfig.PEST_ON_THE_TRACK_STUCK_TIMEOUT_MS.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0).withSuffix("ms"))
            .add(ToggleSetting("Skip during Jacob's Contests", { AetherConfig.PEST_ON_THE_TRACK_SKIP_JACOB.get() },
                { v -> AetherConfig.PEST_ON_THE_TRACK_SKIP_JACOB.set(v); AetherConfig.save() })))

        groups.add(SettingGroup.of("Manual Pest Killing", "Tabs you in and pauses when pests spawn so you can kill them by hand, then warps to garden and restarts (overrides Pest Destroyer)",
            java.util.function.BooleanSupplier { AetherConfig.MANUAL_PEST_MODE.get() }, Consumer<Boolean> { v -> AetherConfig.MANUAL_PEST_MODE.set(v); AetherConfig.save() })
            .add(ToggleSetting("Switch to Vacuum When Start", { AetherConfig.VACCUM_WHEN_START.get() },
                { v -> AetherConfig.VACCUM_WHEN_START.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.MANUAL_PEST_MODE.get() })
            .add(ToggleSetting("Auto Alt-Tab", { AetherConfig.FAILSAFE_AUTO_ALT_TAB.get() },
                { v -> AetherConfig.FAILSAFE_AUTO_ALT_TAB.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.MANUAL_PEST_MODE.get() })
            .add(DropdownSetting("Manual Pest Sound", manualPestSoundOptions,
                { getSoundIndex(manualPestSoundOptions, AetherConfig.MANUAL_PEST_SOUND_FILE.get()) },
                { i ->
                    if (i < 0 || i >= manualPestSoundOptions.size) return@DropdownSetting
                    AetherConfig.MANUAL_PEST_SOUND_FILE.set(manualPestSoundOptions[i]); AetherConfig.save()
                })
                .addIconAction("/assets/aether/icons/folder.svg", Runnable { FailsafeSoundManager.openSoundFolder() })
                .addIconAction("/assets/aether/icons/refresh.svg", Runnable { refreshSoundOptions(manualPestSoundOptions) }))
            .add(KeybindSetting("Manual Pest Early Finish", AetherKeybindRegistry.getManualPestEarlyFinishKey())))

        groups.add(SettingGroup.of("Pest Traps", "Clears and refills pest traps",
            java.util.function.BooleanSupplier { AetherConfig.ENABLE_PEST_TRAPS.get() }, Consumer<Boolean> { v -> AetherConfig.ENABLE_PEST_TRAPS.set(v); AetherConfig.save() })
            .add(ToggleSetting("Clear Pest Traps", { AetherConfig.AUTO_CLEAR_PEST_TRAPS.get() },
                { v -> AetherConfig.AUTO_CLEAR_PEST_TRAPS.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Pre-equip Mosquito for Pest Traps", { AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.get() },
                { v -> AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.AUTO_CLEAR_PEST_TRAPS.get() })
            .add(ToggleSetting("Equip Pet After Trap Open", { AetherConfig.AUTO_PET_AFTER_TRAP_OPEN.get() },
                { v -> AetherConfig.AUTO_PET_AFTER_TRAP_OPEN.set(v); AetherConfig.save() }).visibleWhen { AetherConfig.AUTO_CLEAR_PEST_TRAPS.get() })
            .add(TextSetting("Trap Open Pet", "e.g Rose Dragon", { AetherConfig.AUTO_PET_AFTER_TRAP_OPEN_PET.get() },
                { v -> AetherConfig.AUTO_PET_AFTER_TRAP_OPEN_PET.set(v); AetherConfig.save() })
                .visibleWhen { AetherConfig.AUTO_CLEAR_PEST_TRAPS.get() && AetherConfig.AUTO_PET_AFTER_TRAP_OPEN.get() })
            .add(ToggleSetting("Refill Pest Traps", { AetherConfig.AUTO_REFILL_PEST_TRAPS.get() },
                { v -> AetherConfig.AUTO_REFILL_PEST_TRAPS.set(v); AetherConfig.save() }))
            .add(DropdownSetting("Bait Material", java.util.ArrayList(sprayMaterials),
                {
                    val current = AetherConfig.PEST_TRAPS_BAIT_MATERIAL.get()
                    val idx = sprayMaterials.indexOf(current)
                    if (idx >= 0) idx else 5
                },
                { i ->
                    if (i >= 0 && i < sprayMaterials.size) {
                        AetherConfig.PEST_TRAPS_BAIT_MATERIAL.set(sprayMaterials[i]); AetherConfig.save()
                    }
                }).visibleWhen { AetherConfig.AUTO_REFILL_PEST_TRAPS.get() })
            .add(SliderSetting("Bait Amount", 1.0f, 64.0f, { AetherConfig.PEST_TRAPS_BAIT_AMOUNT.get().toFloat() },
                { v -> AetherConfig.PEST_TRAPS_BAIT_AMOUNT.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0)
                .visibleWhen { AetherConfig.AUTO_REFILL_PEST_TRAPS.get() })
            .add(TextSetting("Pest Traps Plot", "Plot number (e.g. 5)", { AetherConfig.PEST_TRAPS_PLOT.get() },
                { v -> AetherConfig.PEST_TRAPS_PLOT.set(v); AetherConfig.save() })
                .visibleWhen { AetherConfig.AUTO_CLEAR_PEST_TRAPS.get() || AetherConfig.AUTO_REFILL_PEST_TRAPS.get() })
            .add(ToggleSetting("Pathfind to Traps", { AetherConfig.PEST_TRAPS_PATHFIND.get() },
                { v -> AetherConfig.PEST_TRAPS_PATHFIND.set(v); AetherConfig.save() })
                .visibleWhen { AetherConfig.AUTO_CLEAR_PEST_TRAPS.get() || AetherConfig.AUTO_REFILL_PEST_TRAPS.get() })
            .add(PositionSetting("Traps Position",
                { AetherConfig.PEST_TRAPS_X.get().toDouble() }, { v -> AetherConfig.PEST_TRAPS_X.set(Math.round(v).toInt()); AetherConfig.save() },
                { AetherConfig.PEST_TRAPS_Y.get().toDouble() }, { v -> AetherConfig.PEST_TRAPS_Y.set(Math.round(v).toInt()); AetherConfig.save() },
                { AetherConfig.PEST_TRAPS_Z.get().toDouble() }, { v -> AetherConfig.PEST_TRAPS_Z.set(Math.round(v).toInt()); AetherConfig.save() },
                { AetherConfig.PEST_TRAPS_HIGHLIGHT.get() }, { v -> AetherConfig.PEST_TRAPS_HIGHLIGHT.set(v); AetherConfig.save() },
                {
                    val player = Minecraft.getInstance().player
                    if (player != null) {
                        AetherConfig.PEST_TRAPS_X.set(player.blockX)
                        AetherConfig.PEST_TRAPS_Y.set(player.blockY)
                        AetherConfig.PEST_TRAPS_Z.set(player.blockZ)
                        AetherConfig.save()
                        NotificationManager.success("Pest Traps Position Set",
                            String.format("X: %d, Y: %d, Z: %d", AetherConfig.PEST_TRAPS_X.get(), AetherConfig.PEST_TRAPS_Y.get(), AetherConfig.PEST_TRAPS_Z.get()))
                    }
                }).visibleWhen { AetherConfig.PEST_TRAPS_PATHFIND.get() && (AetherConfig.AUTO_CLEAR_PEST_TRAPS.get() || AetherConfig.AUTO_REFILL_PEST_TRAPS.get()) }))

        return MainGUIRegistry.toggleSubTab("Pest Manager", "Automatically cleans pests, and manage your pest traps",
            java.util.function.BooleanSupplier { AetherConfig.TRIGGER_PEST_ON_CHAT.get() }, Consumer<Boolean> { v -> AetherConfig.TRIGGER_PEST_ON_CHAT.set(v); AetherConfig.save() }, groups)
    }

    private fun getSoundOptions(): KList<String> {
        val sounds: KMutableList<String> = ArrayList<String>(FailsafeSoundManager.getAvailableSounds())
        if (sounds.isEmpty()) sounds.add(FailsafeSoundManager.getDefaultSoundFileName())
        return sounds
    }

    private fun refreshSoundOptions(options: KMutableList<String>) {
        options.clear()
        options.addAll(getSoundOptions())
    }

    private fun getSoundIndex(options: KList<String>, selected: String): Int {
        val selectedIndex = options.indexOf(selected)
        if (selectedIndex >= 0) return selectedIndex
        val defaultIndex = options.indexOf(FailsafeSoundManager.getDefaultSoundFileName())
        return if (defaultIndex >= 0) defaultIndex else 0
    }
}
