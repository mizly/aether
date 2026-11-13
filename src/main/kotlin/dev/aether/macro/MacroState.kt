package dev.aether.macro

class MacroState {
    enum class State {
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

    enum class Location {
        GARDEN, CRYSTAL_HOLLOWS, HUB, LOBBY, LIMBO, UNKNOWN
    }
}
