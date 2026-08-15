package dev.aether.modules.pest.helpers;

import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class PestDestroyerRuntime {
    volatile PestDestroyer.State state = PestDestroyer.State.IDLE;
    volatile boolean active = false;
    Entity currentTarget = null;
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
    int huntStageEnteredTick = 0;
    long huntLandingWaitStartedAt = 0L;
    double huntTargetY = Double.NaN;
    volatile boolean huntCaughtSignal = false;
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
    volatile double aotvStartY = Double.NaN;
    long activatedAt = 0L;
    long lastRoofRescanAt = 0L;
    PestDestroyer.State roofAotvReturnState = null;

    int zeroPestTabTicks = 0;
    int targetWithoutSkullTicks = 0;

    final PestNavigationState navigation = new PestNavigationState();

    void beginRun(int detectedVacuumSlot, long now) {
        active = true;
        state = PestDestroyer.State.IDLE;
        stateEnteredAt = now;
        activatedAt = now;
        currentTarget = null;
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
        }
        if (newState != PestDestroyer.State.KILL_PEST) {
            targetWithoutSkullTicks = 0;
            lastPreRotateAt = 0L;
            resetKillVacuumRetry();
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

    boolean claimKilledPestEntityId(int entityId) {
        return accountedKilledPestEntityIds.add(entityId);
    }

    private void resetTransientState() {
        stuckTicks = 0;
        approachTicks = 0;
        zeroPestTabTicks = 0;
        targetWithoutSkullTicks = 0;
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
        arrivedAtCurrentTargetViaAotv = false;
        aotvStartY = Double.NaN;
        lastRoofRescanAt = 0L;
        roofAotvReturnState = null;
        resetHuntState();
    }

    void resetHuntState() {
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
        huntStageEnteredTick = 0;
        huntLandingWaitStartedAt = 0L;
        huntTargetY = Double.NaN;
        huntCaughtSignal = false;
    }
}
