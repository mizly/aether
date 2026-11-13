package dev.aether.modules.pest

import dev.aether.config.AetherConfig
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pest.helpers.AutoSprayonatorManager
import dev.aether.modules.pest.helpers.GardenTimeManager
import dev.aether.modules.pest.helpers.VinylManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Objects

class DynamicPestsManager private constructor() {
    companion object {
        private const val FEAST_API_URL = "https://api.elitebot.dev/harvest-feast/current"
        private const val FEAST_FALLBACK_POLL_MS = 5 * 60 * 1000L
        private const val UPDATE_INTERVAL_MS = 5_000L
        private val SPRAY_OPTIONS = arrayOf("Compost", "Honey Jar", "Dung", "Plant Matter", "Tasty Cheese", "Jelly")
        private val VINYL_OPTIONS = arrayOf("Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm", "Mite", "Moth", "Slug", "Beetle", "Firefly", "Dragonfly", "Praying Mantis")
        @Volatile private var feastActiveCrops: List<String> = java.util.List.of()
        @Volatile private var feastNextFetchMs = 0L
        @Volatile private var feastActive = false
        @Volatile private var feastFetchInProgress = false
        @Volatile private var feastInitialFetchComplete = false
        @Volatile private var lastInitialFetchWaitDebugMs = 0L
        @Volatile private var jacobContestCrops: List<String> = java.util.List.of()
        @Volatile private var jacobParsed = false
        @Volatile private var jacobCleared = true
        @Volatile private var lastJacobParseMs = 0L
        private const val NOT_APPLIED = "__none__"
        @Volatile private var appliedCrop = NOT_APPLIED
        @Volatile private var isApplying = false
        @Volatile private var lastUpdateCheckMs = 0L
        private val HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

        @JvmStatic fun reset() {
            appliedCrop = NOT_APPLIED; isApplying = false; lastUpdateCheckMs = 0L
            feastNextFetchMs = 0L
            feastInitialFetchComplete = false
            lastInitialFetchWaitDebugMs = 0L
        }

        @JvmStatic fun update() {
            val client = Minecraft.getInstance()
            if (client.player == null || client.connection == null || !AetherConfig.DYNAMIC_PESTS_ENABLED.get() || !MacroStateManager.isMacroRunning()) return
            tickFeastData(); tickJacob(client)
            if (shouldWaitForInitialFeastFetch()) {
                val now = System.currentTimeMillis()
                if (now - lastInitialFetchWaitDebugMs >= 5000L) { lastInitialFetchWaitDebugMs = now; ClientUtils.sendDebugMessage("DynamicPests: waiting for initial Feast data before applying fallback") }
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastUpdateCheckMs < UPDATE_INTERVAL_MS) return
            lastUpdateCheckMs = now
            applyIfChanged(client, resolveTargetCrop())
        }

        @JvmStatic fun getAvailableCrops(): List<String> = java.util.List.copyOf(PestCropData.BY_CROP.keys)
        @JvmStatic fun triggerTestApply(client: Minecraft?, cropName: String?): Boolean {
            if (client == null || client.player == null || client.connection == null) return false
            val normalizedCrop = normalizeRequestedCrop(cropName) ?: return false
            if (isApplying || PestManager.isCleaningInProgress() || AutoSprayonatorManager.isRunning()) return false
            isApplying = true
            MacroWorkerThread.getInstance().submit("DynamicPests-TestApply", Runnable {
                try {
                    if (shouldAbortApply(client, false) || PestManager.isCleaningInProgress() || AutoSprayonatorManager.isRunning()) return@Runnable
                    if (doApply(client, normalizedCrop, true, false)) appliedCrop = normalizedCrop
                } catch (e: Exception) { ClientUtils.sendDebugMessage("DynamicPests test apply error: " + e.message) }
                finally { isApplying = false }
            })
            return true
        }
        private fun tickFeastData() {
            if (feastFetchInProgress || System.currentTimeMillis() < feastNextFetchMs) return
            feastFetchInProgress = true
            val request = HttpRequest.newBuilder().uri(URI.create(FEAST_API_URL)).timeout(Duration.ofSeconds(10)).header("User-Agent", "Mozilla/5.0").GET().build()
            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
                try {
                    if (error != null || response == null || response.statusCode() != 200) { feastNextFetchMs = System.currentTimeMillis() + FEAST_FALLBACK_POLL_MS; return@whenComplete }
                    val snapshot = HarvestFeastSnapshot.parse(response.body(), System.currentTimeMillis(), FEAST_FALLBACK_POLL_MS)
                    feastActive = snapshot.active(); feastActiveCrops = snapshot.activeCrops(); feastNextFetchMs = snapshot.nextFetchMs()
                } catch (_: Exception) { feastNextFetchMs = System.currentTimeMillis() + FEAST_FALLBACK_POLL_MS }
                finally { feastInitialFetchComplete = true; feastFetchInProgress = false }
            }
        }
        private fun tickJacob(client: Minecraft) {
            val utc = LocalTime.now(ZoneOffset.UTC); val minute = utc.minute; val second = utc.second
            val inParseWindow = (minute == 14 && second >= 30) || (minute >= 15 && minute < 35)
            if (!inParseWindow) { if (!jacobCleared) { jacobContestCrops = java.util.List.of(); jacobParsed = false; jacobCleared = true }; return }
            jacobCleared = false
            if (jacobParsed) return
            val now = System.currentTimeMillis(); if (now - lastJacobParseMs < 5000L) return
            lastJacobParseMs = now
            val crops = DynamicPestPolicy.parseContestCrops(TablistUtils.getRawTabLines(client))
            if (crops.isNotEmpty()) { jacobContestCrops = java.util.List.copyOf(crops); jacobParsed = true }
        }
        private fun resolveTargetCrop(): String? = DynamicPestPolicy.resolve(AetherConfig.DYNAMIC_PESTS_MODE.get(), feastActive, feastActiveCrops, AetherConfig.DYNAMIC_PESTS_FEAST_PRIORITY.get(), jacobContestCrops, AetherConfig.DYNAMIC_PESTS_CONTEST_PRIORITY.get())
        private fun shouldWaitForInitialFeastFetch(): Boolean {
            if (feastInitialFetchComplete) return false
            val mode = AetherConfig.DYNAMIC_PESTS_MODE.get()
            return mode == 0 || (mode == 2 && jacobContestCrops.isEmpty())
        }
        private fun normalizeRequestedCrop(cropName: String?): String? {
            if (cropName == null || cropName.isBlank()) return null
            val requested = cropName.trim()
            for (crop in PestCropData.BY_CROP.keys) if (crop.equals(requested, true)) return crop
            return null
        }
        private fun applyIfChanged(client: Minecraft, targetCrop: String?) {
            val targetKey = targetCrop ?: "null"
            if (!NOT_APPLIED.equals(appliedCrop) && Objects.equals(targetKey, appliedCrop)) return
            if (isApplying || MacroStateManager.getCurrentState() != MacroState.State.FARMING || PestManager.isCleaningInProgress() || AutoSprayonatorManager.isRunning()) return
            isApplying = true
            MacroWorkerThread.getInstance().submit("DynamicPests-Apply", Runnable {
                try {
                    if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING) || PestManager.isCleaningInProgress() || AutoSprayonatorManager.isRunning()) return@Runnable
                    if (doApply(client, targetCrop, false, true)) appliedCrop = targetKey
                } catch (e: Exception) { ClientUtils.sendDebugMessage("DynamicPests apply error: " + e.message) }
                finally { isApplying = false }
            })
        }
        private fun doApply(client: Minecraft, crop: String?, force: Boolean, requireMacroRunning: Boolean): Boolean {
            val guiDelay = ClientUtils.getGuiClickDelayMs(false)
            val sprayMaterial: String
            val vinylName: String?
            if (crop == null) {
                val sprayIdx = clamp(AetherConfig.DYNAMIC_PESTS_FALLBACK_SPRAY.get(), SPRAY_OPTIONS.size)
                val vinylIdx = clamp(AetherConfig.DYNAMIC_PESTS_FALLBACK_VINYL.get(), VINYL_OPTIONS.size)
                sprayMaterial = SPRAY_OPTIONS[sprayIdx]
                vinylName = PestCropData.BY_PEST[VINYL_OPTIONS[vinylIdx]]?.vinyl()
            } else {
                val profile = PestCropData.BY_CROP[crop] ?: return false
                sprayMaterial = profile.spray(); vinylName = profile.vinyl()
            }
            val currentSprayMaterial = AutoSprayonatorManager.getHotbarMaterial(client)
            val sprayAlreadyCorrect = currentSprayMaterial != null && currentSprayMaterial.equals(sprayMaterial, true)
            val vinylAlreadyCorrect = vinylName == null || VinylManager.isTargetVinylPlaying(client, vinylName)
            val gardenTimeAlreadyCorrect = isGardenTimeAlreadyCorrect(client, crop)
            if (!force && sprayAlreadyCorrect && vinylAlreadyCorrect && gardenTimeAlreadyCorrect) { ClientUtils.sendDebugMessage("DynamicPests: spray, vinyl, and garden time already match target, skipping apply"); return true }
            val previousState = MacroStateManager.getCurrentState(); val macroWasRunning = MacroStateManager.isMacroRunning()
            PestManager.setCleaningInProgress(true)
            if (macroWasRunning) { MacroStateManager.setCurrentState(MacroState.State.SPRAYING); client.execute { FarmingMacroManager.disable(client) }; MacroWorkerThread.sleep(guiDelay) }
            try {
                if (shouldAbortApply(client, requireMacroRunning)) return false
                faceStraightDown(client)
                if (shouldAbortApply(client, requireMacroRunning)) return false
                var sprayApplied = false; var sprayChanged = false
                if (AutoSprayonatorManager.holdSprayonator(client)) {
                    if (shouldAbortApply(client, requireMacroRunning)) return false
                    val previousMaterial = AutoSprayonatorManager.getHeldMaterial(client)
                    sprayApplied = AutoSprayonatorManager.ensureMaterial(client, sprayMaterial, guiDelay)
                    val currentMaterial = AutoSprayonatorManager.getHeldMaterial(client)
                    sprayChanged = if (previousMaterial == null) currentMaterial != null && currentMaterial.equals(sprayMaterial, true) else !previousMaterial.equals(sprayMaterial, true) && currentMaterial != null && currentMaterial.equals(sprayMaterial, true)
                } else ClientUtils.sendMessage("\u00A7cDynamic Pests: Sprayonator not found in hotbar.")
                if (!sprayApplied) { ClientUtils.sendDebugMessage("DynamicPests: failed to set spray material " + sprayMaterial); return false }
                if (sprayChanged && !AutoSprayonatorManager.sprayHeldMaterialAndHandleMissing(client, guiDelay)) { ClientUtils.sendDebugMessage("DynamicPests: failed to trigger spray after material swap"); return false }
                if (shouldAbortApply(client, requireMacroRunning)) return false
                if (vinylName != null && !VinylManager.setVinyl(client, vinylName)) { ClientUtils.sendDebugMessage("DynamicPests: failed to set vinyl " + vinylName); return false }
                if (shouldAbortApply(client, requireMacroRunning)) return false
                if (!switchGardenTimeForCrop(client, crop)) { ClientUtils.sendDebugMessage("DynamicPests: failed to switch garden time for " + crop); return false }
                if (shouldAbortApply(client, requireMacroRunning)) return false
                ClientUtils.sendMessage("\u00A7aDynamic Pests: Switched to \u00A7e" + (crop ?: "fallback") + "\u00A7a.")
                return true
            } finally {
                GearManager.swapToFarmingToolSync(client)
                if (macroWasRunning) { SqueakyMousematManager.armReapplyAttempt(); client.execute { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) }; MacroStateManager.setCurrentState(MacroState.State.FARMING) }
                else if (MacroStateManager.getCurrentState() != previousState) MacroStateManager.setCurrentState(previousState)
                PestManager.setCleaningInProgress(false)
            }
        }
        private fun shouldAbortApply(client: Minecraft?, requireMacroRunning: Boolean) = MacroWorkerThread.getInstance().isCancelled() || client == null || client.player == null || (requireMacroRunning && !MacroStateManager.isMacroRunning())
        private fun isGardenTimeAlreadyCorrect(client: Minecraft, crop: String?) = when (crop) { "Sunflower" -> GardenTimeManager.isDaytime(client); "Moonflower" -> GardenTimeManager.isNightTime(client); else -> true }
        private fun switchGardenTimeForCrop(client: Minecraft, crop: String?) = when (crop) { "Sunflower" -> GardenTimeManager.switchToDaytime(client); "Moonflower" -> GardenTimeManager.switchToNightTime(client); else -> true }
        private fun faceStraightDown(client: Minecraft) {
            if (client.player == null) return
            client.execute { client.player?.let { RotationManager.rotateToYawPitch(client, it.yRot, 90.0f, AetherConfig.ROTATION_TIME.get().toLong(), true) } }
            MacroWorkerThread.sleep(AetherConfig.ROTATION_TIME.get().toLong() + 50L)
            val deadline = System.currentTimeMillis() + 1500L
            while (RotationManager.isRotating() && System.currentTimeMillis() < deadline) MacroWorkerThread.sleep(25)
        }
        private fun clamp(value: Int, size: Int): Int = Math.max(0, Math.min(size - 1, value))
    }
}
