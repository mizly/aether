package dev.aether.modules.session

import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroState
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.ReconnectScheduler
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.PestPrepSwapManager
import dev.aether.modules.pest.helpers.PestReturnManager
import dev.aether.ui.DynamicRestScreen
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import java.util.concurrent.ThreadLocalRandom
import net.minecraft.client.Minecraft

class RestartManager {
    companion object {
        private const val RESTART_COMMAND_GAP_MS = 2000L
        private const val RESTART_MIN_DELAY_SECONDS = 10L
        private const val RESTART_MAX_DELAY_SECONDS = 20L
        private const val JACOB_CONTEST_RESTART_MIN_DELAY_SECONDS = 0L
        private const val JACOB_CONTEST_RESTART_MAX_DELAY_SECONDS = 5L
        private const val PROXY_RESTART_MIN_DELAY_SECONDS = 15L
        private const val PROXY_RESTART_MAX_DELAY_SECONDS = 30L

        private var isRestartPending: Boolean = false
        private var restartExecutionTime: Long = 0
        private var restartSequenceStage: Int = 0
        private var nextRestartActionTime: Long = 0
        private var restartSetSpawnTime: Long = 0
        private var restartDelaySeconds: Long = 0
        private var isRestartAborted: Boolean = false
        private var restartQueuedAfterWardrobe: Boolean = false
        private var restartSetSpawnWindow: CommandUtils.ChatWindow? = null
        private var isProxyRestartPending: Boolean = false
        private var proxyRestartSequenceStage: Int = 0
        private var nextProxyRestartActionTime: Long = 0
        private var proxyRestartSetSpawnTime: Long = 0
        private var proxyRestartDelaySeconds: Long = 0
        private var proxyRestartQueuedAfterWardrobe: Boolean = false
        private var proxyRestartSetSpawnWindow: CommandUtils.ChatWindow? = null

        @JvmStatic
        fun reset() {
            resetRestartState()
            resetProxyRestartState()
        }

        private fun resetRestartState() {
            isRestartPending = false
            restartExecutionTime = 0
            restartSequenceStage = 0
            nextRestartActionTime = 0
            restartSetSpawnTime = 0
            restartDelaySeconds = 0
            isRestartAborted = false
            restartQueuedAfterWardrobe = false
            restartSetSpawnWindow = null
        }

        private fun resetProxyRestartState() {
            isProxyRestartPending = false
            proxyRestartSequenceStage = 0
            nextProxyRestartActionTime = 0
            proxyRestartSetSpawnTime = 0
            proxyRestartDelaySeconds = 0
            proxyRestartQueuedAfterWardrobe = false
            proxyRestartSetSpawnWindow = null
        }

        private fun isSafeToRunRestartAbort(state: MacroState.State): Boolean {
            if (state == MacroState.State.OFF || state == MacroState.State.RECOVERING) {
                return false
            }

            // Never interrupt active pest/visitor flows; wait until they finish naturally.
            if (PestManager.isCleaningInProgress()
                || PestPrepSwapManager.isPrepSwapping()
                || PestReturnManager.isFinishingInProgress()
                || PestReturnManager.isReturnToLocationActive()
                || PestReturnManager.isReturningFromPestVisitor()
                || state == MacroState.State.CLEANING
                || state == MacroState.State.SPRAYING
                || state == MacroState.State.VISITING) {
                return false
            }

            return state == MacroState.State.FARMING
        }

        @JvmStatic
        fun handleRestartMessage(client: Minecraft, isImmediate: Boolean) {
            if (MacroStateManager.getCurrentState() != MacroState.State.OFF
                && MacroStateManager.getCurrentState() != MacroState.State.RECOVERING
                && !isRestartPending && !isProxyRestartPending) {
                val contestMs = if (isImmediate) 0 else ClientUtils.getJacobsContestRemainingMs()
                if (contestMs > 0) {
                    restartDelaySeconds = ThreadLocalRandom.current()
                        .nextLong(JACOB_CONTEST_RESTART_MIN_DELAY_SECONDS, JACOB_CONTEST_RESTART_MAX_DELAY_SECONDS + 1)
                    ClientUtils.sendMessage("\u00A7c" + String.format(
                        dev.aether.util.AetherLang.localize(
                            "Server restart detected. Delaying abort until Jacob's contest ends, then waiting %ds..."),
                        restartDelaySeconds), false)
                    restartExecutionTime = System.currentTimeMillis() + contestMs + (restartDelaySeconds * 1000L)
                } else {
                    restartDelaySeconds = ThreadLocalRandom.current()
                        .nextLong(RESTART_MIN_DELAY_SECONDS, RESTART_MAX_DELAY_SECONDS + 1)
                    ClientUtils.sendMessage("\u00A7c" + String.format(
                        dev.aether.util.AetherLang.localize(
                            "Server restart or evacuation detected. Waiting %ds before aborting..."),
                        restartDelaySeconds), false)
                    restartExecutionTime = System.currentTimeMillis() + (restartDelaySeconds * 1000L)
                }
                // Defer interruption to stage 0 so active pest/visitor flows can finish safely.
                isRestartPending = true
                restartSequenceStage = 0
            }
        }

        @JvmStatic
        fun handleProxyRestartMessage(client: Minecraft?) {
            if (client == null || MacroStateManager.getCurrentState() == MacroState.State.OFF
                || MacroStateManager.getCurrentState() == MacroState.State.RECOVERING
                || isRestartPending || isProxyRestartPending) {
                return
            }

            proxyRestartDelaySeconds = ThreadLocalRandom.current()
                .nextLong(PROXY_RESTART_MIN_DELAY_SECONDS, PROXY_RESTART_MAX_DELAY_SECONDS + 1)

            ClientUtils.sendMessage("\u00A7eProxy restart detected. Waiting for farming to resume, then disconnecting for "
                + proxyRestartDelaySeconds + "s...", false)
            isProxyRestartPending = true
            proxyRestartSequenceStage = 0
            nextProxyRestartActionTime = System.currentTimeMillis()
        }

        @JvmStatic
        fun update() {
            val client = Minecraft.getInstance()
            val state = MacroStateManager.getCurrentState()

            if (isProxyRestartPending) {
                updateProxyRestart(client, state)
            }

            if (!isRestartPending) {
                return
            }

            if (state == MacroState.State.OFF) {
                resetRestartState()
                return
            }

            if (restartSequenceStage == 0 && System.currentTimeMillis() >= restartExecutionTime) {
                val loc = ClientUtils.getCurrentLocation()
                val alreadyDisplaced = loc != MacroState.Location.GARDEN && loc != MacroState.Location.UNKNOWN

                if (!alreadyDisplaced && (LoadoutManager.isSwappingLoadout || state == MacroState.State.WARDROBE)) {
                    if (!restartQueuedAfterWardrobe) {
                        restartQueuedAfterWardrobe = true
                        ClientUtils.sendDebugMessage("Server restart abort queued until the wardrobe swap finishes.")
                    }
                    return
                }

                if (!alreadyDisplaced && !isSafeToRunRestartAbort(state)) {
                    return
                }

                restartQueuedAfterWardrobe = false

                if (alreadyDisplaced) {
                    ClientUtils.sendDebugMessage("Displaced during restart delay (Location: " + loc + "). Aborting immediately.")
                }

                ClientUtils.sendMessage("\u00A7cExecuting delayed restart abort sequence...", false)
                ClientUtils.sendDebugMessage("Disabling farming macro: Server restart/evacuation detected")
                // Cancel worker tasks right before abort execution.
                MacroWorkerThread.getInstance().cancelCurrent()
                client.execute { dev.aether.macro.farming.FarmingMacroManager.disable(client) }
                ClientUtils.forceReleaseKeys()
                restartSetSpawnWindow = CommandUtils.beginChatWindow()
                dev.aether.util.CommandUtils.initiateSetSpawn()
                restartSetSpawnTime = System.currentTimeMillis()
                restartSequenceStage = 1
                nextRestartActionTime = System.currentTimeMillis() + 5000
            } else if (restartSequenceStage == 1) {
                val spawnConfirmed = dev.aether.util.CommandUtils.hasSpawnBeenSet(restartSetSpawnWindow)
                val now = System.currentTimeMillis()
                if (!spawnConfirmed && now < nextRestartActionTime) {
                    return
                }

                if (restartSetSpawnTime > 0 && now - restartSetSpawnTime < RESTART_COMMAND_GAP_MS) {
                    return
                }

                ClientUtils.sendDebugMessage(if (CommandUtils.shouldSkipSetSpawn())
                    "Restart sequence: queueing /hub without /setspawn."
                else "Restart sequence: queueing /hub after /setspawn.")
                ClientUtils.sendCommand("/hub")
                restartSequenceStage = 2
                nextRestartActionTime = now + 10000
            } else if (restartSequenceStage == 2 && System.currentTimeMillis() >= nextRestartActionTime) {
                ClientUtils.sendDebugMessage("Disabling farming macro: Entering recovery mode after server restart")
                client.execute { dev.aether.macro.farming.FarmingMacroManager.disable(client) }
                RecoveryManager.beginRecovery()
                restartSequenceStage = 0
                isRestartPending = false
            }
        }

        private fun updateProxyRestart(client: Minecraft, state: MacroState.State) {
            val now = System.currentTimeMillis()
            if (now < nextProxyRestartActionTime) {
                return
            }

            if (proxyRestartSequenceStage == 0) {
                if (LoadoutManager.isSwappingLoadout || state == MacroState.State.WARDROBE) {
                    if (!proxyRestartQueuedAfterWardrobe) {
                        proxyRestartQueuedAfterWardrobe = true
                        ClientUtils.sendDebugMessage("Proxy restart queued until the wardrobe swap finishes.")
                    }
                    return
                }

                if (!isSafeToRunRestartAbort(state)) {
                    return
                }

                proxyRestartQueuedAfterWardrobe = false
                ClientUtils.sendMessage(if (CommandUtils.shouldSkipSetSpawn())
                    "\u00A7eProxy restart recovery: preparing disconnect..."
                else "\u00A7eProxy restart recovery: running /setspawn before disconnecting...", false)
                ClientUtils.sendDebugMessage("Disabling farming macro: Proxy restart recovery")
                MacroWorkerThread.getInstance().cancelCurrent()
                client.execute { dev.aether.macro.farming.FarmingMacroManager.disable(client) }
                ClientUtils.forceReleaseKeys()
                proxyRestartSetSpawnWindow = CommandUtils.beginChatWindow()
                CommandUtils.initiateSetSpawn()
                proxyRestartSetSpawnTime = now
                MacroStateManager.setCurrentState(MacroState.State.OFF)
                proxyRestartSequenceStage = 1
                nextProxyRestartActionTime = now + 5000
                return
            }

            if (proxyRestartSequenceStage != 1) {
                return
            }

            val spawnConfirmed = CommandUtils.hasSpawnBeenSet(proxyRestartSetSpawnWindow)
            if (!spawnConfirmed && now < nextProxyRestartActionTime) {
                return
            }

            if (proxyRestartSetSpawnTime > 0 && now - proxyRestartSetSpawnTime < RESTART_COMMAND_GAP_MS) {
                return
            }

            val reconnectDelayMs = proxyRestartDelaySeconds * 1000L
            val reconnectAtMs = now + reconnectDelayMs
            ClientUtils.sendMessage("\u00A7eProxy restart recovery: disconnecting from Hypixel for "
                + proxyRestartDelaySeconds + "s...", false)
            MacroStateManager.setIntentionalDisconnect(true)
            ReconnectScheduler.scheduleReconnect(
                proxyRestartDelaySeconds, true, ReconnectScheduler.ReconnectMode.PROXY_RESTART)
            ClientUtils.disconnectWithScreen(DynamicRestScreen(
                "Proxy Restart Recovery",
                "Proxy Restart Recovery",
                "reconnecting to Hypixel in",
                "Cancel Reconnect & Exit to Menu",
                reconnectAtMs,
                reconnectDelayMs), net.minecraft.network.chat.Component.literal("Proxy restart recovery"))
            resetProxyRestartState()
        }

        @JvmStatic
        fun isRestartPending(): Boolean = isRestartPending

        @JvmStatic
        fun isRestartSequenceActive(): Boolean = isRestartPending && restartSequenceStage > 0

        @JvmStatic
        fun onWardrobeSwapCompleted(client: Minecraft) {
            if (isRestartPending && restartQueuedAfterWardrobe && restartSequenceStage == 0) {
                ClientUtils.sendDebugMessage("Wardrobe swap finished. Resuming queued server restart abort.")
                restartQueuedAfterWardrobe = false
                restartExecutionTime = Math.min(restartExecutionTime, System.currentTimeMillis())
            }

            if (isProxyRestartPending && proxyRestartQueuedAfterWardrobe && proxyRestartSequenceStage == 0) {
                ClientUtils.sendDebugMessage("Wardrobe swap finished. Resuming queued proxy restart recovery.")
                proxyRestartQueuedAfterWardrobe = false
                nextProxyRestartActionTime = Math.min(nextProxyRestartActionTime, System.currentTimeMillis())
            }
        }
    }
}
