package dev.aether.modules.pest

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.pest.helpers.PestAotvManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.modules.pest.helpers.PestBonusManager
import dev.aether.modules.pest.helpers.PestPlotId
import dev.aether.modules.pest.helpers.PestLifecycleManager
import dev.aether.modules.pest.helpers.PestOnTheTrackManager
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.pest.helpers.PestReturnManager
import dev.aether.modules.GreenhouseManager
import dev.aether.modules.CropFeverManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.inventorymanager.GeorgeManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import java.util.LinkedHashSet
import dev.aether.modules.pest.helpers.PestPrepSwapManager
import dev.aether.modules.visitor.VisitorsMacro

class PestManager private constructor() {
    companion object {
        @Volatile private var isCleaningInProgress = false
        @Volatile private var currentInfestedPlot: String? = null
        @Volatile private var ballsackShredderActiveForCurrentCycle = false
        @Volatile private var currentInfestedPlots: Set<String> = emptySet()
        @Volatile private var currentPestSessionId = 0
        private const val PEST_REENTRY_COOLDOWN_MS = 30_000L
        private var lastZeroPestTime = 0L
        @Volatile private var predictedAliveCount = 0
        @Volatile private var lastChatSpawnUpdateMs = 0L
        @Volatile private var lastLocalKillUpdateMs = 0L
        @Volatile private var isCleaningTriggerPending = false
        @Volatile private var pestReentryCooldownUntilMs = 0L
        @Volatile private var pendingChatTrigger: PendingChatTrigger? = null
        @Volatile private var pendingChatTriggerWaitsForLoadout = false
        @Volatile private var pendingChatTriggerDelayAfterLoadoutMs = 0L
        @Volatile private var pendingChatTriggerUsesBallsack = false
        @Volatile private var lastCleaningAliveCount = -1
        @Volatile private var lastCleaningProgressAtMs = 0L
        @Volatile private var rewarpTriggerAvailable = false
        @Volatile private var cleaningTriggerClaimedAtMs = 0L
        private const val TAB_SYNC_GRACE_MS = 5000L
        private const val CLEANING_STALL_TIMEOUT_MS = 30_000L
        private const val CLEANING_TRIGGER_CLAIM_TIMEOUT_MS = 30_000L
        private const val PENDING_CHAT_TRIGGER_MAX_AGE_MS = 120_000L
        private var cachedTabTick = Int.MIN_VALUE
        private var cachedTabConnectionId = 0
        private var cachedTabListData: PestTabSnapshot? = null

        @JvmStatic fun isCleaningInProgress() = isCleaningInProgress
        @JvmStatic fun setCleaningInProgress(cleaning: Boolean) { isCleaningInProgress = cleaning }
        @JvmStatic fun getCurrentInfestedPlot(): String? = currentInfestedPlot
        @JvmStatic fun getCurrentInfestedPlots(): Set<String> = currentInfestedPlots
        @JvmStatic fun setCurrentInfestedPlots(plots: java.util.Set<String>) {
            currentInfestedPlots = java.util.LinkedHashSet(plots).toSet()
        }
        @JvmStatic fun getCurrentPestSessionId() = currentPestSessionId
        @JvmStatic fun isBallsackShredderActiveForCurrentCycle() = ballsackShredderActiveForCurrentCycle
        @JvmStatic fun shouldUseBallsackShredderForSpawn(plot: String) =
            shouldRunBallsackShredderForSpawn(plot) && PestPlotId.equals(plot, ClientUtils.getCurrentPlot())
        private fun shouldRunBallsackShredderForSpawn(plot: String) = AetherConfig.BALLSACK_SHREDDER.get() &&
            dev.aether.modules.pest.helpers.PestBallsackShredder.isConfiguredForPlot(plot, AetherConfig.BALLSACK_SHREDDER_PLOTS.get())
        private fun isThresholdMet(aliveCount: Int) = aliveCount >= AetherConfig.PEST_THRESHOLD.get() || aliveCount >= 8
        @JvmStatic fun isPestDestroyerEnabled() = AetherConfig.TRIGGER_PEST_ON_CHAT.get()
        @JvmStatic fun arePestTrapsEnabled() = AetherConfig.ENABLE_PEST_TRAPS.get()
        private fun isPestReentryCooldownActive() = getPestReentryCooldownRemainingMs() > 0
        @JvmStatic fun getPestReentryCooldownRemainingMs() = Math.max(0L, pestReentryCooldownUntilMs - System.currentTimeMillis())
        @JvmStatic fun startPestReentryCooldown() { pestReentryCooldownUntilMs = System.currentTimeMillis() + PEST_REENTRY_COOLDOWN_MS }

        @Synchronized private fun claimCleaningTrigger(): Boolean {
            if (!canStartCleaningInState(MacroStateManager.getCurrentState()) || GeorgeManager.shouldDelayPestTrigger() ||
                isCleaningTriggerPending || isCleaningInProgress || isPestReentryCooldownActive() || PestDestroyer.isActive() ||
                PestOnTheTrackManager.getInstance().isNotIdle() || ManualPestManager.isActive() ||
                PestReturnManager.isFinishingInProgress() || PestReturnManager.isReturnToLocationActive() || LoadoutManager.isSwappingLoadout) return false
            isCleaningTriggerPending = true
            cleaningTriggerClaimedAtMs = System.currentTimeMillis()
            return true
        }
        @JvmStatic @Synchronized fun clearCleaningTriggerPending() { isCleaningTriggerPending = false; cleaningTriggerClaimedAtMs = 0L }

        @Synchronized private fun releaseStuckCleaningTrigger() {
            if (!isCleaningTriggerPending || cleaningTriggerClaimedAtMs == 0L || isCleaningInProgress || PestDestroyer.isActive() ||
                ManualPestManager.isActive() || PestReturnManager.isFinishingInProgress() || PestReturnManager.isReturnToLocationActive()) return
            if (System.currentTimeMillis() - cleaningTriggerClaimedAtMs < CLEANING_TRIGGER_CLAIM_TIMEOUT_MS) return
            ClientUtils.sendDebugMessage("[PestManager] Cleaning trigger claim stuck for ${CLEANING_TRIGGER_CLAIM_TIMEOUT_MS}ms without starting a cycle; releasing it.")
            isCleaningTriggerPending = false
            cleaningTriggerClaimedAtMs = 0L
            PestLifecycleManager.releaseStuckPreStage()
        }
        @JvmStatic fun markRewarpCompleted() { rewarpTriggerAvailable = true }
        private fun canTriggerAfterRewarp() = !AetherConfig.PEST_TRIGGER_ONLY_AFTER_REWARP.get() || rewarpTriggerAvailable
        private fun consumeRewarpTrigger() { if (AetherConfig.PEST_TRIGGER_ONLY_AFTER_REWARP.get()) rewarpTriggerAvailable = false }
        @JvmStatic fun getInfestedPlotsFromTab(client: Minecraft): Set<String> = java.util.LinkedHashSet(parseTabList(client).infestedPlots()).toSet()
        @JvmStatic fun getTabAliveCountNow(client: Minecraft?): Int {
            if (client == null || client.getConnection() == null || client.player == null) return -1
            return parseTabList(client).aliveCount()
        }
        private fun parseTabList(client: Minecraft): PestTabSnapshot {
            if (client.getConnection() == null || client.player == null) return PestTabSnapshot.empty()
            val tick = client.player!!.tickCount
            val connectionId = System.identityHashCode(client.getConnection())
            if (cachedTabListData != null && cachedTabTick == tick && cachedTabConnectionId == connectionId) return cachedTabListData!!
            val data = PestTabSnapshot.read(client)
            cachedTabTick = tick
            cachedTabConnectionId = connectionId
            cachedTabListData = data
            return data
        }

        @JvmStatic fun reset() {
            isCleaningInProgress = false
            currentInfestedPlot = null
            ballsackShredderActiveForCurrentCycle = false
            currentInfestedPlots = emptySet()
            lastZeroPestTime = 0L
            predictedAliveCount = 0
            lastChatSpawnUpdateMs = 0L
            lastLocalKillUpdateMs = 0L
            isCleaningTriggerPending = false
            cleaningTriggerClaimedAtMs = 0L
            pestReentryCooldownUntilMs = 0L
            pendingChatTrigger = null
            pendingChatTriggerWaitsForLoadout = false
            pendingChatTriggerDelayAfterLoadoutMs = 0L
            lastCleaningAliveCount = -1
            lastCleaningProgressAtMs = 0L
            rewarpTriggerAvailable = false
            cachedTabTick = Int.MIN_VALUE
            cachedTabConnectionId = 0
            cachedTabListData = null
            currentPestSessionId++
            PestPrepSwapManager.resetState()
            PestLifecycleManager.reset()
            PestReturnManager.resetState()
            PestAotvManager.resetState()
            PestBonusManager.resetState()
            AutoPestExchangeManager.reset()
            PestDestroyer.reset()
            ManualPestManager.reset()
            DynamicPestsManager.reset()
        }

        @JvmStatic fun checkTabListForPests(client: Minecraft, currentState: MacroState.State) {
            if (client.getConnection() == null || client.player == null || !MacroStateManager.isMacroRunning()) return
            if (!isPestDestroyerEnabled()) return
            releaseStuckCleaningTrigger()
            if (processPendingChatTrigger(client, currentState)) return
            if (pendingChatTrigger != null) return
            if (isCleaningInProgress && currentState == MacroState.State.FARMING && !PestDestroyer.isActive() &&
                !PestReturnManager.isFinishingInProgress() && !PestReturnManager.isReturnToLocationActive()) isCleaningInProgress = false
            val data = parseTabList(client)
            syncPredictedAliveFromTab(data.aliveCount())
            val effectiveAlive = getEffectiveAliveCount(data.aliveCount())
            data.bonusInactive()?.let { PestBonusManager.applyTabBonusState(it) }
            if (data.cooldownSeconds() != -1) {
                val thresholdMet = isThresholdMet(effectiveAlive)
                PestPrepSwapManager.updatePrepSwapFlag(data.cooldownSeconds(), isCleaningInProgress, thresholdMet)
                if (!thresholdMet && PestPrepSwapManager.shouldTriggerPrepSwap(currentState, data.cooldownSeconds(), isCleaningInProgress, PestReturnManager.isReturnToLocationActive())) PestPrepSwapManager.triggerPrepSwap()
            }
            if (currentState == MacroState.State.CLEANING && !GreenhouseManager.isRunning() && !ManualPestManager.isActive()) {
                val completionAlive = selectCompletionAliveCount(AetherConfig.ESTIMATE_PEST_DESTROYER_COMPLETION.get(), data.aliveCount(), effectiveAlive)
                updateCleaningProgressTracker(completionAlive)
                if (completionAlive <= 0) {
                    if (lastZeroPestTime == 0L) lastZeroPestTime = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - lastZeroPestTime > 10000L) {
                        ClientUtils.sendMessage("\u00A7cFail-safe: No pests detected for 10s. Returning to farm.", true)
                        lastZeroPestTime = 0L
                        handlePestCleaningFinished(client)
                        return
                    }
                } else lastZeroPestTime = 0L
                if (shouldAbortCleaningForStall(completionAlive)) {
                    ClientUtils.sendMessage("\u00A7cPest cleaner made no pest-count progress for 30s. Aborting cleaner.", true)
                    ClientUtils.sendDebugMessage("PestManager: aborting pest cleaner after 30s without alive-count progress. Last alive=$lastCleaningAliveCount, current alive=$completionAlive")
                    resetCleaningProgressTracker()
                    handlePestCleaningFinished(client)
                    return
                }
            } else {
                lastZeroPestTime = 0L
                resetCleaningProgressTracker()
            }
            if (isCleaningInProgress || !canStartCleaningInState(currentState) || VisitorsMacro.isRunning || GeorgeManager.shouldDelayPestTrigger()) return
            var actionablePlots: java.util.Set<String> = PestDestroyer.filterRememberedLeaveOnePlots(data.infestedPlots())
            actionablePlots = recoverStaleLeaveOneMemory(actionablePlots, data.infestedPlots(), effectiveAlive)
            if (isThresholdMet(effectiveAlive) && actionablePlots.isNotEmpty()) {
                if (isPestReentryCooldownActive() || !canTriggerAfterRewarp() || AutoPestExchangeManager.isExchangePending()) return
                if (AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() && CropFeverManager.isCropFeverActive) return
                if (effectiveAlive >= 8 && effectiveAlive < 99) ClientUtils.sendMessage("\u00A7eMax Pests (8) reached. Starting cleaning...", true)
                setCurrentInfestedPlots(actionablePlots)
                val targetPlot = actionablePlots.firstOrNull()
                ClientUtils.sendDebugMessage("[PestManager] Tab threshold met. infestedPlots=$actionablePlots targetPlot=$targetPlot currentPlot=${ClientUtils.getCurrentPlot()}")
                val useBallsackRoute = shouldRunBallsackShredderForSpawn(targetPlot ?: "")
                if (startCleaningSequence(client, targetPlot, effectiveAlive)) {
                    ballsackShredderActiveForCurrentCycle = useBallsackRoute
                    consumeRewarpTrigger()
                }
            }
        }

