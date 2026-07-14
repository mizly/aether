package dev.aether.modules.pest.helpers;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
    float vacuumRange = 7.5f;

    int aotvSlot = -1;
    int aotvUseCount = 0;
    long aotvLastUseAt = 0L;
    long aotvNextUseAt = 0L;
    long aotvPostClickGraceUntil = 0L;
    long aotvPendingUseAt = 0L;
    double aotvLastUsePlayerX = Double.NaN;
    double aotvLastUsePlayerY = Double.NaN;
    double aotvLastUsePlayerZ = Double.NaN;
    boolean arrivedAtCurrentTargetViaAotv = false;
    volatile double aotvStartY = Double.NaN;
    long activatedAt = 0L;
    long lastRoofRescanAt = 0L;
    PestDestroyer.State roofAotvReturnState = null;
    boolean sunsetPestsRestoreNight = false;

    int etherwarpEntryAttempts = 0;
    long etherwarpEntryClickAt = 0L;
    long etherwarpEntryGraceUntil = 0L;
    long etherwarpEntryRetryAt = 0L;
    boolean etherwarpEntryClicked = false;
    Vec3 etherwarpEntryPredicted = null;
    double etherwarpEntryPreX = Double.NaN;
    double etherwarpEntryPreY = Double.NaN;
    double etherwarpEntryPreZ = Double.NaN;
    // Set when the etherwarp entry gives up, so the hold lock stops re-engaging
    // and the run falls back to the normal destroyer.
    boolean holdDestinationAbandoned = false;

    int zeroPestTabTicks = 0;
    int targetWithoutSkullTicks = 0;

    final PestNavigationState navigation = new PestNavigationState();

    void resetEtherwarpEntry() {
        etherwarpEntryClickAt = 0L;
        etherwarpEntryGraceUntil = 0L;
        etherwarpEntryRetryAt = 0L;
        etherwarpEntryClicked = false;
        etherwarpEntryPredicted = null;
        etherwarpEntryPreX = Double.NaN;
        etherwarpEntryPreY = Double.NaN;
        etherwarpEntryPreZ = Double.NaN;
    }
}
