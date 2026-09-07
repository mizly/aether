package dev.aether.macro;

import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroStateManagerTest {
    @Test
    void commandPathfindingIsStoppableWhileFarmingIsOff() throws ReflectiveOperationException {
        Field navigating = PathfindingManager.class.getDeclaredField("navigating");
        navigating.setAccessible(true);
        boolean previous = navigating.getBoolean(null);
        try {
            navigating.setBoolean(null, true);
            assertFalse(MacroStateManager.isMacroRunning());
            assertTrue(MacroStateManager.isAutomationRunning());
            assertEquals(MacroState.State.OFF, MacroStateManager.getCurrentState());
        } finally {
            navigating.setBoolean(null, previous);
        }
    }

    @Test
    void commandPestDestroyerIsStoppableWithoutANavigationOrFarmingSession() throws ReflectiveOperationException {
        Field runtimeField = PestDestroyer.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        Object runtime = runtimeField.get(null);
        Field active = runtime.getClass().getDeclaredField("active");
        active.setAccessible(true);
        boolean previous = active.getBoolean(runtime);
        try {
            active.setBoolean(runtime, true);
            assertFalse(MacroStateManager.isMacroRunning());
            assertFalse(PathfindingManager.isNavigating());
            assertTrue(MacroStateManager.isAutomationRunning());
            assertEquals(0, MacroStateManager.getSessionRunningTime());
        } finally {
            active.setBoolean(runtime, previous);
        }
    }
}