        @JvmStatic fun canStartCleaningInState(state: MacroState.State) = state == MacroState.State.FARMING
        private fun recoverStaleLeaveOneMemory(actionablePlots: java.util.Set<String>, infestedPlots: kotlin.collections.Set<String>, effectiveAlive: Int): java.util.Set<String> {
            if (actionablePlots.isNotEmpty() || infestedPlots.isEmpty() || PestDestroyer.isLeaveOneSatisfied(effectiveAlive)) return actionablePlots
            ClientUtils.sendDebugMessage("[PestManager] Leave-one memory is stale (alive=$effectiveAlive, infested=$infestedPlots); clearing remembered plots so cleaning can trigger.")
            PestDestroyer.clearRememberedLeaveOnePlots()
            return PestDestroyer.filterRememberedLeaveOnePlots(infestedPlots)
        }
        @JvmStatic fun handlePestCleaningFinished(client: Minecraft) {
            clearCleaningTriggerPending()
            if (!PestReturnManager.isFinishingInProgress()) startPestReentryCooldown()
            if (PestDestroyer.isActive()) PestDestroyer.stop(client)
            ballsackShredderActiveForCurrentCycle = false
            PestLifecycleManager.startPostStage(client)
        }
        @JvmStatic fun update() {
            val client = Minecraft.getInstance()
            PestReturnManager.tickFinishingWatchdog()
            checkTabListForPests(client, MacroStateManager.getCurrentState())
        }

