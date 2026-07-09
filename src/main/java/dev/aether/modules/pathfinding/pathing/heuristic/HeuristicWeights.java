package dev.aether.modules.pathfinding.pathing.heuristic;

public final class HeuristicWeights {
    public final double manhattanWeight;
    public final double octileWeight;
    public final double perpendicularWeight;
    public final double heightWeight;

    public static final HeuristicWeights DEFAULT_WEIGHTS =
        new HeuristicWeights(0.0, 1.0, 0.0, 0.0);

    public HeuristicWeights(double manhattanWeight, double octileWeight,
                             double perpendicularWeight, double heightWeight) {
        this.manhattanWeight = manhattanWeight;
        this.octileWeight = octileWeight;
        this.perpendicularWeight = perpendicularWeight;
        this.heightWeight = heightWeight;
    }
}
