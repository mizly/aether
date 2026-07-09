package dev.aether.modules.pathfinding.pathing.processing;

public final class Cost {
    public final double value;

    public static final Cost ZERO = new Cost(0.0);

    private Cost(double value) {
        this.value = value;
    }

    public static Cost of(double value) {
        if (Double.isNaN(value) || value < 0) {
            throw new IllegalArgumentException("Cost must be a positive number or 0");
        }
        return new Cost(value);
    }
}
