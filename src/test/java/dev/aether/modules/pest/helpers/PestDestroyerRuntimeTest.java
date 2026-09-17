package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void claimsMultipleKilledPestsOncePerEntity() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();

        assertTrue(runtime.claimKilledPestEntityId(10));
        assertTrue(runtime.claimKilledPestEntityId(11));
        assertTrue(runtime.claimKilledPestEntityId(12));
        assertFalse(runtime.claimKilledPestEntityId(10));
        assertFalse(runtime.claimKilledPestEntityId(12));
        assertEquals(3, runtime.accountedKilledPestEntityIds.size());
    }
}
