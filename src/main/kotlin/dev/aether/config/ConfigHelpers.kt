package dev.aether.config

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.util.ClientUtils
import java.util.concurrent.ThreadLocalRandom

class ConfigHelpers private constructor() {
    companion object {
        @JvmStatic fun getRandomizedDelay(baseDelay: Int): Int {
            val jitter = Math.round(Math.abs(baseDelay) * 0.15f)
            return getRandomizedDelay(baseDelay - jitter, baseDelay + jitter)
        }

        @JvmStatic fun getRandomizedDelay(minDelay: Int, maxDelay: Int): Int {
            val lower = Math.max(0, Math.min(minDelay, maxDelay))
            val upper = Math.max(0, Math.max(minDelay, maxDelay))
            if (lower == upper) return lower
            return ThreadLocalRandom.current().nextInt(lower, upper + 1)
        }

        @JvmStatic fun executePlotTpRewarp() {
            if (AetherConfig.ENABLE_PLOT_TP_REWARP.get()) executePlotTpRewarp(AetherConfig.PLOT_TP_NUMBER.get())
        }

        @JvmStatic fun executePlotTpRewarp(plotNumber: String) { executeRewarpCommand(RewarpMode.PLOT_TP, plotNumber) }

        @JvmStatic fun executeRewarpCommand(mode: RewarpMode?, plotNumber: String?) {
            val resolvedMode = mode ?: RewarpMode.FLY
            if (!resolvedMode.usesCommand()) return
            val sanitizedPlotNumber = if (plotNumber == null || plotNumber.isBlank()) "0" else plotNumber.trim()
            AetherBootstrapHooks.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toLong())
            if (resolvedMode == RewarpMode.WARP_GARDEN) ClientUtils.sendCommand("/warp garden")
            else ClientUtils.sendCommand("/plottp $sanitizedPlotNumber")
        }

        @JvmStatic fun getUnflyMode(): UnflyMode {
            try { return UnflyMode.valueOf(AetherConfig.UNFLY_MODE.get()) }
            catch (_: IllegalArgumentException) { return UnflyMode.DOUBLE_TAP_SPACE }
        }

        @JvmStatic fun getFreelookMode(): FreelookMode {
            try { return FreelookMode.valueOf(AetherConfig.FREELOOK_MODE.get()) }
            catch (_: IllegalArgumentException) { return FreelookMode.HOLD }
        }
    }
}
