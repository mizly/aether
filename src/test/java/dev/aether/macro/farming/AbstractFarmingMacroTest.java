package dev.aether.macro.farming;

import org.junit.jupiter.api.Test;

import static dev.aether.macro.farming.AbstractFarmingMacro.State;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AbstractFarmingMacroTest {
    @Test
    void reversesBothHorizontalDirections() {
        ADFarmMacro macro = new ADFarmMacro();
        macro.currentState = State.LEFT;
        assertEquals(1, macro.getOppositeCycleStep());

        macro.currentState = State.RIGHT;
        assertEquals(0, macro.getOppositeCycleStep());
    }

    @Test
    void reversesBothForwardAndBackwardDirections() {
        WSFarmMacro macro = new WSFarmMacro();
        macro.currentState = State.FORWARD;
        assertEquals(1, macro.getOppositeCycleStep());

        macro.currentState = State.BACKWARD;
        assertEquals(0, macro.getOppositeCycleStep());
    }

    @Test
    void skipsLaneSwitchStepsWhenFindingOppositeDirection() {
        CocoaBeansMacro macro = new CocoaBeansMacro();
        macro.currentState = State.FORWARD;
        assertEquals(2, macro.getOppositeCycleStep());

        macro.currentState = State.BACKWARD;
        assertEquals(0, macro.getOppositeCycleStep());
    }

    @Test
    void leavesUnsupportedDirectionsUnchanged() {
        SShapeSugarCaneMacro macro = new SShapeSugarCaneMacro();
        macro.currentState = State.LEFT;
        assertNull(macro.getOppositeCycleStep());

        macro.currentState = State.NONE;
        assertNull(macro.getOppositeCycleStep());
        assertNull(new CustomFarmMacro().getOppositeCycleStep());
    }
}
