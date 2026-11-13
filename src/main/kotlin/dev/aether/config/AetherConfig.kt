package dev.aether.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.config.entries.BooleanEntry
import dev.aether.config.entries.DoubleEntry
import dev.aether.config.entries.FloatEntry
import dev.aether.config.entries.IntEntry
import dev.aether.config.entries.ListEntry
import dev.aether.config.entries.StringEntry
import dev.aether.util.AetherLanguageManager
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.nio.file.Files
import java.util.ArrayList

// Every entry is created by a Config.* factory, which also registers it for JSON persistence.
// JSON keys stay camelCase so existing config files keep loading.
class AetherConfig private constructor() {
    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val CORRUPTED_EPOCH_WINDOW_MS = 30L * DAY_MS
        private val DEFAULT_AUTOSELL_ITEM_NAMES: List<String> = listOf(
            "Atmospheric Filter", "Squeaky Toy", "Beady Eyes", "Clipped Wings",
            "Overclocker", "Mantid Claw", "Flowering Bouquet", "Bookworm",
            "Chirping Stereo", "Firefly", "Capsule", "Vinyl", "Wriggling Larva",
            "Quickdraw", "Rarefinder"
        )
        private val DEFAULT_SUPERCRAFT_ITEMS: List<String> = listOf("Box of Seeds", "Enchanted Hay Bale")

        private val CONFIG_FILE: File = FabricLoader.getInstance().configDir.resolve("aether_config.json").toFile().also {
            Config.setConfigPath(it.toPath())
        }

        @JvmStatic fun init() {
            HumanizationPresetManager.init()
            FarmingMacroPresetManager.init()
            load()
            AetherLanguageManager.init()
        }

        @JvmStatic fun save() {
            Config.save()
            ConfigProfileManager.syncActiveProfileFromLiveConfig()
        }

        @JvmStatic fun flush() {
            Config.flush()
            ConfigProfileManager.syncActiveProfileFromLiveConfig()
        }

        @JvmStatic fun load() {
            Config.load()
            migrateLegacyLoadoutKeys(CONFIG_FILE)
            migrateLegacyDelayRanges(CONFIG_FILE)
            resetRuntimeOnlyEntries()
            sanitizeLifetimeAccumulated()
            ensureAutoSellDefaults()
            AetherLanguageManager.onConfigLoaded()
        }

        @JvmStatic fun reset() = Config.reset()

        @JvmStatic fun toJsonString(): String = Config.toJsonString()

        @JvmStatic fun loadFrom(file: File): Boolean {
            val loaded = Config.loadFrom(file.toPath())
            if (loaded) {
                migrateLegacyDelayRanges(file)
                migrateLegacyLoadoutKeys(file)
                resetRuntimeOnlyEntries()
                sanitizeLifetimeAccumulated()
                ensureAutoSellDefaults()
                AetherLanguageManager.onConfigLoaded()
            }
            return loaded
        }

        @JvmStatic fun getConfigFile(): File = CONFIG_FILE

