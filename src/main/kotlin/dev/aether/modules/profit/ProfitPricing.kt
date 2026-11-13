package dev.aether.modules.profit

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.config.AetherConfig
import dev.aether.config.PetInfo
import dev.aether.modules.profit.helpers.PetXpTracker
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class ProfitPricing(private val onPricesChanged: Runnable) {
    private val bazaarPrices = LinkedHashMap<String, Double>()
    private val bazaarBuyPrices = LinkedHashMap<String, Double>()
    private val petLvl1Prices = HashMap<String, Long>()
    private val petMaxLvlPrices = HashMap<String, Long>()
    private val idByNameCache = ConcurrentHashMap<String, String>()
    @Volatile private var lastBazaarFetchTime = 0L

    init { syncConfiguredPetXpPrices() }

    fun canonicalizeTrackedName(itemName: String): String {
        val clean = sanitizeName(itemName)
        if (clean.isBlank()) return "Unknown Item"
        mappedNameForId(clean)?.let { return it }
        TRACKED_ITEMS.keys.firstOrNull { it.equals(clean, true) }?.let { return it }
        if (isPetXpEntry(clean)) return clean
        return normalizeName(clean)
    }

    fun getItemPrice(itemName: String): Double {
        val clean = sanitizeName(itemName)
        if (clean.isBlank()) return 0.0
        if (clean.startsWith("[Visitor] ")) {
            val actual = clean.substring(10)
            if (actual == "Visitor Cost") return 1.0
            if (actual == "Copper") return getPreferredPrice("ENCHANTMENT_GREEN_THUMB_1").let { if (it > 0) it / 1500.0 else 0.0 }
            return getItemPrice(actual)
        }
        if (clean == "[Spray] Sprayonator" || clean == "Purse") return 1.0
        return getPreferredPrice(resolveLookupKey(clean))
    }

    fun getNpcPrice(itemName: String) = TRACKED_ITEMS.getOrDefault(resolveLookupKey(itemName), 0.0)
    fun getItemValue(itemName: String, count: Long): Double {
        val clean = sanitizeName(itemName)
        if (count < 0) getPreferredBuyPrice(resolveLookupKey(clean)).takeIf { it > 0 }?.let { return it * count }
        return getItemPrice(itemName) * count
    }
    fun isPredefinedTrackedItem(itemName: String): Boolean {
        val clean = sanitizeName(itemName)
        return clean.isNotBlank() && (isPetXpEntry(clean) || TRACKED_ITEMS.containsKey(resolveLookupKey(clean)))
    }

    fun getCategorizedName(name: String): String {
        val clean = sanitizeName(name)
        if (clean == "[Spray] Sprayonator") return "[COST] Sprayonator"
        if (clean == "[Visitor] Visitor Cost") return "[COST] Visitor Cost"
        if (clean.startsWith("[Visitor] ")) return "[VISITOR] ${clean.substring(10)}"
        val tag = when {
            isCrop(clean) -> "CROP"
            isShard(clean) -> "SHARD"
            isPestItem(clean) -> "PEST"
            isPet(clean) -> "PET"
            isFeastItem(clean) -> "FEAST"
            isMiscDrop(clean) || isPetXpEntry(clean) -> "MISC"
            else -> "OTHER"
        }
        var displayName = clean.replace("Enchanted ", "Ench. ")
        if (isPetXpEntry(clean)) displayName = clean.substring(8, clean.length - 1) + " XP"
        return "[$tag] $displayName"
    }

    fun getCompactCategoryLabel(category: String) = when (category) {
        "Crops" -> "[CROP]"; "Shards" -> "[SHARD]"; "Pest Items" -> "[PEST]"; "Pets" -> "[PET]"; "Feast" -> "[FEAST]"; "Misc Drops" -> "[MISC]"; "Visitor" -> "[VISITOR]"; "Costs" -> "[COST]"; else -> "[OTHER]"
    }
    private fun isNpcSellable(key: String) = CROPS_SET.contains(key) || NPC_SELLABLE_DROPS.contains(key)
    fun isCrop(name: String) = CROPS_SET.contains(sanitizeName(name))
    fun isPestItem(name: String) = PEST_ITEMS_SET.contains(sanitizeName(name))
    fun isShard(name: String) = SHARDS_SET.contains(sanitizeName(name))
    fun isPet(name: String) = PETS_SET.contains(sanitizeName(name))
    fun isFeastItem(name: String) = FEAST_ITEMS_SET.contains(sanitizeName(name))
    fun isMiscDrop(name: String) = MISC_DROPS_SET.contains(sanitizeName(name))
    fun isPetXpEntry(name: String) = sanitizeName(name).lowercase().startsWith("pet xp (")

    fun printPetXpPriceDebug(client: Minecraft) {
        if (client.player == null) return
        ClientUtils.sendMessage("§b[Pet XP Tracker] §fCurrently tracking:", false)
        for (config in AetherConfig.PET_TRACKER_LIST.get()) {
            val info = PetInfo(config)
            val levelOne = petLvl1Prices.getOrDefault(info.name, 0L)
            val maximum = petMaxLvlPrices.getOrDefault(info.name, 0L)
            val perXp = bazaarPrices.getOrDefault("Pet XP (${info.name})", 0.0)
            val levelOneText = if (levelOne > 0) String.format("%,d", levelOne) else "not found"
            val maximumText = if (maximum > 0) String.format("%,d", maximum) else "not found"
            val margin = if (perXp > 0) String.format("%.3f", perXp) else "not fetched"
            ClientUtils.sendMessage("${info.name}§f: §7L1: §6$levelOneText §7Max: §6$maximumText §7-> §a$margin §7C/XP", false)
        }
    }

    fun reloadConfiguredPetXpPrices() = syncConfiguredPetXpPrices()
    fun startStartupPriceFetch() = fetchBazaarPrices()
    fun handlePriceSourceChanged() {
        onPricesChanged.run()
        if (ProfitPriceSource.fromConfig(AetherConfig.PROFIT_PRICE_SOURCE.get()) == ProfitPriceSource.BAZAAR) fetchBazaarPrices()
    }
    fun handleShardPriceSourceChanged() { onPricesChanged.run(); fetchBazaarPrices() }
    fun refreshBazaarPricesIfNeeded() { if (System.currentTimeMillis() - lastBazaarFetchTime > 3_600_000L) fetchBazaarPrices() }

    fun fetchIdByName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (idByNameCache.containsKey(name)) return idByNameCache[name]?.takeIf { it.isNotEmpty() }
        try {
            val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val request = HttpRequest.newBuilder().uri(URI.create("https://sky.coflnet.com/api/item/search/$encoded")).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val results: JsonArray = JsonParser.parseString(response.body()).asJsonArray
                if (!results.isEmpty) return results[0].asJsonObject.get("id").asString.also { idByNameCache[name] = it }
            }
        } catch (e: Exception) { System.err.println("[Aether] Cofl item ID lookup failed for '$name': ${e.message}") }
        idByNameCache[name] = ""
        return null
    }

    private fun getPreferredPrice(key: String?): Double {
        if (key.isNullOrBlank()) return 0.0
        if (isShard(key)) {
            val prices = if (ShardPriceSource.fromConfig(AetherConfig.SHARD_PRICE_SOURCE.get()) == ShardPriceSource.BUY_OFFER) bazaarBuyPrices else bazaarPrices
            prices[key]?.takeIf { it > 0 }?.let { return it }
        }
        if (ProfitPriceSource.fromConfig(AetherConfig.PROFIT_PRICE_SOURCE.get()) == ProfitPriceSource.BAZAAR) {
            val market = bazaarPrices.getOrDefault(key, 0.0)
            if (isNpcSellable(key)) return maxOf(market, TRACKED_ITEMS.getOrDefault(key, 0.0))
            if (market > 0) return market
        }
        return TRACKED_ITEMS.getOrDefault(key, 0.0)
    }
    private fun getPreferredBuyPrice(key: String?): Double {
        if (key.isNullOrBlank()) return 0.0
        if (ProfitPriceSource.fromConfig(AetherConfig.PROFIT_PRICE_SOURCE.get()) == ProfitPriceSource.BAZAAR) bazaarBuyPrices[key]?.takeIf { it > 0 }?.let { return it }
        return getPreferredPrice(key)
    }
    private fun resolveLookupKey(itemName: String): String {
        val clean = sanitizeName(itemName)
        mappedNameForId(clean)?.let { return it }
        return TRACKED_ITEMS.keys.firstOrNull { it.equals(clean, true) } ?: clean
    }
    private fun mappedNameForId(itemName: String) = BAZAAR_MAPPING.entries.firstOrNull { it.value.equals(itemName, true) }?.key

    @Synchronized private fun fetchBazaarPrices() {
        lastBazaarFetchTime = System.currentTimeMillis()
        Thread({ performFetchInternal(HttpClient.newHttpClient()) }, "Aether-Profit-Pricing").start()
    }
    private fun performFetchInternal(http: HttpClient) {
        for ((name, id) in BAZAAR_MAPPING) try { fetchAndStoreBazaarPrice(http, name, id) } catch (e: Exception) { System.err.println("Failed to fetch bazaar price for $name: ${e.message}") }
        for (name in SEARCH_BACKED_BAZAAR_ITEMS) {
            if (BAZAAR_MAPPING.containsKey(name)) continue
            try { fetchIdByName(name)?.takeIf { it.isNotBlank() }?.let { fetchAndStoreBazaarPrice(http, name, it) } }
            catch (e: Exception) { System.err.println("Failed to fetch search-backed bazaar price for $name: ${e.message}") }
        }
        syncConfiguredPetXpPrices(); onPricesChanged.run()
    }
    private fun fetchAndStoreBazaarPrice(http: HttpClient, itemName: String, itemTag: String) {
        val request = HttpRequest.newBuilder().uri(URI.create("https://sky.coflnet.com/api/bazaar/$itemTag/snapshot")).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return
        val root = JsonParser.parseString(response.body())
        if (!root.isJsonObject) return
        val data = root.asJsonObject
        getDouble(data, "sellPrice").takeIf { it > 0 }?.let { bazaarPrices[itemName] = it }
        getDouble(data, "buyPrice").takeIf { it > 0 }?.let { bazaarBuyPrices[itemName] = it }
    }
    private fun syncConfiguredPetXpPrices() {
        for (config in AetherConfig.PET_TRACKER_LIST.get()) {
            val info = PetInfo(config)
            val totalXp = PetXpTracker.getXpTable(info.rarity, info.maxLevel)[info.maxLevel]
            petLvl1Prices[info.name] = info.level1Price
            petMaxLvlPrices[info.name] = info.maxLevelPrice
            if (info.level1Price >= 0 && info.maxLevelPrice > info.level1Price && totalXp > 0) {
                val price = (info.maxLevelPrice - info.level1Price).toDouble() / totalXp
                bazaarPrices["Pet XP (${info.name})"] = price
                bazaarBuyPrices["Pet XP (${info.name})"] = price
            } else {
                bazaarPrices.remove("Pet XP (${info.name})"); bazaarBuyPrices.remove("Pet XP (${info.name})")
            }
        }
        onPricesChanged.run()
    }

    private fun sanitizeName(name: String) = TablistUtils.stripColors(name).trim()
    private fun normalizeName(name: String): String = buildString {
        var nextUpper = true
        for (char in name) {
            if (Character.isSpaceChar(char)) { nextUpper = true; append(char) }
            else if (nextUpper) { append(char.uppercaseChar()); nextUpper = false }
            else append(char.lowercaseChar())
        }
    }

    companion object {
        private val CROPS_SET = setOf("Wheat", "Enchanted Wheat", "Enchanted Hay Bale", "Seeds", "Enchanted Seeds", "Box of Seeds", "Potato", "Enchanted Potato", "Enchanted Baked Potato", "Carrot", "Enchanted Carrot", "Enchanted Golden Carrot", "Melon Slice", "Melon Block", "Enchanted Melon Slice", "Enchanted Melon", "Pumpkin", "Enchanted Pumpkin", "Polished Pumpkin", "Sugar Cane", "Enchanted Sugar", "Enchanted Sugar Cane", "Cactus", "Enchanted Cactus Green", "Enchanted Cactus", "Mushroom", "Red Mushroom", "Brown Mushroom", "Enchanted Red Mushroom", "Enchanted Brown Mushroom", "Enchanted Red Mushroom Block", "Enchanted Brown Mushroom Block", "Cocoa Beans", "Enchanted Cocoa Beans", "Enchanted Cookie", "Nether Wart", "Enchanted Nether Wart", "Mutant Nether Wart", "Sunflower", "Enchanted Sunflower", "Compacted Sunflower", "Moonflower", "Enchanted Moonflower", "Compacted Moonflower", "Wild Rose", "Enchanted Wild Rose", "Compacted Wild Rose")
        private val PEST_ITEMS_SET = setOf("Beady Eyes", "Chirping Stereo", "Sunder VI Book", "Clipped Wings", "Bookworm's Favorite Book", "Atmospheric Filter", "Wriggling Larva", "Pesterminator I Book", "Squeaky Toy", "Squeaky Mousemat", "Fire in a Bottle", "Vermin Vaporizer Chip", "Mantid Claw", "Overclocker 3000", "Vinyl", "Dung", "Honey Jar", "Plant Matter", "Tasty Cheese", "Compost", "Jelly", "Pest Shard", "Locust Larva")
        private val PETS_SET = setOf("Epic Slug", "Legendary Slug", "Rat")
        private val SHARDS_SET = setOf("Pest Shard", "Fly Shard", "Cricket Shard", "Locust Shard", "Rat Shard", "Mosquito Shard", "Earthworm Shard", "Mite Shard", "Moth Shard", "Slug Shard", "Beetle Shard", "Firefly Shard", "Dragonfly Shard", "Praying Mantis Shard")
        private val MISC_DROPS_SET = setOf("Cropie", "Squash", "Fermento", "Helianthus", "Tool EXP Capsule", "Pet XP", "Purse", "Raw Mutton", "Enchanted Raw Mutton", "Enchanted Cooked Mutton")
        private val FEAST_ITEMS_SET = setOf("Cornucopia", "Carrot Zest", "Deepfries", "Aggourdian", "Cane Knot", "Melon Juice", "Cactus Flower", "Designer Coffee Beans", "Feastfungus", "Botroot", "Salted Sunflower Seeds", "Crystalized Moonlight", "Floral Gelatin", "Seasoning")
        private val NPC_SELLABLE_DROPS = setOf("Cropie", "Squash", "Fermento", "Tool EXP Capsule", "Vinyl")
        private val TRACKED_ITEMS = mapOf(
            "Wheat" to 6.0, "Enchanted Wheat" to 960.0, "Enchanted Hay Bale" to 153600.0, "Seeds" to 3.0, "Enchanted Seeds" to 480.0, "Box of Seeds" to 76800.0,
            "Potato" to 3.0, "Enchanted Potato" to 480.0, "Enchanted Baked Potato" to 76800.0, "Carrot" to 3.0, "Enchanted Carrot" to 480.0, "Enchanted Golden Carrot" to 76800.0,
            "Melon Slice" to 2.0, "Melon Block" to 18.0, "Enchanted Melon Slice" to 320.0, "Enchanted Melon" to 51200.0, "Pumpkin" to 10.0, "Enchanted Pumpkin" to 1600.0, "Polished Pumpkin" to 256000.0,
            "Sugar Cane" to 4.0, "Enchanted Sugar" to 640.0, "Enchanted Sugar Cane" to 102400.0, "Cactus" to 4.0, "Enchanted Cactus Green" to 640.0, "Enchanted Cactus" to 102400.0,
            "Mushroom" to 10.0, "Red Mushroom" to 10.0, "Brown Mushroom" to 10.0, "Enchanted Red Mushroom" to 1600.0, "Enchanted Brown Mushroom" to 1600.0, "Enchanted Red Mushroom Block" to 256000.0, "Enchanted Brown Mushroom Block" to 256000.0,
            "Cocoa Beans" to 3.0, "Enchanted Cocoa Beans" to 480.0, "Enchanted Cookie" to 76800.0, "Nether Wart" to 4.0, "Enchanted Nether Wart" to 640.0, "Mutant Nether Wart" to 102400.0,
            "Sunflower" to 4.0, "Enchanted Sunflower" to 640.0, "Compacted Sunflower" to 102400.0, "Moonflower" to 4.0, "Enchanted Moonflower" to 640.0, "Compacted Moonflower" to 102400.0,
            "Wild Rose" to 4.0, "Enchanted Wild Rose" to 640.0, "Compacted Wild Rose" to 102400.0, "Beady Eyes" to 25000.0, "Chirping Stereo" to 100000.0, "Sunder VI Book" to 0.0,
            "Clipped Wings" to 25000.0, "Bookworm's Favorite Book" to 10000.0, "Atmospheric Filter" to 100000.0, "Wriggling Larva" to 250000.0, "Pesterminator I Book" to 0.0,
            "Squeaky Toy" to 10000.0, "Squeaky Mousemat" to 1000000.0, "Fire in a Bottle" to 100000.0, "Vermin Vaporizer Chip" to 0.0, "Mantid Claw" to 75000.0, "Overclocker 3000" to 250000.0,
            "Vinyl" to 50000.0, "Dung" to 0.0, "Honey Jar" to 0.0, "Plant Matter" to 0.0, "Tasty Cheese" to 0.0, "Compost" to 0.0, "Jelly" to 0.0,
            "Epic Slug" to 500000.0, "Legendary Slug" to 5000000.0, "Rat" to 5000.0, "Cropie" to 25000.0, "Squash" to 75000.0, "Fermento" to 250000.0,
            "Helianthus" to 0.0, "Tool EXP Capsule" to 100000.0, "Pet XP" to 0.0, "Raw Mutton" to 0.0, "Enchanted Raw Mutton" to 0.0, "Enchanted Cooked Mutton" to 0.0,
            "Pest Shard" to 0.0, "Fly Shard" to 0.0, "Cricket Shard" to 0.0, "Locust Shard" to 0.0, "Rat Shard" to 0.0, "Mosquito Shard" to 0.0, "Earthworm Shard" to 0.0,
            "Mite Shard" to 0.0, "Moth Shard" to 0.0, "Slug Shard" to 0.0, "Beetle Shard" to 0.0, "Firefly Shard" to 0.0, "Dragonfly Shard" to 0.0, "Praying Mantis Shard" to 0.0,
            "Biofuel" to 0.0, "Farming Exp Boost" to 0.0, "Harvest Harbinger V Potion" to 0.0, "Velvet Top Hat" to 0.0, "Cashmere Hat" to 0.0, "Satin Trousers" to 0.0, "Oxford Shoes" to 0.0,
            "Space Helmet" to 0.0, "Wild Strawberry Dye" to 0.0, "Copper Dye" to 0.0, "Clouds Rune I" to 0.0, "Fire Spiral Rune I" to 0.0, "Gem Rune I" to 0.0, "Golden Rune I" to 0.0,
            "Hearts Rune I" to 0.0, "Hot Rune I" to 0.0, "Lava Rune I" to 0.0, "Lightning Rune I" to 0.0, "Magical Rune I" to 0.0, "Music Rune I" to 0.0, "Rainbow Rune I" to 0.0,
            "Redstone Rune I" to 0.0, "Smokey Rune I" to 0.0, "Snow Rune I" to 0.0, "Sparkling Rune I" to 0.0, "Wake Rune I" to 0.0, "White Spiral Rune I" to 0.0, "Zap Rune I" to 0.0,
            "Overgrown Grass" to 0.0, "Flowering Bouqet" to 0.0, "Green Bandana" to 0.0, "Hypercharge Chip" to 0.0, "Quickdraw Chip" to 0.0, "Superboom TNT" to 0.0, "Green Candy" to 0.0,
            "Purple Candy" to 0.0, "Ice Essence" to 0.0, "Diamond Essence" to 0.0, "Gold Essence" to 0.0, "Jacob's Ticket" to 0.0, "Turbo-Cacti I Book" to 0.0, "Turbo-Cane I Book" to 0.0,
            "Turbo-Carrot I Book" to 0.0, "Turbo-Cocoa I Book" to 0.0, "Turbo-Melon I Book" to 0.0, "Turbo-Moonflower I Book" to 0.0, "Turbo-Mushrooms I Book" to 0.0, "Turbo-Potato I Book" to 0.0,
            "Turbo-Pumpkin I Book" to 0.0, "Turbo-Rose I Book" to 0.0, "Turbo-Sunflower I Book" to 0.0, "Turbo-Warts I Book" to 0.0, "Turbo-Wheat I Book" to 0.0, "Cultivating I Book" to 0.0,
            "Delicate V Book" to 0.0, "Replenish I Book" to 0.0, "Dedication IV Book" to 0.0, "Jungle Key" to 0.0, "Pet Cake" to 0.0, "Fine Flour" to 0.0, "Arachne Fragment" to 0.0,
            "Locust Larva" to 0.0, "Cornucopia" to 0.0, "Carrot Zest" to 0.0, "Deepfries" to 0.0, "Aggourdian" to 0.0, "Cane Knot" to 0.0, "Melon Juice" to 0.0, "Cactus Flower" to 0.0,
            "Designer Coffee Beans" to 0.0, "Feastfungus" to 0.0, "Botroot" to 0.0, "Salted Sunflower Seeds" to 0.0, "Crystalized Moonlight" to 0.0, "Floral Gelatin" to 0.0, "Seasoning" to 0.0, "Purse" to 1.0
        )
        private val BAZAAR_MAPPING = mapOf(
            "Sunder VI Book" to "ENCHANTMENT_SUNDER_6", "Pesterminator I Book" to "ENCHANTMENT_PESTERMINATOR_1", "Dung" to "DUNG", "Honey Jar" to "HONEY_JAR", "Plant Matter" to "PLANT_MATTER", "Tasty Cheese" to "CHEESE_FUEL", "Compost" to "COMPOST", "Jelly" to "JELLY", "Helianthus" to "HELIANTHUS", "Vermin Vaporizer Chip" to "VERMIN_VAPORIZER_GARDEN_CHIP",
            "ENCHANTMENT_GREEN_THUMB_1" to "ENCHANTMENT_GREEN_THUMB_1", "Pest Shard" to "SHARD_PEST", "Fly Shard" to "SHARD_FLY", "Cricket Shard" to "SHARD_CRICKET", "Locust Shard" to "SHARD_LOCUST", "Rat Shard" to "SHARD_RAT", "Mosquito Shard" to "SHARD_MOSQUITO", "Earthworm Shard" to "SHARD_EARTHWORM", "Mite Shard" to "SHARD_MITE", "Moth Shard" to "SHARD_MOTH", "Slug Shard" to "SHARD_SLUG", "Beetle Shard" to "SHARD_BEETLE", "Firefly Shard" to "SHARD_FIREFLY", "Dragonfly Shard" to "SHARD_DRAGONFLY", "Praying Mantis Shard" to "SHARD_PRAYING_MANTIS",
            "Overgrown Grass" to "OVERGROWN_GRASS", "Flowering Bouqet" to "FLOWERING_BOUQUET", "Green Bandana" to "GREEN_BANDANA", "Hypercharge Chip" to "HYPERCHARGE_GARDEN_CHIP", "Quickdraw Chip" to "QUICKDRAW_GARDEN_CHIP", "Superboom TNT" to "SUPERBOOM_TNT", "Green Candy" to "GREEN_CANDY", "Purple Candy" to "PURPLE_CANDY", "Ice Essence" to "ESSENCE_ICE", "Diamond Essence" to "ESSENCE_DIAMOND", "Gold Essence" to "ESSENCE_GOLD", "Jacob's Ticket" to "JACOBS_TICKET",
            "Turbo-Cacti I Book" to "ENCHANTMENT_TURBO_CACTUS_1", "Turbo-Cane I Book" to "ENCHANTMENT_TURBO_CANE_1", "Turbo-Carrot I Book" to "ENCHANTMENT_TURBO_CARROT_1", "Turbo-Cocoa I Book" to "ENCHANTMENT_TURBO_COCO_1", "Turbo-Melon I Book" to "ENCHANTMENT_TURBO_MELON_1", "Turbo-Moonflower I Book" to "ENCHANTMENT_TURBO_MOONFLOWER_1", "Turbo-Mushrooms I Book" to "ENCHANTMENT_TURBO_MUSHROOMS_1", "Turbo-Potato I Book" to "ENCHANTMENT_TURBO_POTATO_1", "Turbo-Pumpkin I Book" to "ENCHANTMENT_TURBO_PUMPKIN_1", "Turbo-Rose I Book" to "ENCHANTMENT_TURBO_ROSE_1", "Turbo-Sunflower I Book" to "ENCHANTMENT_TURBO_SUNFLOWER_1", "Turbo-Warts I Book" to "ENCHANTMENT_TURBO_WARTS_1", "Turbo-Wheat I Book" to "ENCHANTMENT_TURBO_WHEAT_1", "Cultivating I Book" to "ENCHANTMENT_CULTIVATING_1", "Delicate V Book" to "ENCHANTMENT_DELICATE_5", "Replenish I Book" to "ENCHANTMENT_REPLENISH_1", "Dedication IV Book" to "ENCHANTMENT_DEDICATION_4", "Jungle Key" to "JUNGLE_KEY", "Pet Cake" to "PET_CAKE", "Fine Flour" to "FINE_FLOUR", "Arachne Fragment" to "ARACHNE_FRAGMENT", "Biofuel" to "BIOFUEL", "Farming Exp Boost" to "PET_ITEM_FARMING_SKILL_BOOST_UNCOMMON", "Harvest Harbinger V Potion" to "POTION_harvest_harbinger",
            "Velvet Top Hat" to "VELVET_TOP_HAT", "Cashmere Hat" to "CASHMERE_JACKET", "Satin Trousers" to "SATIN_TROUSERS", "Oxford Shoes" to "OXFORD_SHOES", "Space Helmet" to "DCTR_SPACE_HELM", "Wild Strawberry Dye" to "DYE_WILD_STRAWBERRY", "Copper Dye" to "DYE_COPPER", "Clouds Rune I" to "RUNE_CLOUDS", "Fire Spiral Rune I" to "RUNE_FIRE_SPIRAL", "Gem Rune I" to "RUNE_GEM", "Golden Rune I" to "RUNE_GOLDEN", "Hearts Rune I" to "RUNE_HEARTS", "Hot Rune I" to "RUNE_HOT", "Lava Rune I" to "RUNE_LAVA", "Lightning Rune I" to "RUNE_LIGHTNING", "Magical Rune I" to "RUNE_MAGIC", "Music Rune I" to "RUNE_MUSIC", "Rainbow Rune I" to "RUNE_RAINBOW", "Redstone Rune I" to "RUNE_REDSTONE", "Smokey Rune I" to "RUNE_SMOKEY", "Snow Rune I" to "RUNE_SNOW", "Sparkling Rune I" to "RUNE_SPARKLING", "Wake Rune I" to "RUNE_WAKE", "White Spiral Rune I" to "RUNE_WHITE_SPIRAL", "Zap Rune I" to "RUNE_ZAP",
            "Cornucopia" to "CORNUCOPIA", "Carrot Zest" to "CARROT_ZEST", "Deepfries" to "DEEPFRIES", "Aggourdian" to "AGGOURDIAN", "Cane Knot" to "CANE_KNOT", "Melon Juice" to "MELON_JUICE", "Cactus Flower" to "CACTUS_FLOWER", "Designer Coffee Beans" to "DESIGNER_COFFEE_BEANS", "Feastfungus" to "FEASTFUNGUS", "Botroot" to "BOTROOT", "Salted Sunflower Seeds" to "SALTED_SUNFLOWER_SEEDS", "Crystalized Moonlight" to "CRYSTALIZED_MOONLIGHT", "Floral Gelatin" to "FLORAL_GELATIN", "Locust Larva" to "LOCUST_LARVA", "Raw Mutton" to "MUTTON", "Enchanted Raw Mutton" to "ENCHANTED_MUTTON", "Enchanted Cooked Mutton" to "ENCHANTED_COOKED_MUTTON"
        )
        private val SEARCH_BACKED_BAZAAR_ITEMS = (CROPS_SET + PEST_ITEMS_SET + MISC_DROPS_SET + FEAST_ITEMS_SET).filterNot { it == "Pet XP" || it == "Purse" || it == "Seasoning" }.toSet()
        private fun getDouble(obj: JsonObject, key: String): Double {
            val value: JsonElement = obj.get(key) ?: return 0.0
            if (value.isJsonNull) return 0.0
            return try { value.asDouble } catch (_: RuntimeException) { 0.0 }
        }
    }
}
