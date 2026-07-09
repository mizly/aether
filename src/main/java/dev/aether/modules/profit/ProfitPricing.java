package dev.aether.modules.profit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.config.AetherConfig;
import dev.aether.config.PetInfo;
import dev.aether.modules.profit.helpers.PetXpTracker;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ProfitPricing {
    private static final Set<String> CROPS_SET = Set.of(
            "Wheat", "Enchanted Wheat", "Enchanted Hay Bale",
            "Seeds", "Enchanted Seeds", "Box of Seeds",
            "Potato", "Enchanted Potato", "Enchanted Baked Potato",
            "Carrot", "Enchanted Carrot", "Enchanted Golden Carrot",
            "Melon Slice", "Melon Block", "Enchanted Melon Slice", "Enchanted Melon",
            "Pumpkin", "Enchanted Pumpkin", "Polished Pumpkin",
            "Sugar Cane", "Enchanted Sugar", "Enchanted Sugar Cane",
            "Cactus", "Enchanted Cactus Green", "Enchanted Cactus",
            "Mushroom", "Red Mushroom", "Brown Mushroom",
            "Enchanted Red Mushroom", "Enchanted Brown Mushroom",
            "Enchanted Red Mushroom Block", "Enchanted Brown Mushroom Block",
            "Cocoa Beans", "Enchanted Cocoa Beans", "Enchanted Cookie",
            "Nether Wart", "Enchanted Nether Wart", "Mutant Nether Wart",
            "Sunflower", "Enchanted Sunflower", "Compacted Sunflower",
            "Moonflower", "Enchanted Moonflower", "Compacted Moonflower",
            "Wild Rose", "Enchanted Wild Rose", "Compacted Wild Rose");

    private static final Set<String> PEST_ITEMS_SET = Set.of(
            "Beady Eyes", "Chirping Stereo", "Sunder VI Book", "Clipped Wings",
            "Bookworm's Favorite Book", "Atmospheric Filter", "Wriggling Larva",
            "Pesterminator I Book", "Squeaky Toy", "Squeaky Mousemat",
            "Fire in a Bottle", "Vermin Vaporizer Chip", "Mantid Claw",
            "Overclocker 3000", "Vinyl",
            "Dung", "Honey Jar", "Plant Matter", "Tasty Cheese", "Compost", "Jelly",
            "Pest Shard", "Locust Larva");

    private static final Set<String> PETS_SET = Set.of("Epic Slug", "Legendary Slug", "Rat");
    private static final Set<String> MISC_DROPS_SET = Set.of("Cropie", "Squash", "Fermento", "Helianthus",
            "Tool EXP Capsule", "Pet XP", "Purse",
            "Raw Mutton", "Enchanted Raw Mutton", "Enchanted Cooked Mutton");
    private static final Set<String> FEAST_ITEMS_SET = Set.of(
            "Cornucopia", "Carrot Zest", "Deepfries", "Aggourdian", "Cane Knot",
            "Melon Juice", "Cactus Flower", "Designer Coffee Beans", "Feastfungus",
            "Botroot", "Salted Sunflower Seeds", "Crystalized Moonlight", "Floral Gelatin",
            "Seasoning");
    private static final Set<String> SEARCH_BACKED_BAZAAR_ITEMS = buildSearchBackedBazaarItems();

    private static final Map<String, Double> TRACKED_ITEMS = Map.ofEntries(
            Map.entry("Wheat", 6.0), Map.entry("Enchanted Wheat", 960.0), Map.entry("Enchanted Hay Bale", 153600.0),
            Map.entry("Seeds", 3.0), Map.entry("Enchanted Seeds", 480.0), Map.entry("Box of Seeds", 76800.0),
            Map.entry("Potato", 3.0), Map.entry("Enchanted Potato", 480.0), Map.entry("Enchanted Baked Potato", 76800.0),
            Map.entry("Carrot", 3.0), Map.entry("Enchanted Carrot", 480.0), Map.entry("Enchanted Golden Carrot", 76800.0),
            Map.entry("Melon Slice", 2.0), Map.entry("Melon Block", 18.0), Map.entry("Enchanted Melon Slice", 320.0),
            Map.entry("Enchanted Melon", 51200.0),
            Map.entry("Pumpkin", 10.0), Map.entry("Enchanted Pumpkin", 1600.0), Map.entry("Polished Pumpkin", 256000.0),
            Map.entry("Sugar Cane", 4.0), Map.entry("Enchanted Sugar", 640.0), Map.entry("Enchanted Sugar Cane", 102400.0),
            Map.entry("Cactus", 4.0), Map.entry("Enchanted Cactus Green", 640.0), Map.entry("Enchanted Cactus", 102400.0),
            Map.entry("Mushroom", 10.0), Map.entry("Red Mushroom", 10.0), Map.entry("Brown Mushroom", 10.0),
            Map.entry("Enchanted Red Mushroom", 1600.0), Map.entry("Enchanted Brown Mushroom", 1600.0),
            Map.entry("Enchanted Red Mushroom Block", 256000.0), Map.entry("Enchanted Brown Mushroom Block", 256000.0),
            Map.entry("Cocoa Beans", 3.0), Map.entry("Enchanted Cocoa Beans", 480.0), Map.entry("Enchanted Cookie", 76800.0),
            Map.entry("Nether Wart", 4.0), Map.entry("Enchanted Nether Wart", 640.0), Map.entry("Mutant Nether Wart", 102400.0),
            Map.entry("Sunflower", 4.0), Map.entry("Enchanted Sunflower", 640.0), Map.entry("Compacted Sunflower", 102400.0),
            Map.entry("Moonflower", 4.0), Map.entry("Enchanted Moonflower", 640.0), Map.entry("Compacted Moonflower", 102400.0),
            Map.entry("Wild Rose", 4.0), Map.entry("Enchanted Wild Rose", 640.0), Map.entry("Compacted Wild Rose", 102400.0),
            Map.entry("Beady Eyes", 25000.0), Map.entry("Chirping Stereo", 100000.0), Map.entry("Sunder VI Book", 0.0),
            Map.entry("Clipped Wings", 25000.0), Map.entry("Bookworm's Favorite Book", 10000.0),
            Map.entry("Atmospheric Filter", 100000.0), Map.entry("Wriggling Larva", 250000.0), Map.entry("Pesterminator I Book", 0.0),
            Map.entry("Squeaky Toy", 10000.0), Map.entry("Squeaky Mousemat", 1000000.0), Map.entry("Fire in a Bottle", 100000.0),
            Map.entry("Vermin Vaporizer Chip", 0.0), Map.entry("Mantid Claw", 75000.0), Map.entry("Overclocker 3000", 250000.0),
            Map.entry("Vinyl", 50000.0), Map.entry("Dung", 0.0), Map.entry("Honey Jar", 0.0), Map.entry("Plant Matter", 0.0),
            Map.entry("Tasty Cheese", 0.0), Map.entry("Compost", 0.0), Map.entry("Jelly", 0.0),
            Map.entry("Epic Slug", 500000.0), Map.entry("Legendary Slug", 5000000.0), Map.entry("Rat", 5000.0),
            Map.entry("Cropie", 25000.0), Map.entry("Squash", 75000.0), Map.entry("Fermento", 250000.0),
            Map.entry("Helianthus", 0.0), Map.entry("Tool EXP Capsule", 100000.0), Map.entry("Pet XP", 0.0),
            Map.entry("Raw Mutton", 0.0), Map.entry("Enchanted Raw Mutton", 0.0), Map.entry("Enchanted Cooked Mutton", 0.0),
            Map.entry("Pest Shard", 0.0),
            Map.entry("Biofuel", 0.0), Map.entry("Farming Exp Boost", 0.0), Map.entry("Harvest Harbinger V Potion", 0.0),
            Map.entry("Velvet Top Hat", 0.0), Map.entry("Cashmere Hat", 0.0), Map.entry("Satin Trousers", 0.0),
            Map.entry("Oxford Shoes", 0.0), Map.entry("Space Helmet", 0.0), Map.entry("Wild Strawberry Dye", 0.0),
            Map.entry("Copper Dye", 0.0), Map.entry("Clouds Rune I", 0.0), Map.entry("Fire Spiral Rune I", 0.0),
            Map.entry("Gem Rune I", 0.0), Map.entry("Golden Rune I", 0.0), Map.entry("Hearts Rune I", 0.0),
            Map.entry("Hot Rune I", 0.0), Map.entry("Lava Rune I", 0.0), Map.entry("Lightning Rune I", 0.0),
            Map.entry("Magical Rune I", 0.0), Map.entry("Music Rune I", 0.0), Map.entry("Rainbow Rune I", 0.0),
            Map.entry("Redstone Rune I", 0.0), Map.entry("Smokey Rune I", 0.0), Map.entry("Snow Rune I", 0.0),
            Map.entry("Sparkling Rune I", 0.0), Map.entry("Wake Rune I", 0.0), Map.entry("White Spiral Rune I", 0.0),
            Map.entry("Zap Rune I", 0.0),
            Map.entry("Overgrown Grass", 0.0), Map.entry("Flowering Bouqet", 0.0), Map.entry("Green Bandana", 0.0),
            Map.entry("Hypercharge Chip", 0.0), Map.entry("Quickdraw Chip", 0.0), Map.entry("Superboom TNT", 0.0),
            Map.entry("Green Candy", 0.0), Map.entry("Purple Candy", 0.0), Map.entry("Ice Essence", 0.0),
            Map.entry("Diamond Essence", 0.0), Map.entry("Gold Essence", 0.0), Map.entry("Jacob's Ticket", 0.0),
            Map.entry("Turbo-Cacti I Book", 0.0), Map.entry("Turbo-Cane I Book", 0.0), Map.entry("Turbo-Carrot I Book", 0.0),
            Map.entry("Turbo-Cocoa I Book", 0.0), Map.entry("Turbo-Melon I Book", 0.0), Map.entry("Turbo-Moonflower I Book", 0.0),
            Map.entry("Turbo-Mushrooms I Book", 0.0), Map.entry("Turbo-Potato I Book", 0.0), Map.entry("Turbo-Pumpkin I Book", 0.0),
            Map.entry("Turbo-Rose I Book", 0.0), Map.entry("Turbo-Sunflower I Book", 0.0), Map.entry("Turbo-Warts I Book", 0.0),
            Map.entry("Turbo-Wheat I Book", 0.0), Map.entry("Cultivating I Book", 0.0), Map.entry("Delicate V Book", 0.0),
            Map.entry("Replenish I Book", 0.0), Map.entry("Dedication IV Book", 0.0), Map.entry("Jungle Key", 0.0),
            Map.entry("Pet Cake", 0.0), Map.entry("Fine Flour", 0.0), Map.entry("Arachne Fragment", 0.0),
            Map.entry("Locust Larva", 0.0),
            Map.entry("Cornucopia", 0.0), Map.entry("Carrot Zest", 0.0), Map.entry("Deepfries", 0.0),
            Map.entry("Aggourdian", 0.0), Map.entry("Cane Knot", 0.0), Map.entry("Melon Juice", 0.0),
            Map.entry("Cactus Flower", 0.0), Map.entry("Designer Coffee Beans", 0.0),
            Map.entry("Feastfungus", 0.0), Map.entry("Botroot", 0.0),
            Map.entry("Salted Sunflower Seeds", 0.0), Map.entry("Crystalized Moonlight", 0.0),
            Map.entry("Floral Gelatin", 0.0), Map.entry("Seasoning", 0.0),
            Map.entry("Purse", 1.0));

    private static final Map<String, String> BAZAAR_MAPPING = Map.ofEntries(
            Map.entry("Sunder VI Book", "ENCHANTMENT_SUNDER_6"),
            Map.entry("Pesterminator I Book", "ENCHANTMENT_PESTERMINATOR_1"),
            Map.entry("Dung", "DUNG"),
            Map.entry("Honey Jar", "HONEY_JAR"),
            Map.entry("Plant Matter", "PLANT_MATTER"),
            Map.entry("Tasty Cheese", "CHEESE_FUEL"),
            Map.entry("Compost", "COMPOST"),
            Map.entry("Jelly", "JELLY"),
            Map.entry("Helianthus", "HELIANTHUS"),
            Map.entry("Vermin Vaporizer Chip", "VERMIN_VAPORIZER_GARDEN_CHIP"),
            Map.entry("ENCHANTMENT_GREEN_THUMB_1", "ENCHANTMENT_GREEN_THUMB_1"),
            Map.entry("Pest Shard", "SHARD_PEST"),
            Map.entry("Overgrown Grass", "OVERGROWN_GRASS"),
            Map.entry("Flowering Bouqet", "FLOWERING_BOUQUET"),
            Map.entry("Green Bandana", "GREEN_BANDANA"),
            Map.entry("Hypercharge Chip", "HYPERCHARGE_GARDEN_CHIP"),
            Map.entry("Quickdraw Chip", "QUICKDRAW_GARDEN_CHIP"),
            Map.entry("Superboom TNT", "SUPERBOOM_TNT"),
            Map.entry("Green Candy", "GREEN_CANDY"),
            Map.entry("Purple Candy", "PURPLE_CANDY"),
            Map.entry("Ice Essence", "ESSENCE_ICE"),
            Map.entry("Diamond Essence", "ESSENCE_DIAMOND"),
            Map.entry("Gold Essence", "ESSENCE_GOLD"),
            Map.entry("Jacob's Ticket", "JACOBS_TICKET"),
            Map.entry("Turbo-Cacti I Book", "ENCHANTMENT_TURBO_CACTUS_1"),
            Map.entry("Turbo-Cane I Book", "ENCHANTMENT_TURBO_CANE_1"),
            Map.entry("Turbo-Carrot I Book", "ENCHANTMENT_TURBO_CARROT_1"),
            Map.entry("Turbo-Cocoa I Book", "ENCHANTMENT_TURBO_COCO_1"),
            Map.entry("Turbo-Melon I Book", "ENCHANTMENT_TURBO_MELON_1"),
            Map.entry("Turbo-Moonflower I Book", "ENCHANTMENT_TURBO_MOONFLOWER_1"),
            Map.entry("Turbo-Mushrooms I Book", "ENCHANTMENT_TURBO_MUSHROOMS_1"),
            Map.entry("Turbo-Potato I Book", "ENCHANTMENT_TURBO_POTATO_1"),
            Map.entry("Turbo-Pumpkin I Book", "ENCHANTMENT_TURBO_PUMPKIN_1"),
            Map.entry("Turbo-Rose I Book", "ENCHANTMENT_TURBO_ROSE_1"),
            Map.entry("Turbo-Sunflower I Book", "ENCHANTMENT_TURBO_SUNFLOWER_1"),
            Map.entry("Turbo-Warts I Book", "ENCHANTMENT_TURBO_WARTS_1"),
            Map.entry("Turbo-Wheat I Book", "ENCHANTMENT_TURBO_WHEAT_1"),
            Map.entry("Cultivating I Book", "ENCHANTMENT_CULTIVATING_1"),
            Map.entry("Delicate V Book", "ENCHANTMENT_DELICATE_5"),
            Map.entry("Replenish I Book", "ENCHANTMENT_REPLENISH_1"),
            Map.entry("Dedication IV Book", "ENCHANTMENT_DEDICATION_4"),
            Map.entry("Jungle Key", "JUNGLE_KEY"),
            Map.entry("Pet Cake", "PET_CAKE"),
            Map.entry("Fine Flour", "FINE_FLOUR"),
            Map.entry("Arachne Fragment", "ARACHNE_FRAGMENT"),
            Map.entry("Biofuel", "BIOFUEL"),
            Map.entry("Farming Exp Boost", "PET_ITEM_FARMING_SKILL_BOOST_UNCOMMON"),
            Map.entry("Harvest Harbinger V Potion", "POTION_harvest_harbinger"),
            Map.entry("Velvet Top Hat", "VELVET_TOP_HAT"),
            Map.entry("Cashmere Hat", "CASHMERE_JACKET"),
            Map.entry("Satin Trousers", "SATIN_TROUSERS"),
            Map.entry("Oxford Shoes", "OXFORD_SHOES"),
            Map.entry("Space Helmet", "DCTR_SPACE_HELM"),
            Map.entry("Wild Strawberry Dye", "DYE_WILD_STRAWBERRY"),
            Map.entry("Copper Dye", "DYE_COPPER"),
            Map.entry("Clouds Rune I", "RUNE_CLOUDS"),
            Map.entry("Fire Spiral Rune I", "RUNE_FIRE_SPIRAL"),
            Map.entry("Gem Rune I", "RUNE_GEM"),
            Map.entry("Golden Rune I", "RUNE_GOLDEN"),
            Map.entry("Hearts Rune I", "RUNE_HEARTS"),
            Map.entry("Hot Rune I", "RUNE_HOT"),
            Map.entry("Lava Rune I", "RUNE_LAVA"),
            Map.entry("Lightning Rune I", "RUNE_LIGHTNING"),
            Map.entry("Magical Rune I", "RUNE_MAGIC"),
            Map.entry("Music Rune I", "RUNE_MUSIC"),
            Map.entry("Rainbow Rune I", "RUNE_RAINBOW"),
            Map.entry("Redstone Rune I", "RUNE_REDSTONE"),
            Map.entry("Smokey Rune I", "RUNE_SMOKEY"),
            Map.entry("Snow Rune I", "RUNE_SNOW"),
            Map.entry("Sparkling Rune I", "RUNE_SPARKLING"),
            Map.entry("Wake Rune I", "RUNE_WAKE"),
            Map.entry("White Spiral Rune I", "RUNE_WHITE_SPIRAL"),
            Map.entry("Zap Rune I", "RUNE_ZAP"),
            Map.entry("Cornucopia", "CORNUCOPIA"),
            Map.entry("Carrot Zest", "CARROT_ZEST"),
            Map.entry("Deepfries", "DEEPFRIES"),
            Map.entry("Aggourdian", "AGGOURDIAN"),
            Map.entry("Cane Knot", "CANE_KNOT"),
            Map.entry("Melon Juice", "MELON_JUICE"),
            Map.entry("Cactus Flower", "CACTUS_FLOWER"),
            Map.entry("Designer Coffee Beans", "DESIGNER_COFFEE_BEANS"),
            Map.entry("Feastfungus", "FEASTFUNGUS"),
            Map.entry("Botroot", "BOTROOT"),
            Map.entry("Salted Sunflower Seeds", "SALTED_SUNFLOWER_SEEDS"),
            Map.entry("Crystalized Moonlight", "CRYSTALIZED_MOONLIGHT"),
            Map.entry("Floral Gelatin", "FLORAL_GELATIN"),
            Map.entry("Locust Larva", "LOCUST_LARVA"),
            Map.entry("Raw Mutton", "MUTTON"),
            Map.entry("Enchanted Raw Mutton", "ENCHANTED_MUTTON"),
            Map.entry("Enchanted Cooked Mutton", "ENCHANTED_COOKED_MUTTON"));

    private final Map<String, Double> bazaarPrices = new LinkedHashMap<>();
    private final Map<String, Double> bazaarBuyPrices = new LinkedHashMap<>();
    private final Map<String, Long> petLvl1Prices = new HashMap<>();
    private final Map<String, Long> petMaxLvlPrices = new HashMap<>();
    private final Map<String, String> idByNameCache = new ConcurrentHashMap<>();
    private final Runnable onPricesChanged;

    private volatile long lastBazaarFetchTime = 0L;

    public ProfitPricing(Runnable onPricesChanged) {
        this.onPricesChanged = onPricesChanged;
        syncConfiguredPetXpPrices();
    }

    public String canonicalizeTrackedName(String itemName) {
        String cleanName = sanitizeName(itemName);
        if (cleanName.isBlank()) {
            return "Unknown Item";
        }

        String mappedName = getMappedNameForId(cleanName);
        if (mappedName != null) {
            return mappedName;
        }

        for (String tracked : TRACKED_ITEMS.keySet()) {
            if (tracked.equalsIgnoreCase(cleanName)) {
                return tracked;
            }
        }

        if (isPetXpEntry(cleanName)) {
            return cleanName;
        }

        return normalizeName(cleanName);
    }

    public double getItemPrice(String itemName) {
        String cleanName = sanitizeName(itemName);
        if (cleanName.isBlank()) {
            return 0.0;
        }

        if (cleanName.startsWith("[Visitor] ")) {
            String actualName = cleanName.substring(10);
            if ("Visitor Cost".equals(actualName)) {
                return 1.0;
            }
            if ("Copper".equals(actualName)) {
                double greenThumbPrice = getPreferredPrice("ENCHANTMENT_GREEN_THUMB_1");
                return greenThumbPrice > 0.0 ? greenThumbPrice / 1500.0 : 0.0;
            }
            return getItemPrice(actualName);
        }

        if ("[Spray] Sprayonator".equals(cleanName) || "Purse".equals(cleanName)) {
            return 1.0;
        }

        return getPreferredPrice(resolveLookupKey(cleanName));
    }

    public double getItemValue(String itemName, long count) {
        String cleanName = sanitizeName(itemName);
        if (count < 0L) {
            double buyPrice = getPreferredBuyPrice(resolveLookupKey(cleanName));
            if (buyPrice > 0.0) {
                return buyPrice * count;
            }
        }
        return getItemPrice(itemName) * count;
    }

    public boolean isPredefinedTrackedItem(String itemName) {
        String cleanName = sanitizeName(itemName);
        if (cleanName.isBlank()) {
            return false;
        }
        if (isPetXpEntry(cleanName)) {
            return true;
        }
        String lookupKey = resolveLookupKey(cleanName);
        return TRACKED_ITEMS.containsKey(lookupKey);
    }

    public String getCategorizedName(String name) {
        String cleanName = sanitizeName(name);
        if (cleanName.equals("[Spray] Sprayonator")) {
            return "[COST] Sprayonator";
        }
        if (cleanName.equals("[Visitor] Visitor Cost")) {
            return "[COST] Visitor Cost";
        }
        if (cleanName.startsWith("[Visitor] ")) {
            return "[VISITOR] " + cleanName.substring(10);
        }

        String tag = "OTHER";
        if (isCrop(cleanName)) {
            tag = "CROP";
        } else if (isPestItem(cleanName)) {
            tag = "PEST";
        } else if (isPet(cleanName)) {
            tag = "PET";
        } else if (isFeastItem(cleanName)) {
            tag = "FEAST";
        } else if (isMiscDrop(cleanName) || isPetXpEntry(cleanName)) {
            tag = "MISC";
        }

        String displayName = cleanName.replace("Enchanted ", "Ench. ");
        if (isPetXpEntry(cleanName)) {
            displayName = cleanName.substring(8, cleanName.length() - 1) + " XP";
        }
        return "[" + tag + "] " + displayName;
    }

    public String getCompactCategoryLabel(String category) {
        return switch (category) {
            case "Crops" -> "[CROP]";
            case "Pest Items" -> "[PEST]";
            case "Pets" -> "[PET]";
            case "Feast" -> "[FEAST]";
            case "Misc Drops" -> "[MISC]";
            case "Visitor" -> "[VISITOR]";
            case "Costs" -> "[COST]";
            default -> "[OTHER]";
        };
    }

    public boolean isCrop(String name) {
        return CROPS_SET.contains(sanitizeName(name));
    }

    public boolean isPestItem(String name) {
        return PEST_ITEMS_SET.contains(sanitizeName(name));
    }

    public boolean isPet(String name) {
        return PETS_SET.contains(sanitizeName(name));
    }

    public boolean isFeastItem(String name) {
        return FEAST_ITEMS_SET.contains(sanitizeName(name));
    }

    public boolean isMiscDrop(String name) {
        return MISC_DROPS_SET.contains(sanitizeName(name));
    }

    public boolean isPetXpEntry(String name) {
        String cleanName = sanitizeName(name);
        return cleanName.toLowerCase().startsWith("pet xp (");
    }

    public void printPetXpPriceDebug(Minecraft client) {
        if (client.player == null) {
            return;
        }

        ClientUtils.sendMessage("§b[Pet XP Tracker] §fCurrently tracking:", false);
        for (String petConfig : AetherConfig.PET_TRACKER_LIST.get()) {
            PetInfo info = new PetInfo(petConfig);
            long lvl1Price = petLvl1Prices.getOrDefault(info.name, 0L);
            long maxLevelPrice = petMaxLvlPrices.getOrDefault(info.name, 0L);
            double pricePerXp = bazaarPrices.getOrDefault("Pet XP (" + info.name + ")", 0.0);

            String lvl1Str = lvl1Price > 0 ? String.format("%,d", lvl1Price) : "not found";
            String lvlMaxStr = maxLevelPrice > 0 ? String.format("%,d", maxLevelPrice) : "not found";
            String marginStr = pricePerXp > 0 ? String.format("%.3f", pricePerXp) : "not fetched";

            ClientUtils.sendMessage(info.name + "§f: §7L1: §6" + lvl1Str + " §7Max: §6" + lvlMaxStr + " §7-> §a"
                            + marginStr + " §7C/XP",
                    false);
        }
    }

    public void reloadConfiguredPetXpPrices() {
        syncConfiguredPetXpPrices();
    }

    public void startStartupPriceFetch() {
        fetchBazaarPrices();
    }

    public void handlePriceSourceChanged() {
        onPricesChanged.run();
        if (ProfitPriceSource.fromConfig(AetherConfig.PROFIT_PRICE_SOURCE.get()) == ProfitPriceSource.BAZAAR) {
            fetchBazaarPrices();
        }
    }

    public void refreshBazaarPricesIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastBazaarFetchTime > 3600000L) {
            fetchBazaarPrices();
        }
    }

    public String fetchIdByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (idByNameCache.containsKey(name)) {
            String cached = idByNameCache.get(name);
            return cached.isEmpty() ? null : cached;
        }

        try {
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://sky.coflnet.com/api/items/search/" + encoded + "?limit=1"))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonArray results = JsonParser.parseString(response.body()).getAsJsonArray();
                if (!results.isEmpty()) {
                    String tag = results.get(0).getAsJsonObject().get("tag").getAsString();
                    idByNameCache.put(name, tag);
                    return tag;
                }
            }
        } catch (Exception e) {
            System.err.println("[Aether] Cofl item ID lookup failed for '" + name + "': " + e.getMessage());
        }

        idByNameCache.put(name, "");
        return null;
    }

    private double getPreferredPrice(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            return 0.0;
        }

        ProfitPriceSource source = ProfitPriceSource.fromConfig(AetherConfig.PROFIT_PRICE_SOURCE.get());
        if (source == ProfitPriceSource.BAZAAR) {
            double marketPrice = bazaarPrices.getOrDefault(lookupKey, 0.0);
            if (marketPrice > 0.0) {
                return marketPrice;
            }
        }
        return TRACKED_ITEMS.getOrDefault(lookupKey, 0.0);
    }

    private double getPreferredBuyPrice(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            return 0.0;
        }

        ProfitPriceSource source = ProfitPriceSource.fromConfig(AetherConfig.PROFIT_PRICE_SOURCE.get());
        if (source == ProfitPriceSource.BAZAAR) {
            double marketPrice = bazaarBuyPrices.getOrDefault(lookupKey, 0.0);
            if (marketPrice > 0.0) {
                return marketPrice;
            }
        }
        return getPreferredPrice(lookupKey);
    }

    private String resolveLookupKey(String itemName) {
        String cleanName = sanitizeName(itemName);
        String mappedName = getMappedNameForId(cleanName);
        if (mappedName != null) {
            return mappedName;
        }

        for (String tracked : TRACKED_ITEMS.keySet()) {
            if (tracked.equalsIgnoreCase(cleanName)) {
                return tracked;
            }
        }

        return cleanName;
    }

    private String getMappedNameForId(String itemName) {
        for (Map.Entry<String, String> entry : BAZAAR_MAPPING.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(itemName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private synchronized void fetchBazaarPrices() {
        lastBazaarFetchTime = System.currentTimeMillis();
        new Thread(() -> performFetchInternal(HttpClient.newHttpClient()), "Aether-Profit-Pricing").start();
    }

    private void performFetchInternal(HttpClient httpClient) {
        for (Map.Entry<String, String> entry : BAZAAR_MAPPING.entrySet()) {
            try {
                fetchAndStoreBazaarPrice(httpClient, entry.getKey(), entry.getValue());
            } catch (Exception e) {
                System.err.println("Failed to fetch bazaar price for " + entry.getKey() + ": " + e.getMessage());
            }
        }

        for (String itemName : SEARCH_BACKED_BAZAAR_ITEMS) {
            if (BAZAAR_MAPPING.containsKey(itemName)) {
                continue;
            }
            try {
                String itemTag = fetchIdByName(itemName);
                if (itemTag != null && !itemTag.isBlank()) {
                    fetchAndStoreBazaarPrice(httpClient, itemName, itemTag);
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch search-backed bazaar price for " + itemName + ": " + e.getMessage());
            }
        }

        syncConfiguredPetXpPrices();
        onPricesChanged.run();
    }

    private void fetchAndStoreBazaarPrice(HttpClient httpClient, String itemName, String itemTag) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://sky.coflnet.com/api/bazaar/" + itemTag + "/snapshot"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return;
        }

        JsonElement root = JsonParser.parseString(response.body());
        if (!root.isJsonObject()) {
            return;
        }

        JsonObject data = root.getAsJsonObject();
        double sellPrice = getDouble(data, "sellPrice");
        if (sellPrice > 0.0) {
            bazaarPrices.put(itemName, sellPrice);
        }

        double buyPrice = getDouble(data, "buyPrice");
        if (buyPrice > 0.0) {
            bazaarBuyPrices.put(itemName, buyPrice);
        }
    }

    private static double getDouble(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return 0.0;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    private void syncConfiguredPetXpPrices() {
        for (String petConfig : AetherConfig.PET_TRACKER_LIST.get()) {
            PetInfo info = new PetInfo(petConfig);
            long[] xpTable = PetXpTracker.getXpTable(info.rarity, info.maxLevel);
            long totalXp = xpTable[info.maxLevel];

            petLvl1Prices.put(info.name, info.level1Price);
            petMaxLvlPrices.put(info.name, info.maxLevelPrice);

            if (info.level1Price >= 0 && info.maxLevelPrice > info.level1Price && totalXp > 0) {
                double pricePerXp = (double) (info.maxLevelPrice - info.level1Price) / totalXp;
                bazaarPrices.put("Pet XP (" + info.name + ")", pricePerXp);
                bazaarBuyPrices.put("Pet XP (" + info.name + ")", pricePerXp);
            } else {
                bazaarPrices.remove("Pet XP (" + info.name + ")");
                bazaarBuyPrices.remove("Pet XP (" + info.name + ")");
            }
        }
        onPricesChanged.run();
    }

    private static String normalizeName(String name) {
        StringBuilder builder = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (Character.isSpaceChar(c)) {
                nextUpper = true;
                builder.append(c);
            } else if (nextUpper) {
                builder.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }

    private static String sanitizeName(String name) {
        return TablistUtils.stripColors(name).trim();
    }

    private static Set<String> buildSearchBackedBazaarItems() {
        Set<String> items = new LinkedHashSet<>();
        items.addAll(CROPS_SET);
        items.addAll(PEST_ITEMS_SET);
        items.addAll(MISC_DROPS_SET);
        items.addAll(FEAST_ITEMS_SET);
        items.remove("Pet XP");
        items.remove("Purse");
        items.remove("Seasoning");
        return items;
    }
}
