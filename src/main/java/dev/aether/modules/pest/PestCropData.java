package dev.aether.modules.pest;

import java.util.Map;

public final class PestCropData {

    public record PestProfile(String crop, String spray, String pest, String vinyl) {}

    private static final PestProfile[] ALL = {
        new PestProfile("Wheat",         "Dung",         "Fly",             "Pretty Fly"),
        new PestProfile("Carrot",        "Honey Jar",    "Cricket",         "Cricket Choir"),
        new PestProfile("Potato",        "Plant Matter", "Locust",          "Cicada Symphony"),
        new PestProfile("Pumpkin",       "Tasty Cheese", "Rat",             "Rodent Revolution"),
        new PestProfile("Sugar Cane",    "Compost",      "Mosquito",        "Buzzin' Beats"),
        new PestProfile("Melon Slice",   "Compost",      "Earthworm",       "Earthworm Ensemble"),
        new PestProfile("Cactus",        "Tasty Cheese", "Mite",            "DynaMITES"),
        new PestProfile("Cocoa Beans",   "Honey Jar",    "Moth",            "Wings of Harmony"),
        new PestProfile("Mushrooms",     "Plant Matter", "Slug",            "Slow and Groovy"),
        new PestProfile("Nether Wart",   "Dung",         "Beetle",          "Not Just a Pest"),
        new PestProfile("Moonflower",    "Jelly",        "Firefly",         "Firefly in the Hole"),
        new PestProfile("Sunflower",     "Jelly",        "Dragonfly",       "Imagine Dragonflies"),
        new PestProfile("Wild Rose",     "Jelly",        "Praying Mantis",  "Pray For Me"),
    };

    public static final Map<String, PestProfile> BY_CROP;
    public static final Map<String, PestProfile> BY_PEST;

    static {
        var byCrop = new java.util.LinkedHashMap<String, PestProfile>();
        var byPest = new java.util.LinkedHashMap<String, PestProfile>();
        for (PestProfile p : ALL) {
            byCrop.put(p.crop(), p);
            byPest.put(p.pest(), p);
        }
        BY_CROP = java.util.Collections.unmodifiableMap(byCrop);
        BY_PEST = java.util.Collections.unmodifiableMap(byPest);
    }

    private PestCropData() {}
}
