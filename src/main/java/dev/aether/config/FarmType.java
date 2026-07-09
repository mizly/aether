package dev.aether.config;

public enum FarmType {
    S_SHAPE("S-Shape"),
    S_SHAPE_SUGAR_CANE("S-Shape (Cane)"),
    SDS_MUSHROOM("SDS (Mushroom)"),
    COCOA_BEANS("Cocoa Beans"),
    A_D_FARM("A/D"),
    W_S_FARM("W/S (Flower)"),
    W_S_CROP("W/S (Crop)");

    private final String label;

    FarmType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