        @JvmStatic @Synchronized fun scheduleChatCleaningTrigger(plot: String, spawnedCount: Int, delayMs: Long, ballsackDelayMs: Long, spawnedMessage: Boolean) {
            val useBallsackRoute = spawnedMessage && shouldRunBallsackShredderForSpawn(plot)
            val useBallsackTriggerFlow = spawnedMessage && shouldUseBallsackShredderForSpawn(plot)
            val selectedDelayMs = if (useBallsackTriggerFlow) ballsackDelayMs else delayMs
            var requestedBallsackLoadout = false
            if (useBallsackTriggerFlow && AetherConfig.AUTO_LOADOUT_ENABLED.get()) {
                val farmingSlot = AetherConfig.LOADOUT_SLOT_FARMING.get()
                if (farmingSlot > 0 && LoadoutManager.trackedLoadoutSlot != farmingSlot && !(LoadoutManager.isSwappingLoadout && LoadoutManager.targetLoadoutSlot == farmingSlot)) {
                    LoadoutManager.triggerLoadoutSwap(Minecraft.getInstance(), farmingSlot)
                    requestedBallsackLoadout = true
                }
            }
            val normalizedDelayMs = Math.max(0L, selectedDelayMs)
            val now = System.currentTimeMillis()
            val triggerAt = now + normalizedDelayMs
            val pending = pendingChatTrigger
            if (pending == null) {
                pendingChatTrigger = PendingChatTrigger(plot, Math.max(0, spawnedCount), triggerAt, now)
                pendingChatTriggerUsesBallsack = useBallsackRoute
            } else {
                pendingChatTrigger = PendingChatTrigger(plot, pending.spawnedCount + Math.max(0, spawnedCount), Math.min(pending.triggerAtMs, triggerAt), pending.createdAtMs)
                pendingChatTriggerUsesBallsack = pendingChatTriggerUsesBallsack || useBallsackRoute
            }
            if (requestedBallsackLoadout) {
                pendingChatTriggerWaitsForLoadout = true
                pendingChatTriggerDelayAfterLoadoutMs = if (pendingChatTriggerDelayAfterLoadoutMs == 0L) normalizedDelayMs else Math.min(pendingChatTriggerDelayAfterLoadoutMs, normalizedDelayMs)
            }
        }

