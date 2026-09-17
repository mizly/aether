package dev.aether.macro.farming;

// farms alternating w/s rows
public class WSFarmMacro extends AbstractFarmingMacro {
    private final StateCycle rows = stateCycle(0.005, 2, State.FORWARD, State.BACKWARD);

    @Override
    protected DefaultAngle defaultAngle() {
        return new DefaultAngle(-3f, -16.83f);
    }
}
