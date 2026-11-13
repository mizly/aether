package dev.aether.modules.failsafe

import java.util.Locale

enum class FailsafeAction {
    STOP,
    IGNORE,
    CUSTOM;

    companion object {
        @JvmStatic
        fun fromConfig(value: String?): FailsafeAction {
            if (value == null || value.isBlank()) {
                return STOP
            }

            try {
                return valueOf(value.trim().uppercase(Locale.ROOT))
            } catch (ignored: IllegalArgumentException) {
                return STOP
            }
        }
    }
}