        @Synchronized private fun processPendingChatTrigger(client: Minecraft, currentState: MacroState.State): Boolean {
            val pending = pendingChatTrigger ?: return false
            if (System.currentTimeMillis() - pending.createdAtMs > PENDING_CHAT_TRIGGER_MAX_AGE_MS) {
                ClientUtils.sendDebugMessage("[PestManager] Pending chat pest trigger for plot ${pending.plot} expired after ${PENDING_CHAT_TRIGGER_MAX_AGE_MS}ms; falling back to the tab-list trigger.")
                clearPendingChatTrigger()
                return false
            }
            if (GeorgeManager.shouldDelayPestTrigger()) return false
            if (pendingChatTriggerWaitsForLoadout) {
                if (!AetherConfig.AUTO_LOADOUT_ENABLED.get()) {
                    pendingChatTriggerWaitsForLoadout = false
                    pendingChatTriggerDelayAfterLoadoutMs = 0L
                } else if (LoadoutManager.isSwappingLoadout || !LoadoutManager.loadoutGuiCloseComplete || currentState != MacroState.State.FARMING) return false
                else {
                    pendingChatTrigger = PendingChatTrigger(pending.plot, pending.spawnedCount, System.currentTimeMillis() + pendingChatTriggerDelayAfterLoadoutMs, pending.createdAtMs)
                    pendingChatTriggerWaitsForLoadout = false
                    pendingChatTriggerDelayAfterLoadoutMs = 0L
                    return false
                }
            }
            if (currentState != MacroState.State.FARMING) {
                if (pendingChatTriggerUsesBallsack && LoadoutManager.isSwappingLoadout) return false
                clearPendingChatTrigger()
                return false
            }
            if (System.currentTimeMillis() < pending.triggerAtMs || LoadoutManager.isSwappingLoadout || !MacroStateManager.isMacroRunning()) return false
            val useBallsackFlow = pendingChatTriggerUsesBallsack
            clearPendingChatTrigger()
            tryStartCleaningSequenceFromChat(client, pending.plot, pending.spawnedCount, useBallsackFlow)
            return true
        }
        private fun clearPendingChatTrigger() {
            pendingChatTrigger = null
            pendingChatTriggerWaitsForLoadout = false
            pendingChatTriggerDelayAfterLoadoutMs = 0L
            pendingChatTriggerUsesBallsack = false
        }
        private data class PendingChatTrigger(val plot: String, val spawnedCount: Int, val triggerAtMs: Long, val createdAtMs: Long)

