package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

class PestPrepSwapManager private constructor() {
    companion object {
        @Volatile private var prepSwappedForCurrentPestCycle = false
        @Volatile private var isPrepSwapping = false

        @JvmStatic fun wasPrepSwappedForCurrentCycle(): Boolean = prepSwappedForCurrentPestCycle
        @JvmStatic fun isPrepSwapping(): Boolean = isPrepSwapping
        @JvmStatic fun clearCycleState() { prepSwappedForCurrentPestCycle = false; isPrepSwapping = false }
        @JvmStatic fun resetState() { prepSwappedForCurrentPestCycle = false; isPrepSwapping = false }
        private fun client(): Minecraft = Minecraft.getInstance()

        @JvmStatic fun updatePrepSwapFlag(cooldownSeconds: Int, isCleaningInProgress: Boolean, thresholdMet: Boolean) {
            if (!AetherConfig.AUTO_LOADOUT_ENABLED.get()) {
                prepSwappedForCurrentPestCycle = false; isPrepSwapping = false; return
            }
            if (cooldownSeconds > getPrepSwapResetCooldownSeconds() && prepSwappedForCurrentPestCycle && !isCleaningInProgress) {
                if (thresholdMet) prepSwappedForCurrentPestCycle = false else restoreFarmingLoadout()
            }
        }

        @JvmStatic fun shouldTriggerPrepSwap(currentState: MacroState.State, cooldownSeconds: Int, isCleaningInProgress: Boolean, isReturnToLocationActive: Boolean): Boolean {
            if (currentState != MacroState.State.FARMING || cooldownSeconds == -1 || cooldownSeconds < 0) return false
            if (prepSwappedForCurrentPestCycle || isCleaningInProgress || isReturnToLocationActive) return false
            if (!hasAnyPrepSwapTasksEnabled()) return false
            return cooldownSeconds <= getPrepSwapTriggerCooldownSeconds()
        }

        @JvmStatic fun triggerPrepSwap() {
            if (!AetherConfig.AUTO_LOADOUT_ENABLED.get()) return
            prepSwappedForCurrentPestCycle = true
            isPrepSwapping = true
            ClientUtils.sendDebugMessage("Pest cooldown detected. Triggering prep-swap...")
            MacroWorkerThread.getInstance().submit("PrepSwap") {
                try {
                    val client = client()
                    if (shouldAbortPrepSwap()) return@submit
                    if (AetherConfig.SUNSET_PESTS.get() && AetherConfig.SUNSET_PESTS_NIGHT_BEFORE_SPAWN.get()) {
                        val farmingWasActive = FarmingMacroManager.isActive()
                        if (farmingWasActive) {
                            client.execute { FarmingMacroManager.disable(client) }
                            if (!MacroWorkerThread.sleep(150)) return@submit
                        }
                        if (!GardenTimeManager.switchToNightTime(client)) ClientUtils.sendDebugMessage("Sunset Pests: failed to switch garden time to night before prep-swap.")
                        if (farmingWasActive && !PestManager.isCleaningInProgress()) client.execute { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) }
                        if (shouldAbortPrepSwap()) return@submit
                    }
                    if (!runPrepLoadoutSwap()) return@submit
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isPrepSwapping = false
                }
            }
        }

        private fun hasAnyPrepSwapTasksEnabled(): Boolean = AetherConfig.AUTO_LOADOUT_ENABLED.get() && AetherConfig.LOADOUT_SLOT_PEST.get() != AetherConfig.LOADOUT_SLOT_FARMING.get()
        private fun getPrepSwapTriggerCooldownSeconds(): Int = AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.get()
        private fun getPrepSwapResetCooldownSeconds(): Int = AetherConfig.LOADOUT_PEST_SWAP_TIME_SECONDS.get()

