package dev.aether.config

enum class FarmType(private val label: String) {
    S_SHAPE("S-Shape"),
    S_SHAPE_SUGAR_CANE("S-Shape (Cane)"),
    SDS_MUSHROOM("SDS (Mushroom)"),
    COCOA_BEANS("Cocoa Beans"),
    A_D_FARM("A/D"),
    W_S_FARM("W/S"),
    CUSTOM("Custom");

    fun getLabel(): String = label
}
