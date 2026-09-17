package dev.aether.macro.farming;

// farms alternating a/d rows
public class ADFarmMacro extends AbstractFarmingMacro {
    private final StateCycle rows = stateCycle(0.005, 2, State.LEFT, State.RIGHT);

    @Override
    protected DefaultAngle defaultAngle() {
        return new DefaultAngle(-3f, 0f);
    }

}
