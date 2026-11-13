package dev.aether.modules.pest

import java.util.Collections
import java.util.LinkedHashMap

class PestCropData private constructor() {
    class PestProfile(private val cropValue: String, private val sprayValue: String, private val pestValue: String, private val vinylValue: String) {
        fun crop(): String = cropValue
        fun spray(): String = sprayValue
        fun pest(): String = pestValue
        fun vinyl(): String = vinylValue
        override fun equals(other: Any?): Boolean = other is PestProfile && cropValue == other.cropValue && sprayValue == other.sprayValue && pestValue == other.pestValue && vinylValue == other.vinylValue
        override fun hashCode(): Int = java.util.Objects.hash(cropValue, sprayValue, pestValue, vinylValue)
        override fun toString(): String = "PestProfile[crop=$cropValue, spray=$sprayValue, pest=$pestValue, vinyl=$vinylValue]"
    }

    companion object {
        private val ALL = arrayOf(
            PestProfile("Wheat", "Dung", "Fly", "Pretty Fly"),
            PestProfile("Carrot", "Honey Jar", "Cricket", "Cricket Choir"),
            PestProfile("Potato", "Plant Matter", "Locust", "Cicada Symphony"),
            PestProfile("Pumpkin", "Tasty Cheese", "Rat", "Rodent Revolution"),
            PestProfile("Sugar Cane", "Compost", "Mosquito", "Buzzin' Beats"),
            PestProfile("Melon Slice", "Compost", "Earthworm", "Earthworm Ensemble"),
            PestProfile("Cactus", "Tasty Cheese", "Mite", "DynaMITES"),
            PestProfile("Cocoa Beans", "Honey Jar", "Moth", "Wings of Harmony"),
            PestProfile("Mushrooms", "Plant Matter", "Slug", "Slow and Groovy"),
            PestProfile("Nether Wart", "Dung", "Beetle", "Not Just a Pest"),
            PestProfile("Moonflower", "Jelly", "Firefly", "Firefly in the Hole"),
            PestProfile("Sunflower", "Jelly", "Dragonfly", "Imagine Dragonflies"),
            PestProfile("Wild Rose", "Jelly", "Praying Mantis", "Pray For Me")
        )
        @JvmField val BY_CROP: Map<String, PestProfile>
        @JvmField val BY_PEST: Map<String, PestProfile>
        init {
            val byCrop = LinkedHashMap<String, PestProfile>()
            val byPest = LinkedHashMap<String, PestProfile>()
            for (profile in ALL) {
                byCrop[profile.crop()] = profile
                byPest[profile.pest()] = profile
            }
            BY_CROP = Collections.unmodifiableMap(byCrop)
            BY_PEST = Collections.unmodifiableMap(byPest)
        }
    }
}
