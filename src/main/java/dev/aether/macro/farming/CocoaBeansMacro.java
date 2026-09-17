package dev.aether.macro.farming;

import java.util.Map;

// forward row, right lane, backward row, right lane
public class CocoaBeansMacro extends AbstractFarmingMacro {
    private final StateCycle states = stateCycle(0.02, 2,
            State.FORWARD, State.SWITCHING_LANE, State.BACKWARD, State.SWITCHING_LANE);

    @Override
    protected DefaultAngle defaultAngle() {
        return new DefaultAngle(-75f, 0f);
    }

    @Override
    protected Map<State, StateKeys> stateKeys() {
        return Map.of(
                State.SWITCHING_LANE, new StateKeys(false, true, false, false, true, false, false));
    }

}
