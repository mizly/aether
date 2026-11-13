package dev.aether.modules.pest.helpers

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import java.util.Deque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class PestDestroyerRuntime {
    @JvmField @Volatile var state: PestDestroyer.State = PestDestroyer.State.IDLE
    @JvmField @Volatile var active: Boolean = false
    @JvmField var currentTarget: Entity? = null
    @JvmField val killedEntities: MutableList<Entity> = CopyOnWriteArrayList<Entity>()
    @JvmField val deferredTargets: PestTargetDeferrals = PestTargetDeferrals()
    @JvmField val flightController = PestFlightController()
    @JvmField val flightRecovery = PestFlightRecovery()
    @JvmField val pestTargetQueue: Deque<Entity> = ArrayDeque()
    @JvmField val accountedKilledPestEntityIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @JvmField var stateEnteredAt = 0L
    @JvmField var lastVacuumUseAt = 0L
    @JvmField var lastPreRotateAt = 0L
    @JvmField var killVacuumHoldStartedAt = 0L
    @JvmField var killVacuumRetryPressAt = 0L
    @JvmField var killVacuumReleaseUntil = 0L
    @JvmField var stuckTicks = 0
    @JvmField var approachTicks = 0
    @JvmField var flyTapTicks = 0

    @JvmField var vacuumSlot = -1
    @JvmField var stunVacuumSlot = -1
    @JvmField var killVacuumSlot = -1
    @JvmField var vacuumRange = 7.5f

    @JvmField var lassoSlot = -1
    @JvmField var huntStage = PestHuntingController.Stage.STUN
    @JvmField var huntStartedAt = 0L
    @JvmField var huntStageEnteredAt = 0L
    @JvmField var huntThrownAt = 0L
    @JvmField var huntLastReelClickAt = 0L
    @JvmField var huntLastAttachedAt = 0L
    @JvmField var huntAttachedSince = 0L
    @JvmField @Volatile var huntReelSignalAt = 0L
    @JvmField var huntThrowCount = 0
    @JvmField var huntReelCount = 0
    @JvmField var huntEverAttached = false
    @JvmField var huntReelPromptLatched = false
    @JvmField var huntReelPromptTicks = 0
    @JvmField var huntReelPromptClearTicks = 0
    @JvmField var huntReelAwaitingResponseUntil = 0L
    @JvmField var huntReelPromptArmed = true
    @JvmField var huntSwapReadyTick = 0
    @JvmField var huntDelayBeforeStunSwap = false
    @JvmField var huntStunDelivered = false
    @JvmField var huntStunHoldStartedAt = 0L
    @JvmField var huntStunRangeSince = 0L
    @JvmField var huntFollowMove = 0
    @JvmField var huntLeashStretchSince = 0L
    @JvmField var huntAimCorrecting = false
    @JvmField var huntCloseRate = 0.0
    @JvmField var huntLastHorizontal = Double.NaN
    @JvmField var huntCloseRateTargetId = -1
    @JvmField var huntStageEnteredTick = 0
    @JvmField var huntLandingWaitStartedAt = 0L
    @JvmField var huntTargetY = Double.NaN
    @JvmField @Volatile var huntCaughtSignal = false
    @JvmField @Volatile var huntEscapeSignal = false
    @JvmField var huntFocus: Entity? = null
    @JvmField var huntPendingFocus: Entity? = null
    @JvmField var huntPendingFocusSince = 0L
    @JvmField var huntAimFocusId = -1
    @JvmField var huntLastAimPoint: Vec3? = null
    @JvmField var huntAimBlendOffset: Vec3? = null
    @JvmField var huntAimBlendStartedAt = 0L
    @JvmField var currentTargetUsesLasso = false

    @JvmField var aotvSlot = -1
    @JvmField var aotvUseCount = 0
    @JvmField var aotvLastUseAt = 0L
    @JvmField var aotvNextUseAt = 0L
    @JvmField var aotvPostClickGraceUntil = 0L
    @JvmField var aotvPendingUseAt = 0L
    @JvmField var aotvAimStartedAt = 0L
    @JvmField var aotvLastUsePlayerX = Double.NaN
    @JvmField var aotvLastUsePlayerY = Double.NaN
    @JvmField var aotvLastUsePlayerZ = Double.NaN
    @JvmField var arrivedAtCurrentTargetViaAotv = false
    @JvmField @Volatile var aotvStartY = Double.NaN
    @JvmField var activatedAt = 0L
    @JvmField var lastRoofRescanAt = 0L
    @JvmField var roofAotvReturnState: PestDestroyer.State? = null

    @JvmField var zeroPestTabTicks = 0
    @JvmField var targetWithoutSkullTicks = 0
    @JvmField var airborneRecoveryActive = false
    @JvmField var airborneRecoveryTargetEntityId = -1
    @JvmField var airborneRecoveryAimPoint: Vec3? = null
    @JvmField var airborneRecoveryAimUpdatedAt = 0L
    @JvmField var pestEtherwarpActive = false
    @JvmField var pestEtherwarpTargetEntityId = -1
    @JvmField var pestEtherwarpAimPoint: Vec3? = null
    @JvmField var pestEtherwarpClickAt = 0L
    @JvmField var pestEtherwarpRetryAfter = 0L
    @JvmField var pestEtherwarpLandingBlock: BlockPos? = null
    @JvmField val pestEtherwarpFailedBlocksUntil: MutableMap<Long, Long> = ConcurrentHashMap()
    @JvmField var pestEtherwarpMaintainHeight = false
    @JvmField var pestEtherwarpJumpHeld = false

    @JvmField val navigation: PestNavigationState = PestNavigationState()

    fun beginRun(detectedVacuumSlot: Int, now: Long) {
        active = true
        state = PestDestroyer.State.IDLE
        stateEnteredAt = now
        activatedAt = now
        currentTarget = null
        currentTargetUsesLasso = false
        killedEntities.clear()
        deferredTargets.clear()
        pestTargetQueue.clear()
        accountedKilledPestEntityIds.clear()
        vacuumSlot = detectedVacuumSlot
        killVacuumSlot = detectedVacuumSlot
        vacuumRange = 7.5f
        resetTransientState()
        navigation.resetForRun()
    }

    fun stopRun() {
        active = false
        state = PestDestroyer.State.IDLE
        currentTarget = null
        currentTargetUsesLasso = false
        killedEntities.clear()
        deferredTargets.clear()
        pestTargetQueue.clear()
        accountedKilledPestEntityIds.clear()
        targetWithoutSkullTicks = 0
        navigation.resetForRun()
        resetTransientState()
    }

    fun resetAll() {
        stopRun()
        lastPreRotateAt = 0L
    }

    fun transitionTo(newState: PestDestroyer.State, now: Long) {
        if (newState == PestDestroyer.State.GET_LOCATION) navigation.trackerSearch.beginSearch()
        state = newState
        stateEnteredAt = now
        stuckTicks = 0
        approachTicks = 0
        flyTapTicks = 0
        flightRecovery.reset()
        if (newState == PestDestroyer.State.CHECK_NEXT || newState == PestDestroyer.State.FINISH || newState == PestDestroyer.State.IDLE) {
            arrivedAtCurrentTargetViaAotv = false
            flightController.reset()
        }
        if (newState != PestDestroyer.State.AOTV_BETWEEN_PESTS) {
            aotvLastUseAt = 0L
            aotvNextUseAt = 0L
            aotvPostClickGraceUntil = 0L
            aotvPendingUseAt = 0L
            aotvAimStartedAt = 0L
            aotvLastUsePlayerX = Double.NaN
            aotvLastUsePlayerY = Double.NaN
            aotvLastUsePlayerZ = Double.NaN
            pestEtherwarpActive = false
            pestEtherwarpTargetEntityId = -1
            pestEtherwarpAimPoint = null
            pestEtherwarpClickAt = 0L
            pestEtherwarpRetryAfter = 0L
            pestEtherwarpLandingBlock = null
        }
        if (newState != PestDestroyer.State.KILL_PEST) {
            targetWithoutSkullTicks = 0
            lastPreRotateAt = 0L
            resetKillVacuumRetry()
            resetAirborneRecovery()
        }
        if (newState != PestDestroyer.State.HUNT_PEST) resetHuntState()
    }

    fun resetKillVacuumRetry() {
        killVacuumHoldStartedAt = 0L
        killVacuumRetryPressAt = 0L
        killVacuumReleaseUntil = 0L
    }

    fun claimKilledPestEntityId(entityId: Int): Boolean = accountedKilledPestEntityIds.add(entityId)

    private fun resetTransientState() {
        flightController.reset()
        stuckTicks = 0
        approachTicks = 0
        flyTapTicks = 0
        zeroPestTabTicks = 0
        targetWithoutSkullTicks = 0
        resetAirborneRecovery()
        lastVacuumUseAt = 0L
        flightRecovery.reset()
        killVacuumHoldStartedAt = 0L
        killVacuumRetryPressAt = 0L
        killVacuumReleaseUntil = 0L
        aotvSlot = -1
        aotvUseCount = 0
        aotvLastUseAt = 0L
        aotvNextUseAt = 0L
        aotvPostClickGraceUntil = 0L
        aotvPendingUseAt = 0L
        aotvAimStartedAt = 0L
        aotvLastUsePlayerX = Double.NaN
        aotvLastUsePlayerY = Double.NaN
        aotvLastUsePlayerZ = Double.NaN
        pestEtherwarpActive = false
        pestEtherwarpTargetEntityId = -1
        pestEtherwarpAimPoint = null
        pestEtherwarpClickAt = 0L
        pestEtherwarpRetryAfter = 0L
        pestEtherwarpLandingBlock = null
        pestEtherwarpFailedBlocksUntil.clear()
        pestEtherwarpMaintainHeight = false
        pestEtherwarpJumpHeld = false
        arrivedAtCurrentTargetViaAotv = false
        aotvStartY = Double.NaN
        lastRoofRescanAt = 0L
        roofAotvReturnState = null
        resetHuntState()
    }

    fun resetAirborneRecovery() {
        airborneRecoveryActive = false
        airborneRecoveryTargetEntityId = -1
        airborneRecoveryAimPoint = null
        airborneRecoveryAimUpdatedAt = 0L
    }

    fun resetHuntState() {
        PestHuntingController.releaseStunVacuum(this)
        lassoSlot = -1
        huntStage = PestHuntingController.Stage.STUN
        huntStartedAt = 0L
        huntStageEnteredAt = 0L
        huntThrownAt = 0L
        huntLastReelClickAt = 0L
        huntLastAttachedAt = 0L
        huntAttachedSince = 0L
        huntReelSignalAt = 0L
        huntThrowCount = 0
        huntReelCount = 0
        huntEverAttached = false
        huntReelPromptLatched = false
        huntReelPromptTicks = 0
        huntReelAwaitingResponseUntil = 0L
        huntReelPromptClearTicks = 0
        huntReelPromptArmed = true
        huntSwapReadyTick = 0
        huntDelayBeforeStunSwap = false
        huntStunDelivered = false
        huntStunHoldStartedAt = 0L
        huntStunRangeSince = 0L
        huntFollowMove = 0
        huntLeashStretchSince = 0L
        huntAimCorrecting = false
        huntCloseRate = 0.0
        huntLastHorizontal = Double.NaN
        huntCloseRateTargetId = -1
        huntStageEnteredTick = 0
        huntLandingWaitStartedAt = 0L
        huntTargetY = Double.NaN
        huntCaughtSignal = false
        huntEscapeSignal = false
        resetHuntAimState()
    }

    fun resetHuntAimState() {
        huntFocus = null
        huntPendingFocus = null
        huntPendingFocusSince = 0L
        huntAimFocusId = -1
        huntLastAimPoint = null
        huntAimBlendOffset = null
        huntAimBlendStartedAt = 0L
    }
}
