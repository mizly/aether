package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import net.minecraft.client.Minecraft

// watches the tab-list bonus line and runs the phillip exchange when it reads INACTIVE while farming
class AutoPestExchangeManager private constructor() {
    companion object {
        private const val RUN_COOLDOWN_MS = 30_000L
        // a finished exchange is remembered this long while the tab still claims INACTIVE
        private const val EXCHANGE_MEMORY_MS = 600_000L

        @Volatile private var running = false
        @Volatile private var bonusInactiveSinceMs = 0L
        @Volatile private var lastRunMs = 0L
        @Volatile private var pendingTriggerReadyAtMs = 0L
        @Volatile private var exchangeRememberedAtMs = 0L
        @Volatile private var sawBonusInactive = false
        @Volatile private var pendingTrigger = false

        @JvmStatic fun isRunning(): Boolean = running

        @JvmStatic
        fun getBonusInactiveElapsedMs(): Long {
            if (!PestBonusManager.isBonusInactive() || bonusInactiveSinceMs == 0L) return 0L
            return Math.max(0L, System.currentTimeMillis() - bonusInactiveSinceMs)
        }

        @JvmStatic
        fun getRunCooldownRemainingMs(): Long =
            Math.max(0L, RUN_COOLDOWN_MS - (System.currentTimeMillis() - lastRunMs))

        @JvmStatic
        fun getExchangeMemoryRemainingMs(): Long {
            val now = System.currentTimeMillis()
            return if (isExchangeRemembered(now)) EXCHANGE_MEMORY_MS - (now - exchangeRememberedAtMs) else 0L
        }

        @JvmStatic
        fun reset() {
            running = false
            bonusInactiveSinceMs = 0L
            lastRunMs = 0L
            pendingTriggerReadyAtMs = 0L
            exchangeRememberedAtMs = 0L
            sawBonusInactive = false
            pendingTrigger = false
        }

        @JvmStatic
        fun rememberExchange() {
            exchangeRememberedAtMs = if (running) lastRunMs else System.currentTimeMillis()
        }

        @JvmStatic
        fun isExchangePending(): Boolean {
            if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) return false
            if (running || PestExchangeManager.isExchanging()) return true
            return pendingTrigger && PestBonusManager.isBonusInactive() && !isExchangeRemembered(System.currentTimeMillis())
        }

