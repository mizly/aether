package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static dev.aether.modules.pest.helpers.PestFlightRecovery.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PestFlightRecoveryTest {
    @Test
    void repeatedFailedRoutesBackOffAndGiveUpDespiteWiggling() {
        PestFlightRecovery recovery = new PestFlightRecovery();
        assertEquals(CONTINUE, recovery.update(true, Vec3.ZERO, 0, 0, 30_000));
        assertEquals(RETRY, recovery.update(false, Vec3.ZERO, 50, 0, 30_000));
        assertEquals(WAIT, recovery.update(false, new Vec3(0.2, 0, 0), 100, 0, 30_000));
        assertEquals(RETRY, recovery.update(false, new Vec3(-0.2, 0, 0), 350, 0, 30_000));
        assertEquals(WAIT, recovery.update(false, Vec3.ZERO, 949, 0, 30_000));
        assertEquals(RETRY, recovery.update(false, Vec3.ZERO, 950, 0, 30_000));
        assertEquals(GIVE_UP, recovery.update(false, Vec3.ZERO, 2_150, 0, 30_000));
    }

    @Test
    void timeoutAppliesWhileNavigatingIdleOrWaitingToRetry() {
        for (boolean navigating : new boolean[]{true, false}) {
            PestFlightRecovery recovery = new PestFlightRecovery();
            assertEquals(RETRY, recovery.update(false, Vec3.ZERO, 29_999, 0, 30_000));
            assertEquals(GIVE_UP, recovery.update(navigating, Vec3.ZERO, 30_001, 0, 30_000));
        }
    }

    @Test
    void realProgressRenewsRetriesWithoutExtendingTheTimeout() {
        PestFlightRecovery recovery = new PestFlightRecovery();
        for (long now : new long[]{0, 300, 900}) {
            assertEquals(RETRY, recovery.update(false, Vec3.ZERO, now, 0, 30_000));
        }
        Vec3 advanced = new Vec3(0, 0, 3);
        assertEquals(CONTINUE, recovery.update(true, advanced, 2_100, 0, 30_000));
        assertEquals(RETRY, recovery.update(false, advanced, 2_150, 0, 30_000));
        assertEquals(GIVE_UP, recovery.update(false, new Vec3(0, 0, 6), 30_001, 0, 30_000));
    }

    @Test
    void resettingForAnotherFlightClearsFailuresAndDelay() {
        PestFlightRecovery recovery = new PestFlightRecovery();
        for (long now : new long[]{0, 300, 900}) {
            recovery.update(false, Vec3.ZERO, now, 0, 30_000);
        }
        recovery.reset();
        assertEquals(RETRY, recovery.update(false, Vec3.ZERO, 950, 950, 30_000));
    }
}