        // Blanks webhook, co-op names and usernames so an exported config is safe to paste publicly.
        @JvmStatic fun exportSanitizedJson(): String {
            val json = toJsonString()
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                obj.addProperty("discordWebhookUrl", "")
                obj.addProperty("remoteControlBotToken", "")
                obj.add("coopNames", JsonArray())
                obj.addProperty("customUsername", "")
                obj.addProperty("serverNick", "")
                com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj)
            } catch (e: Exception) {
                json
            }
        }

        // Runs the same post-load fixups as loadFrom; false on invalid JSON.
        @JvmStatic fun importFromJson(json: String): Boolean {
            val loaded = Config.loadFromJson(json)
            if (loaded) {
                try {
                    migrateLegacyLoadoutKeys(JsonParser.parseString(json).asJsonObject)
                } catch (_: Exception) {
                }
                resetRuntimeOnlyEntries()
                sanitizeLifetimeAccumulated()
                ensureAutoSellDefaults()
                AetherLanguageManager.onConfigLoaded()
                save()
            }
            return loaded
        }

        private fun sanitizeLifetimeAccumulated() {
            val rawValue = LIFETIME_ACCUMULATED.get()
            val savedValue = rawValue.toLong()
            val normalized = maxOf(0L, savedValue)
            val now = System.currentTimeMillis()
            var sanitized = normalized
            if (kotlin.math.abs(normalized - now) <= CORRUPTED_EPOCH_WINDOW_MS) {
                System.err.println("[Aether] Ignoring corrupted lifetime timer value that matched epoch time: $normalized")
                sanitized = 0L
            }
            if (sanitized != savedValue || rawValue != sanitized.toDouble()) {
                LIFETIME_ACCUMULATED.set(sanitized.toDouble())
                save()
            }
        }

        private fun resetRuntimeOnlyEntries() { ENABLE_METAL_DETECTOR.set(false) }

        private fun ensureAutoSellDefaults() {
            if (AUTO_SELL_ITEMS.get().isNotEmpty()) return
            val fallback = if (BOOSTER_COOKIE_ITEMS.get().isEmpty()) DEFAULT_AUTOSELL_ITEM_NAMES else BOOSTER_COOKIE_ITEMS.get()
            AUTO_SELL_ITEMS.set(ArrayList(fallback))
            save()
        }

        private fun migrateLegacyDelayRanges(sourceFile: File?) {
            if (sourceFile == null || !sourceFile.exists()) return
            try {
                Files.newBufferedReader(sourceFile.toPath()).use { reader ->
                    val root = JsonParser.parseReader(reader).asJsonObject
                    val additionalRandomDelay = readInt(root, "additionalRandomDelay", 0)
                    var updated = false
                    updated = updated or migrateLegacyDelayRange(root, "macroLaneSwitchDelay", "macroLaneSwitchDelayMin", "macroLaneSwitchDelayMax", MACRO_LANE_SWITCH_DELAY_MIN, MACRO_LANE_SWITCH_DELAY_MAX, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "pestChatTriggerDelay", "pestChatTriggerDelayMin", "pestChatTriggerDelayMax", PEST_CHAT_TRIGGER_DELAY_MIN, PEST_CHAT_TRIGGER_DELAY_MAX, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "pestAotvDelay", "pestAotvDelayMin", "pestAotvDelayMax", PEST_AOTV_DELAY_MIN, PEST_AOTV_DELAY_MAX, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "rodSwapDelay", "rodSwapDelayMin", "rodSwapDelayMax", ROD_SWAP_DELAY_MIN, ROD_SWAP_DELAY_MAX, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "guiFirstClickDelay", "guiFirstClickDelayMin", "guiFirstClickDelayMax", GUI_FIRST_CLICK_DELAY_MIN, GUI_FIRST_CLICK_DELAY_MAX, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "guiClickDelay", "guiClickDelayMin", "guiClickDelayMax", GUI_CLICK_DELAY_MIN, GUI_CLICK_DELAY_MAX, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "pickUpStashDelay", "pickUpStashDelayMin", "pickUpStashDelayMax", PICK_UP_STASH_DELAY_MIN, PICK_UP_STASH_DELAY_MAX, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "junkItemDropDelay", "junkItemDropDelayMin", "junkItemDropDelayMax", JUNK_ITEM_DROP_DELAY_MIN, JUNK_ITEM_DROP_DELAY_MAX, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "georgePostSellDelayMs", "georgePostSellDelayMinMs", "georgePostSellDelayMaxMs", GEORGE_POST_SELL_DELAY_MIN_MS, GEORGE_POST_SELL_DELAY_MAX_MS, additionalRandomDelay)
                    updated = updated or migrateLegacyDelayRange(root, "bazaarDelay", "bazaarDelayMin", "bazaarDelayMax", BAZAAR_DELAY_MIN, BAZAAR_DELAY_MAX, additionalRandomDelay)
                    if (updated) save()
                }
            } catch (_: Exception) {
            }
        }

        private fun migrateLegacyLoadoutKeys(sourceFile: File?) {
            if (sourceFile == null || !sourceFile.exists()) return
            try {
                Files.newBufferedReader(sourceFile.toPath()).use { reader ->
                    val root = JsonParser.parseReader(reader).asJsonObject
                    if (migrateLegacyLoadoutKeys(root)) save()
                }
            } catch (_: Exception) {
            }
        }

        private fun migrateLegacyLoadoutKeys(root: JsonObject): Boolean {
            var updated = false
            if (root.has("autoLoadoutPest") || root.has("autoLoadoutVisitor") || root.has("autoWardrobePest") || root.has("autoWardrobeVisitor")) updated = true
            if (!root.has("loadoutSlotFarming") && root.has("wardrobeSlotFarming")) {
                LOADOUT_SLOT_FARMING.set(readInt(root, "wardrobeSlotFarming", LOADOUT_SLOT_FARMING.get())); updated = true
            }
            if (!root.has("loadoutSlotPest") && root.has("wardrobeSlotPest")) {
                LOADOUT_SLOT_PEST.set(readInt(root, "wardrobeSlotPest", LOADOUT_SLOT_PEST.get())); updated = true
            }
            if (!root.has("loadoutSlotPestKill") && root.has("loadoutSlotFarming")) {
                LOADOUT_SLOT_PEST_KILL.set(readInt(root, "loadoutSlotFarming", LOADOUT_SLOT_PEST_KILL.get())); updated = true
            } else if (!root.has("loadoutSlotPestKill") && root.has("wardrobeSlotFarming")) {
                LOADOUT_SLOT_PEST_KILL.set(readInt(root, "wardrobeSlotFarming", LOADOUT_SLOT_PEST_KILL.get())); updated = true
            }
            if (!root.has("loadoutSlotVisitor") && root.has("wardrobeSlotVisitor")) {
                LOADOUT_SLOT_VISITOR.set(readInt(root, "wardrobeSlotVisitor", LOADOUT_SLOT_VISITOR.get())); updated = true
            }
            return updated
        }

        private fun migrateLegacyDelayRange(root: JsonObject, legacyKey: String, minKey: String, maxKey: String, minEntry: IntEntry, maxEntry: IntEntry, additionalRandomDelay: Int): Boolean {
            if (!root.has(legacyKey) || root.has(minKey) || root.has(maxKey)) return false
            val legacyValue = readInt(root, legacyKey, minEntry.get())
            val extra = maxOf(0, additionalRandomDelay)
            minEntry.set(legacyValue)
            maxEntry.set(legacyValue + extra)
            return true
        }

        private fun readInt(root: JsonObject?, key: String, fallback: Int): Int {
            if (root == null || !root.has(key) || !root.get(key).isJsonPrimitive) return fallback
            return try { root.get(key).asInt } catch (_: Exception) { fallback }
        }

        private fun readBoolean(root: JsonObject?, key: String, fallback: Boolean): Boolean {
            if (root == null || !root.has(key) || !root.get(key).isJsonPrimitive) return fallback
            return try { root.get(key).asBoolean } catch (_: Exception) { fallback }
        }

        // All configuration entries follow below.

        @JvmField val AUTO_UPDATE: BooleanEntry = Config.bool("autoUpdate", false)
        @JvmField val CHECK_FOR_UPDATES: BooleanEntry = Config.bool("checkForUpdates", false)
        @JvmField val LANGUAGE_CODE: StringEntry = Config.string("languageCode", "en_us")
        @JvmField val TABLIST_SETUP_COMPLETE: BooleanEntry = Config.bool("tablistSetupComplete", false)
        // -- PEST ------------------------------------------------------------------

        @JvmField val PEST_THRESHOLD: IntEntry = Config.integer("pestThreshold", 2).range(1, 8)
        @JvmField val TRIGGER_PEST_ON_CHAT: BooleanEntry = Config.bool("triggerPestOnChat", true)
        @JvmField val ESTIMATE_PEST_DESTROYER_COMPLETION: BooleanEntry =
                        Config.bool("estimatePestDestroyerCompletion", true)
        @JvmField val USE_PEST_TRACKER_ABILITY: BooleanEntry = Config.bool("usePestTrackerAbility", true)
        @JvmField val PEST_TRACKER_DRAW_ARC: BooleanEntry = Config.bool("pestTrackerDrawArc", false)
        @JvmField val PEST_TRIGGER_ONLY_AFTER_REWARP: BooleanEntry = Config.bool("pestTriggerOnlyAfterRewarp", false)
        @JvmField val PEST_CHAT_TRIGGER_DELAY_MIN: IntEntry = Config.integer("pestChatTriggerDelayMin", 500)
                        .range(0, 5000)
        @JvmField val PEST_CHAT_TRIGGER_DELAY_MAX: IntEntry = Config.integer("pestChatTriggerDelayMax", 3000)
                        .range(0, 5000)
        @JvmField val PEST_DESTROYER_WALK_MODE: BooleanEntry = Config.bool("pestDestroyerWalkMode", false)
        @JvmField val DELAY_PEST_FOR_CROP_FEVER: BooleanEntry = Config.bool("delayPestForCropFever", false)
        @JvmField val PEST_ON_TRACK_ENABLED: BooleanEntry = Config.bool("pestOnTrackEnabled", false)
        // start: farmhelper ish pest on track
        @JvmField val PEST_ON_THE_TRACK_FOV: IntEntry = Config.integer("pestOnTheTrackFov", 360).range(1, 360)
        @JvmField val PEST_ON_THE_TRACK_ACQUIRE_DELAY_MS: IntEntry = Config.integer("pestOnTheTrackAcquireDelayMs", 750).range(0, 2000)
        @JvmField val PEST_ON_THE_TRACK_STUCK_TIMEOUT_MS: IntEntry = Config.integer("pestOnTheTrackStuckTimeoutMs", 5000).range(4000, 25000)
        @JvmField val PEST_ON_THE_TRACK_SKIP_JACOB: BooleanEntry = Config.bool("pestOnTheTrackSkipJacob", true)
        // end: farmhelper ish on track
        @JvmField val PEST_PLOT_TP_FOR_CURRENT_PLOT: BooleanEntry = Config.bool("pestPlotTpForCurrentPlot", false)
        @JvmField val ENABLE_PEST_TRAPS: BooleanEntry = Config.bool("enablePestTraps", false)
        @JvmField val PEST_TRAPS_PLOT: StringEntry = Config.string("pestTrapsPlot", "0")
        @JvmField val AUTO_CLEAR_PEST_TRAPS: BooleanEntry = Config.bool("autoClearPestTraps", false)
        @JvmField val AUTO_REFILL_PEST_TRAPS: BooleanEntry = Config.bool("autoRefillPestTraps", false)
        @JvmField val PEST_TRAPS_BAIT_MATERIAL: StringEntry = Config.string("pestTrapsBaitMaterial", "Tasty Cheese")
        @JvmField val PEST_TRAPS_BAIT_AMOUNT: IntEntry = Config.integer("pestTrapsBaitAmount", 64).range(1, 64)
        @JvmField val AUTO_MOSQUITO_FOR_PEST_TRAPS: BooleanEntry = Config.bool("autoMosquitoForPestTraps", false)
        @JvmField val PEST_TRAPS_PATHFIND: BooleanEntry = Config.bool("pestTrapsPathfind", false)
        @JvmField val PEST_TRAPS_X: IntEntry = Config.integer("pestTrapsX", 0)
        @JvmField val PEST_TRAPS_Y: IntEntry = Config.integer("pestTrapsY", 0)
        @JvmField val PEST_TRAPS_Z: IntEntry = Config.integer("pestTrapsZ", 0)
        @JvmField val PEST_TRAPS_HIGHLIGHT: BooleanEntry = Config.bool("pestTrapsHighlight", false)
        @JvmField val AUTO_PET_AFTER_TRAP_OPEN: BooleanEntry = Config.bool("autoPetAfterTrapOpen", false)
        @JvmField val AUTO_PET_AFTER_TRAP_OPEN_PET: StringEntry = Config.string("autoPetAfterTrapOpenPet", "")
        @JvmField val LEAVE_ONE_PEST_ALIVE: BooleanEntry = Config.bool("leaveOnePestAlive", false)
        @JvmField val LEAVE_ONE_PEST_PLOTS: ListEntry<String> = Config.list("leaveOnePestPlots", emptyList<String>(), String::class.java)
        @JvmField val SUNSET_PESTS: BooleanEntry = Config.bool("sunsetPests", false)
        // When enabled, the day->night restore doesn't happen right after cleaning finishes -
        // it stays day (Overbloom) through the whole farming stretch, and only flips to night
        // right before the pre-spawn loadout swap (long enough to let Fireflies spawn), then
        // back to day again the moment actual cleaning starts.
        @JvmField val SUNSET_PESTS_NIGHT_BEFORE_SPAWN: BooleanEntry =
                        Config.bool("sunsetPestsNightBeforeSpawn", false)
        @JvmField val BALLSACK_SHREDDER: BooleanEntry = Config.bool("ballsackShredder", false)
        @JvmField val BALLSACK_SHREDDER_TRIGGER_DELAY_MIN: IntEntry =
                        Config.integer("ballsackShredderTriggerDelayMin", 20000).range(0, 30000)
        @JvmField val BALLSACK_SHREDDER_TRIGGER_DELAY_MAX: IntEntry =
                        Config.integer("ballsackShredderTriggerDelayMax", 25000).range(0, 30000)
        @JvmField val BALLSACK_SHREDDER_PLOTS: ListEntry<String> = Config.list("ballsackShredderPlots", emptyList<String>(), String::class.java)
        @JvmField val BALLSACK_WARPS: IntEntry = Config.integer("ballsackWarps", 2).range(1, 5)
        @JvmField val BALLSACK_LOOK_DOWN: BooleanEntry = Config.bool("ballsackLookDown", true)
        @JvmField val BALLSACK_LOOK_DOWN_TIME_MS: IntEntry = Config.integer("ballsackLookDownTimeMs", 1000)
                        .range(0, 3000)
        @JvmField val PEST_AOTV_BETWEEN: BooleanEntry = Config.bool("pestAotvBetween", false)
        @JvmField val PEST_SMART_AOTV_ROUTING: BooleanEntry = Config.bool("pestSmartAotvRouting", true)
        @JvmField val PEST_ETHERWARP_TO_PEST: BooleanEntry = Config.bool("pestEtherwarpToPest", true)
        @JvmField val PEST_ETHERWARP_MIN_DISTANCE: FloatEntry =
                        Config.floatVal("pestEtherwarpMinDistance", 20.0f).range(10.0f, 50.0f)
        @JvmField val PEST_AOTV_START_DISTANCE: FloatEntry =
                        Config.floatVal("pestAotvStartDistance", 20.0f).range(12.0f, 40.0f)
        @JvmField val PEST_AOTV_STOP_DISTANCE: FloatEntry =
                        Config.floatVal("pestAotvStopDistance", 11.0f).range(6.0f, 20.0f)
        @JvmField val PEST_AOTV_CONFIRM_BETWEEN: BooleanEntry = Config.bool("pestAotvConfirmBetween", false)
        @JvmField val PEST_AOTV_DELAY_MIN: IntEntry = Config.integer("pestAotvDelayMin", 150).range(100, 250)
        @JvmField val PEST_AOTV_DELAY_MAX: IntEntry = Config.integer("pestAotvDelayMax", 250).range(100, 250)
        @JvmField val PEST_FOV_RANGE: FloatEntry = Config.floatVal("pestFovRange", 20.0f).range(0.0f, 90.0f)
        @JvmField val PEST_MAX_TURN_SPEED: FloatEntry =
                        Config.floatVal("pestMaxTurnSpeed", 300.0f).range(60.0f, 1200.0f)
        @JvmField val PEST_NEXT_TARGET_TURN_SPEED: FloatEntry =
                        Config.floatVal("pestNextTargetTurnSpeed", 450.0f).range(60.0f, 1200.0f)
        @JvmField val PEST_VACUUM_FOLLOW_DISTANCE: FloatEntry =
                        Config.floatVal("pestVacuumFollowDistance", 5.0f).range(2.0f, 7.0f)
        @JvmField val RESPECT_VACUUM_TRUE_RANGE: BooleanEntry = Config.bool("respectVacuumTrueRange", true)
        @JvmField val PEST_APPROACH_SPEED: FloatEntry =
                        Config.floatVal("pestApproachSpeed", 0.35f).range(0.15f, 0.8f)
        @JvmField val PEST_TRACKING_SMOOTHING_MS: FloatEntry =
                        Config.floatVal("pestTrackingSmoothingMs", 220.0f).range(100.0f, 500.0f)
        @JvmField val PEST_AIM_DRIFT: FloatEntry = Config.floatVal("pestAimDrift", 1.0f).range(0.0f, 2.0f)
        @JvmField val PEST_ABOVE_TARGET_PITCH_MIN: FloatEntry = Config.floatVal("pestAboveTargetPitchMin", 25.0f)
                        .range(20.0f, 40.0f)
        @JvmField val PEST_ABOVE_TARGET_PITCH_MAX: FloatEntry = Config.floatVal("pestAboveTargetPitchMax", 40.0f)
                        .range(10.0f, 90.0f)
        // -- PEST HUNTING ----------------------------------------------------------

        @JvmField val PEST_HUNTING: BooleanEntry = Config.bool("pestHunting", false)
        @JvmField val PEST_HUNTING_TRACKING_SMOOTHING_MS: FloatEntry =
                        Config.floatVal("pestHuntingTrackingSmoothingMs", 90f).range(75f, 300f)
        @JvmField val PEST_HUNTING_MAX_TURN_SPEED: FloatEntry =
                        Config.floatVal("pestHuntingMaxTurnSpeed", 700f).range(180f, 900f)
        @JvmField val PEST_HUNTING_VACUUM_STUN: BooleanEntry = Config.bool("pestHuntingVacuumStun", true)
        // bitmask of pest types that use the vacuum instead of the lasso
        @JvmField val PEST_HUNTING_VACUUM_PEST_MASK: IntEntry =
                        Config.integer("pestHuntingVacuumPestMask", 0)
        @JvmField val PEST_HUNTING_FOLLOW_DISTANCE: FloatEntry =
                        Config.floatVal("pestHuntingFollowDistance", 5.0f).range(1.0f, 8.0f)
        @JvmField val PEST_HUNTING_MAX_DISTANCE: FloatEntry =
                        Config.floatVal("pestHuntingMaxDistance", 8.0f).range(4.0f, 10.0f)
        @JvmField val PEST_HUNTING_TIMEOUT_MS: IntEntry = Config.integer("pestHuntingTimeoutMs", 45000)
                        .range(10000, 120000)
        @JvmField val PEST_HUNTING_MAX_THROWS: IntEntry = Config.integer("pestHuntingMaxThrows", 6)
                        .range(1, 15)
        // -- MANUAL PEST MODE ------------------------------------------------------

        @JvmField val MANUAL_PEST_MODE: BooleanEntry = Config.bool("manualPestMode", false)
        @JvmField val VACCUM_WHEN_START: BooleanEntry = Config.bool("vaccumwhenstart", false)
        @JvmField val MANUAL_PEST_SOUND_FILE: StringEntry = Config.string("manualPestSoundFile", "fnaf.mp3")
        // -- PEST EXCHANGE ---------------------------------------------------------

        @JvmField val AUTO_PEST_EXCHANGE: BooleanEntry = Config.bool("autoPestExchange", false)
        @JvmField val AUTO_PEST_USE_ABIPHONE: BooleanEntry = Config.bool("autoPestUseAbiphone", false)
        @JvmField val PEST_EXCHANGE_DELAY_MIN: IntEntry = Config.integer("pestExchangeDelayMin", 0)
                        .range(0, 5000)
        @JvmField val PEST_EXCHANGE_DELAY_MAX: IntEntry = Config.integer("pestExchangeDelayMax", 5000)
                        .range(0, 5000)
        @JvmField val PEST_EXCHANGE_DESK_X: IntEntry = Config.integer("pestExchangeDeskX", -26)
        @JvmField val PEST_EXCHANGE_DESK_Y: IntEntry = Config.integer("pestExchangeDeskY", 71)
        @JvmField val PEST_EXCHANGE_DESK_Z: IntEntry = Config.integer("pestExchangeDeskZ", -14)
        @JvmField val PEST_HIGHLIGHT_DESK: BooleanEntry = Config.bool("pestHighlightDesk", true)
        @JvmField val PEST_EXCHANGE_PATHFIND: BooleanEntry = Config.bool("pestExchangePathfind", true)
        @JvmField val PEST_EXCHANGE_FOV_RANGE: FloatEntry = Config.floatVal("pestExchangeFovRange", 4.0f)
                        .range(0.0f, 15.0f)
        // -- VISITOR ---------------------------------------------------------------

        @JvmField val VISITOR_THRESHOLD: IntEntry = Config.integer("visitorThreshold", 5).range(1, 10)
        @JvmField val AUTO_VISITOR: BooleanEntry = Config.bool("autoVisitor", false)
        @JvmField val VISITOR_ignore: ListEntry<String> = Config.list("visitorignore",
                        listOf("Spaceman", "Ravenous Rhino", "Taylor", "Vinyl Collector"), String::class.java)
        @JvmField val VISITOR_REJECT: ListEntry<String> = Config.list("visitorReject", emptyList<String>(), String::class.java)
        @JvmField val EQUIP_VISITOR_CUSTOM_ITEM: BooleanEntry = Config.bool("equipVisitorCustomItem", false)
        @JvmField val VISITOR_CUSTOM_ITEM: StringEntry = Config.string("visitorCustomItem", "")
        @JvmField val VISITOR_MAX_PURCHASE_LIMIT: IntEntry = Config.integer("visitorMaxPurchaseLimit", 10_000_000)
                        .range(0, 20_000_000)
        @JvmField val VISITOR_COINS_PER_COPPER: BooleanEntry = Config.bool("visitorCoinsPerCopper", false)
        @JvmField val VISITOR_COINS_PER_COPPER_LIMIT: IntEntry = Config.integer("visitorCoinsPerCopperLimit", 20_000)
                        .range(0, 100_000)
        @JvmField val VISITOR_ONLY_RARE_DROPS: BooleanEntry = Config.bool("visitorOnlyRareDrops", false)
        @JvmField val DISABLE_VISITORS_DURING_JACOBS_CONTEST: BooleanEntry = Config.bool("disableVisitorsDuringJacobsContest", false)
        @JvmField val DISABLE_COMPACTORS_DURING_VISITORS: BooleanEntry = Config.bool("disableCompactorsDuringVisitors", false)
        @JvmField val VISITOR_EMPTY_TIP_JAR: BooleanEntry = Config.bool("visitorEmptyTipJar", false)
        @JvmField val VISITOR_FOV_RANGE: FloatEntry = Config.floatVal("visitorFovRange", 12.0f)
                        .range(0.0f, 30.0f)
        @JvmField val VISITOR_DELAY_MIN: IntEntry = Config.integer("visitorDelayMin", 300).range(0, 1000)
        @JvmField val VISITOR_DELAY_MAX: IntEntry = Config.integer("visitorDelayMax", 500).range(0, 1000)
        // -- AUTO SPRAYONATOR -----------------------------------------------------

        @JvmField val AUTO_SPRAYONATOR: BooleanEntry = Config.bool("autoSprayonator", false)
        @JvmField val AUTO_SPRAYONATOR_MATERIAL: StringEntry = Config.string("autoSprayonatorMaterial", "Use Selected")
        @JvmField val AUTO_SPRAYONATOR_AUTO_BUY: BooleanEntry = Config.bool("autoSprayonatorAutoBuy", true)
        @JvmField val AUTO_SPRAYONATOR_AUTO_BUY_AMOUNT: IntEntry = Config
                        .integer("autoSprayonatorAutoBuyAmount", 64).range(1, 640)
        @JvmField val AUTO_SPRAYONATOR_DETECT_TIME: IntEntry = Config
                        .integer("autoSprayonatorDetectTime", 10).range(5, 30)
        // -- DYNAMIC PESTS --------------------------------------------------------

        @JvmField val DYNAMIC_PESTS_ENABLED: BooleanEntry = Config
                        .bool("dynamicPestsEnabled", false)
        @JvmField val DYNAMIC_PESTS_MODE: IntEntry = Config
                        .integer("dynamicPestsMode", 0).range(0, 2)
        @JvmField val DYNAMIC_PESTS_FALLBACK_SPRAY: IntEntry = Config
                        .integer("dynamicPestsFallbackSpray", 0).range(0, 5)
        @JvmField val DYNAMIC_PESTS_FALLBACK_VINYL: IntEntry = Config
                        .integer("dynamicPestsFallbackVinyl", 0).range(0, 12)
        @JvmField val DYNAMIC_PESTS_FEAST_PRIORITY: ListEntry<String> = Config
                        .list("dynamicPestsFeastPriority", emptyList<String>(), String::class.java)
        @JvmField val DYNAMIC_PESTS_CONTEST_PRIORITY: ListEntry<String> = Config
                        .list("dynamicPestsContestPriority", emptyList<String>(), String::class.java)
        // -- AUTO LOADOUT ----------------------------------------------------------

        @JvmField val AUTO_LOADOUT_ENABLED: BooleanEntry = Config.bool("autoLoadoutEnabled", true)
        @JvmField val LOADOUT_SLOT_FARMING: IntEntry = Config.integer("loadoutSlotFarming", 1).range(1, 12)
        @JvmField val LOADOUT_SLOT_PEST: IntEntry = Config.integer("loadoutSlotPest", 2).range(1, 12)
        @JvmField val LOADOUT_SLOT_PEST_KILL: IntEntry = Config.integer("loadoutSlotPestKill", 1).range(1, 12)
        @JvmField val LOADOUT_SLOT_VISITOR: IntEntry = Config.integer("loadoutSlotVisitor", 3).range(1, 12)
        @JvmField val LOADOUT_PEST_SWAP_TIME_SECONDS: IntEntry = Config.integer("loadoutPestSwapTimeSeconds", 170)
                        .range(0, 180)
        @JvmField val ROD_SWAP_DELAY_MIN: IntEntry = Config.integer("rodSwapDelayMin", 100).range(0, 1000)
        @JvmField val ROD_SWAP_DELAY_MAX: IntEntry = Config.integer("rodSwapDelayMax", 500).range(0, 1000)
        // -- AOTV ------------------------------------------------------------------

        @JvmField val AOTV_TO_ROOF: BooleanEntry = Config.bool("aotvToRoof", false)
        @JvmField val AOTV_ROOF_PITCH: IntEntry = Config.integer("aotvRoofPitch", 88).range(0, 90)
        @JvmField val AOTV_ROOF_PITCH_HUMANIZATION: IntEntry = Config.integer("aotvRoofPitchHumanization", 5)
                        .range(0, 15)
        @JvmField val AOTV_ROOF_PLOTS: ListEntry<String> = Config.list("aotvRoofPlots", emptyList<String>(), String::class.java)
        @JvmField val UNFLY_MODE: StringEntry = Config.string("unflyMode", "DOUBLE_TAP_SPACE")
        @JvmField val BREAK_BLOCKS_BEFORE_AOTV: BooleanEntry = Config.bool("breakBlocksBeforeAotv", false)
        // -- MINING ----------------------------------------------------------------

        // compat stub; metal detector activation is runtime-only
        @Deprecated("")
        
        @JvmField val ENABLE_METAL_DETECTOR: BooleanEntry = Config.bool("enableMetalDetector", false).nonPersistent()
        @JvmField val METAL_DETECTOR_BACKPACK_BLACKLIST: ListEntry<String> = Config.list(
                        "metalDetectorBackpackBlacklist",
                        emptyList<String>(),
                        String::class.java)
        // -- INVENTORY MANAGERS ----------------------------------------------------

        @JvmField val AUTO_STASH_MANAGER: BooleanEntry = Config.bool("autoStashManager", false)
        @JvmField val PICK_UP_STASH_DELAY_MIN: IntEntry = Config.integer("pickUpStashDelayMin", 3000)
                        .range(0, 5000)
        @JvmField val PICK_UP_STASH_DELAY_MAX: IntEntry = Config.integer("pickUpStashDelayMax", 5000)
                        .range(0, 5000)
        @JvmField val AUTO_BOOK_COMBINE: BooleanEntry = Config.bool("autoBookCombine", false)
        @JvmField val ALWAYS_ACTIVE_COMBINE: BooleanEntry = Config.bool("alwaysActiveCombine", false)
        @JvmField val AUTO_GEORGE_SELL: BooleanEntry = Config.bool("autoGeorgeSell", false)
        @JvmField val FARM_WHILE_CALLING_GEORGE: BooleanEntry = Config.bool("farmWhileCallingGeorge", false)
        @JvmField val GEORGE_SELL_THRESHOLD: IntEntry = Config.integer("georgeSellThreshold", 3).range(1, 36)
        @JvmField val GEORGE_POST_SELL_DELAY_MIN_MS: IntEntry = Config.integer("georgePostSellDelayMinMs", 2000)
                        .range(0, 5000)
        @JvmField val GEORGE_POST_SELL_DELAY_MAX_MS: IntEntry = Config.integer("georgePostSellDelayMaxMs", 5000)
                        .range(0, 5000)
        @JvmField val AUTOSELL_PASSIVE: BooleanEntry = Config.bool("autoSellPassive", false)
        @JvmField val AUTO_SELL: BooleanEntry = Config.bool("autoSell", false)
        @JvmField val AUTO_SELL_NPC: BooleanEntry = Config.bool("autoSellNpc", true)
        @JvmField val AUTO_SELL_BAZAAR: BooleanEntry = Config.bool("autoSellBazaar", true)
        @JvmField val AUTO_SELL_THRESHOLD: IntEntry = Config.integer("autoSellThreshold", 75).range(1, 100)
        @JvmField val AUTO_SELL_TIME: IntEntry = Config.integer("autoSellTime", 10).range(1, 60)
        @JvmField val AUTO_SELL_BEFORE_VISITORS: BooleanEntry = Config.bool("autoSellBeforeVisitors", false)
        @JvmField val AUTO_SELL_BEFORE_PEST_TRAPS: BooleanEntry = Config.bool("autoSellBeforePestTraps", false)
        @JvmField val AUTO_DROP_JUNK: BooleanEntry = Config.bool("autoDropJunk", false)
        @JvmField val AUTO_SELL_ITEMS: ListEntry<String> = Config.list("autoSellItems",
                        DEFAULT_AUTOSELL_ITEM_NAMES,
                        String::class.java)
        @JvmField val BOOSTER_COOKIE_ITEMS: ListEntry<String> = Config.list("boosterCookieItems",
                        DEFAULT_AUTOSELL_ITEM_NAMES,
                        String::class.java)
        @JvmField val CUSTOM_ENCHANTMENT_LEVELS: ListEntry<String> = Config.list("customEnchantmentLevels", emptyList<String>(), String::class.java)
        @JvmField val JUNK_ITEMS: ListEntry<String> = Config.list("junkItems",
                        listOf("Fruit Bowl", "Farming Exp Boost", "Sunder VI"), String::class.java)
        @JvmField val DROP_JUNK_PLOT_TP: StringEntry = Config.string("dropJunkPlotTp", "0")
        @JvmField val JUNK_THRESHOLD: IntEntry = Config.integer("junkThreshold", 3).range(1, 36)
        @JvmField val JUNK_ITEM_DROP_DELAY_MIN: IntEntry = Config.integer("junkItemDropDelayMin", 300)
                        .range(0, 1000)
        @JvmField val JUNK_ITEM_DROP_DELAY_MAX: IntEntry = Config.integer("junkItemDropDelayMax", 500)
                        .range(0, 1000)
        // -- BOOK COMBINE ----------------------------------------------------------

        @JvmField val BOOK_COMBINE_DELAY: IntEntry = Config.integer("bookCombineDelay", 300).range(0, 5000)
        @JvmField val BOOK_THRESHOLD: IntEntry = Config.integer("bookThreshold", 7).range(2, 36)
        // -- TIMING / DELAYS -------------------------------------------------------

        @JvmField val GUI_FIRST_CLICK_DELAY_MIN: IntEntry = Config.integer("guiFirstClickDelayMin", 150)
                        .range(0, 1000)
        @JvmField val GUI_FIRST_CLICK_DELAY_MAX: IntEntry = Config.integer("guiFirstClickDelayMax", 250)
                        .range(0, 1000)
        @JvmField val GUI_CLICK_DELAY_MIN: IntEntry = Config.integer("guiClickDelayMin", 100).range(0, 1000)
        @JvmField val GUI_CLICK_DELAY_MAX: IntEntry = Config.integer("guiClickDelayMax", 250).range(0, 1000)
        @JvmField val BAZAAR_DELAY_MIN: IntEntry = Config.integer("bazaarDelayMin", 250).range(0, 1000)
        @JvmField val BAZAAR_DELAY_MAX: IntEntry = Config.integer("bazaarDelayMax", 500).range(0, 1000)
        @JvmField val HUMANIZATION_PRESET: StringEntry = Config.string("humanizationPreset", "NORMAL")
        @JvmField val ROTATION_TIME: IntEntry = Config.integer("rotationTime", 100).range(0, 5000)
        @JvmField val ROTATION_DYNAMIC_DURATION_MS_PER_DEGREE: FloatEntry = Config
                        .floatVal("rotationDynamicDurationMsPerDegree", 2.0f)
                        .range(0.0f, 20.0f)
        @JvmField val ROTATION_EASE_IN: BooleanEntry = Config.bool("rotationEaseIn", true)
        @JvmField val ROTATION_EASE_IN_FACTOR: FloatEntry = Config.floatVal("rotationEaseInFactor", 2.0f).range(1.0f, 5.0f)
        @JvmField val ROTATION_EASE_OUT: BooleanEntry = Config.bool("rotationEaseOut", true)
        @JvmField val ROTATION_EASE_OUT_FACTOR: FloatEntry = Config.floatVal("rotationEaseOutFactor", 2.0f).range(1.0f, 5.0f)
        @JvmField val ROTATION_TRACKING_NOISE_MIN: FloatEntry = Config.floatVal("rotationTrackingNoiseMin", 2.0f)
                        .range(0.0f, 10.0f)
        @JvmField val ROTATION_TRACKING_NOISE_MAX: FloatEntry = Config.floatVal("rotationTrackingNoiseMax", 6.0f)
                        .range(0.0f, 10.0f)
        // -- DYNAMIC REST ----------------------------------------------------------

        @JvmField val DYNAMIC_REST_ENABLED: BooleanEntry = Config.bool("dynamicRestEnabled", false)
        @JvmField val REST_SCRIPTING_TIME: IntEntry = Config.integer("restScriptingTime", 30).range(1, 1440)
        @JvmField val REST_SCRIPTING_TIME_OFFSET: IntEntry = Config.integer("restScriptingTimeOffset", 3).range(0,
                        300)
        @JvmField val REST_BREAK_TIME: IntEntry = Config.integer("restBreakTime", 20).range(1, 1440)
        @JvmField val REST_BREAK_TIME_OFFSET: IntEntry = Config.integer("restBreakTimeOffset", 3).range(0, 300)
        @JvmField val PERSIST_SESSION_TIMER: BooleanEntry = Config.bool("persistSessionTimer", true)
        @JvmField val DAILY_FARM_THRESHOLD_HOURS: DoubleEntry = Config.doubleVal("dailyFarmThresholdHours", 0.0)
        @JvmField val CLOSE_GAME_ON_DAILY_THRESHOLD: BooleanEntry = Config.bool("closeGameOnDailyThreshold",
                        false)
        // -- REWARP --------------------------------------------------------
        
        @JvmField val ENABLE_REWARP: BooleanEntry = Config.bool("enableRewarp", false)
        @JvmField val ENABLE_PLOT_TP_REWARP: BooleanEntry = Config.bool("enablePlotTpRewarp", false)
        @JvmField val REWARP_AOTV_ALIGN: BooleanEntry = Config.bool("rewarpAotvAlign", false)
        @JvmField val PLOT_TP_NUMBER: StringEntry = Config.string("plotTpNumber", "0")
        @JvmField val HOLD_W_UNTIL_WALL: BooleanEntry = Config.bool("holdWUntilWall", false)
        @JvmField val REWARP_DELAY_MIN: IntEntry = Config.integer("rewarpDelayMin", 0).range(0, 1000)
        @JvmField val REWARP_DELAY_MAX: IntEntry = Config.integer("rewarpDelayMax", 500).range(0, 1000)
        @JvmField val REWARP_END_X: DoubleEntry = Config.doubleVal("rewarpEndX", 0.0)
        @JvmField val REWARP_END_Y: DoubleEntry = Config.doubleVal("rewarpEndY", 0.0)
        @JvmField val REWARP_END_Z: DoubleEntry = Config.doubleVal("rewarpEndZ", 0.0)
        @JvmField val REWARP_END_POS_SET: BooleanEntry = Config.bool("rewarpEndPosSet", true)
        @JvmField val REWARP_HIGHLIGHT_END: BooleanEntry = Config.bool("rewarpHighlightEnd", true)
        @JvmField val REWARP_START_X: DoubleEntry = Config.doubleVal("rewarpStartX", 0.0)
        @JvmField val REWARP_START_Y: DoubleEntry = Config.doubleVal("rewarpStartY", 0.0)
        @JvmField val REWARP_START_Z: DoubleEntry = Config.doubleVal("rewarpStartZ", 0.0)
        @JvmField val REWARP_START_POS_SET: BooleanEntry = Config.bool("rewarpStartPosSet", true)
        @JvmField val REWARP_HIGHLIGHT_START: BooleanEntry = Config.bool("rewarpHighlightStart", true)
        @JvmField val REWARP_POINT_PAIRS: ListEntry<String> = Config.list("rewarpPointPairs",
                        listOf(RewarpPointPair.defaultConfig(0)), String::class.java)
        // -- DISCORD ---------------------------------------------------------------

        // persisted locally, blanked on profile export
        @JvmField val DISCORD_WEBHOOK_URL: StringEntry = Config.string("discordWebhookUrl", "")
        @JvmField val DISCORD_STATUS_UPDATE_TIME: IntEntry = Config.integer("discordStatusUpdateTime", 5).range(1,
                        60)
        @JvmField val SEND_DISCORD_STATUS: BooleanEntry = Config.bool("sendDiscordStatus", false)
        // -- IRC -------------------------------------------------------------------

        @JvmField val IRC_ENABLED: BooleanEntry = Config.bool("ircEnabled", true)
        // -- REMOTE CONTROL --------------------------------------------------------

        @JvmField val REMOTE_CONTROL_ENABLED: BooleanEntry = Config.bool("remoteControlEnabled", false)
        // persisted locally, blanked on profile export
        @JvmField val REMOTE_CONTROL_BOT_TOKEN: StringEntry = Config.string("remoteControlBotToken", "")
        @JvmField val REMOTE_CONTROL_GUILD_ID: StringEntry = Config.string("remoteControlGuildId", "")
        @JvmField val REMOTE_CONTROL_CHANNEL_ID: StringEntry = Config.string("remoteControlChannelId", "")
        @JvmField val REMOTE_CONTROL_COMMAND_PREFIX: StringEntry = Config.string("remoteControlCommandPrefix", "!aether")
        // json map of discord channel id to the user id pinged on failsafe
        @JvmField val REMOTE_CONTROL_PING_TARGETS: StringEntry = Config.string("remoteControlPingTargets", "{}")
        // -- PROFIT / HUD ----------------------------------------------------------

        @JvmField val PROFIT_HUD_ENABLED: BooleanEntry = Config.bool("profitHudEnabled", true)
        @JvmField val COMPACT_PROFIT_CALCULATOR: BooleanEntry = Config.bool("compactProfitCalculator", true)
        @JvmField val PROFIT_PRICE_SOURCE: StringEntry = Config.string("profitPriceSource", "BAZAAR")
        @JvmField val SHARD_PRICE_SOURCE: StringEntry = Config.string("shardPriceSource", "INSTA_SELL")
        @JvmField val FARMING_XP_HUD: BooleanEntry = Config.bool("farmingXpHud", true)
        @JvmField val FARMING_HUD_XP_RATE: BooleanEntry = Config.bool("farmingHudXpRate", true)
        @JvmField val FARMING_HUD_ETA_NEXT: BooleanEntry = Config.bool("farmingHudEtaNext", true)
        @JvmField val FARMING_HUD_ETA_MAX: BooleanEntry = Config.bool("farmingHudEtaMax", true)
        @JvmField val HIDE_FILTERED_CHAT: BooleanEntry = Config.bool("hideFilteredChat", true)
        @JvmField val GUI_ONLY_IN_GARDEN: BooleanEntry = Config.bool("guiOnlyInGarden", false)
        @JvmField val HUD_ONLY_WHILE_MACRO_RUNNING: BooleanEntry = Config.bool("hudOnlyWhileMacroRunning", false)
        // -- PET TRACKER -----------------------------------------------------------

        @JvmField val PET_TRACKER_LIST: ListEntry<String> = Config.list("petTrackerList",
                        listOf("Rose Dragon:200:650000000:1250000000:LEGENDARY"), String::class.java)
        // -- HUD POSITIONS ---------------------------------------------------------
        @JvmField val CUSTOM_UI_ENABLED: BooleanEntry = Config.bool("customUiEnabled", false)
        @JvmField val STREAMER_MODE: BooleanEntry = Config.bool("streamerMode", false)
        @JvmField val HUD_THEME: IntEntry = Config.integer("hudTheme", 2).range(0, 3)
        @JvmField val HUD_X: IntEntry = Config.integer("hudX", 410)
        @JvmField val HUD_Y: IntEntry = Config.integer("hudY", 360)
        @JvmField val HUD_SCALE: FloatEntry = Config.floatVal("hudScale", 1.0f).range(0.5f, 3.0f)
        @JvmField val SHOW_HUD: BooleanEntry = Config.bool("showHud", false)
        @JvmField val SHOW_HUD_OUTSIDE_GARDEN: BooleanEntry = Config.bool("showHudOutsideGarden", false)
        @JvmField val SESSION_PROFIT_HUD_X: IntEntry = Config.integer("sessionProfitHudX", 10)
        @JvmField val SESSION_PROFIT_HUD_Y: IntEntry = Config.integer("sessionProfitHudY", 130)
        @JvmField val SESSION_PROFIT_HUD_SCALE: FloatEntry = Config.floatVal("sessionProfitHudScale", 0.5f)
                        .range(0.5f, 3.0f)
        @JvmField val SHOW_SESSION_PROFIT_HUD: BooleanEntry = Config.bool("showSessionProfitHud", true)
        @JvmField val SESSION_PROFIT_GRAPH: BooleanEntry = Config.bool("sessionProfitGraph", false)
        @JvmField val SESSION_PROFIT_GRAPH_MINUTES: IntEntry = Config.integer("sessionProfitGraphMinutes", 5).range(1, 15)
        @JvmField val DAILY_HUD_X: IntEntry = Config.integer("dailyHudX", 10)
        @JvmField val DAILY_HUD_Y: IntEntry = Config.integer("dailyHudY", 290)
        @JvmField val DAILY_HUD_SCALE: FloatEntry = Config.floatVal("dailyHudScale", 1.0f).range(0.5f, 3.0f)
        @JvmField val SHOW_DAILY_HUD: BooleanEntry = Config.bool("showDailyHud", true)
        @JvmField val LIFETIME_HUD_X: IntEntry = Config.integer("lifetimeHudX", 280)
        @JvmField val LIFETIME_HUD_Y: IntEntry = Config.integer("lifetimeHudY", 50)
        @JvmField val LIFETIME_HUD_SCALE: FloatEntry = Config.floatVal("lifetimeHudScale", 0.6175f).range(0.5f, 3.0f)
        @JvmField val SHOW_LIFETIME_HUD: BooleanEntry = Config.bool("showLifetimeHud", true)
        @JvmField val INTERMEDIARIES_HUD_X: IntEntry = Config.integer("intermediariesHudX", 10)
        @JvmField val INTERMEDIARIES_HUD_Y: IntEntry = Config.integer("intermediariesHudY", 280)
        @JvmField val INTERMEDIARIES_HUD_SCALE: FloatEntry = Config.floatVal("intermediariesHudScale", 0.5f)
                        .range(0.5f, 3.0f)
        @JvmField val SHOW_INTERMEDIARIES_HUD: BooleanEntry = Config.bool("showIntermediariesHud", false)
        @JvmField val MID_FARMING_HUD_X: IntEntry = Config.integer("midFarmingHudX", 410)
        @JvmField val MID_FARMING_HUD_Y: IntEntry = Config.integer("midFarmingHudY", 220)
        @JvmField val MID_FARMING_HUD_SCALE: FloatEntry = Config.floatVal("midFarmingHudScale", 0.5f)
                        .range(0.5f, 3.0f)
        @JvmField val SHOW_MID_FARMING_HUD: BooleanEntry = Config.bool("showMidFarmingHud", false)
        @JvmField val FAILSAFES_HUD_X: IntEntry = Config.integer("failsafesHudX", 1510)
        @JvmField val FAILSAFES_HUD_Y: IntEntry = Config.integer("failsafesHudY", 320)
        @JvmField val FAILSAFES_HUD_SCALE: FloatEntry = Config.floatVal("failsafesHudScale", 1.0f)
                        .range(0.5f, 3.0f)
        @JvmField val SHOW_FAILSAFES_HUD: BooleanEntry = Config.bool("showFailsafesHud", false)
        @JvmField val WATERMARK_HUD_X: IntEntry = Config.integer("watermarkHudX", 10)
        @JvmField val WATERMARK_HUD_Y: IntEntry = Config.integer("watermarkHudY", 10)
        @JvmField val WATERMARK_HUD_SCALE: FloatEntry = Config.floatVal("watermarkHudScale", 1.0f).range(0.5f, 3.0f)
        @JvmField val SHOW_WATERMARK_HUD: BooleanEntry = Config.bool("showWatermarkHud", true)
        @JvmField val WATERMARK_CUSTOM_USERNAME: StringEntry = Config.string("watermarkCustomUsername", "")
        @JvmField val WATERMARK_SHOW_USERNAME: BooleanEntry = Config.bool("watermarkShowUsername", true)
        @JvmField val WATERMARK_SHOW_FPS: BooleanEntry = Config.bool("watermarkShowFps",      true)
        @JvmField val WATERMARK_SHOW_PING: BooleanEntry = Config.bool("watermarkShowPing",     true)
        @JvmField val WATERMARK_SHOW_TIME: BooleanEntry = Config.bool("watermarkShowTime",     true)
        @JvmField val WATERMARK_STYLE: IntEntry = Config.integer("watermarkStyle",           0)
        @JvmField val WATERMARK_SHOW_LOGO: BooleanEntry = Config.bool("watermarkShowLogo",      true)
        @JvmField val WATERMARK_SHOW_NAME: BooleanEntry = Config.bool("watermarkShowName",      true)
        @JvmField val WATERMARK_GRADIENT: BooleanEntry = Config.bool("watermarkGradient",      false)
        @JvmField val WATERMARK_GRADIENT_LEFT: IntEntry = Config.integer("watermarkGradientLeft", -2931921)
        @JvmField val WATERMARK_GRADIENT_COLOR: IntEntry = Config.integer("watermarkGradientColor", -8694929)
        @JvmField val WATERMARK_SHOW_MACRO_STATUS: BooleanEntry = Config.bool("watermarkShowMacroStatus", true)
        @JvmField val MAIN_STATUS_HUD_X: IntEntry = Config.integer("mainStatusHudX", 740)
        @JvmField val MAIN_STATUS_HUD_Y: IntEntry = Config.integer("mainStatusHudY", 10)
        @JvmField val MAIN_STATUS_HUD_SCALE: FloatEntry = Config.floatVal("mainStatusHudScale", 1.0f).range(0.5f, 3.0f)
        @JvmField val MAIN_STATUS_GRADIENT: BooleanEntry = Config.bool("mainStatusGradient",      false)
        @JvmField val MAIN_STATUS_GRADIENT_LEFT: IntEntry = Config.integer("mainStatusGradientLeft", -2931921)
        @JvmField val MAIN_STATUS_GRADIENT_RIGHT: IntEntry = Config.integer("mainStatusGradientRight", -8694929)
        @JvmField val CUSTOM_SCOREBOARD: BooleanEntry = Config.bool("customScoreboard", false)
        @JvmField val SCOREBOARD_TITLE_TEXT: StringEntry = Config.string("scoreboardTitleText", "")
        @JvmField val SCOREBOARD_SERVER_TEXT: StringEntry = Config.string("scoreboardServerText", "")
        @JvmField val SCOREBOARD_HUD_X: IntEntry = Config.integer("scoreboardHudX", -1).range(-1, Int.MAX_VALUE)
        @JvmField val SCOREBOARD_HUD_Y: IntEntry = Config.integer("scoreboardHudY", -1).range(-1, Int.MAX_VALUE)
        @JvmField val SCOREBOARD_HUD_SCALE: FloatEntry = Config.floatVal("scoreboardHudScale", 1.0f).range(0.5f, 2.5f)
        @JvmField val INVENTORY_HUD_X: IntEntry = Config.integer("inventoryHudX", 10)
        @JvmField val INVENTORY_HUD_Y: IntEntry = Config.integer("inventoryHudY", 40)
        @JvmField val INVENTORY_HUD_SCALE: FloatEntry = Config.floatVal("inventoryHudScale", 1.0f).range(1.0f, 1.0f)
        @JvmField val SHOW_INVENTORY_HUD: BooleanEntry = Config.bool("showInventoryHud", true)
        @JvmField val INVENTORY_HUD_SHOW_PLAYER_MODEL: BooleanEntry = Config.bool("inventoryHudShowPlayerModel", true)
        @JvmField val INVENTORY_HUD_SHOW_ARMOR: BooleanEntry = Config.bool("inventoryHudShowArmor", true)
        // -- LIFETIME ACCUMULATED --------------------------------------------------

        // double, not long, so the millisecond accumulator cannot overflow
        @JvmField val LIFETIME_ACCUMULATED: DoubleEntry = Config.doubleVal("lifetimeAccumulated", 0.0)
        @JvmField val DAILY_FARM_ACCUMULATED: DoubleEntry = Config.doubleVal("dailyFarmAccumulated", 0.0)
        @JvmField val DAILY_FARM_DATE: StringEntry = Config.string("dailyFarmDate", "")
        // -- PERFORMANCE MODE ------------------------------------------------------

        @JvmField val PERFORMANCE_MODE: BooleanEntry = Config.bool("performanceMode", false)
        @JvmField val PERFORMANCE_LIMIT_FPS: BooleanEntry = Config.bool("performanceLimitFps", true)
        @JvmField val PERFORMANCE_MODE_MAX_FPS: IntEntry = Config.integer("performanceModeMaxFps", 20).range(20,
                        60)
        @JvmField val PERFORMANCE_LIMIT_CHUNK_DISTANCE: BooleanEntry = Config.bool("performanceLimitChunkDistance", true)
        @JvmField val PERFORMANCE_CHUNK_DISTANCE: IntEntry = Config.integer("performanceChunkDistance", 2).range(2, 8)
        @JvmField val PERFORMANCE_DISABLE_PARTICLES: BooleanEntry = Config.bool("performanceDisableParticles", true)
        @JvmField val MUTE_GAME: BooleanEntry = Config.bool("muteGame", false)
        // Master volume applied while Mute Game is active, as a 0.0-1.0 fraction (0.0 = fully muted).
        @JvmField val MUTE_GAME_VOLUME: FloatEntry = Config.floatVal("muteGameVolume", 0.0f).range(0.0f, 1.0f)
        @JvmField val KEEP_FOCUS: BooleanEntry = Config.bool("keepFocus", true)
        @JvmField val FLY_BRAKING_LOOKAHEAD_TICKS: FloatEntry =
                        Config.floatVal("flyBrakingLookaheadTicks", 2.0f).range(0.0f, 6.0f)
        @JvmField val PATHFINDER_MAX_JUMP_HEIGHT: IntEntry = Config.integer("pathfinderMaxJumpHeight", 1)
                        .range(1, 6)
        @JvmField val PATHFINDER_RAYCAST_JUMP: BooleanEntry = Config.bool("pathfinderRaycastJump", true)
        @JvmField val PATHFINDER_JUMP_LOOKAHEAD_TICKS: FloatEntry =
                        Config.floatVal("pathfinderJumpLookaheadTicks", 2.0f).range(0.0f, 5.0f)
        @JvmField val PATHFINDER_AIM_LOOKAHEAD: FloatEntry =
                        Config.floatVal("pathfinderAimLookahead", 3.5f).range(1.0f, 8.0f)
        @JvmField val PATHFINDER_TURN_SPEED: FloatEntry =
                        Config.floatVal("pathfinderTurnSpeed", 240.0f).range(60.0f, 720.0f)
        @JvmField val PATHFINDER_SPRINT: BooleanEntry = Config.bool("pathfinderSprint", true)
        @JvmField val PATHFINDER_STUCK_TIMEOUT_MS: IntEntry =
                        Config.integer("pathfinderStuckTimeoutMs", 1800).range(750, 5000)
        // -- AUTO CARNIVAL ---------------------------------------------------------

        @JvmField val AUTO_CARNIVAL_SHOOTOUT: BooleanEntry = Config.bool("autoCarnivalShootout", false)
        @JvmField val AUTO_CARNIVAL_PING: IntEntry = Config.integer("autoCarnivalPing", 400).range(0, 1000)
        // -- FARMING MACRO ---------------------------------------------------------

        @JvmField val FARMING_MACRO_PRESET_NAME: StringEntry = Config.string("farmingMacroPresetName", "")
        @JvmField val FARMING_MACRO_PRESET: StringEntry = Config.string("farmingMacroPreset", "")
        @JvmField val FARM_TYPE: StringEntry = Config.string("farmType", FarmType.S_SHAPE.name)
        @JvmField val MACRO_UNGRAB_MOUSE: BooleanEntry = Config.bool("macroUngrabMouse", true)
        @JvmField val AUTO_RECONNECT: BooleanEntry = Config.bool("autoReconnect", true)
        @JvmField val MACRO_USE_CUSTOM_PITCH: BooleanEntry = Config.bool("macroUseCustomPitch", false)
        // -90 looks straight up, 90 straight down
        @JvmField val MACRO_CUSTOM_PITCH: FloatEntry = Config.floatVal("macroCustomPitch", 30.0f).range(-90f, 90f)
        @JvmField val MACRO_CUSTOM_PITCH_HUMANIZATION: FloatEntry = Config
                        .floatVal("macroCustomPitchHumanization", 0.0f).range(0.0f, 10.0f)
        @JvmField val MACRO_USE_CUSTOM_YAW: BooleanEntry = Config.bool("macroUseCustomYaw", false)
        @JvmField val MACRO_CUSTOM_YAW: FloatEntry = Config.floatVal("macroCustomYaw", 0.0f).range(-180f, 180f)
        @JvmField val MACRO_CUSTOM_YAW_HUMANIZATION: FloatEntry = Config
                        .floatVal("macroCustomYawHumanization", 0.0f).range(0.0f, 10.0f)
        // swaps the mousemat first when the current rotation differs from the stored one
        @JvmField val SQUEAKY_MOUSEMAT: BooleanEntry = Config.bool("squeakyMousemat", false)
        @JvmField val MACRO_HOLD_W_WHILE_FARMING: BooleanEntry = Config.bool("macroHoldWWhileFarming", false)
        // flips the lane pattern from a/s/s+d to d/s/s+a
        @JvmField val MACRO_SDS_MUSHROOM_REVERSE_LANE: BooleanEntry = Config.bool("macroSdsMushroomReverseLane", false)
        @JvmField val MACRO_DISABLE_SETSPAWN: BooleanEntry = Config.bool("macroDisableSetspawn", false)
        // turns on the configured lane boundary instead of waiting for a stall
        @JvmField val MACRO_FAST_LANE_SWITCH: BooleanEntry = Config.bool("macroFastLaneSwitch", false)
        @JvmField val MACRO_FAST_LANE_BOUNDARY_AXIS: StringEntry = Config.string("macroFastLaneBoundaryAxis", "X")
        @JvmField val MACRO_FAST_LANE_LEFT_BOUNDARY: IntEntry = Config.integer("macroFastLaneLeftBoundary", -48)
                        .range(-240, 240)
        @JvmField val MACRO_FAST_LANE_RIGHT_BOUNDARY: IntEntry = Config.integer("macroFastLaneRightBoundary", 48)
                        .range(-240, 240)
        @JvmField val MACRO_FARM_WAYPOINTS: ListEntry<String> = Config.list("macroFarmWaypoints", emptyList<String>(), String::class.java)
        @JvmField val MACRO_CUSTOM_WAYPOINT_SWITCH_RADIUS: FloatEntry = Config
                        .floatVal("macroCustomWaypointSwitchRadius", 0.20f)
                        .range(0.05f, 1.00f)
        @JvmField val BEDROCK_PLOT_MAKER_PLOT: StringEntry = Config.string("bedrockPlotMakerPlot", "1")
        @JvmField val MACRO_LANE_SWITCH_DELAY_MIN: IntEntry = Config.integer("macroLaneSwitchDelayMin", 0)
                        .range(0, 5000)
        @JvmField val MACRO_LANE_SWITCH_DELAY_MAX: IntEntry = Config.integer("macroLaneSwitchDelayMax", 500)
                        .range(0, 5000)
        @JvmField val FAILSAFE_INVENTORY_SLOT_CHANGED: BooleanEntry = Config.bool("failsafeInventorySlotChanged", true)
        @JvmField val FAILSAFE_UNEXPECTED_INVENTORY_GUI: BooleanEntry = Config.bool("failsafeUnexpectedInventoryGui", true)
        @JvmField val FAILSAFE_BPS: BooleanEntry = Config.bool("failsafeBps", true)
        @JvmField val FAILSAFE_BPS_THRESHOLD: IntEntry = Config.integer("failsafeBpsThreshold", 10)
                        .range(5, 15)
        @JvmField val FAILSAFE_BPS_WINDOW_SECONDS: IntEntry = Config.integer("failsafeBpsWindowSeconds", 5)
                        .range(5, 30)
        @JvmField val FAILSAFE_BPS_TRIGGER_DELAY_SECONDS: FloatEntry = Config
                        .floatVal("failsafeBpsTriggerDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f)
        @JvmField val FAILSAFE_GHOST_BLOCK: BooleanEntry = Config.bool("failsafeGhostBlock", true)
        @JvmField val FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS: IntEntry = Config
                        .integer("failsafeGhostBlockWindowSeconds", 5)
                        .range(1, 30)
        @JvmField val FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS: FloatEntry = Config
                        .floatVal("failsafeGhostBlockTriggerDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f)
        @JvmField val FAILSAFE_DIRT_CHECK: BooleanEntry = Config.bool("failsafeDirtCheck", true)
        @JvmField val FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS: FloatEntry = Config
                        .floatVal("failsafeDirtCheckTriggerDelaySeconds", 2.0f)
                        .range(0.0f, 10.0f)
        @JvmField val FAILSAFE_ROTATION: BooleanEntry = Config.bool("failsafeRotation", true)
        @JvmField val FAILSAFE_WORLD_CHANGE: BooleanEntry = Config.bool("failsafeWorldChange", true)
        @JvmField val FAILSAFE_ROTATION_PITCH_THRESHOLD: IntEntry = Config.integer("failsafeRotationPitchThreshold", 10)
                        .range(5, 30)
        @JvmField val FAILSAFE_ROTATION_YAW_THRESHOLD: IntEntry = Config.integer("failsafeRotationYawThreshold", 10)
                        .range(5, 30)
        @JvmField val FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS: FloatEntry = Config
                        .floatVal("failsafeRotationTriggerDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f)
        @JvmField val FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER: BooleanEntry = Config
                        .bool("failsafeRotationTriggerDuringPestCleaner", true)
        @JvmField val FAILSAFE_ROTATION_PEST_CLEANER_DELAY_MS: IntEntry = Config
                        .integer("failsafeRotationPestCleanerDelayMs", 750)
                        .range(0, 5000)
        @JvmField val FAILSAFE_ROTATION_WARP_GRACE_MS: IntEntry = Config.integer("failsafeRotationWarpGraceMs", 2000)
                        .range(1000, 5000)
        @JvmField val FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS: FloatEntry = Config
                        .floatVal("failsafeAdditionalRandomDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f)
        @JvmField val FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION: StringEntry = Config
                        .string("failsafeInventorySlotChangedAction", "IGNORE")
        @JvmField val FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION: StringEntry = Config
                        .string("failsafeUnexpectedInventoryGuiAction", "IGNORE")
        @JvmField val FAILSAFE_BPS_ACTION: StringEntry = Config.string("failsafeBpsAction", "STOP")
        @JvmField val FAILSAFE_GHOST_BLOCK_ACTION: StringEntry = Config.string("failsafeGhostBlockAction",
                        "IGNORE")
        @JvmField val FAILSAFE_DIRT_CHECK_ACTION: StringEntry = Config.string("failsafeDirtCheckAction",
                        "IGNORE")
        @JvmField val FAILSAFE_ROTATION_ACTION: StringEntry = Config.string("failsafeRotationAction", "IGNORE")
        @JvmField val FAILSAFE_PEST_ROTATION_ACTION: StringEntry = Config.string("failsafePestRotationAction",
                        "STOP")
        @JvmField val FAILSAFE_WORLD_CHANGE_ACTION: StringEntry = Config.string("failsafeWorldChangeAction",
                        "IGNORE")
        @JvmField val FAILSAFE_INVENTORY_SLOT_CHANGED_CUSTOM_REPLAY: StringEntry = Config
                        .string("failsafeInventorySlotChangedCustomReplay", "Random")
        @JvmField val FAILSAFE_UNEXPECTED_INVENTORY_GUI_CUSTOM_REPLAY: StringEntry = Config
                        .string("failsafeUnexpectedInventoryGuiCustomReplay", "Random")
        @JvmField val FAILSAFE_BPS_CUSTOM_REPLAY: StringEntry = Config.string("failsafeBpsCustomReplay", "Random")
        @JvmField val FAILSAFE_GHOST_BLOCK_CUSTOM_REPLAY: StringEntry = Config
                        .string("failsafeGhostBlockCustomReplay", "Random")
        @JvmField val FAILSAFE_DIRT_CHECK_CUSTOM_REPLAY: StringEntry = Config
                        .string("failsafeDirtCheckCustomReplay", "Random")
        @JvmField val FAILSAFE_ROTATION_CUSTOM_REPLAY: StringEntry = Config
                        .string("failsafeRotationCustomReplay", "Random")
        @JvmField val FAILSAFE_PEST_ROTATION_CUSTOM_REPLAY: StringEntry = Config
                        .string("failsafePestRotationCustomReplay", "Random")
        @JvmField val FAILSAFE_WORLD_CHANGE_CUSTOM_REPLAY: StringEntry = Config
                        .string("failsafeWorldChangeCustomReplay", "Random")
        @JvmField val FAILSAFE_ACTION: StringEntry = Config.string("failsafeAction", "STOP")
        @JvmField val FAILSAFE_SOUND_ENABLED: BooleanEntry = Config.bool("failsafeSoundEnabled", true)
        @JvmField val FAILSAFE_COLOUR_FLASH_ENABLED: BooleanEntry = Config.bool("failsafeColourFlashEnabled", false)
        @JvmField val FAILSAFE_COLOUR_FLASH_FIRST: IntEntry = Config.integer("failsafeColourFlashFirst", -60896)
        @JvmField val FAILSAFE_COLOUR_FLASH_SECOND: IntEntry = Config.integer("failsafeColourFlashSecond", -14671872)
        @JvmField val FAILSAFE_COLOUR_FLASH_OPACITY: FloatEntry = Config
                        .floatVal("failsafeColourFlashOpacity", 0.35f)
                        .range(0.0f, 1.0f)
        @JvmField val FAILSAFE_COLOUR_FLASH_SWAP_DELAY_SECONDS: FloatEntry = Config
                        .floatVal("failsafeColourFlashSwapDelaySeconds", 0.5f)
                        .range(0.1f, 5.0f)
        @JvmField val FAILSAFE_SOUND_FILE: StringEntry = Config.string("failsafeSoundFile", "fnaf.mp3")
        // Per-action overrides. Blank = fall back to the shared FAILSAFE_SOUND_FILE above.
        @JvmField val FAILSAFE_SOUND_FILE_STOP: StringEntry = Config.string("failsafeSoundFileStop", "")
        @JvmField val FAILSAFE_SOUND_FILE_IGNORE: StringEntry = Config.string("failsafeSoundFileIgnore", "")
        // Playback volume as a 0.0-1.0 fraction (e.g. 0.05 = 5% to stay quietly audible).
        @JvmField val FAILSAFE_SOUND_VOLUME: FloatEntry = Config.floatVal("failsafeSoundVolume", 1.0f).range(0.0f, 1.0f)
        @JvmField val FAILSAFE_DESKTOP_NOTIFICATION_ENABLED: BooleanEntry = Config.bool("failsafeDesktopNotificationEnabled", false)
        @JvmField val FAILSAFE_AUTO_ALT_TAB: BooleanEntry = Config.bool("failsafeAutoAltTab", true)
        @JvmField val FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS: FloatEntry = Config
                        .floatVal("failsafeInventorySlotChangedDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f)
        @JvmField val FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS: FloatEntry = Config
                        .floatVal("failsafeUnexpectedInventoryGuiDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f)
        @JvmField val FAILSAFE_WORLD_CHANGE_RECOVERY_WAIT_SECONDS: FloatEntry = Config
                        .floatVal("failsafeWorldChangeRecoveryWaitSeconds", 5.0f)
                        .range(0.0f, 30.0f)
        // -- BPS -------------------------------------------------------------------
        @JvmField val BPS_AVERAGE_WINDOW: IntEntry = Config.integer("bpsAverageWindow", 30).range(5, 60)
        // -- NICK HIDER ------------------------------------------------------------
        @JvmField val NICK_HIDER_MASTER_ENABLED: BooleanEntry = Config.bool("nickHiderMasterEnabled", true)
        @JvmField val NICK_HIDER_ENABLED: BooleanEntry = Config.bool("nickHiderEnabled", false)
        @JvmField val HIDE_SERVER_ID: BooleanEntry = Config.bool("hideServerId", false)
        @JvmField val CUSTOM_SERVER_ID: StringEntry = Config.string("customServerId", ".gg/aethersb")
        @JvmField val COOP_HIDER_ENABLED: BooleanEntry = Config.bool("coopHiderEnabled", false)
        @JvmField val COOP_NAMES: ListEntry<String> = Config.list("coopNames", 
                        listOf("Coop1", "Coop2", "Coop3"), String::class.java)
        @JvmField val CUSTOM_USERNAME: StringEntry = Config.string("customUsername", "AetherUser")
        @JvmField val SERVER_NICK: StringEntry = Config.string("serverNick", "")
        @JvmField val HIDE_SKIN: BooleanEntry = Config.bool("hideSkin", false)
        @JvmField val SPOOF_VALUES_ENABLED: BooleanEntry = Config.bool("spoofValuesEnabled", true)
        @JvmField val PURSE_OFFSET: DoubleEntry = Config.doubleVal("purseOffset", 0.0)
        @JvmField val BITS_OFFSET: DoubleEntry = Config.doubleVal("bitsOffset", 0.0)
        @JvmField val COPPER_OFFSET: DoubleEntry = Config.doubleVal("copperOffset", 0.0)
        @JvmField val SAWDUST_OFFSET: DoubleEntry = Config.doubleVal("sawdustOffset", 0.0)
        @JvmField val FARMING_EXP_OFFSET: DoubleEntry = Config.doubleVal("farmingExpOffset", 0.0)
        @JvmField val CUSTOM_SB_LEVEL_ENABLED: BooleanEntry = Config.bool("customSbLevelEnabled", false)
        @JvmField val CUSTOM_SB_LEVEL: IntEntry = Config.integer("customSbLevel", 0)
        // -- FUN -----------------------------------------------------------------
        @JvmField val SKYBOX_ENABLED: BooleanEntry = Config.bool("skyboxEnabled", false)
        @JvmField val SKYBOX_PRESET: IntEntry = Config.integer("skyboxPreset", 0).range(0, 4)
        @JvmField val SKYBOX_SPEED: FloatEntry = Config.floatVal("skyboxSpeed", 1f).range(0f, 2f)
        @JvmField val SKYBOX_BRIGHTNESS: FloatEntry = Config.floatVal("skyboxBrightness", 1f).range(0.5f, 1.5f)
        @JvmField val HAT_ENABLED: BooleanEntry = Config.bool("hatEnabled", true)
        @JvmField val HAT_FILLED: BooleanEntry = Config.bool("hatFilled", true)
        @JvmField val HAT_RENDER_FIRST_PERSON: BooleanEntry = Config.bool("hatRenderFirstPerson", false)
        @JvmField val HAT_HEIGHT: FloatEntry = Config.floatVal("hatHeight", 0.5f).range(0.1f, 3.0f)
        @JvmField val HAT_RADIUS: FloatEntry = Config.floatVal("hatRadius", 0.8f).range(0.1f, 3.0f)
        @JvmField val HAT_VERTICES: IntEntry = Config.integer("hatVertices", 20).range(3, 30)
        @JvmField val HAT_Y_OFFSET: FloatEntry = Config.floatVal("hatYOffset", 0.2f).range(0.0f, 3.0f)
        @JvmField val FUNNY_DYNAMIC_REST: BooleanEntry = Config.bool("funnyDynamicRest", true)
        @JvmField val PEST_DEFEAT_EFFECTS: BooleanEntry = Config.bool("pestDefeatEffects", false)
        @JvmField val PEST_DEFEAT_STYLE: IntEntry = Config.integer("pestDefeatStyle", 0).range(0, 2)
        @JvmField val PEST_DEFEAT_SCALE: FloatEntry = Config.floatVal("pestDefeatScale", 1f).range(0.5f, 2f)
        @JvmField val PEST_DEFEAT_PARTICLES: IntEntry = Config.integer("pestDefeatParticles", 18).range(8, 24)
        @JvmField val DRAGON_WINGS_ENABLED: BooleanEntry = Config.bool("dragonWingsEnabled", false)
        @JvmField val DRAGON_WINGS_WIREFRAME: BooleanEntry = Config.bool("dragonWingsWireframe", false)
        @JvmField val DRAGON_WINGS_SCALE: FloatEntry = Config.floatVal("dragonWingsScale", 0.85f).range(0.5f, 1.4f)
        @JvmField val DRAGON_WINGS_SPEED: FloatEntry = Config.floatVal("dragonWingsSpeed", 1f).range(0.4f, 2f)
        @JvmField val DRAGON_WINGS_COLOR: IntEntry = Config.integer("dragonWingsColor", -5211915)
        @JvmField val DRAGON_WINGS_GLOW: BooleanEntry = Config.bool("dragonWingsGlow", true)
        @JvmField val HALO_ENABLED: BooleanEntry = Config.bool("haloEnabled", false)
        @JvmField val HALO_STYLE: IntEntry = Config.integer("haloStyle", 0).range(0, 2)
        @JvmField val HALO_SCALE: FloatEntry = Config.floatVal("haloScale", 1f).range(0.7f, 1.5f)
        @JvmField val HALO_HEIGHT: FloatEntry = Config.floatVal("haloHeight", 0.28f).range(0.12f, 0.7f)
        @JvmField val HALO_TILT: FloatEntry = Config.floatVal("haloTilt", 0f).range(-25f, 25f)
        @JvmField val HALO_SPEED: FloatEntry = Config.floatVal("haloSpeed", 1f).range(0f, 2f)
        @JvmField val HALO_COLOR: IntEntry = Config.integer("haloColor", -539966)
        @JvmField val HALO_GLOW: FloatEntry = Config.floatVal("haloGlow", 1f).range(0f, 1f)
        @JvmField val CAPE_ENABLED: BooleanEntry = Config.bool("capeEnabled", true)
        @JvmField val FREECAM_ENABLED: BooleanEntry = Config.bool("freecamEnabled", true)
        @JvmField val FREECAM_SPEED: FloatEntry = Config.floatVal("freecamSpeed", 0.45f).range(0.1f, 2.5f)
        @JvmField val FREELOOK_ENABLED: BooleanEntry = Config.bool("freelookEnabled", true)
        @JvmField val FREELOOK_MODE: StringEntry = Config.string("freelookMode", "HOLD")
        @JvmField val PIP_WINDOW_WIDTH: IntEntry = Config.integer("pipWindowWidth", 480).range(240, 1920)
        @JvmField val PIP_WINDOW_HEIGHT: IntEntry = Config.integer("pipWindowHeight", 270).range(135, 1080)
        @JvmField val PIP_START_FLOATING: BooleanEntry = Config.bool("pipStartFloating", true)
        @JvmField val PIP_START_DECORATED: BooleanEntry = Config.bool("pipStartDecorated", true)
        @JvmField val PIP_ENABLE_ZOOM: BooleanEntry = Config.bool("pipEnableZoom", true)
        // -- PEST ESP -------------------------------------------------------------
        @JvmField val SHOW_PEST_TARGET_HUD: BooleanEntry = Config.bool("showPestTargetHud", false)
        @JvmField val PEST_TARGET_HUD_X: IntEntry = Config.integer("pestTargetHudX", -1)
        @JvmField val PEST_TARGET_HUD_Y: IntEntry = Config.integer("pestTargetHudY", -1)
        @JvmField val PEST_TARGET_HUD_SCALE: FloatEntry = Config.floatVal("pestTargetHudScale", 1f).range(0.5f, 2.5f)
        @JvmField val PEST_ESP_ENABLED: BooleanEntry = Config.bool("pestEspEnabled", false)
        @JvmField val PEST_ESP_MODE: StringEntry = Config.string("pestEspMode", "BOX")
        @JvmField val PEST_ESP_HIGHLIGHT: BooleanEntry = Config.bool("pestEspHighlight", true)
        @JvmField val PEST_ESP_HIGHLIGHT_COLOR: IntEntry = Config.integer("pestEspHighlightColor", -53104)
        @JvmField val PEST_ESP_TRACER: BooleanEntry = Config.bool("pestEspTracer", true)
        @JvmField val PEST_ESP_TRACER_COLOR: IntEntry = Config.integer("pestEspTracerColor", -53104)
        @JvmField val PEST_ESP_OPTIMIZED_ROUTE: BooleanEntry = Config.bool("pestEspOptimizedRoute", false)
        @JvmField val PEST_ESP_OPTIMIZED_ROUTE_COLOR: IntEntry = Config.integer("pestEspOptimizedRouteColor", -16715776)
        // -- GREENHOUSE ------------------------------------------------------------
        @JvmField val AUTO_GREENHOUSE: BooleanEntry = Config.bool("autoGreenhouse", false)
        @JvmField val AUTO_GREENHOUSE_INTERVAL_MINUTES: IntEntry = Config.integer("autoGreenhouseIntervalMinutes", 120)
                        .range(1, 1440)
        @JvmField val EQUIP_GREENHOUSE_CUSTOM_ITEM: BooleanEntry = Config.bool("equipNetherWartHoe", false)
        @JvmField val GREENHOUSE_CUSTOM_ITEM: StringEntry = Config.string("greenhouseCustomItem", "")
        @JvmField val GREENHOUSE_PLOTS: ListEntry<String> = Config.list("greenhousePlots", emptyList<String>(), String::class.java)
        @JvmField val HARVEST_ASHWREATH: BooleanEntry = Config.bool("harvestAshwreath", false)
        @JvmField val HARVEST_TURTELLINI: BooleanEntry = Config.bool("harvestTurtellini", false)
        @JvmField val HARVEST_GLASSCORN: BooleanEntry = Config.bool("harvestGlasscorn", false)
        // -- COMPOSTER -------------------------------------------------------------
        @JvmField val AUTO_COMPOSTER: BooleanEntry = Config.bool("autoComposter", false)
        @JvmField val AUTO_COMPOSTER_INTERVAL_MINUTES: IntEntry = Config.integer("autoComposterIntervalMinutes", 120)
                        .range(1, 1440)
        @JvmField val AUTO_COMPOSTER_X: IntEntry = Config.integer("autoComposterX", -11)
        @JvmField val AUTO_COMPOSTER_Y: IntEntry = Config.integer("autoComposterY", 72)
        @JvmField val AUTO_COMPOSTER_Z: IntEntry = Config.integer("autoComposterZ", -27)
        @JvmField val AUTO_COMPOSTER_HIGHLIGHT: BooleanEntry = Config.bool("autoComposterHighlight", true)
        @JvmField val AUTO_COMPOSTER_MIN_PURSE: IntEntry = Config.integer("autoComposterMinPurse", 1000000)
                        .range(0, 2000000000)
        @JvmField val AUTO_COMPOSTER_SOURCE_MODE: StringEntry = Config.string("autoComposterSourceMode", "SACKS")
        @JvmField val AUTO_COMPOSTER_CROP_MATERIAL: StringEntry = Config.string("autoComposterCropMaterial", "Box of Seeds")
        @JvmField val AUTO_COMPOSTER_CROP_AMOUNT: IntEntry = Config.integer("autoComposterCropAmount", 1)
                        .range(1, 2000000)
        @JvmField val AUTO_COMPOSTER_FUEL_MATERIAL: StringEntry = Config.string("autoComposterFuelMaterial", "Volta")
        @JvmField val AUTO_COMPOSTER_FUEL_AMOUNT: IntEntry = Config.integer("autoComposterFuelAmount", 1)
                        .range(1, 2000000)
        // -- SUPERCRAFT ------------------------------------------------------------
        @JvmField val AUTO_SUPERCRAFT: BooleanEntry = Config.bool("autoSupercraft", false)
        @JvmField val AUTO_SUPERCRAFT_INTERVAL_MINUTES: IntEntry = Config.integer("autoSupercraftIntervalMinutes", 120)
                        .range(1, 1440)
        @JvmField val AUTO_SUPERCRAFT_ITEMS: ListEntry<String> = Config.list("autoSupercraftItems",
                        DEFAULT_SUPERCRAFT_ITEMS,
                        String::class.java)
    }
}
