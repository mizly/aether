package dev.aether.macro.farming

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.rewarp.RewarpManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.modules.session.RecoveryManager
import dev.aether.modules.session.RestartManager
import dev.aether.util.ClientUtils
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// tick() has to be wired to END_CLIENT_TICK
class FarmingMacroManager private constructor() {
    companion object {
        private const val START_GUI_CLOSE_TIMEOUT_MS = 3500L
        private const val START_GUI_CLOSE_POLL_MS = 50L
        private const val START_IN_WORLD_STABLE_MS = 300L
        private const val START_DELAY_MIN_TICKS = 1
        private const val START_DELAY_MAX_TICKS = 10
        private var activeMacro: AbstractFarmingMacro? = null
        @Volatile private var deferredStartPending = false
        private var pendingEnableTicks = 0
        @Volatile private var cachedCycleStep: Int? = null

        @JvmStatic fun loadCycleStep() {
            if (cachedCycleStep == null) try {
                val path = FabricLoader.getInstance().configDir.resolve("aether_last_direction.txt")
                if (Files.exists(path)) cachedCycleStep = Integer.parseInt(Files.readString(path).trim())
            } catch (_: Exception) { }
        }
        @JvmStatic fun saveCycleStep(step: Int) {
            cachedCycleStep = Math.max(0, step)
            try {
                val path = FabricLoader.getInstance().configDir.resolve("aether_last_direction.txt")
                Files.writeString(path, Integer.toString(cachedCycleStep!!))
            } catch (_: Exception) { }
        }
        @JvmStatic fun getCachedCycleStep(): Int? = cachedCycleStep
        @JvmStatic fun restoreConfiguredOrientation(mc: Minecraft): Boolean = activeMacro != null && activeMacro!!.restoreConfiguredOrientation(mc)

        @JvmStatic fun createMacroFromConfig(): AbstractFarmingMacro = when (AetherConfig.FARM_TYPE.get()) {
            "A_D_FARM" -> ADFarmMacro()
            "COCOA_BEANS" -> CocoaBeansMacro()
            "SDS_MUSHROOM" -> SDSMushroomMacro()
            "W_S_FARM", "W_S_CROP" -> WSFarmMacro()
            "CUSTOM" -> CustomFarmMacro()
            "S_SHAPE" -> SShapeCropMacro()
            "S_SHAPE_SUGAR_CANE" -> SShapeSugarCaneMacro()
            else -> SShapeCropMacro()
        }

        @JvmStatic fun enable(mc: Minecraft, macro: AbstractFarmingMacro) {
            if (RestartManager.isRestartSequenceActive()) return
            if (AutoPestExchangeManager.shouldBlockFarmingResume()) { ClientUtils.sendDebugMessage("Farming start deferred because pest exchange has priority."); return }
            if (!ensureFarmingLocation()) return
            activeMacro?.let { it.onDisable(mc); activeMacro = null; pendingEnableTicks = 0 }
            if (SqueakyMousematManager.shouldUseBeforeFarming(mc)) {
                if (AutoPestExchangeManager.shouldBlockFarmingResume()) { ClientUtils.sendDebugMessage("Mousemat start skipped because pest exchange has priority."); return }
                MacroWorkerThread.getInstance().submit("FarmingStartMousemat", Runnable {
                    if (MacroWorkerThread.shouldAbortTask(mc, MacroState.State.FARMING) || AutoPestExchangeManager.shouldBlockFarmingResume()) return@Runnable
                    SqueakyMousematManager.useIfNeeded(mc)
                    if (MacroWorkerThread.shouldAbortTask(mc, MacroState.State.FARMING) || AutoPestExchangeManager.shouldBlockFarmingResume()) return@Runnable
                    RewarpManager.runPendingPostResumeActions(mc)
                    mc.execute { startMacroNow(mc, macro) }
                })
                return
            }
            RewarpManager.runPendingPostResumeActions(mc)
            startMacroNow(mc, macro)
        }

        private fun startMacroNow(mc: Minecraft, macro: AbstractFarmingMacro) {
            if (!ensureFarmingLocation()) return
            if (hasBlockingScreenOrContainer(mc)) { deferStartUntilReady(mc, macro); return }
            ClientUtils.forceReleaseKeys()
            if (!GearManager.swapToFarmingToolSync(mc)) ClientUtils.sendDebugMessage("Farming start: no farming tool found in hotbar, continuing with current item.")
            activeMacro = macro
            pendingEnableTicks = ConfigHelpers.getRandomizedDelay(START_DELAY_MIN_TICKS, START_DELAY_MAX_TICKS)
        }

        private fun ensureFarmingLocation(): Boolean {
            val state = MacroStateManager.getCurrentState()
            if (state == MacroState.State.OFF || state == MacroState.State.RECOVERING) return false
            if (ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) { RecoveryManager.beginRecovery(); return false }
            return true
        }

        private fun deferStartUntilReady(mc: Minecraft, macro: AbstractFarmingMacro) {
            if (deferredStartPending) return
            deferredStartPending = true
            ClientUtils.sendDebugMessage("Farming start deferred until open GUI/container closes.")
            MacroWorkerThread.getInstance().submit("FarmingStart-WaitForGuiClose", Runnable {
                if (waitForFarmingResumeReady(mc, START_GUI_CLOSE_TIMEOUT_MS)) {
                    deferredStartPending = false
                    mc.execute { startMacroNow(mc, macro) }
                } else if (!MacroWorkerThread.shouldAbortTask(mc)) {
                    deferredStartPending = false
                    ClientUtils.sendDebugMessage("Farming start aborted: GUI/container did not fully close before resume timeout.")
                } else deferredStartPending = false
            })
        }

        @JvmStatic fun waitForFarmingResumeReady(mc: Minecraft, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            var readySince = -1L
            while (System.currentTimeMillis() < deadline && !MacroWorkerThread.shouldAbortTask(mc)) {
                if (isReadyForFarmingInput(mc)) {
                    if (readySince < 0L) readySince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - readySince >= START_IN_WORLD_STABLE_MS) return true
                } else readySince = -1L
                MacroWorkerThread.sleep(START_GUI_CLOSE_POLL_MS)
            }
            return false
        }

