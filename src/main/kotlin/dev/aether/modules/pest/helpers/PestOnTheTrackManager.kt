package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroInput
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.mixin.AccessorInventory
import dev.aether.modules.CropFeverManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

class PestOnTheTrackManager private constructor() {
    enum class State { IDLE, PREPARING_TO_SUCK, SUCKING_CANDIDATE, RESTORING }
    @Volatile private var state = State.IDLE
    private var trackedPest: Entity? = null
    private var candidateSelectedAt = 0L
    private var foundSuckTargetAt = 0L
    private var noneSuckTargetSince = 0L
    private var vacuumSlot = -1
    private var vacuumRange = 0.0
    private var hasPreSuckRotation = false
    private var preSuckYaw = 0f
    private var preSuckPitch = 0f
    @Volatile private var mousematWorkerPending = false
    @Volatile private var currentRestoreAttemptId = 0L
    private val accountedKills = IntOpenHashSet()

    fun update(client: Minecraft?) {
        if (client == null || client.player == null || client.level == null || client.options == null) { resetOTTState(client, false); return }
        if (state != State.IDLE && !canRemainActive(client)) { if (state == State.SUCKING_CANDIDATE) resetOTTState(client, true) else clearOTTState(); return }
        when (state) {
            State.IDLE -> updateWhileIdle(client)
            State.PREPARING_TO_SUCK -> updateWhilePreparing(client)
            State.SUCKING_CANDIDATE -> updateWhileSucking(client)
            State.RESTORING -> updateWhileRestoring(client)
        }
    }

