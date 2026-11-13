package dev.aether.ui.providers.failsafes

import dev.aether.config.AetherConfig
import java.util.ArrayList
import dev.aether.config.entries.StringEntry
import dev.aether.modules.failsafe.FailsafeSoundManager
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractFailsafesRegistryProvider
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.ToggleSetting

class GeneralFailsafesRegistryProvider : AbstractFailsafesRegistryProvider(0) {
    companion object {
        private const val SAME_AS_DEFAULT = "Same as Default"
    }

    override fun createSubTab(): ModulesTab.SubTab {
        val defaultOptions: kotlin.collections.List<String> = ArrayList<String>(getFailsafeSoundOptions())
        val stopOptions = withDefaultSentinel(defaultOptions)
        val ignoreOptions = withDefaultSentinel(defaultOptions)

        val group = SettingGroup.alwaysOn("Failsafe Settings", "Shared settings for failsafes")
            .add(ToggleSetting("Play Failsafe Sound",
                { AetherConfig.FAILSAFE_SOUND_ENABLED.get() },
                { v -> AetherConfig.FAILSAFE_SOUND_ENABLED.set(v); AetherConfig.save() }))
            .add(SliderSetting("Failsafe Volume", 0.0f, 100.0f,
                { AetherConfig.FAILSAFE_SOUND_VOLUME.get() * 100.0f },
                { v -> AetherConfig.FAILSAFE_SOUND_VOLUME.set(clamp01(v / 100.0f)); AetherConfig.save() })
                .withDecimals(0).withSuffix("%")
                .visibleWhen { AetherConfig.FAILSAFE_SOUND_ENABLED.get() })
            .add(ToggleSetting("Desktop Notification",
                { AetherConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get() },
                { v -> AetherConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Auto Alt-Tab",
                { AetherConfig.FAILSAFE_AUTO_ALT_TAB.get() },
                { v -> AetherConfig.FAILSAFE_AUTO_ALT_TAB.set(v); AetherConfig.save() }))
            .add(ToggleSetting("Colour Flash on Failsafe Trigger",
                { AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get() },
                { v -> AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.set(v); AetherConfig.save() }))
            .add(ColorSetting("First Flash Colour",
                { AetherConfig.FAILSAFE_COLOUR_FLASH_FIRST.get() },
                { v -> AetherConfig.FAILSAFE_COLOUR_FLASH_FIRST.set(v); AetherConfig.save() })
                .visibleWhen { AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get() })
            .add(ColorSetting("Second Flash Colour",
                { AetherConfig.FAILSAFE_COLOUR_FLASH_SECOND.get() },
                { v -> AetherConfig.FAILSAFE_COLOUR_FLASH_SECOND.set(v); AetherConfig.save() })
                .visibleWhen { AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get() })
            .add(SliderSetting("Flash Opacity", 0.0f, 100.0f,
                { AetherConfig.FAILSAFE_COLOUR_FLASH_OPACITY.get() * 100.0f },
                { v -> AetherConfig.FAILSAFE_COLOUR_FLASH_OPACITY.set(clamp01(v / 100.0f)); AetherConfig.save() })
                .withDecimals(0).withSuffix("%")
                .visibleWhen { AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get() })
            .add(SliderSetting("Colour Swap Delay", 0.1f, 5.0f,
                { AetherConfig.FAILSAFE_COLOUR_FLASH_SWAP_DELAY_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_COLOUR_FLASH_SWAP_DELAY_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s")
                .visibleWhen { AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get() })
            .add(DropdownSetting("Default Failsafe Sound", defaultOptions,
                { getFailsafeSoundIndex(defaultOptions) },
                { i ->
                    if (i < 0 || i >= defaultOptions.size) return@DropdownSetting
                    AetherConfig.FAILSAFE_SOUND_FILE.set(defaultOptions[i])
                    AetherConfig.save()
                })
                .addIconAction("/assets/aether/icons/folder.svg", Runnable { FailsafeSoundManager.openSoundFolder() })
                .addIconAction("/assets/aether/icons/refresh.svg", Runnable {
                    FailsafeSoundManager.refresh()
                    refreshFailsafeSoundOptions(ArrayList(defaultOptions))
                    refreshOverrideOptions(ArrayList(stopOptions), defaultOptions)
                    refreshOverrideOptions(ArrayList(ignoreOptions), defaultOptions)
                })
                .visibleWhen { AetherConfig.FAILSAFE_SOUND_ENABLED.get() })
            .add(DropdownSetting("Stop Failsafe Sound", stopOptions,
                { getOverrideIndex(stopOptions, AetherConfig.FAILSAFE_SOUND_FILE_STOP) },
                { i -> setOverride(stopOptions, i, AetherConfig.FAILSAFE_SOUND_FILE_STOP) })
                .visibleWhen { AetherConfig.FAILSAFE_SOUND_ENABLED.get() })
            .add(DropdownSetting("Ignore Failsafe Sound", ignoreOptions,
                { getOverrideIndex(ignoreOptions, AetherConfig.FAILSAFE_SOUND_FILE_IGNORE) },
                { i -> setOverride(ignoreOptions, i, AetherConfig.FAILSAFE_SOUND_FILE_IGNORE) })
                .visibleWhen { AetherConfig.FAILSAFE_SOUND_ENABLED.get() })
            .add(SliderSetting("Failsafe Additional Random Delay", 0.0f, 5.0f,
                { AetherConfig.FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS.get() },
                { v -> AetherConfig.FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS.set(v); AetherConfig.save() })
                .withDecimals(1).withSuffix("s"))

        return MainGUIRegistry.subTab("General", "Shared settings for failsafes", listOf(group))
    }

    private fun clamp01(value: Float): Float = Math.max(0.0f, Math.min(1.0f, value))

    private fun getFailsafeSoundOptions(): kotlin.collections.List<String> {
        val sounds: kotlin.collections.MutableList<String> = ArrayList(FailsafeSoundManager.getAvailableSounds())
        if (sounds.isEmpty()) sounds.add(FailsafeSoundManager.getDefaultSoundFileName())
        return sounds
    }

    private fun withDefaultSentinel(base: kotlin.collections.List<String>): kotlin.collections.List<String> {
        val options: kotlin.collections.MutableList<String> = ArrayList<String>(base.size + 1)
        options.add(SAME_AS_DEFAULT)
        options.addAll(base)
        return options
    }

    private fun refreshFailsafeSoundOptions(options: kotlin.collections.MutableList<String>) {
        options.clear()
        options.addAll(getFailsafeSoundOptions())
    }

    private fun refreshOverrideOptions(overrideOptions: kotlin.collections.MutableList<String>, base: kotlin.collections.List<String>) {
        overrideOptions.clear()
        overrideOptions.add(SAME_AS_DEFAULT)
        for (option in base) overrideOptions.add(option)
    }

    private fun getFailsafeSoundIndex(options: kotlin.collections.List<String>): Int {
        val selected = AetherConfig.FAILSAFE_SOUND_FILE.get()
        val selectedIndex = options.indexOf(selected)
        if (selectedIndex >= 0) return selectedIndex
        val defaultIndex = options.indexOf(FailsafeSoundManager.getDefaultSoundFileName())
        return if (defaultIndex >= 0) defaultIndex else 0
    }

    // index into the sentinel-prefixed list; 0 when the override is blank
    private fun getOverrideIndex(options: kotlin.collections.List<String>, entry: StringEntry): Int {
        val selected = entry.get()
        if (selected == null || selected.isBlank()) return 0
        val index = options.indexOf(selected)
        return if (index >= 0) index else 0
    }

    private fun setOverride(options: kotlin.collections.List<String>, index: Int, entry: StringEntry) {
        if (index <= 0 || index >= options.size) {
            entry.set("")
        } else {
            entry.set(options[index])
        }
        AetherConfig.save()
    }
}
