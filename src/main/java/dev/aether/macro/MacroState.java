package dev.aether.macro;

public class MacroState {
    public enum State {
        OFF,
        FARMING,
        METAL_DETECTING,
        AUTO_CARNIVAL,
        CLEANING,
        RECOVERING,
        VISITING,
        AUTOSELLING,
        SPRAYING,
        WARDROBE,
        EQUIPMENT,
        GEORGE,
        DROPPING_JUNK,
        REWARPING
    }

    public enum Location {
        GARDEN, CRYSTAL_HOLLOWS, HUB, LOBBY, LIMBO, UNKNOWN
    }
}
