package dev.aether.macro.fishing;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishingMacroTest {
    @Test
    void anUnknownAimSettingFishesWater() {
        assertEquals(FishingMacro.AimAt.LAVA, FishingMacro.AimAt.fromConfig("LAVA"));
        assertEquals(FishingMacro.AimAt.HOTSPOT, FishingMacro.AimAt.fromConfig(" hotspot "));
        assertEquals(FishingMacro.AimAt.WATER, FishingMacro.AimAt.fromConfig("WATER"));
        assertEquals(FishingMacro.AimAt.WATER, FishingMacro.AimAt.fromConfig("magma"));
        assertEquals(FishingMacro.AimAt.WATER, FishingMacro.AimAt.fromConfig(""));
        assertEquals(FishingMacro.AimAt.WATER, FishingMacro.AimAt.fromConfig(null));
    }

    @Test
    void theHotspotFallbackFishesWhicheverLiquidIsCloser() {
        assertEquals(FishingMacro.AimAt.LAVA, FishingMacro.nearerLiquid(2.0, 3.5));
        assertEquals(FishingMacro.AimAt.WATER, FishingMacro.nearerLiquid(4.0, 3.5));
        assertEquals(FishingMacro.AimAt.WATER, FishingMacro.nearerLiquid(3.0, 3.0));
        assertEquals(FishingMacro.AimAt.LAVA, FishingMacro.nearerLiquid(5.0, Double.POSITIVE_INFINITY));
        assertEquals(FishingMacro.AimAt.WATER,
                FishingMacro.nearerLiquid(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
    }

    @Test
    void theFlightTimeIsTheTickTheFloatReachesItsLanding() {
        Vec3 eye = new Vec3(0.5, 65.62, 0.5);
        Vec3[] path = CastSim.castPath(eye, 30.0f, 45.0f, CastSim.DEFAULT_TICKS);

        assertEquals(10, FishingMacro.flightTicks(path, eye, path[10]));
        assertEquals(1, FishingMacro.flightTicks(path, eye, eye));
        assertEquals(CastSim.DEFAULT_TICKS, FishingMacro.flightTicks(path, eye, eye.add(0.0, 0.0, 500.0)));
    }

    @Test
    void theMacroStopsOnTheThirdEmptySweepInARow() {
        assertFalse(FishingMacro.sweepsExhausted(1));
        assertFalse(FishingMacro.sweepsExhausted(2));
        assertTrue(FishingMacro.sweepsExhausted(3));
    }

    @Test
    void aFloatStuckInAMobForOverASecondIsASnag() {
        assertFalse(FishingMacro.snagHeld(0L, 50_000L));
        assertFalse(FishingMacro.snagHeld(10_000L, 11_000L));
        assertTrue(FishingMacro.snagHeld(10_000L, 11_001L));
    }

    @Test
    void aCatchOutOfReachForSixSecondsIsLeftAlone() {
        assertFalse(FishingMacro.unreachedTooLong(10_000L, 16_000L));
        assertTrue(FishingMacro.unreachedTooLong(10_000L, 16_001L));
    }

    @Test
    void theCastPitchBandAllowsTheRotationGridAndEitherOrder() {
        assertTrue(FishingMacro.withinPitchBand(45.0f, 20.0f, 70.0f));
        assertTrue(FishingMacro.withinPitchBand(19.2f, 20.0f, 70.0f));
        assertTrue(FishingMacro.withinPitchBand(70.8f, 70.0f, 20.0f));
        assertFalse(FishingMacro.withinPitchBand(18.9f, 20.0f, 70.0f));
        assertFalse(FishingMacro.withinPitchBand(71.1f, 70.0f, 20.0f));
        assertTrue(FishingMacro.withinPitchBand(30.5f, 30.0f, 30.0f));
        assertFalse(FishingMacro.withinPitchBand(32.0f, 30.0f, 30.0f));
    }

    @Test
    void theSwingCadenceStaysBetweenThreeAndSixClicksASecond() {
        SplittableRandom random = new SplittableRandom(7L);
        for (int i = 0; i < 500; i++) {
            long delay = FishingMacro.nextAttackDelayMs(random);
            assertTrue(FishingMacro.attackDelayInRange(delay));
            double cps = 1000.0 / delay;
            assertTrue(cps >= 3.0 && cps <= 6.0, "cps " + cps);
        }
    }
}