        @JvmStatic fun getEffectiveAliveCountNow(client: Minecraft?): Int {
            if (client == null || client.getConnection() == null || client.player == null) return -1
            val data = parseTabList(client)
            if (data.aliveCount() < 0 && predictedAliveCount <= 0) return -1
            syncPredictedAliveFromTab(data.aliveCount())
            return getEffectiveAliveCount(data.aliveCount())
        }
        @JvmStatic fun getPestDestroyerCompletionAliveCountNow(client: Minecraft?): Int {
            if (client == null || client.getConnection() == null || client.player == null) return -1
            val data = parseTabList(client)
            if (!AetherConfig.ESTIMATE_PEST_DESTROYER_COMPLETION.get()) return Math.max(0, data.aliveCount())
            syncPredictedAliveFromTab(data.aliveCount())
            return getEffectiveAliveCount(data.aliveCount())
        }
        @JvmStatic fun startCleaningSequence(client: Minecraft, plot: String?): Boolean =
            startCleaningSequence(client, plot, Math.max(1, getEffectiveAliveCountNow(client)))
        private fun startCleaningSequence(client: Minecraft, plot: String?, pestCount: Int): Boolean {
            if (!claimCleaningTrigger()) return false
            currentInfestedPlot = plot
            currentPestSessionId++
            resetCleaningProgressTracker()
            if (PestLifecycleManager.start(client, plot ?: "", pestCount, currentPestSessionId)) return true
            clearCleaningTriggerPending()
            return false
        }
        @JvmStatic fun handlePhillipMessage(client: Minecraft, text: String) { PestBonusManager.handlePhillipMessage(client, text, currentInfestedPlot ?: "") }
        @JvmStatic fun tryStartCleaningSequenceFromChat(client: Minecraft, requestedPlot: String, spawnedCount: Int) = tryStartCleaningSequenceFromChat(client, requestedPlot, spawnedCount, false)
        private fun tryStartCleaningSequenceFromChat(client: Minecraft?, requestedPlot: String, spawnedCount: Int, useBallsackFlow: Boolean): Boolean {
            if (!isPestDestroyerEnabled() || client == null || client.getConnection() == null || client.player == null || isCleaningInProgress) return false
            val data = parseTabList(client)
            if (spawnedCount > 0) {
                predictedAliveCount = reconcileChatSpawnCount(predictedAliveCount, spawnedCount, data.aliveCount())
                lastChatSpawnUpdateMs = System.currentTimeMillis()
            }
            syncPredictedAliveFromTab(data.aliveCount())
            val effectiveAlive = getEffectiveAliveCount(data.aliveCount())
            if (!isThresholdMet(effectiveAlive)) {
                ClientUtils.sendDebugMessage("Chat pest trigger ignored: effective=$effectiveAlive (chat=$predictedAliveCount, tab=${data.aliveCount()}) < threshold=${AetherConfig.PEST_THRESHOLD.get()}")
                return false
            }
            if (isPestReentryCooldownActive()) {
                ClientUtils.sendDebugMessage("Chat pest trigger ignored: pest re-entry cooldown active for ${getPestReentryCooldownRemainingMs()}ms.")
                return false
            }
            if (!canTriggerAfterRewarp()) {
                ClientUtils.sendDebugMessage("Chat pest trigger ignored: waiting for rewarp before triggering cleaner.")
                return false
            }
            if (AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() && CropFeverManager.isCropFeverActive) {
                ClientUtils.sendDebugMessage("Chat pest trigger ignored: Crop Fever is currently active.")
                return false
            }
            val candidatePlots: MutableSet<String> = LinkedHashSet()
            val normalizedRequestedPlot = PestPlotId.normalize(requestedPlot)
            if (PestPlotId.isUsable(normalizedRequestedPlot)) candidatePlots.add(normalizedRequestedPlot!!)
            candidatePlots.addAll(data.infestedPlots())
            val filteredPlots: java.util.Set<String> = PestDestroyer.filterRememberedLeaveOnePlots(candidatePlots)
            val targetPlot = filteredPlots.firstOrNull() ?: if (PestPlotId.isUsable(requestedPlot)) PestPlotId.normalize(requestedPlot) else null
            setCurrentInfestedPlots(filteredPlots)
            ClientUtils.sendDebugMessage("Chat pest trigger selecting plot $targetPlot from tab=${data.infestedPlots()}, chat=$normalizedRequestedPlot, ordered=$currentInfestedPlots")
            val started = startCleaningSequence(client, targetPlot, effectiveAlive)
            if (started) {
                ballsackShredderActiveForCurrentCycle = useBallsackFlow
                consumeRewarpTrigger()
            }
            return started
        }

