package dev.aether.modules.failsafe;

import dev.aether.macro.MacroState;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TeleportDetectionTest {
    private static final String HOE = "Blessed Melon Dicer";

    @Test
    void farmingOnlyModeKeepsProtectionRestrictedToActiveFarmingInGarden() {
        assertTrue(TeleportDetection.shouldMonitor(MacroState.State.FARMING, true, true, false, true));
        assertFalse(TeleportDetection.shouldMonitor(MacroState.State.FARMING, true, false, false, true));
        assertFalse(TeleportDetection.shouldMonitor(MacroState.State.FARMING, true, true, true, true));
        assertFalse(TeleportDetection.shouldMonitor(MacroState.State.FARMING, true, true, false, false));
        for (MacroState.State state : MacroState.State.values()) {
            if (state != MacroState.State.FARMING) {
                assertFalse(TeleportDetection.shouldMonitor(state, true, true, false, true), state.name());
            }
        }
    }

    @Test
    void disablingFarmingOnlyModeMonitorsOtherRunningMacroPhases() {
        for (MacroState.State state : MacroState.State.values()) {
            boolean expected = state != MacroState.State.OFF && state != MacroState.State.RECOVERING;
            assertEquals(expected, TeleportDetection.shouldMonitor(state, false, false, true, false), state.name());
        }
    }

    @Test
    void resolvesAbsolutePositionBeforeMeasuringDistance() {
        Vec3 delta = TeleportDetection.displacement(pose(100, 70, -100), pose(120, 70, -100), Set.of());
        assertEquals(new Vec3(20, 0, 0), delta);
    }

    @Test
    void resolvesRelativeAxesIndependently() {
        Vec3 delta = TeleportDetection.displacement(pose(100, 70, -100), pose(2, 71, -3),
                Set.of(Relative.X, Relative.Z));
        assertEquals(new Vec3(2, 1, -3), delta);
    }

    @Test
    void doesNotMistakeFullyRelativeCoordinatesForAnAbsoluteTeleport() {
        Vec3 delta = TeleportDetection.displacement(pose(200, 70, -200), pose(0.5, 0, -0.5),
                Set.of(Relative.X, Relative.Y, Relative.Z));
        assertFalse(TeleportDetection.isUnexpected(delta, 6, 0.5, HOE));
    }

    @Test
    void ignoresCorrectionsAtHorizontalLimitButDetectsDiagonalAndAxisAlignedTeleports() {
        assertFalse(TeleportDetection.isUnexpected(new Vec3(6, 0, 0), 6, 0.5, HOE));
        assertFalse(TeleportDetection.isUnexpected(new Vec3(3, 0, 3), 6, 0.5, HOE));
        assertTrue(TeleportDetection.isUnexpected(new Vec3(6.01, 0, 0), 6, 0.5, HOE));
        assertTrue(TeleportDetection.isUnexpected(new Vec3(5, 0, 5), 6, 0.5, HOE));
    }

    @Test
    void detectsVerticalMovesInBothDirectionsEvenInsideHorizontalLimit() {
        assertTrue(TeleportDetection.isUnexpected(new Vec3(1, 0.5, 0), 6, 0.5, HOE));
        assertTrue(TeleportDetection.isUnexpected(new Vec3(0, -0.5, 0), 6, 0.5, HOE));
        assertFalse(TeleportDetection.isUnexpected(new Vec3(1, 0.49, 0), 6, 0.5, HOE));
    }

    @Test
    void preservesFrostTeleportWeaponExceptionForHorizontalMoves() {
        for (String weapon : new String[]{"Heroic Aspect of the Void", "Hyperion", "Scylla", "Valkyrie", "Astraea"}) {
            assertFalse(TeleportDetection.isUnexpected(new Vec3(20, 0, 0), 6, 0.5, weapon));
            assertTrue(TeleportDetection.isUnexpected(new Vec3(20, 1, 0), 6, 0.5, weapon));
        }
    }

    @Test
    void rotationOnlyPacketsDoNotTriggerEvenWithZeroLimits() {
        assertFalse(TeleportDetection.isUnexpected(Vec3.ZERO, 0, 0, HOE));
        assertFalse(TeleportDetection.isUnexpected(new Vec3(Double.NaN, 0, 0), 6, 0.5, HOE));
    }

    @Test
    void marksOnlyCommandsThatRequestATeleport() {
        for (String command : new String[]{"/warp garden", "plottp 6", " /TpToPlot 2 ", "hub", "lobby", "visit Player"}) {
            assertTrue(TeleportDetection.isTeleportCommand(command), command);
        }
        for (String command : new String[]{"setspawn", "sethome", "warpdrive", "chat warp garden", "", "   "}) {
            assertFalse(TeleportDetection.isTeleportCommand(command), command);
        }
        assertFalse(TeleportDetection.isTeleportCommand(null));
    }

    @Test
    void gracePeriodExpiresAndNeverSuppressesBeforeTheFirstCommand() {
        assertFalse(TeleportDetection.isSettling(1000, Long.MIN_VALUE, 1500));
        assertTrue(TeleportDetection.isSettling(2499, 1000, 1500));
        assertFalse(TeleportDetection.isSettling(2500, 1000, 1500));
        assertFalse(TeleportDetection.isSettling(1000, 1000, 0));
    }

    private static PositionMoveRotation pose(double x, double y, double z) {
        return new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, 0, 0);
    }
}
