package dev.aether.modules.pest.helpers;

import dev.aether.modules.pathfinding.execution.FlightMotion;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestDestroyerRuntimeTest {
    @Test
    void anotherFlightClearsThePreviousRecoveryBudget() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        for (long now : new long[]{0, 300, 900}) {
            runtime.flightRecovery.update(false, Vec3.ZERO, now, 0, 30_000);
        }
        assertEquals(PestFlightRecovery.Action.GIVE_UP,
                runtime.flightRecovery.update(false, Vec3.ZERO, 2_100, 0, 30_000));

        runtime.transitionTo(PestDestroyer.State.FLY_TO_PEST, 2_100);

        assertEquals(PestFlightRecovery.Action.RETRY,
                runtime.flightRecovery.update(false, Vec3.ZERO, 2_100, runtime.stateEnteredAt, 30_000));
    }

    @Test
    void beginRunClearsTransientAndNavigationState() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.stuckTicks = 12;
        runtime.aotvSlot = 4;
        runtime.navigation.plotQueue.add("3");

        runtime.beginRun(6, 1234L);

        assertTrue(runtime.active);
        assertEquals(PestDestroyer.State.IDLE, runtime.state);
        assertEquals(6, runtime.vacuumSlot);
        assertEquals(1234L, runtime.activatedAt);
        assertEquals(0, runtime.stuckTicks);
        assertEquals(-1, runtime.aotvSlot);
        assertTrue(runtime.navigation.plotQueue.isEmpty());
    }

    @Test
    void resetAllReturnsRuntimeToInactiveBaseline() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.beginRun(2, 10L);
        runtime.state = PestDestroyer.State.KILL_PEST;
        runtime.zeroPestTabTicks = 3;
        runtime.navigation.plotQueue.add("8");

        runtime.resetAll();

        assertFalse(runtime.active);
        assertEquals(PestDestroyer.State.IDLE, runtime.state);
        assertEquals(0, runtime.zeroPestTabTicks);
        assertTrue(runtime.navigation.plotQueue.isEmpty());
    }

    @Test
    void aRunStartsAtItsOwnPaceWithNoTurnInFlight() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.acquisition.perceive(7, PestAimAcquisition.AimKind.VACUUM, 1_000_000L, 200L);
        runtime.lastPathHandoffArmAt = 1_000_000L;

        runtime.beginRun(3, 1_000_500L);

        assertEquals(PestAimAcquisition.Phase.IDLE, runtime.acquisition.phase());
        assertEquals(0L, runtime.lastPathHandoffArmAt);
        assertTrue(runtime.acquisition.tempo() >= 0.90 && runtime.acquisition.tempo() < 1.15);
    }

    @Test
    void stoppingOrResettingTheRunDropsTheTurn() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.beginRun(3, 1_000_000L);
        runtime.acquisition.perceive(7, PestAimAcquisition.AimKind.EYE, 1_000_000L, 200L);
        runtime.lastPathHandoffArmAt = 1_000_000L;

        runtime.stopRun();

        assertEquals(PestAimAcquisition.Phase.IDLE, runtime.acquisition.phase());
        assertEquals(0L, runtime.lastPathHandoffArmAt);

        runtime.beginRun(3, 1_000_000L);
        runtime.acquisition.perceive(7, PestAimAcquisition.AimKind.HUNT, 1_000_000L, 200L);

        runtime.resetAll();

        assertEquals(PestAimAcquisition.Phase.IDLE, runtime.acquisition.phase());
    }

    @Test
    void aTurnSurvivesOnlyTheStatesThatAimAtThePest() {
        Set<PestDestroyer.State> aiming = EnumSet.of(
                PestDestroyer.State.KILL_PEST,
                PestDestroyer.State.APPROACH_PEST,
                PestDestroyer.State.AOTV_BETWEEN_PESTS,
                PestDestroyer.State.HUNT_PEST);
        for (PestDestroyer.State state : PestDestroyer.State.values()) {
            PestDestroyerRuntime runtime = new PestDestroyerRuntime();
            runtime.acquisition.perceive(7, PestAimAcquisition.AimKind.VACUUM, 1_000_000L, 200L);

            runtime.transitionTo(state, 1_000_050L);

            assertEquals(aiming.contains(state), runtime.acquisition.isHolding(), state.name());
        }

        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.acquisition.perceive(7, PestAimAcquisition.AimKind.VACUUM, 1_000_000L, 200L);
        runtime.transitionTo(PestDestroyer.State.KILL_PEST, 1_000_010L);
        runtime.transitionTo(PestDestroyer.State.APPROACH_PEST, 1_000_020L);
        runtime.transitionTo(PestDestroyer.State.KILL_PEST, 1_000_030L);
        assertTrue(runtime.acquisition.isHolding());
    }

    @Test
    void whatThePlayerSawLastsAsLongAsTheRun() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.sightings.record(7, Vec3.ZERO, 1_000_000L);

        runtime.beginRun(3, 1_000_100L);
        assertNull(runtime.sightings.lastSeen(7, 1_000_100L, Long.MAX_VALUE));

        runtime.sightings.record(7, Vec3.ZERO, 1_000_200L);
        for (PestDestroyer.State state : PestDestroyer.State.values()) {
            runtime.transitionTo(state, 1_000_300L);
        }
        assertNotNull(runtime.sightings.lastSeen(7, 1_000_300L, Long.MAX_VALUE));

        runtime.stopRun();
        assertNull(runtime.sightings.lastSeen(7, 1_000_300L, Long.MAX_VALUE));

        runtime.sightings.record(7, Vec3.ZERO, 1_000_400L);
        runtime.resetAll();
        assertNull(runtime.sightings.lastSeen(7, 1_000_400L, Long.MAX_VALUE));
    }

    @Test
    void leavingKillPestCancelsTheBackUpButKeepsItsKeys() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.closeBackoff.begin(7, Vec3.ZERO, backStraight(), 3.6, 100, 3);

        runtime.transitionTo(PestDestroyer.State.KILL_PEST, 1_000_000L);

        assertTrue(runtime.closeBackoff.isActive());

        runtime.transitionTo(PestDestroyer.State.AOTV_BETWEEN_PESTS, 1_000_050L);

        assertFalse(runtime.closeBackoff.isActive());
        assertTrue(runtime.closeBackoff.takeAbandonedKeys());
        assertFalse(runtime.closeBackoff.takeAbandonedKeys());
    }

    @Test
    void everyStateButKillPestEndsTheBackUp() {
        for (PestDestroyer.State state : PestDestroyer.State.values()) {
            PestDestroyerRuntime runtime = new PestDestroyerRuntime();
            runtime.closeBackoff.begin(7, Vec3.ZERO, backStraight(), 3.6, 100, 3);

            runtime.transitionTo(state, 1_000_000L);

            assertEquals(state == PestDestroyer.State.KILL_PEST, runtime.closeBackoff.isActive(), state.name());
        }
    }

    @Test
    void resettingTheRunDropsTheBackUpAndItsKeys() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.beginRun(3, 1_000_000L);
        runtime.closeBackoff.begin(7, Vec3.ZERO, backStraight(), 3.6, 100, 3);

        runtime.resetAll();

        assertFalse(runtime.closeBackoff.isActive());
        assertFalse(runtime.closeBackoff.holdsKeys());
        assertFalse(runtime.closeBackoff.takeAbandonedKeys());

        runtime.beginRun(3, 1_000_100L);
        runtime.closeBackoff.begin(7, Vec3.ZERO, backStraight(), 3.6, 100, 3);
        runtime.stopRun();

        assertFalse(runtime.closeBackoff.isActive());
        assertFalse(runtime.closeBackoff.holdsKeys());

        runtime.closeBackoff.begin(7, Vec3.ZERO, backStraight(), 3.6, 100, 3);
        runtime.beginRun(3, 1_000_200L);

        assertFalse(runtime.closeBackoff.isActive());
        assertFalse(runtime.closeBackoff.holdsKeys());
    }

    @Test
    void claimsMultipleKilledPestsOncePerEntity() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();

        assertTrue(runtime.claimKilledPestEntityId(10));
        assertTrue(runtime.claimKilledPestEntityId(11));
        assertTrue(runtime.claimKilledPestEntityId(12));
        assertFalse(runtime.claimKilledPestEntityId(10));
        assertFalse(runtime.claimKilledPestEntityId(12));
        assertEquals(3, runtime.accountedKilledPestEntityIds.size());
    }

    private static PestCloseRangeBackoff.Retreat backStraight() {
        return new PestCloseRangeBackoff.Retreat(new FlightMotion.Input(-1, 0), new Vec3(0, 0, -1), 3.0);
    }
}
