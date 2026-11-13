package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.renderer.FailsafeColourFlashRenderer

object FailsafeColourFlashManager {
    @Volatile private var triggeredAtNanos = Long.MIN_VALUE
    @Volatile private var active = false

    @JvmStatic
    fun trigger() {
        if (AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get()) {
            triggeredAtNanos = System.nanoTime()
            active = true
        }
    }

    @JvmStatic
    fun dismiss() {
        active = false
    }

    @JvmStatic
    fun render() {
        if (!AetherConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get()) {
            active = false
            return
        }
        if (!active) return

        val elapsedNanos = maxOf(0L, System.nanoTime() - triggeredAtNanos)
        val swapDelayNanos = getSwapDelayNanos()
        val useSecondColour = (elapsedNanos / swapDelayNanos and 1L) != 0L
        val colour = if (useSecondColour) AetherConfig.FAILSAFE_COLOUR_FLASH_SECOND.get()
        else AetherConfig.FAILSAFE_COLOUR_FLASH_FIRST.get()
        FailsafeColourFlashRenderer.render(colour, AetherConfig.FAILSAFE_COLOUR_FLASH_OPACITY.get())
    }

    private fun getSwapDelayNanos(): Long {
        val delaySeconds = AetherConfig.FAILSAFE_COLOUR_FLASH_SWAP_DELAY_SECONDS.get()
        return maxOf(1L, Math.round(delaySeconds * 1_000_000_000.0))
    }
}