        private fun isReadyForFarmingInput(mc: Minecraft) = !hasBlockingScreenOrContainer(mc) && LoadoutManager.loadoutCleanupTicks <= 0
        private fun hasBlockingScreenOrContainer(mc: Minecraft): Boolean {
            if (mc == null || mc.player == null) return true
            if (!mc.isSameThread()) {
                val future = CompletableFuture<Boolean>()
                mc.execute { future.complete(hasBlockingScreenOrContainer(mc)) }
                return try { future.get(750, TimeUnit.MILLISECONDS) } catch (_: Exception) { true }
            }
            if (mc.gui.screen() != null) return true
            val player = mc.player ?: return false
            return player.containerMenu != null && player.inventoryMenu != null && player.containerMenu.containerId != player.inventoryMenu.containerId
        }

        @JvmStatic fun disable(mc: Minecraft) {
            activeMacro?.let { it.onDisable(mc); activeMacro = null; pendingEnableTicks = 0 }
        }
        @JvmStatic fun getActiveMacro(): AbstractFarmingMacro? = activeMacro
        @JvmStatic fun isActive() = activeMacro != null
        @JvmStatic fun releaseInputs(mc: Minecraft) { if (activeMacro != null && mc != null && mc.options != null) activeMacro!!.releaseAll(mc) }
        @JvmStatic fun tick(mc: Minecraft) {
            val macro = activeMacro
            if (macro == null || mc.player == null) return
            if (pendingEnableTicks > 0) {
                if (!ensureFarmingLocation()) return
                if (--pendingEnableTicks > 0) return
                macro.onEnable(mc)
                return
            }
            macro.onTick(mc)
        }
    }
}
