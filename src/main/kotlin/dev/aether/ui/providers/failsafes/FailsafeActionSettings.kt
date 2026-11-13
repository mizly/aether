package dev.aether.ui.providers.failsafes

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeCustomReplayManager
import dev.aether.ui.settings.DropdownSetting
import java.util.ArrayList
import java.util.Locale
import java.util.function.Consumer
import java.util.function.Supplier

internal class FailsafeActionSettings private constructor() {
    companion object {
        private val ACTION_OPTIONS: List<String> = listOf("Stop", "Ignore", "Custom")

        @JvmStatic
        fun createActionDropdown(label: String, getter: Supplier<String>, setter: Consumer<String>): DropdownSetting {
            return DropdownSetting(label, ACTION_OPTIONS,
                { getActionIndex(getter.get()) },
                { index ->
                    if (index < 0 || index >= ACTION_OPTIONS.size) return@DropdownSetting
                    setter.accept(ACTION_OPTIONS[index].uppercase(Locale.ROOT))
                    AetherConfig.save()
                })
        }

        @JvmStatic
        fun createCustomReplayDropdown(
            type: FailsafeCustomReplayManager.FailsafeReplayType,
            actionGetter: Supplier<String>
        ): DropdownSetting {
            val replayOptions = ArrayList(FailsafeCustomReplayManager.getAvailableReplayOptions(type))
            return DropdownSetting("Custom Replay (/aether movement or aether.cat/editor)", replayOptions,
                { FailsafeCustomReplayManager.getSelectedReplayIndex(type, replayOptions) },
                { index -> FailsafeCustomReplayManager.setSelectedReplay(type, replayOptions, index) })
                .addIconAction("/assets/aether/icons/folder.svg", Runnable { FailsafeCustomReplayManager.openReplayFolder(type) })
                .addIconAction("/assets/aether/icons/refresh.svg", Runnable { FailsafeCustomReplayManager.refreshReplayOptions(type, replayOptions) })
                .visibleWhen { "CUSTOM".equals(actionGetter.get(), ignoreCase = true) }
        }

        private fun getActionIndex(selected: String?): Int {
            if (selected.isNullOrBlank()) return 0
            val normalized = selected.substring(0, 1).uppercase(Locale.ROOT) + selected.substring(1).lowercase(Locale.ROOT)
            val index = ACTION_OPTIONS.indexOf(normalized)
            return if (index >= 0) index else 0
        }
    }
}