        private fun restoreFarmingLoadout() {
            if (!AetherConfig.AUTO_LOADOUT_ENABLED.get() || isPrepSwapping) {
                if (!AetherConfig.AUTO_LOADOUT_ENABLED.get()) prepSwappedForCurrentPestCycle = false
                return
            }
            isPrepSwapping = true
            ClientUtils.sendDebugMessage("Pest spawn stayed below threshold. Restoring farming loadout...")
            MacroWorkerThread.getInstance().submit("PrepSwap-RestoreFarming") {
                try {
                    val client = client()
                    if (!AetherConfig.AUTO_LOADOUT_ENABLED.get() || MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING) || PestManager.isCleaningInProgress()) return@submit
                    client.execute { FarmingMacroManager.disable(client) }
                    MacroWorkerThread.sleep(400)
                    if (!AetherConfig.AUTO_LOADOUT_ENABLED.get() || MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING) || PestManager.isCleaningInProgress()) return@submit
                    if (PestReturnManager.restoreFarmingLoadout(client) && !PestManager.isCleaningInProgress()) GearManager.finalResume(client)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    if (!PestManager.isCleaningInProgress()) prepSwappedForCurrentPestCycle = false
                    isPrepSwapping = false
                }
            }
        }

        private fun shouldAbortPrepSwap(): Boolean {
            val client = client()
            if (!AetherConfig.AUTO_LOADOUT_ENABLED.get() || MacroWorkerThread.shouldAbortTask(client) && !LoadoutManager.isSwappingLoadout || MacroStateManager.getCurrentState() != MacroState.State.FARMING && !LoadoutManager.isSwappingLoadout || PestManager.isCleaningInProgress()) {
                if (MacroStateManager.getCurrentState() != MacroState.State.FARMING || !MacroStateManager.isMacroRunning()) prepSwappedForCurrentPestCycle = false
                return true
            }
            return false
        }

        @Throws(InterruptedException::class)
        private fun runPrepLoadoutSwap(): Boolean {
            val client = client()
            if (!AetherConfig.AUTO_LOADOUT_ENABLED.get()) { prepSwappedForCurrentPestCycle = false; return true }
            if (AetherConfig.LOADOUT_SLOT_PEST.get() <= 0) return !shouldAbortPrepSwap()
            ClientUtils.sendDebugMessage("Prep-swap: Initiating loadout swap to slot " + AetherConfig.LOADOUT_SLOT_PEST.get())
            GearManager.triggerLoadoutSwap(client, AetherConfig.LOADOUT_SLOT_PEST.get())
            if (!LoadoutManager.isSwappingLoadout) {
                ClientUtils.sendDebugMessage("Prep-swap: Loadout swap not needed (already on correct slot).")
                return !shouldAbortPrepSwap()
            }
            ClientUtils.sendDebugMessage("Prep-swap: Waiting for loadout GUI...")
            ClientUtils.waitForWardrobeGui()
            if (!LoadoutManager.loadoutGuiDetected) {
                ClientUtils.sendDebugMessage("§cPrep-swap: Loadout GUI not detected! Retrying in 1 second...")
                MacroWorkerThread.sleep(1000)
                if (shouldAbortPrepSwap()) return false
                GearManager.triggerLoadoutSwap(client, AetherConfig.LOADOUT_SLOT_PEST.get())
                if (LoadoutManager.isSwappingLoadout) {
                    ClientUtils.sendDebugMessage("Prep-swap: Retry - Waiting for loadout GUI...")
                    ClientUtils.waitForWardrobeGui()
                    if (!LoadoutManager.loadoutGuiDetected) {
                        ClientUtils.sendDebugMessage("§cPrep-swap: Loadout GUI still not detected after retry! Aborting prep-swap.")
                        prepSwappedForCurrentPestCycle = false
                        return false
                    }
                }
            }
            while ((LoadoutManager.isSwappingLoadout || !LoadoutManager.loadoutGuiCloseComplete) && !PestManager.isCleaningInProgress()) MacroWorkerThread.sleep(50)
            MacroWorkerThread.sleep(250)
            ClientUtils.sendDebugMessage("Prep-swap: Loadout swap completed.")
            return true
        }
    }
}