    private fun updateWhileIdle(client: Minecraft) {
        if (!canStartOTT(client) || !requireAndUpdateVac(client)) return
        val found = selectEligiblePest(client, true) ?: return
        trackedPest = found; candidateSelectedAt = System.currentTimeMillis(); state = State.PREPARING_TO_SUCK
    }
    private fun updateWhilePreparing(client: Minecraft) {
        if (!canStartOTT(client) || !requireAndUpdateVac(client)) { clearOTTState(); return }
        val found = selectEligiblePest(client, true) ?: run { clearOTTState(); return }
        val now = System.currentTimeMillis()
        if (trackedPest == null || trackedPest!!.id != found.id) { trackedPest = found; candidateSelectedAt = now; return }
        if (now - candidateSelectedAt >= AetherConfig.PEST_ON_THE_TRACK_ACQUIRE_DELAY_MS.get()) beginSuckingTheCandidate(client, now)
    }
    private fun updateWhileSucking(client: Minecraft) {
        val now = System.currentTimeMillis()
        if (!requireAndUpdateVac(client)) { resetOTTState(client, true); return }
        accountForTheDeathOfPreviousTarget(client)
        val found = selectEligiblePest(client, false)
        if (found == null) {
            ClientUtils.setKeyMappingState(client.options!!.keyUse, false)
            if (noneSuckTargetSince == 0L) noneSuckTargetSince = now else if (now - noneSuckTargetSince >= NO_TARGET_GRACE_MS) resetOTTState(client, true)
            return
        }
        if (trackedPest == null || trackedPest!!.id != found.id) { trackedPest = found; foundSuckTargetAt = now }
        noneSuckTargetSince = 0L
        if (now - foundSuckTargetAt >= AetherConfig.PEST_ON_THE_TRACK_STUCK_TIMEOUT_MS.get().toLong()) { ClientUtils.sendDebugMessage("[Pest-On-The-Track] Sucking timed out! Resuming farming..."); resetOTTState(client, true); return }
        MacroInput.releaseMovement(client); MacroInput.setAttack(client.options!!.keyAttack, false)
        if ((client.player!!.inventory as AccessorInventory).getSelected() != vacuumSlot) { ClientUtils.setKeyMappingState(client.options!!.keyUse, false); FailsafeManager.selectHotbarSlot(client, vacuumSlot); return }
        val aimTarget = PestCombatCoordinator.buildCombatAimTarget(client, trackedPest ?: return)
        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) { ClientUtils.setKeyMappingState(client.options!!.keyUse, false); return }
        RotationManager.forceRotation(client, aimTarget, AIM_REFRESH_MS.toLong()); ClientUtils.setKeyMappingState(client.options!!.keyUse, true)
    }
    private fun beginSuckingTheCandidate(client: Minecraft, timestamp: Long) {
        foundSuckTargetAt = timestamp; noneSuckTargetSince = 0L; accountedKills.clear()
        hasPreSuckRotation = true; preSuckYaw = client.player!!.yRot; preSuckPitch = client.player!!.xRot; state = State.SUCKING_CANDIDATE
        MacroInput.releaseMovement(client); MacroInput.setAttack(client.options!!.keyAttack, false); ClientUtils.setKeyMappingState(client.options!!.keyUse, false)
        FailsafeManager.selectHotbarSlot(client, vacuumSlot)
        ClientUtils.sendDebugMessage("[Pest-On-The-Track] A pest entered vacuum range. Pausing farming input and preparing to suck")
    }
    private fun updateWhileRestoring(client: Minecraft) {
        MacroInput.releaseMovement(client); MacroInput.setAttack(client.options!!.keyAttack, false); ClientUtils.setKeyMappingState(client.options!!.keyUse, false)
        if (mousematWorkerPending) return
        if (!RotationManager.isRotating()) finishFarmingRestore()
    }
    private fun selectEligiblePest(client: Minecraft, needsInFOV: Boolean): Entity? {
        val margin = if (AetherConfig.RESPECT_VACUUM_TRUE_RANGE.get()) 0.0 else RANGE_SAFETY_MARGIN
        return PestTargetTracker.selectClosestPestWithin(client, Math.max(0.0, vacuumRange - margin)) { target -> !needsInFOV || isWithinGrabFOV(client, target) }
    }
    private fun isWithinGrabFOV(client: Minecraft, target: Entity): Boolean {
        val playerEyePos = client.player!!.eyePosition
        val targetEyePos = target.position().add(0.0, target.getEyeHeight(target.pose).toDouble(), 0.0)
        val deltaX = targetEyePos.x - playerEyePos.x; val deltaZ = targetEyePos.z - playerEyePos.z
        val targetYaw = Math.toDegrees(Math.atan2(deltaZ, deltaX)).toFloat() - 90f
        val yawOffset = Math.abs(Mth.wrapDegrees(targetYaw - client.player!!.yRot))
        return yawOffset <= AetherConfig.PEST_ON_THE_TRACK_FOV.get() * 0.5f
    }
    private fun requireAndUpdateVac(client: Minecraft): Boolean {
        val foundSlot = PestLoadoutHelper.findVacuumHotbarSlot(client)
        if (foundSlot < 0) { if (state == State.SUCKING_CANDIDATE) ClientUtils.sendMessage("\u00A7cOn-The-Track pest destroyer needs a vacuum in the hotbar!!", true); return false }
        vacuumSlot = foundSlot; vacuumRange = PestLoadoutHelper.detectVacuumRange(client, foundSlot).toDouble(); return true
    }
    private fun canStartOTT(client: Minecraft): Boolean = AetherConfig.PEST_ON_TRACK_ENABLED.get() && canRemainActive(client) && client.gui.screen() == null && client.player!!.containerMenu == client.player!!.inventoryMenu && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN && !PathfindingManager.isNavigating() && !AutoPestExchangeManager.isRunning() && !AutoPestExchangeManager.shouldBlockFarmingResume() && !AutoSprayonatorManager.isRunning() && !PestDestroyer.isActive() && !PestManager.isCleaningInProgress() && !LoadoutManager.isSwappingLoadout && (!AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() || !CropFeverManager.isCropFeverActive) && (!AetherConfig.PEST_ON_THE_TRACK_SKIP_JACOB.get() || ClientUtils.getJacobsContestRemainingMs() <= 0L)
    private fun canRemainActive(client: Minecraft): Boolean = AetherConfig.PEST_ON_TRACK_ENABLED.get() && MacroStateManager.getCurrentState() == MacroState.State.FARMING && FarmingMacroManager.isActive() && client.connection != null
    private fun resetOTTState(client: Minecraft?, restoreTool: Boolean) {
        releaseOwnedInput(client)
        if (restoreTool && client?.player != null && MacroStateManager.getCurrentState() == MacroState.State.FARMING && FarmingMacroManager.isActive()) { beginFarmingRestore(client); return }
        clearOTTState(); ClientUtils.sendDebugMessage("[Pest-On-The-Track] Resuming farming input...")
    }
    private fun beginFarmingRestore(client: Minecraft) {
        state = State.RESTORING; val restoreAttemptId = ++currentRestoreAttemptId
        ClientUtils.sendDebugMessage("[Pest-On-The-Track] Restoring prior farming direction...")
        if (AetherConfig.SQUEAKY_MOUSEMAT.get()) {
            ClientUtils.sendDebugMessage("[Pest-On-The-Track] Restoring direction using mousemat (id: $restoreAttemptId)"); mousematWorkerPending = true
            MacroWorkerThread.getInstance().submit("POTT:MousematWorker") {
                var hasRestored = false
                try {
                    if (state == State.RESTORING && currentRestoreAttemptId == restoreAttemptId) {
                        hasRestored = SqueakyMousematManager.useIfNeeded(client)
                        if (currentRestoreAttemptId == restoreAttemptId && !MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) GearManager.swapToFarmingToolSync(client)
                    }
                } catch (e: Exception) { ClientUtils.sendDebugMessage("[Pest-On-The-Track] Mousemat restore exception: ${e.message}") }
                finally {
                    val mousematOk = hasRestored
                    client.execute {
                        if (state != State.RESTORING || currentRestoreAttemptId != restoreAttemptId) return@execute
                        mousematWorkerPending = false
                        if (mousematOk) finishFarmingRestore() else startFallbackRotation(client)
                    }
                }
            }
            return
        }
        ClientUtils.sendDebugMessage("[Pest-On-The-Track] Swapping to the prev tool..."); GearManager.swapToFarmingTool(client)
        ClientUtils.sendDebugMessage("[Pest-On-The-Track] Restoring direction using head rotation (id: $restoreAttemptId)"); startFallbackRotation(client)
    }
    private fun startFallbackRotation(client: Minecraft) {
        if (AetherConfig.MACRO_USE_CUSTOM_YAW.get() || AetherConfig.MACRO_USE_CUSTOM_PITCH.get()) { FarmingMacroManager.restoreConfiguredOrientation(client); ClientUtils.sendDebugMessage("[Pest-On-The-Track] Using configured values to restore...") }
        else if (hasPreSuckRotation && client.player != null) { RotationManager.rotateToYawPitch(client, preSuckYaw, preSuckPitch, AetherConfig.ROTATION_TIME.get().toLong(), true); ClientUtils.sendDebugMessage("[Pest-On-The-Track] Using pre-suck values to restore...") }
        else { ClientUtils.sendMessage("\u00A7cUNABLE TO RESTORE ON-THE-TRACK FARMING DIRECTION! PLEASE REPORT THIS!", false); finishFarmingRestore() }
    }
    private fun finishFarmingRestore() { clearOTTState(); ClientUtils.sendDebugMessage("[Pest-On-The-Track] Farming direction restored. Resuming input...") }
    fun reset(client: Minecraft?) = resetOTTState(client, false)
    private fun releaseOwnedInput(client: Minecraft?) {
        if (client?.options == null) return
        ClientUtils.setKeyMappingState(client.options!!.keyUse, false); MacroInput.releaseMovement(client); MacroInput.setAttack(client.options!!.keyAttack, false)
        if (state == State.SUCKING_CANDIDATE) RotationManager.cancelRotation()
    }
    private fun clearOTTState() {
        currentRestoreAttemptId++; state = State.IDLE; trackedPest = null; candidateSelectedAt = 0L; foundSuckTargetAt = 0L; noneSuckTargetSince = 0L
        vacuumSlot = -1; vacuumRange = 0.0; mousematWorkerPending = false; accountedKills.clear(); hasPreSuckRotation = false; preSuckYaw = 0f; preSuckPitch = 0f
    }
    private fun accountForTheDeathOfPreviousTarget(client: Minecraft) {
        val pest = trackedPest ?: return
        if (!isDead(pest) || accountedKills.contains(pest.id)) return
        accountedKills.add(pest.id); PestManager.decrementPredictedAliveCount(client)
    }
    private fun isDead(entity: Entity): Boolean = entity.isRemoved || entity is LivingEntity && entity.isDeadOrDying
    fun isNotIdle(): Boolean = state != State.IDLE
    fun isBlockingFarming(): Boolean = state == State.SUCKING_CANDIDATE || state == State.RESTORING

    companion object {
        const val RANGE_SAFETY_MARGIN = 0.5
        const val NO_TARGET_GRACE_MS = 500L
        const val AIM_REFRESH_MS = 120
        private val INSTANCE = PestOnTheTrackManager()
        @JvmStatic fun getInstance(): PestOnTheTrackManager = INSTANCE
    }
}
