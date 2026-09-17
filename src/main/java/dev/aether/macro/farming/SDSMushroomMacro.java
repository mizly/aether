package dev.aether.macro.farming;

import dev.aether.config.AetherConfig;

import java.util.Map;

// repeating a -> s -> s+d rows, or d -> s -> s+a when flipped
public class SDSMushroomMacro extends AbstractFarmingMacro {
    private static final float CARDINAL_OFFSET_DEGREES = 16f;
    private final boolean reverseLane = AetherConfig.MACRO_SDS_MUSHROOM_REVERSE_LANE.get();
    private final StateCycle rows = reverseLane
            ? stateCycle(0.005, 2, State.RIGHT, State.BACKWARD, State.LEFT)
            : stateCycle(0.005, 2, State.LEFT, State.BACKWARD, State.RIGHT);

    @Override
    protected DefaultAngle defaultAngle() {
        return new DefaultAngle(5.5f, reverseLane ? CARDINAL_OFFSET_DEGREES : -CARDINAL_OFFSET_DEGREES);
    }

    @Override
    protected Map<State, StateKeys> stateKeys() {
        return Map.of(
                State.LEFT, new StateKeys(true, false, false, reverseLane, true, false, false),
                State.RIGHT, new StateKeys(false, true, false, !reverseLane, true, false, false));
    }

}