        @JvmStatic fun decrementPredictedAliveCount(client: Minecraft) { decrementPredictedAliveCount(client, 1) }
        @JvmStatic @Synchronized fun decrementPredictedAliveCount(client: Minecraft, killedCount: Int) {
            val appliedKills = Math.min(predictedAliveCount, Math.max(0, killedCount))
            if (appliedKills > 0) {
                predictedAliveCount -= appliedKills
                lastLocalKillUpdateMs = System.currentTimeMillis()
                ClientUtils.sendDebugMessage("Pest kill detected! Count: $appliedKills, predicted alive: $predictedAliveCount, currentPlot=${ClientUtils.getCurrentPlot()}, whitelistedPlots=${AetherConfig.LEAVE_ONE_PEST_PLOTS.get()}")
                if (AetherConfig.ESTIMATE_PEST_DESTROYER_COMPLETION.get() && PestDestroyer.isActive() && PestDestroyer.shouldFinishForAliveCount(client, predictedAliveCount)) {
                    val reason = if (predictedAliveCount == 0) "0 pests predicted" else "only whitelisted leave-one plot(s) predicted remaining"
                    ClientUtils.sendDebugMessage("PestManager: threshold reached: $reason. Finishing immediately.")
                    PestDestroyer.finish(client)
                }
            }
        }
        private fun syncPredictedAliveFromTab(tabAliveCount: Int) {
            if (tabAliveCount < 0) return
            val now = System.currentTimeMillis()
            if (tabAliveCount > predictedAliveCount && hasRecentLocalKill(now)) return
            if (tabAliveCount >= predictedAliveCount) { predictedAliveCount = tabAliveCount; return }
            if (now - lastChatSpawnUpdateMs > TAB_SYNC_GRACE_MS) predictedAliveCount = tabAliveCount
        }
        private fun getEffectiveAliveCount(tabAliveCount: Int): Int {
            if (tabAliveCount < 0) return predictedAliveCount
            if (tabAliveCount > predictedAliveCount && hasRecentLocalKill(System.currentTimeMillis())) return predictedAliveCount
            return Math.max(tabAliveCount, predictedAliveCount)
        }
        @JvmStatic fun selectCompletionAliveCount(estimateCompletion: Boolean, tabAliveCount: Int, effectiveAliveCount: Int) = if (estimateCompletion) effectiveAliveCount else Math.max(0, tabAliveCount)
        @JvmStatic fun reconcileChatSpawnCount(previousPrediction: Int, spawnedCount: Int, tabAliveCount: Int): Int {
            if (tabAliveCount >= 0) return tabAliveCount
            return Math.min(99, Math.max(0, previousPrediction) + Math.max(0, spawnedCount))
        }
        private fun hasRecentLocalKill(now: Long) = lastLocalKillUpdateMs > 0 && now - lastLocalKillUpdateMs <= TAB_SYNC_GRACE_MS
        private fun updateCleaningProgressTracker(effectiveAlive: Int) {
            if (effectiveAlive < 0) return
            val now = System.currentTimeMillis()
            if (lastCleaningAliveCount < 0 || effectiveAlive < lastCleaningAliveCount || PestDestroyer.isCatchInProgress()) {
                lastCleaningAliveCount = effectiveAlive
                lastCleaningProgressAtMs = now
                return
            }
            if (lastCleaningProgressAtMs == 0L) {
                lastCleaningAliveCount = effectiveAlive
                lastCleaningProgressAtMs = now
            }
        }
        private fun shouldAbortCleaningForStall(effectiveAlive: Int) = effectiveAlive > 0 && lastCleaningAliveCount >= 0 && lastCleaningProgressAtMs > 0L && System.currentTimeMillis() - lastCleaningProgressAtMs >= CLEANING_STALL_TIMEOUT_MS
        private fun resetCleaningProgressTracker() { lastCleaningAliveCount = -1; lastCleaningProgressAtMs = 0L }
    }
}
