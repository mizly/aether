package dev.aether.modules.pest.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class PestDestroyerRuntime {
    volatile PestDestroyer.State state = PestDestroyer.State.IDLE;
    volatile boolean active = false;
    Entity currentTarget = null;
    // When Target Lock is enabled, this id remains committed until the pest is
    // killed, disappears, becomes invalid, or is explicitly deferred. Small
    // distance changes from moving pests must not reshuffle the active route.
    int lockedTargetEntityId = -1;
    final List<Entity> killedEntities = new CopyOnWriteArrayList<>();
    final PestTargetDeferrals deferredTargets = new PestTargetDeferrals();
    final Deque<Entity> pestTargetQueue = new ArrayDeque<>();
    final Set<Integer> accountedKilledPestEntityIds = ConcurrentHashMap.newKeySet();

    long stateEnteredAt = 0L;
    long lastVacuumUseAt = 0L;
    long lastPreRotateAt = 0L;
    long flyRetryAfterUnflyAt = 0L;
    long killVacuumHoldStartedAt = 0L;
    long killVacuumRetryPressAt = 0L;
    long killVacuumReleaseUntil = 0L;
    int stuckTicks = 0;
    int approachTicks = 0;

    int vacuumSlot = -1;
    int stunVacuumSlot = -1;
    int killVacuumSlot = -1;
    float vacuumRange = 7.5f;

    int lassoSlot = -1;
    PestHuntingController.Stage huntStage = PestHuntingController.Stage.STUN;
    long huntStartedAt = 0L;
    long huntStageEnteredAt = 0L;
    long huntThrownAt = 0L;
    long huntLastReelClickAt = 0L;
    long huntLastAttachedAt = 0L;
    long huntAttachedSince = 0L;
    volatile long huntReelSignalAt = 0L;
    int huntThrowCount = 0;
    int huntReelCount = 0;
    boolean huntEverAttached = false;
    boolean huntReelPromptLatched = false;
    int huntReelPromptTicks = 0;
    int huntReelPromptClearTicks = 0;
    long huntReelAwaitingResponseUntil = 0L;
    boolean huntReelPromptArmed = true;
    int huntSwapReadyTick = 0;
    boolean huntDelayBeforeStunSwap = false;
    boolean huntStunDelivered = false;
    long huntStunHoldStartedAt = 0L;
    long huntStunRangeSince = 0L;
    int huntFollowMove = 0;
    long huntLeashStretchSince = 0L;
    boolean huntAimCorrecting = false;
    double huntCloseRate = 0.0;
    double huntLastHorizontal = Double.NaN;
    int huntCloseRateTargetId = -1;
    int huntStageEnteredTick = 0;
    long huntLandingWaitStartedAt = 0L;
    double huntTargetY = Double.NaN;
    volatile boolean huntCaughtSignal = false;
    volatile boolean huntEscapeSignal = false;
    Entity huntFocus = null;
    Entity huntPendingFocus = null;
    long huntPendingFocusSince = 0L;
    int huntAimFocusId = -1;
    Vec3 huntLastAimPoint = null;
    Vec3 huntAimBlendOffset = null;
    long huntAimBlendStartedAt = 0L;
    boolean currentTargetUsesLasso = false;

    int aotvSlot = -1;
    int aotvUseCount = 0;
    long aotvLastUseAt = 0L;
    long aotvNextUseAt = 0L;
    long aotvPostClickGraceUntil = 0L;
    long aotvPendingUseAt = 0L;
    long aotvAimStartedAt = 0L;
    double aotvLastUsePlayerX = Double.NaN;
    double aotvLastUsePlayerY = Double.NaN;
    double aotvLastUsePlayerZ = Double.NaN;
    boolean arrivedAtCurrentTargetViaAotv = false;
    boolean pestEtherwarpActive = false;
    int pestEtherwarpTargetEntityId = -1;
    Vec3 pestEtherwarpAimPoint = null;
    long pestEtherwarpClickAt = 0L;
    long pestEtherwarpRetryAfter = 0L;
    BlockPos pestEtherwarpLandingBlock = null;
    // Landing blocks that looked valid client-side but failed to Etherwarp are
    // avoided briefly so repeated route decisions do not retry the same bad spot.
    final Map<Long, Long> pestEtherwarpFailedBlocksUntil = new ConcurrentHashMap<>();
    // After a successful pest Etherwarp, stay flight-ready by keeping the
    // player a few blocks above the local ground. This is deliberately kept
    // separate from pestEtherwarpActive so it can persist through the kill and
    // into the next target handoff.
    boolean pestEtherwarpMaintainHeight = false;
    boolean pestEtherwarpJumpHeld = false;
    volatile double aotvStartY = Double.NaN;
    long activatedAt = 0L;
    long lastRoofRescanAt = 0L;
    PestDestroyer.State roofAotvReturnState = null;

    int zeroPestTabTicks = 0;
    int targetWithoutSkullTicks = 0;

    // Stable normal-combat aim state. The sampled target point and deadzone
    // keep tiny pest movements from becoming a fresh head correction every tick.
    int combatAimTargetEntityId = -1;
    Vec3 combatAimPoint = null;
    long combatAimUpdatedAt = 0L;
    boolean combatAimCorrecting = false;

    // A jumping pest can briefly separate from its marker and move several
    // blocks vertically. Keep that recovery isolated from normal pathing/death
    // heuristics so the cleaner does not spin, repath, or switch targets.
    boolean airborneRecoveryActive = false;
    int airborneRecoveryTargetEntityId = -1;
    Vec3 airborneRecoveryAimPoint = null;
    long airborneRecoveryAimUpdatedAt = 0L;

    // Optional fast vacuum confirmation. The named-entity snapshot lets the
    // combat coordinator distinguish a newly spawned damage popup from health
    // bars/name tags that were already present around the pest.
    int oneTapTargetEntityId = -1;
    long oneTapVacuumNearStartedAt = 0L;
    final Set<Integer> oneTapKnownNamedEntityIds = ConcurrentHashMap.newKeySet();
    // One Tap is intentionally optimistic. Keep those handoffs separate from
    // confirmed deaths so the final scan can revive a pest that survived.
    final Map<Integer, Long> oneTapAssumedKilledAt = new ConcurrentHashMap<>();

    final PestNavigationState navigation = new PestNavigationState();

    void beginRun(int detectedVacuumSlot, long now) {
        active = true;
        state = PestDestroyer.State.IDLE;
        stateEnteredAt = now;
        activatedAt = now;
        currentTarget = null;
        lockedTargetEntityId = -1;
        currentTargetUsesLasso = false;
        killedEntities.clear();
        deferredTargets.clear();
        pestTargetQueue.clear();
        accountedKilledPestEntityIds.clear();
        vacuumSlot = detectedVacuumSlot;
        killVacuumSlot = detectedVacuumSlot;
        vacuumRange = 7.5f;
        resetTransientState();
        navigation.resetForRun();
    }

    void stopRun() {
        active = false;
        state = PestDestroyer.State.IDLE;
        currentTarget = null;
        lockedTargetEntityId = -1;
        currentTargetUsesLasso = false;
        killedEntities.clear();
        deferredTargets.clear();
        pestTargetQueue.clear();
        accountedKilledPestEntityIds.clear();
        targetWithoutSkullTicks = 0;
        navigation.resetForRun();
        resetTransientState();
    }

    void resetAll() {
        stopRun();
        lastPreRotateAt = 0L;
    }

    void transitionTo(PestDestroyer.State newState, long now) {
        state = newState;
        stateEnteredAt = now;
        stuckTicks = 0;
        approachTicks = 0;
        flyRetryAfterUnflyAt = 0L;
        if (newState == PestDestroyer.State.CHECK_NEXT
                || newState == PestDestroyer.State.FINISH
                || newState == PestDestroyer.State.IDLE) {
            arrivedAtCurrentTargetViaAotv = false;
            resetCombatAimState();
        }
        if (newState != PestDestroyer.State.AOTV_BETWEEN_PESTS) {
            aotvLastUseAt = 0L;
            aotvNextUseAt = 0L;
            aotvPostClickGraceUntil = 0L;
            aotvPendingUseAt = 0L;
            aotvAimStartedAt = 0L;
            aotvLastUsePlayerX = Double.NaN;
            aotvLastUsePlayerY = Double.NaN;
            aotvLastUsePlayerZ = Double.NaN;
            pestEtherwarpActive = false;
            pestEtherwarpTargetEntityId = -1;
            pestEtherwarpAimPoint = null;
            pestEtherwarpClickAt = 0L;
            pestEtherwarpRetryAfter = 0L;
            pestEtherwarpLandingBlock = null;
        }
        if (newState != PestDestroyer.State.KILL_PEST) {
            targetWithoutSkullTicks = 0;
            lastPreRotateAt = 0L;
            resetKillVacuumRetry();
            resetOneTapTracking();
            resetAirborneRecovery();
        }
        if (newState != PestDestroyer.State.HUNT_PEST) {
            resetHuntState();
        }
    }

    void resetKillVacuumRetry() {
        killVacuumHoldStartedAt = 0L;
        killVacuumRetryPressAt = 0L;
        killVacuumReleaseUntil = 0L;
    }

    void resetOneTapTracking() {
        oneTapTargetEntityId = -1;
        oneTapVacuumNearStartedAt = 0L;
        oneTapKnownNamedEntityIds.clear();
    }

    void resetAirborneRecovery() {
        airborneRecoveryActive = false;
        airborneRecoveryTargetEntityId = -1;
        airborneRecoveryAimPoint = null;
        airborneRecoveryAimUpdatedAt = 0L;
    }

    void resetCombatAimState() {
        combatAimTargetEntityId = -1;
        combatAimPoint = null;
        combatAimUpdatedAt = 0L;
        combatAimCorrecting = false;
    }

    boolean claimKilledPestEntityId(int entityId) {
        return accountedKilledPestEntityIds.add(entityId);
    }

    private void resetTransientState() {
        stuckTicks = 0;
        approachTicks = 0;
        zeroPestTabTicks = 0;
        targetWithoutSkullTicks = 0;
        resetOneTapTracking();
        resetAirborneRecovery();
        resetCombatAimState();
        oneTapAssumedKilledAt.clear();
        lastVacuumUseAt = 0L;
        flyRetryAfterUnflyAt = 0L;
        killVacuumHoldStartedAt = 0L;
        killVacuumRetryPressAt = 0L;
        killVacuumReleaseUntil = 0L;
        aotvSlot = -1;
        aotvUseCount = 0;
        aotvLastUseAt = 0L;
        aotvNextUseAt = 0L;
        aotvPostClickGraceUntil = 0L;
        aotvPendingUseAt = 0L;
        aotvAimStartedAt = 0L;
        aotvLastUsePlayerX = Double.NaN;
        aotvLastUsePlayerY = Double.NaN;
        aotvLastUsePlayerZ = Double.NaN;
        pestEtherwarpActive = false;
        pestEtherwarpTargetEntityId = -1;
        pestEtherwarpAimPoint = null;
        pestEtherwarpClickAt = 0L;
        pestEtherwarpRetryAfter = 0L;
        pestEtherwarpLandingBlock = null;
        pestEtherwarpFailedBlocksUntil.clear();
        pestEtherwarpMaintainHeight = false;
        pestEtherwarpJumpHeld = false;
        arrivedAtCurrentTargetViaAotv = false;
        lockedTargetEntityId = -1;
        aotvStartY = Double.NaN;
        lastRoofRescanAt = 0L;
        roofAotvReturnState = null;
        resetHuntState();
    }

    void resetHuntState() {
        // A state change out from under a stun leaves the vacuum key held; the
        // hunt's own exits clear it, everything else lands here.
        PestHuntingController.releaseStunVacuum(this);
        lassoSlot = -1;
        huntStage = PestHuntingController.Stage.STUN;
        huntStartedAt = 0L;
        huntStageEnteredAt = 0L;
        huntThrownAt = 0L;
        huntLastReelClickAt = 0L;
        huntLastAttachedAt = 0L;
        huntAttachedSince = 0L;
        huntReelSignalAt = 0L;
        huntThrowCount = 0;
        huntReelCount = 0;
        huntEverAttached = false;
        huntReelPromptLatched = false;
        huntReelPromptTicks = 0;
        huntReelAwaitingResponseUntil = 0L;
        huntReelPromptClearTicks = 0;
        huntReelPromptArmed = true;
        huntSwapReadyTick = 0;
        huntDelayBeforeStunSwap = false;
        huntStunDelivered = false;
        huntStunHoldStartedAt = 0L;
        huntStunRangeSince = 0L;
        huntFollowMove = 0;
        huntLeashStretchSince = 0L;
        huntAimCorrecting = false;
        huntCloseRate = 0.0;
        huntLastHorizontal = Double.NaN;
        huntCloseRateTargetId = -1;
        huntStageEnteredTick = 0;
        huntLandingWaitStartedAt = 0L;
        huntTargetY = Double.NaN;
        huntCaughtSignal = false;
        huntEscapeSignal = false;
        resetHuntAimState();
    }

    void resetHuntAimState() {
        huntFocus = null;
        huntPendingFocus = null;
        huntPendingFocusSince = 0L;
        huntAimFocusId = -1;
        huntLastAimPoint = null;
        huntAimBlendOffset = null;
        huntAimBlendStartedAt = 0L;
    }
}