        @JvmStatic
        fun shouldBlockFarmingResume(): Boolean {
            if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) return false
            if (running || PestExchangeManager.isExchanging()) return true
            if (!PestBonusManager.isBonusInactive() || !pendingTrigger) return false
            val now = System.currentTimeMillis()
            return !isExchangeRemembered(now) && isPendingTriggerReady(now)
        }

        @JvmStatic
        fun update() {
            val client = Minecraft.getInstance()
            if (client.player == null || client.connection == null) return
            if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) {
                clearPendingTrigger()
                exchangeRememberedAtMs = 0L
                return
            }

            val now = System.currentTimeMillis()
            if (isExchangeRemembered(now)) {
                clearPendingTrigger()
                return
            }
            exchangeRememberedAtMs = 0L

            if (!PestBonusManager.isBonusInactive()) {
                clearPendingTrigger()
                return
            }

            if (bonusInactiveSinceMs == 0L) bonusInactiveSinceMs = now
            if (!sawBonusInactive) {
                sawBonusInactive = true
                pendingTrigger = true
                pendingTriggerReadyAtMs = now + ConfigHelpers.getRandomizedDelay(
                    AetherConfig.PEST_EXCHANGE_DELAY_MIN.get(),
                    AetherConfig.PEST_EXCHANGE_DELAY_MAX.get()
                )
            }
            tryTriggerPending(client, now)
        }

        @JvmStatic
        fun tryTriggerPending(client: Minecraft): Boolean = tryTriggerPending(client, System.currentTimeMillis())

        private fun tryTriggerPending(client: Minecraft, now: Long): Boolean {
            if (client.player == null || client.connection == null) return false
            if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) return false
            if (running || PestExchangeManager.isExchanging()) return true
            if (isExchangeRemembered(now)) return false
            if (PestManager.isCleaningInProgress()) return false

            val state = MacroStateManager.getCurrentState()
            val stateAllowsPriority = state == MacroState.State.FARMING || state == MacroState.State.WARDROBE
            if (!stateAllowsPriority || !pendingTrigger || !isPendingTriggerReady(now)) return false

            if (LoadoutManager.isSwappingLoadout) LoadoutManager.abortSwapForPriorityTask(client, "pest exchange")
            MacroStateManager.setCurrentState(MacroState.State.CLEANING)
            PestManager.setCleaningInProgress(true)
            running = true
            lastRunMs = now
            val worker = MacroWorkerThread.getInstance()
            if (worker.isBusy()) {
                worker.replaceCurrent("AutoPestExchange") { runSequence(client) }
                ClientUtils.sendDebugMessage("AutoPestExchange: replaced queued worker tasks for priority.")
            } else {
                worker.submit("AutoPestExchange") { runSequence(client) }
            }
            return true
        }

        private fun runSequence(client: Minecraft) {
            val guiDelay = Math.max(50L, ClientUtils.getGuiClickDelayMs(false))
            var canResumeFarming = true
            try {
                if (MacroWorkerThread.shouldAbortTask(client)) return

                MacroStateManager.setCurrentState(MacroState.State.CLEANING)
                PestManager.setCleaningInProgress(true)

                msg(client, "\u00A7eBonus inactive detected. Running pest exchange...")
                client.execute { FarmingMacroManager.disable(client) }
                MacroWorkerThread.sleep(guiDelay)

                if (MacroWorkerThread.shouldAbortTask(client)) return

                if (!AetherConfig.AUTO_PEST_USE_ABIPHONE.get()) {
                    if (!CommandUtils.setSpawn()) {
                        msg(client, "\u00A7c/setspawn failed before pest exchange. Returning to farm.")
                        return
                    }
                }

                if (MacroWorkerThread.shouldAbortTask(client)) return

                if (PestExchangeManager.runExchangeBlocking(client)) rememberExchange()

                if (MacroWorkerThread.shouldAbortTask(client)) return

                if (!AetherConfig.AUTO_PEST_USE_ABIPHONE.get()) {
                    if (!CommandUtils.warpGarden()) {
                        msg(client, "\u00A7c/warp garden failed after pest exchange.")
                        canResumeFarming = false
                        return
                    }
                }
                MacroWorkerThread.sleep(guiDelay)
            } catch (e: Exception) {
                e.printStackTrace()
                msg(client, "\u00A7cAuto pest exchange error: " + e.message)
            } finally {
                running = false
                if (MacroStateManager.isMacroRunning() && canResumeFarming) {
                    SqueakyMousematManager.armReapplyAttempt()
                    client.execute {
                        FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig())
                    }
                    MacroStateManager.setCurrentState(MacroState.State.FARMING)
                } else if (MacroStateManager.isMacroRunning() && !canResumeFarming) {
                    msg(client, "\u00A7cAuto pest exchange did not resume farming because garden warp failed.")
                }
                PestManager.setCleaningInProgress(false)
                if (canResumeFarming && MacroStateManager.isMacroRunning()) {
                    msg(client, "\u00A7aAuto pest exchange finished. Resuming farming.")
                }
            }
        }

        private fun isExchangeRemembered(now: Long): Boolean {
            if (exchangeRememberedAtMs == 0L) return false
            if (PestBonusManager.hasSeenBonusActiveSince(exchangeRememberedAtMs)) return false
            return now - exchangeRememberedAtMs < EXCHANGE_MEMORY_MS
        }

        private fun clearPendingTrigger() {
            bonusInactiveSinceMs = 0L
            pendingTriggerReadyAtMs = 0L
            sawBonusInactive = false
            pendingTrigger = false
        }

        private fun isPendingTriggerReady(now: Long): Boolean =
            pendingTriggerReadyAtMs > 0L && now >= pendingTriggerReadyAtMs && now - lastRunMs >= RUN_COOLDOWN_MS

        private fun msg(client: Minecraft, text: String) {
            ClientUtils.sendMessage(text)
        }
    }
}
