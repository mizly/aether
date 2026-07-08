package dev.typicalfarmingmacro.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import dev.typicalfarmingmacro.config.entries.BooleanEntry;
import dev.typicalfarmingmacro.config.entries.DoubleEntry;
import dev.typicalfarmingmacro.config.entries.FloatEntry;
import dev.typicalfarmingmacro.config.entries.IntEntry;
import dev.typicalfarmingmacro.config.entries.ListEntry;
import dev.typicalfarmingmacro.config.entries.StringEntry;
import dev.typicalfarmingmacro.util.TfmLanguageManager;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Central config registry for all settings.
 *
 * <h2>How it works</h2>
 * Every setting is a {@code public static final} typed {@code ConfigEntry<T>}
 * field.
 * The {@link Config} factory method both creates the entry and registers it for
 * JSON serialisation to {@code typicalfarmingmacro_config.json}.
 *
 * <h2>Reading / writing a value</h2>
 * 
 * <pre>
 * // Read
 * boolean enabled = TfmConfig.AUTO_VISITOR.get();
 *
 * // Write (persisted immediately)
 * TfmConfig.AUTO_VISITOR.set(true);
 * </pre>
 *
 * <h2>Adding a new entry</h2>
 * <ol>
 * <li>Choose the right factory:
 * <ul>
 * <li>{@code Config.bool("key", default)} ->
 * {@link dev.typicalfarmingmacro.config.entries.BooleanEntry}</li>
 * <li>{@code Config.integer("key", default).range(min, max)} ->
 * {@link dev.typicalfarmingmacro.config.entries.IntEntry}</li>
 * <li>{@code Config.floatVal("key", default).range(min, max)}->
 * {@link dev.typicalfarmingmacro.config.entries.FloatEntry}</li>
 * <li>{@code Config.doubleVal("key", default)} ->
 * {@link dev.typicalfarmingmacro.config.entries.DoubleEntry}</li>
 * <li>{@code Config.string("key", default)} ->
 * {@link dev.typicalfarmingmacro.config.entries.StringEntry}</li>
 * <li>{@code Config.list("key", default, Type.class)} ->
 * {@link dev.typicalfarmingmacro.config.entries.ListEntry}</li>
 * </ul>
 * </li>
 * <li>Declare it in the relevant section below as:
 * {@code public static final XxxEntry MY_SETTING = Config.xxx("jsonKey", defaultValue);}
 * <br>
 * JSON key must be camelCase to stay compatible with existing config
 * files.</li>
 * <li>Wire it to the menu in {@link dev.typicalfarmingmacro.ui.MainGUIRegistry} by adding a
 * {@link dev.typicalfarmingmacro.ui.settings.Setting} to the appropriate
 * {@link dev.typicalfarmingmacro.ui.settings.SettingGroup}.</li>
 * </ol>
 *
 * <h2>Special rules</h2>
 * <ul>
 * <li>Append {@code .nonPersistent()} for entries that must NOT be written to
 * disk.</li>
 * <li>Enums are stored as {@link dev.typicalfarmingmacro.config.entries.StringEntry} (the
 * enum's
 * {@code name()}). Use {@link ConfigHelpers} to parse them back to the enum
 * type.</li>
 * <li>{@link #LIFETIME_ACCUMULATED} uses
 * {@link dev.typicalfarmingmacro.config.entries.DoubleEntry}
 * to avoid {@code long} overflow; cast with {@code (long)(double)} where
 * needed.</li>
 * </ul>
 */
public final class TfmConfig {
        private static final long DAY_MS = 24L * 60L * 60L * 1000L;
        private static final long CORRUPTED_EPOCH_WINDOW_MS = 30L * DAY_MS;
        private static final java.util.List<String> DEFAULT_AUTOSELL_ITEM_NAMES = Arrays.asList(
                        "Atmospheric Filter", "Squeaky Toy", "Beady Eyes", "Clipped Wings",
                        "Overclocker", "Mantid Claw", "Flowering Bouquet", "Bookworm",
                        "Chirping Stereo", "Firefly", "Capsule", "Vinyl", "Wriggling Larva",
                        "Quickdraw", "Rarefinder");
        private static final java.util.List<String> DEFAULT_SUPERCRAFT_ITEMS = Arrays.asList(
                        "Box of Seeds", "Enchanted Hay Bale");

        private static final File CONFIG_FILE = FabricLoader.getInstance()
                        .getConfigDir().resolve("typicalfarmingmacro_config.json").toFile();

        static {
                Config.setConfigPath(CONFIG_FILE.toPath());
        }

        private TfmConfig() {
        }

        public static void init() {
                HumanizationPresetManager.init();
                FarmingMacroPresetManager.init();
                load();
                TfmLanguageManager.init();
        }

        public static void save() {
                Config.save();
                ConfigProfileManager.syncActiveProfileFromLiveConfig();
        }

        public static void flush() {
                Config.flush();
                ConfigProfileManager.syncActiveProfileFromLiveConfig();
        }

        public static void load() {
                Config.load();
                migrateLegacyLoadoutKeys(CONFIG_FILE);
                migrateLegacyDelayRanges(CONFIG_FILE);
                resetRuntimeOnlyEntries();
                sanitizeLifetimeAccumulated();
                ensureAutoSellDefaults();
                TfmLanguageManager.onConfigLoaded();
        }

        public static void reset() {
                Config.reset();
        }

        public static String toJsonString() {
                return Config.toJsonString();
        }

        public static boolean loadFrom(File file) {
                String currentBootstrapLicenseKey = BOOTSTRAP_LICENSE_KEY.get();
                boolean loaded = Config.loadFrom(file.toPath());
                if (loaded) {
                        migrateLegacyDelayRanges(file);
                        migrateLegacyLoadoutKeys(file);
                        if (BOOTSTRAP_LICENSE_KEY.get().isBlank() && currentBootstrapLicenseKey != null
                                        && !currentBootstrapLicenseKey.isBlank()) {
                                BOOTSTRAP_LICENSE_KEY.set(currentBootstrapLicenseKey);
                        }
                        resetRuntimeOnlyEntries();
                        sanitizeLifetimeAccumulated();
                        ensureAutoSellDefaults();
                        TfmLanguageManager.onConfigLoaded();
                }
                return loaded;
        }

        /** Exposes the config file path for profile managers. */
        public static File getConfigFile() {
                return CONFIG_FILE;
        }

        /**
         * Returns the live config as a shareable JSON string, with sensitive/account-specific
         * fields blanked (license key, webhook, co-op names, usernames). Mirrors the profile
         * export sanitization so exported strings are safe to paste publicly.
         */
        public static String exportSanitizedJson() {
                String json = toJsonString();
                try {
                        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                        obj.addProperty("bootstrapLicenseKey", "");
                        obj.addProperty("discordWebhookUrl", "");
                        obj.add("coopNames", new com.google.gson.JsonArray());
                        obj.addProperty("customUsername", "");
                        obj.addProperty("serverNick", "");
                        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj);
                } catch (Exception e) {
                        return json;
                }
        }

        /**
         * Applies a JSON config string to the live config and persists it. Keeps the local
         * license key when the imported string does not carry one, then runs the same
         * post-load fixups as {@link #loadFrom(File)}. Returns {@code false} on invalid JSON.
         */
        public static boolean importFromJson(String json) {
                String currentBootstrapLicenseKey = BOOTSTRAP_LICENSE_KEY.get();
                boolean loaded = Config.loadFromJson(json);
                if (loaded) {
                        try {
                                migrateLegacyLoadoutKeys(JsonParser.parseString(json).getAsJsonObject());
                        } catch (Exception ignored) {
                        }
                        if (BOOTSTRAP_LICENSE_KEY.get().isBlank() && currentBootstrapLicenseKey != null
                                        && !currentBootstrapLicenseKey.isBlank()) {
                                BOOTSTRAP_LICENSE_KEY.set(currentBootstrapLicenseKey);
                        }
                        resetRuntimeOnlyEntries();
                        sanitizeLifetimeAccumulated();
                        ensureAutoSellDefaults();
                        TfmLanguageManager.onConfigLoaded();
                        save();
                }
                return loaded;
        }

        // -- AUTHENTICATION --------------------------------------------------------

        public static final StringEntry BOOTSTRAP_LICENSE_KEY = Config.string("bootstrapLicenseKey", "");
        public static final BooleanEntry AUTO_LOAD_LATEST = Config.bool("autoLoadLatest", true);
        public static final BooleanEntry CHECK_AUTO_UPDATE_PRE_LAUNCH = Config.bool("checkAutoUpdatePreLaunch", true);
        public static final StringEntry LANGUAGE_CODE = Config.string("languageCode", "en_us");

        // -- PEST ------------------------------------------------------------------

        private static void sanitizeLifetimeAccumulated() {
                double rawValue = LIFETIME_ACCUMULATED.get();
                long savedValue = (long) rawValue;
                long normalized = Math.max(0L, savedValue);
                long now = System.currentTimeMillis();
                long sanitized = normalized;

                if (Math.abs(normalized - now) <= CORRUPTED_EPOCH_WINDOW_MS) {
                        System.err.println(
                                        "[Tfm] Ignoring corrupted lifetime timer value that matched epoch time: "
                                                        + normalized);
                        sanitized = 0L;
                }

                if (sanitized != savedValue || rawValue != (double) sanitized) {
                        LIFETIME_ACCUMULATED.set((double) sanitized);
                        save();
                }
        }

        private static void resetRuntimeOnlyEntries() {
                ENABLE_METAL_DETECTOR.set(false);
        }

        private static void ensureAutoSellDefaults() {
                if (!AUTO_SELL_ITEMS.get().isEmpty()) return;

                java.util.List<String> fallback = BOOSTER_COOKIE_ITEMS.get().isEmpty()
                                ? DEFAULT_AUTOSELL_ITEM_NAMES
                                : BOOSTER_COOKIE_ITEMS.get();
                AUTO_SELL_ITEMS.set(new java.util.ArrayList<>(fallback));
                save();
        }

        private static void migrateLegacyDelayRanges(File sourceFile) {
                if (sourceFile == null || !sourceFile.exists()) {
                        return;
                }

                try (Reader reader = Files.newBufferedReader(sourceFile.toPath())) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        int additionalRandomDelay = readInt(root, "additionalRandomDelay", 0);
                        boolean updated = false;

                        updated |= migrateLegacyDelayRange(root,
                                        "macroLaneSwitchDelay",
                                        "macroLaneSwitchDelayMin",
                                        "macroLaneSwitchDelayMax",
                                        MACRO_LANE_SWITCH_DELAY_MIN,
                                        MACRO_LANE_SWITCH_DELAY_MAX,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "pestChatTriggerDelay",
                                        "pestChatTriggerDelayMin",
                                        "pestChatTriggerDelayMax",
                                        PEST_CHAT_TRIGGER_DELAY_MIN,
                                        PEST_CHAT_TRIGGER_DELAY_MAX,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "pestAotvDelay",
                                        "pestAotvDelayMin",
                                        "pestAotvDelayMax",
                                        PEST_AOTV_DELAY_MIN,
                                        PEST_AOTV_DELAY_MAX,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "rodSwapDelay",
                                        "rodSwapDelayMin",
                                        "rodSwapDelayMax",
                                        ROD_SWAP_DELAY_MIN,
                                        ROD_SWAP_DELAY_MAX,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "guiFirstClickDelay",
                                        "guiFirstClickDelayMin",
                                        "guiFirstClickDelayMax",
                                        GUI_FIRST_CLICK_DELAY_MIN,
                                        GUI_FIRST_CLICK_DELAY_MAX,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "guiClickDelay",
                                        "guiClickDelayMin",
                                        "guiClickDelayMax",
                                        GUI_CLICK_DELAY_MIN,
                                        GUI_CLICK_DELAY_MAX,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "pickUpStashDelay",
                                        "pickUpStashDelayMin",
                                        "pickUpStashDelayMax",
                                        PICK_UP_STASH_DELAY_MIN,
                                        PICK_UP_STASH_DELAY_MAX,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "junkItemDropDelay",
                                        "junkItemDropDelayMin",
                                        "junkItemDropDelayMax",
                                        JUNK_ITEM_DROP_DELAY_MIN,
                                        JUNK_ITEM_DROP_DELAY_MAX,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "georgePostSellDelayMs",
                                        "georgePostSellDelayMinMs",
                                        "georgePostSellDelayMaxMs",
                                        GEORGE_POST_SELL_DELAY_MIN_MS,
                                        GEORGE_POST_SELL_DELAY_MAX_MS,
                                        additionalRandomDelay);
                        updated |= migrateLegacyDelayRange(root,
                                        "bazaarDelay",
                                        "bazaarDelayMin",
                                        "bazaarDelayMax",
                                        BAZAAR_DELAY_MIN,
                                        BAZAAR_DELAY_MAX,
                                        additionalRandomDelay);

                        if (updated) {
                                save();
                        }
                } catch (Exception ignored) {
                }
        }

        private static void migrateLegacyLoadoutKeys(File sourceFile) {
                if (sourceFile == null || !sourceFile.exists()) {
                        return;
                }

                try (Reader reader = Files.newBufferedReader(sourceFile.toPath())) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        if (migrateLegacyLoadoutKeys(root)) {
                                save();
                        }
                } catch (Exception ignored) {
                }
        }

        private static boolean migrateLegacyLoadoutKeys(JsonObject root) {
                boolean updated = false;

                if (!root.has("autoLoadoutPest") && root.has("autoWardrobePest")) {
                        AUTO_LOADOUT_PEST.set(readBoolean(root, "autoWardrobePest", AUTO_LOADOUT_PEST.get()));
                        updated = true;
                }
                if (!root.has("autoLoadoutVisitor") && root.has("autoWardrobeVisitor")) {
                        AUTO_LOADOUT_VISITOR.set(readBoolean(root, "autoWardrobeVisitor", AUTO_LOADOUT_VISITOR.get()));
                        updated = true;
                }
                if (!root.has("loadoutSlotFarming") && root.has("wardrobeSlotFarming")) {
                        LOADOUT_SLOT_FARMING.set(readInt(root, "wardrobeSlotFarming", LOADOUT_SLOT_FARMING.get()));
                        updated = true;
                }
                if (!root.has("loadoutSlotPest") && root.has("wardrobeSlotPest")) {
                        LOADOUT_SLOT_PEST.set(readInt(root, "wardrobeSlotPest", LOADOUT_SLOT_PEST.get()));
                        updated = true;
                }
                if (!root.has("loadoutSlotVisitor") && root.has("wardrobeSlotVisitor")) {
                        LOADOUT_SLOT_VISITOR.set(readInt(root, "wardrobeSlotVisitor", LOADOUT_SLOT_VISITOR.get()));
                        updated = true;
                }

                return updated;
        }

        private static boolean migrateLegacyDelayRange(
                        JsonObject root,
                        String legacyKey,
                        String minKey,
                        String maxKey,
                        IntEntry minEntry,
                        IntEntry maxEntry,
                        int additionalRandomDelay
        ) {
                if (!root.has(legacyKey) || root.has(minKey) || root.has(maxKey)) {
                        return false;
                }

                int legacyValue = readInt(root, legacyKey, minEntry.get());
                int extra = Math.max(0, additionalRandomDelay);
                minEntry.set(legacyValue);
                maxEntry.set(legacyValue + extra);
                return true;
        }

        private static int readInt(JsonObject root, String key, int fallback) {
                if (root == null || !root.has(key) || !root.get(key).isJsonPrimitive()) {
                        return fallback;
                }

                try {
                        return root.get(key).getAsInt();
                } catch (Exception ignored) {
                        return fallback;
                }
        }

        private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
                if (root == null || !root.has(key) || !root.get(key).isJsonPrimitive()) {
                        return fallback;
                }

                try {
                        return root.get(key).getAsBoolean();
                } catch (Exception ignored) {
                        return fallback;
                }
        }

        public static final IntEntry PEST_THRESHOLD = Config.integer("pestThreshold", 2).range(1, 8);
        public static final BooleanEntry TRIGGER_PEST_ON_CHAT = Config.bool("triggerPestOnChat", true);
        public static final BooleanEntry PEST_TRIGGER_ONLY_AFTER_REWARP = Config.bool("pestTriggerOnlyAfterRewarp", false);
        public static final IntEntry PEST_CHAT_TRIGGER_DELAY_MIN = Config.integer("pestChatTriggerDelayMin", 500)
                        .range(0, 5000);
        public static final IntEntry PEST_CHAT_TRIGGER_DELAY_MAX = Config.integer("pestChatTriggerDelayMax", 3000)
                        .range(0, 5000);
        public static final BooleanEntry DELAY_PEST_FOR_CROP_FEVER = Config.bool("delayPestForCropFever", false);
        public static final BooleanEntry PEST_PLOT_TP_FOR_CURRENT_PLOT = Config.bool("pestPlotTpForCurrentPlot", false);
        public static final BooleanEntry ENABLE_PEST_TRAPS = Config.bool("enablePestTraps", false);
        public static final StringEntry PEST_TRAPS_PLOT = Config.string("pestTrapsPlot", "0");
        public static final BooleanEntry AUTO_CLEAR_PEST_TRAPS = Config.bool("autoClearPestTraps", false);
        public static final BooleanEntry AUTO_REFILL_PEST_TRAPS = Config.bool("autoRefillPestTraps", false);
        public static final StringEntry PEST_TRAPS_BAIT_MATERIAL = Config.string("pestTrapsBaitMaterial", "Tasty Cheese");
        public static final IntEntry PEST_TRAPS_BAIT_AMOUNT = Config.integer("pestTrapsBaitAmount", 64).range(1, 64);
        public static final BooleanEntry AUTO_MOSQUITO_FOR_PEST_TRAPS = Config.bool("autoMosquitoForPestTraps", false);
        public static final BooleanEntry AUTO_PET_AFTER_TRAP_OPEN = Config.bool("autoPetAfterTrapOpen", false);
        public static final StringEntry AUTO_PET_AFTER_TRAP_OPEN_PET = Config.string("autoPetAfterTrapOpenPet", "");
        public static final BooleanEntry LEAVE_ONE_PEST_ALIVE = Config.bool("leaveOnePestAlive", false);
        public static final ListEntry<String> LEAVE_ONE_PEST_PLOTS = Config.list("leaveOnePestPlots",
                        Collections.emptyList(), String.class);
        public static final BooleanEntry PEST_DISCO_DESTINATION_MODE = Config.bool("pestDiscoDestinationMode", false);
        public static final StringEntry PEST_DISCO_DESTINATION_PLOT = Config.string("pestDiscoDestinationPlot", "0");
        public static final BooleanEntry PEST_AOTV_BETWEEN = Config.bool("pestAotvBetween", false);
        public static final BooleanEntry PEST_AOTV_CONFIRM_BETWEEN = Config.bool("pestAotvConfirmBetween", false);
        public static final IntEntry PEST_AOTV_DELAY_MIN = Config.integer("pestAotvDelayMin", 150).range(100, 250);
        public static final IntEntry PEST_AOTV_DELAY_MAX = Config.integer("pestAotvDelayMax", 250).range(100, 250);
        public static final FloatEntry PEST_FOV_RANGE = Config.floatVal("pestFovRange", 20.0f).range(0.0f, 90.0f);
        public static final FloatEntry PEST_ABOVE_TARGET_PITCH_MIN = Config.floatVal("pestAboveTargetPitchMin", 25.0f)
                        .range(20.0f, 40.0f);
        public static final FloatEntry PEST_ABOVE_TARGET_PITCH_MAX = Config.floatVal("pestAboveTargetPitchMax", 40.0f)
                        .range(10.0f, 90.0f);

        // -- PEST EXCHANGE ---------------------------------------------------------

        public static final BooleanEntry AUTO_PEST_EXCHANGE = Config.bool("autoPestExchange", false);
        public static final BooleanEntry AUTO_PEST_USE_ABIPHONE = Config.bool("autoPestUseAbiphone", false);
        public static final IntEntry PEST_EXCHANGE_DELAY_MIN = Config.integer("pestExchangeDelayMin", 0)
                        .range(0, 5000);
        public static final IntEntry PEST_EXCHANGE_DELAY_MAX = Config.integer("pestExchangeDelayMax", 5000)
                        .range(0, 5000);
        public static final IntEntry PEST_EXCHANGE_DESK_X = Config.integer("pestExchangeDeskX", -26);
        public static final IntEntry PEST_EXCHANGE_DESK_Y = Config.integer("pestExchangeDeskY", 71);
        public static final IntEntry PEST_EXCHANGE_DESK_Z = Config.integer("pestExchangeDeskZ", -14);
        public static final BooleanEntry PEST_HIGHLIGHT_DESK = Config.bool("pestHighlightDesk", true);
        public static final FloatEntry PEST_EXCHANGE_FOV_RANGE = Config.floatVal("pestExchangeFovRange", 4.0f)
                        .range(0.0f, 15.0f);

        // -- VISITOR ---------------------------------------------------------------

        public static final IntEntry VISITOR_THRESHOLD = Config.integer("visitorThreshold", 5).range(1, 25);
        public static final BooleanEntry AUTO_VISITOR = Config.bool("autoVisitor", false);
        public static final ListEntry<String> VISITOR_ignore = Config.list("visitorignore",
                        Arrays.asList("Spaceman", "Rhino", "Taylor"), String.class);
        public static final ListEntry<String> VISITOR_REJECT = Config.list("visitorReject",
                        Collections.emptyList(), String.class);
        public static final BooleanEntry EQUIP_VISITOR_CUSTOM_ITEM = Config.bool("equipVisitorCustomItem", false);
        public static final StringEntry VISITOR_CUSTOM_ITEM = Config.string("visitorCustomItem", "");
        public static final IntEntry VISITOR_MAX_PURCHASE_LIMIT = Config.integer("visitorMaxPurchaseLimit", 10_000_000)
                        .range(0, 20_000_000);
        public static final BooleanEntry DISABLE_VISITORS_DURING_JACOBS_CONTEST = Config.bool("disableVisitorsDuringJacobsContest", false);
        public static final BooleanEntry DISABLE_COMPACTORS_DURING_VISITORS = Config.bool("disableCompactorsDuringVisitors", false);
        public static final FloatEntry VISITOR_FOV_RANGE = Config.floatVal("visitorFovRange", 12.0f)
                        .range(0.0f, 30.0f);

        // -- AUTO SPRAYONATOR -----------------------------------------------------

        public static final BooleanEntry AUTO_SPRAYONATOR = Config.bool("autoSprayonator", false);
        public static final StringEntry AUTO_SPRAYONATOR_MATERIAL = Config.string("autoSprayonatorMaterial", "Use Selected");
        public static final BooleanEntry AUTO_SPRAYONATOR_AUTO_BUY = Config.bool("autoSprayonatorAutoBuy", true);
        public static final IntEntry AUTO_SPRAYONATOR_AUTO_BUY_AMOUNT = Config
                        .integer("autoSprayonatorAutoBuyAmount", 64).range(1, 640);
        public static final IntEntry AUTO_SPRAYONATOR_DETECT_TIME = Config
                        .integer("autoSprayonatorDetectTime", 10).range(5, 30);

        // -- DYNAMIC PESTS --------------------------------------------------------

        public static final BooleanEntry DYNAMIC_PESTS_ENABLED = Config
                        .bool("dynamicPestsEnabled", false);
        public static final IntEntry DYNAMIC_PESTS_MODE = Config
                        .integer("dynamicPestsMode", 0).range(0, 2);
        public static final IntEntry DYNAMIC_PESTS_FALLBACK_SPRAY = Config
                        .integer("dynamicPestsFallbackSpray", 0).range(0, 5);
        public static final IntEntry DYNAMIC_PESTS_FALLBACK_VINYL = Config
                        .integer("dynamicPestsFallbackVinyl", 0).range(0, 12);
        public static final ListEntry<String> DYNAMIC_PESTS_FEAST_PRIORITY = Config
                        .list("dynamicPestsFeastPriority", java.util.List.of(), String.class);
        public static final ListEntry<String> DYNAMIC_PESTS_CONTEST_PRIORITY = Config
                        .list("dynamicPestsContestPriority", java.util.List.of(), String.class);

        // -- AUTO LOADOUT ----------------------------------------------------------

        public static final BooleanEntry AUTO_LOADOUT_PEST = Config.bool("autoLoadoutPest", false);
        public static final BooleanEntry AUTO_LOADOUT_VISITOR = Config.bool("autoLoadoutVisitor", false);
        public static final IntEntry LOADOUT_SLOT_FARMING = Config.integer("loadoutSlotFarming", 1).range(1, 12);
        public static final IntEntry LOADOUT_SLOT_PEST = Config.integer("loadoutSlotPest", 2).range(1, 12);
        public static final IntEntry LOADOUT_SLOT_VISITOR = Config.integer("loadoutSlotVisitor", 3).range(1, 12);
        public static final IntEntry LOADOUT_PEST_SWAP_TIME_SECONDS = Config.integer("loadoutPestSwapTimeSeconds", 170)
                        .range(0, 180);

        public static final IntEntry ROD_SWAP_DELAY_MIN = Config.integer("rodSwapDelayMin", 100).range(0, 1000);
        public static final IntEntry ROD_SWAP_DELAY_MAX = Config.integer("rodSwapDelayMax", 500).range(0, 1000);

        // -- AOTV ------------------------------------------------------------------

        public static final BooleanEntry AOTV_TO_ROOF = Config.bool("aotvToRoof", false);
        public static final IntEntry AOTV_ROOF_PITCH = Config.integer("aotvRoofPitch", 88).range(20, 90);
        public static final IntEntry AOTV_ROOF_PITCH_HUMANIZATION = Config.integer("aotvRoofPitchHumanization", 5)
                        .range(0, 15);
        public static final ListEntry<String> AOTV_ROOF_PLOTS = Config.list("aotvRoofPlots", Collections.emptyList(),
                        String.class);
        public static final StringEntry UNFLY_MODE = Config.string("unflyMode", "DOUBLE_TAP_SPACE");
        public static final BooleanEntry BREAK_BLOCKS_BEFORE_AOTV = Config.bool("breakBlocksBeforeAotv", false);

        // -- MINING ----------------------------------------------------------------

        /** Deprecated compatibility stub; metal detector activation is runtime-only. */
        @Deprecated
        @SuppressWarnings("unchecked")
        public static final BooleanEntry ENABLE_METAL_DETECTOR = Config.bool("enableMetalDetector", false)
                        .nonPersistent();
        public static final ListEntry<String> METAL_DETECTOR_BACKPACK_BLACKLIST = Config.list(
                        "metalDetectorBackpackBlacklist",
                        Collections.emptyList(),
                        String.class);

        // -- INVENTORY MANAGERS ----------------------------------------------------

        public static final BooleanEntry AUTO_STASH_MANAGER = Config.bool("autoStashManager", false);
        public static final IntEntry PICK_UP_STASH_DELAY_MIN = Config.integer("pickUpStashDelayMin", 3000)
                        .range(0, 5000);
        public static final IntEntry PICK_UP_STASH_DELAY_MAX = Config.integer("pickUpStashDelayMax", 5000)
                        .range(0, 5000);
        public static final BooleanEntry AUTO_BOOK_COMBINE = Config.bool("autoBookCombine", false);
        public static final BooleanEntry ALWAYS_ACTIVE_COMBINE = Config.bool("alwaysActiveCombine", false);
        public static final BooleanEntry AUTO_GEORGE_SELL = Config.bool("autoGeorgeSell", false);
        public static final BooleanEntry FARM_WHILE_CALLING_GEORGE = Config.bool("farmWhileCallingGeorge", false);
        public static final IntEntry GEORGE_SELL_THRESHOLD = Config.integer("georgeSellThreshold", 3).range(1, 36);
        public static final IntEntry GEORGE_POST_SELL_DELAY_MIN_MS = Config.integer("georgePostSellDelayMinMs", 2000)
                        .range(0, 5000);
        public static final IntEntry GEORGE_POST_SELL_DELAY_MAX_MS = Config.integer("georgePostSellDelayMaxMs", 5000)
                        .range(0, 5000);
        public static final BooleanEntry AUTOSELL_PASSIVE = Config.bool("autoSellPassive", false);
        public static final BooleanEntry AUTO_SELL = Config.bool("autoSell", false);
        public static final BooleanEntry AUTO_SELL_NPC = Config.bool("autoSellNpc", true);
        public static final BooleanEntry AUTO_SELL_BAZAAR = Config.bool("autoSellBazaar", true);
        public static final IntEntry AUTO_SELL_THRESHOLD = Config.integer("autoSellThreshold", 75).range(1, 100);
        public static final IntEntry AUTO_SELL_TIME = Config.integer("autoSellTime", 10).range(1, 60);
        public static final BooleanEntry AUTO_SELL_BEFORE_VISITORS = Config.bool("autoSellBeforeVisitors", false);
        public static final BooleanEntry AUTO_SELL_BEFORE_PEST_TRAPS = Config.bool("autoSellBeforePestTraps", false);
        public static final BooleanEntry AUTO_DROP_JUNK = Config.bool("autoDropJunk", false);
        public static final ListEntry<String> AUTO_SELL_ITEMS = Config.list("autoSellItems",
                        DEFAULT_AUTOSELL_ITEM_NAMES,
                        String.class);

        public static final ListEntry<String> BOOSTER_COOKIE_ITEMS = Config.list("boosterCookieItems",
                        DEFAULT_AUTOSELL_ITEM_NAMES,
                        String.class);
        public static final ListEntry<String> CUSTOM_ENCHANTMENT_LEVELS = Config.list("customEnchantmentLevels",
                        Collections.emptyList(), String.class);
        public static final ListEntry<String> JUNK_ITEMS = Config.list("junkItems",
                        Arrays.asList("Fruit Bowl", "Farming Exp Boost", "Sunder VI"), String.class);
        public static final StringEntry DROP_JUNK_PLOT_TP = Config.string("dropJunkPlotTp", "0");
        public static final IntEntry JUNK_THRESHOLD = Config.integer("junkThreshold", 3).range(1, 36);
        public static final IntEntry JUNK_ITEM_DROP_DELAY_MIN = Config.integer("junkItemDropDelayMin", 300)
                        .range(0, 1000);
        public static final IntEntry JUNK_ITEM_DROP_DELAY_MAX = Config.integer("junkItemDropDelayMax", 500)
                        .range(0, 1000);

        // -- BOOK COMBINE ----------------------------------------------------------

        public static final IntEntry BOOK_COMBINE_DELAY = Config.integer("bookCombineDelay", 300).range(0, 5000);
        public static final IntEntry BOOK_THRESHOLD = Config.integer("bookThreshold", 7).range(2, 36);

        // -- TIMING / DELAYS -------------------------------------------------------

        public static final IntEntry GUI_FIRST_CLICK_DELAY_MIN = Config.integer("guiFirstClickDelayMin", 150)
                        .range(0, 1000);
        public static final IntEntry GUI_FIRST_CLICK_DELAY_MAX = Config.integer("guiFirstClickDelayMax", 250)
                        .range(0, 1000);
        public static final IntEntry GUI_CLICK_DELAY_MIN = Config.integer("guiClickDelayMin", 100).range(0, 1000);
        public static final IntEntry GUI_CLICK_DELAY_MAX = Config.integer("guiClickDelayMax", 250).range(0, 1000);
        public static final IntEntry BAZAAR_DELAY_MIN = Config.integer("bazaarDelayMin", 250).range(0, 1000);
        public static final IntEntry BAZAAR_DELAY_MAX = Config.integer("bazaarDelayMax", 500).range(0, 1000);
        public static final StringEntry HUMANIZATION_PRESET = Config.string("humanizationPreset", "NORMAL");
        public static final IntEntry ROTATION_TIME = Config.integer("rotationTime", 100).range(0, 5000);
        public static final FloatEntry ROTATION_DYNAMIC_DURATION_MS_PER_DEGREE = Config
                        .floatVal("rotationDynamicDurationMsPerDegree", 2.0f)
                        .range(0.0f, 20.0f);
        public static final BooleanEntry ROTATION_EASE_IN = Config.bool("rotationEaseIn", true);
        public static final FloatEntry ROTATION_EASE_IN_FACTOR = Config.floatVal("rotationEaseInFactor", 2.0f).range(1.0f, 5.0f);
        public static final BooleanEntry ROTATION_EASE_OUT = Config.bool("rotationEaseOut", true);
        public static final FloatEntry ROTATION_EASE_OUT_FACTOR = Config.floatVal("rotationEaseOutFactor", 2.0f).range(1.0f, 5.0f);
        public static final FloatEntry ROTATION_TRACKING_NOISE_MIN = Config.floatVal("rotationTrackingNoiseMin", 2.0f)
                        .range(0.0f, 10.0f);
        public static final FloatEntry ROTATION_TRACKING_NOISE_MAX = Config.floatVal("rotationTrackingNoiseMax", 6.0f)
                        .range(0.0f, 10.0f);

        // -- DYNAMIC REST ----------------------------------------------------------

        public static final BooleanEntry DYNAMIC_REST_ENABLED = Config.bool("dynamicRestEnabled", false);
        public static final IntEntry REST_SCRIPTING_TIME = Config.integer("restScriptingTime", 30).range(1, 1440);
        public static final IntEntry REST_SCRIPTING_TIME_OFFSET = Config.integer("restScriptingTimeOffset", 3).range(0,
                        300);
        public static final IntEntry REST_BREAK_TIME = Config.integer("restBreakTime", 20).range(1, 1440);
        public static final IntEntry REST_BREAK_TIME_OFFSET = Config.integer("restBreakTimeOffset", 3).range(0, 300);
        public static final BooleanEntry PERSIST_SESSION_TIMER = Config.bool("persistSessionTimer", true);
        public static final DoubleEntry DAILY_FARM_THRESHOLD_HOURS = Config.doubleVal("dailyFarmThresholdHours", 0.0);
        public static final BooleanEntry CLOSE_GAME_ON_DAILY_THRESHOLD = Config.bool("closeGameOnDailyThreshold",
                        false);

        // -- REWARP --------------------------------------------------------
        
        public static final BooleanEntry ENABLE_REWARP = Config.bool("enableRewarp", false);
        public static final BooleanEntry ENABLE_PLOT_TP_REWARP = Config.bool("enablePlotTpRewarp", false);
        public static final BooleanEntry REWARP_AOTV_ALIGN = Config.bool("rewarpAotvAlign", true);
        public static final StringEntry PLOT_TP_NUMBER = Config.string("plotTpNumber", "0");
        public static final BooleanEntry HOLD_W_UNTIL_WALL = Config.bool("holdWUntilWall", false);
        public static final IntEntry REWARP_DELAY_MIN = Config.integer("rewarpDelayMin", 0).range(0, 1000);
        public static final IntEntry REWARP_DELAY_MAX = Config.integer("rewarpDelayMax", 500).range(0, 1000);
        public static final DoubleEntry REWARP_END_X = Config.doubleVal("rewarpEndX", 0.0);
        public static final DoubleEntry REWARP_END_Y = Config.doubleVal("rewarpEndY", 0.0);
        public static final DoubleEntry REWARP_END_Z = Config.doubleVal("rewarpEndZ", 0.0);
        public static final BooleanEntry REWARP_END_POS_SET = Config.bool("rewarpEndPosSet", true);
        public static final BooleanEntry REWARP_HIGHLIGHT_END = Config.bool("rewarpHighlightEnd", true);

        public static final DoubleEntry REWARP_START_X = Config.doubleVal("rewarpStartX", 0.0);
        public static final DoubleEntry REWARP_START_Y = Config.doubleVal("rewarpStartY", 0.0);
        public static final DoubleEntry REWARP_START_Z = Config.doubleVal("rewarpStartZ", 0.0);
        public static final BooleanEntry REWARP_START_POS_SET = Config.bool("rewarpStartPosSet", true);
        public static final BooleanEntry REWARP_HIGHLIGHT_START = Config.bool("rewarpHighlightStart", true);
        public static final ListEntry<String> REWARP_POINT_PAIRS = Config.list("rewarpPointPairs",
                        Arrays.asList(RewarpPointPair.defaultConfig(0)), String.class);

        // -- DISCORD ---------------------------------------------------------------

        /** Persisted locally; sanitized when config profiles are exported. */
        public static final StringEntry DISCORD_WEBHOOK_URL = Config.string("discordWebhookUrl", "");
        public static final IntEntry DISCORD_STATUS_UPDATE_TIME = Config.integer("discordStatusUpdateTime", 5).range(1,
                        60);
        public static final BooleanEntry SEND_DISCORD_STATUS = Config.bool("sendDiscordStatus", false);

        // -- PROFIT / HUD ----------------------------------------------------------

        public static final BooleanEntry PROFIT_HUD_ENABLED = Config.bool("profitHudEnabled", true);
        public static final BooleanEntry COMPACT_PROFIT_CALCULATOR = Config.bool("compactProfitCalculator", true);
        public static final StringEntry PROFIT_PRICE_SOURCE = Config.string("profitPriceSource", "BAZAAR");
        public static final BooleanEntry FARMING_XP_HUD = Config.bool("farmingXpHud", true);
        public static final BooleanEntry FARMING_HUD_XP_RATE = Config.bool("farmingHudXpRate", true);
        public static final BooleanEntry FARMING_HUD_ETA_NEXT = Config.bool("farmingHudEtaNext", true);
        public static final BooleanEntry FARMING_HUD_ETA_MAX = Config.bool("farmingHudEtaMax", true);
        public static final BooleanEntry HIDE_FILTERED_CHAT = Config.bool("hideFilteredChat", true);
        public static final BooleanEntry GUI_ONLY_IN_GARDEN = Config.bool("guiOnlyInGarden", false);
        /** Hides all HUD overlays while the macro is not running. */
        public static final BooleanEntry HUD_ONLY_WHILE_MACRO_RUNNING = Config.bool("hudOnlyWhileMacroRunning", false);

        // -- PET TRACKER -----------------------------------------------------------

        public static final ListEntry<String> PET_TRACKER_LIST = Config.list("petTrackerList",
                        Arrays.asList("Rose Dragon:200:650000000:1250000000:LEGENDARY"), String.class);

        // -- HUD POSITIONS ---------------------------------------------------------
        public static final BooleanEntry CUSTOM_UI_ENABLED = Config.bool("customUiEnabled", false);
        public static final BooleanEntry STREAMER_MODE = Config.bool("streamerMode", false);

        public static final IntEntry HUD_THEME = Config.integer("hudTheme", 2).range(0, 3);
        public static final IntEntry HUD_X = Config.integer("hudX", 410);
        public static final IntEntry HUD_Y = Config.integer("hudY", 360);
        public static final FloatEntry HUD_SCALE = Config.floatVal("hudScale", 1.0f).range(0.5f, 3.0f);
        public static final BooleanEntry SHOW_HUD = Config.bool("showHud", false);
        public static final BooleanEntry SHOW_HUD_OUTSIDE_GARDEN = Config.bool("showHudOutsideGarden", false);

        public static final IntEntry SESSION_PROFIT_HUD_X = Config.integer("sessionProfitHudX", 10);
        public static final IntEntry SESSION_PROFIT_HUD_Y = Config.integer("sessionProfitHudY", 130);
        public static final FloatEntry SESSION_PROFIT_HUD_SCALE = Config.floatVal("sessionProfitHudScale", 0.5f)
                        .range(0.5f, 3.0f);
        public static final BooleanEntry SHOW_SESSION_PROFIT_HUD = Config.bool("showSessionProfitHud", true);

        public static final IntEntry DAILY_HUD_X = Config.integer("dailyHudX", 10);
        public static final IntEntry DAILY_HUD_Y = Config.integer("dailyHudY", 290);
        public static final FloatEntry DAILY_HUD_SCALE = Config.floatVal("dailyHudScale", 1.0f).range(0.5f, 3.0f);
        public static final BooleanEntry SHOW_DAILY_HUD = Config.bool("showDailyHud", true);

        public static final IntEntry LIFETIME_HUD_X = Config.integer("lifetimeHudX", 280);
        public static final IntEntry LIFETIME_HUD_Y = Config.integer("lifetimeHudY", 50);
        public static final FloatEntry LIFETIME_HUD_SCALE = Config.floatVal("lifetimeHudScale", 0.6175f).range(0.5f, 3.0f);
        public static final BooleanEntry SHOW_LIFETIME_HUD = Config.bool("showLifetimeHud", true);

        public static final IntEntry INTERMEDIARIES_HUD_X = Config.integer("intermediariesHudX", 10);
        public static final IntEntry INTERMEDIARIES_HUD_Y = Config.integer("intermediariesHudY", 280);
        public static final FloatEntry INTERMEDIARIES_HUD_SCALE = Config.floatVal("intermediariesHudScale", 0.5f)
                        .range(0.5f, 3.0f);
        public static final BooleanEntry SHOW_INTERMEDIARIES_HUD = Config.bool("showIntermediariesHud", false);

        public static final IntEntry MID_FARMING_HUD_X = Config.integer("midFarmingHudX", 410);
        public static final IntEntry MID_FARMING_HUD_Y = Config.integer("midFarmingHudY", 220);
        public static final FloatEntry MID_FARMING_HUD_SCALE = Config.floatVal("midFarmingHudScale", 0.5f)
                        .range(0.5f, 3.0f);
        public static final BooleanEntry SHOW_MID_FARMING_HUD = Config.bool("showMidFarmingHud", false);
        public static final IntEntry FAILSAFES_HUD_X = Config.integer("failsafesHudX", 1510);
        public static final IntEntry FAILSAFES_HUD_Y = Config.integer("failsafesHudY", 320);
        public static final FloatEntry FAILSAFES_HUD_SCALE = Config.floatVal("failsafesHudScale", 1.0f)
                        .range(0.5f, 3.0f);
        public static final BooleanEntry SHOW_FAILSAFES_HUD = Config.bool("showFailsafesHud", false);

        public static final IntEntry WATERMARK_HUD_X = Config.integer("watermarkHudX", 10);
        public static final IntEntry WATERMARK_HUD_Y = Config.integer("watermarkHudY", 10);
        public static final FloatEntry WATERMARK_HUD_SCALE = Config.floatVal("watermarkHudScale", 1.0f).range(0.5f, 3.0f);
        public static final BooleanEntry SHOW_WATERMARK_HUD = Config.bool("showWatermarkHud", true);
        public static final StringEntry WATERMARK_CUSTOM_USERNAME = Config.string("watermarkCustomUsername", "");
        public static final BooleanEntry WATERMARK_SHOW_USERNAME = Config.bool("watermarkShowUsername", true);
        public static final BooleanEntry WATERMARK_SHOW_FPS      = Config.bool("watermarkShowFps",      true);
        public static final BooleanEntry WATERMARK_SHOW_PING     = Config.bool("watermarkShowPing",     true);
        public static final BooleanEntry WATERMARK_SHOW_TIME     = Config.bool("watermarkShowTime",     true);
        public static final IntEntry     WATERMARK_STYLE            = Config.integer("watermarkStyle",           0);
        public static final BooleanEntry WATERMARK_SHOW_LOGO      = Config.bool("watermarkShowLogo",      true);
        public static final BooleanEntry WATERMARK_SHOW_NAME      = Config.bool("watermarkShowName",      true);
        public static final BooleanEntry WATERMARK_GRADIENT       = Config.bool("watermarkGradient",      false);
        public static final IntEntry     WATERMARK_GRADIENT_LEFT   = Config.integer("watermarkGradientLeft",  0xFFD32F2F);
        public static final IntEntry     WATERMARK_GRADIENT_COLOR  = Config.integer("watermarkGradientColor", 0xFF7B4FFF);
        public static final BooleanEntry WATERMARK_SHOW_MACRO_STATUS = Config.bool("watermarkShowMacroStatus", true);

        public static final IntEntry MAIN_STATUS_HUD_X = Config.integer("mainStatusHudX", 740);
        public static final IntEntry MAIN_STATUS_HUD_Y = Config.integer("mainStatusHudY", 10);
        public static final FloatEntry MAIN_STATUS_HUD_SCALE = Config.floatVal("mainStatusHudScale", 1.0f).range(0.5f, 3.0f);
        public static final BooleanEntry MAIN_STATUS_GRADIENT      = Config.bool("mainStatusGradient",      false);
        public static final IntEntry     MAIN_STATUS_GRADIENT_LEFT  = Config.integer("mainStatusGradientLeft",  0xFFD32F2F);
        public static final IntEntry     MAIN_STATUS_GRADIENT_RIGHT = Config.integer("mainStatusGradientRight", 0xFF7B4FFF);

        public static final IntEntry INVENTORY_HUD_X = Config.integer("inventoryHudX", 10);
        public static final IntEntry INVENTORY_HUD_Y = Config.integer("inventoryHudY", 40);
        public static final FloatEntry INVENTORY_HUD_SCALE = Config.floatVal("inventoryHudScale", 1.0f).range(1.0f, 1.0f);
        public static final BooleanEntry SHOW_INVENTORY_HUD = Config.bool("showInventoryHud", true);
        public static final BooleanEntry INVENTORY_HUD_SHOW_PLAYER_MODEL = Config.bool("inventoryHudShowPlayerModel", true);
        public static final BooleanEntry INVENTORY_HUD_SHOW_ARMOR = Config.bool("inventoryHudShowArmor", true);

        // -- LIFETIME ACCUMULATED --------------------------------------------------

        /**
         * Session time accumulator stored as milliseconds. Uses double for precision.
         */
        public static final DoubleEntry LIFETIME_ACCUMULATED = Config.doubleVal("lifetimeAccumulated", 0.0);
        public static final DoubleEntry DAILY_FARM_ACCUMULATED = Config.doubleVal("dailyFarmAccumulated", 0.0);
        public static final StringEntry DAILY_FARM_DATE = Config.string("dailyFarmDate", "");

        // -- PERFORMANCE MODE ------------------------------------------------------

        public static final BooleanEntry PERFORMANCE_MODE = Config.bool("performanceMode", false);
        public static final BooleanEntry PERFORMANCE_LIMIT_FPS = Config.bool("performanceLimitFps", true);
        public static final IntEntry PERFORMANCE_MODE_MAX_FPS = Config.integer("performanceModeMaxFps", 20).range(20,
                        60);
        public static final BooleanEntry PERFORMANCE_LIMIT_CHUNK_DISTANCE = Config.bool("performanceLimitChunkDistance", true);
        public static final IntEntry PERFORMANCE_CHUNK_DISTANCE = Config.integer("performanceChunkDistance", 2).range(2, 8);
        public static final BooleanEntry PERFORMANCE_DISABLE_PARTICLES = Config.bool("performanceDisableParticles", true);
        public static final BooleanEntry MUTE_GAME = Config.bool("muteGame", false);
        // Master volume applied while Mute Game is active, as a 0.0-1.0 fraction (0.0 = fully muted).
        public static final FloatEntry MUTE_GAME_VOLUME = Config.floatVal("muteGameVolume", 0.0f).range(0.0f, 1.0f);
        public static final BooleanEntry KEEP_FOCUS = Config.bool("keepFocus", true);
        public static final IntEntry PATHFINDER_MAX_JUMP_HEIGHT = Config.integer("pathfinderMaxJumpHeight", 1)
                        .range(1, 6);

        // -- AUTO CARNIVAL ---------------------------------------------------------

        public static final BooleanEntry AUTO_CARNIVAL_SHOOTOUT = Config.bool("autoCarnivalShootout", false);
        public static final IntEntry AUTO_CARNIVAL_PING = Config.integer("autoCarnivalPing", 400).range(0, 1000);

        // -- FARMING MACRO ---------------------------------------------------------

        public static final StringEntry FARMING_MACRO_PRESET_NAME = Config.string("farmingMacroPresetName", "");
        public static final StringEntry FARMING_MACRO_PRESET = Config.string("farmingMacroPreset", "");
        public static final StringEntry FARM_TYPE = Config.string("farmType", FarmType.S_SHAPE.name());
        /** Release the mouse cursor while the farming macro is running. */
        public static final BooleanEntry MACRO_UNGRAB_MOUSE = Config.bool("macroUngrabMouse", true);
        /** Use a fixed custom pitch when enabling the farming macro. */
        public static final BooleanEntry MACRO_USE_CUSTOM_PITCH = Config.bool("macroUseCustomPitch", false);
        /** Custom pitch angle (-90 = look straight up, 90 = look straight down). */
        public static final FloatEntry MACRO_CUSTOM_PITCH = Config.floatVal("macroCustomPitch", 30.0f).range(-90f, 90f);
        public static final FloatEntry MACRO_CUSTOM_PITCH_HUMANIZATION = Config
                        .floatVal("macroCustomPitchHumanization", 0.0f).range(0.0f, 10.0f);
        /** Use a fixed custom yaw when enabling the farming macro. */
        public static final BooleanEntry MACRO_USE_CUSTOM_YAW = Config.bool("macroUseCustomYaw", false);
        /** Custom yaw angle in degrees (-180 to 180). */
        public static final FloatEntry MACRO_CUSTOM_YAW = Config.floatVal("macroCustomYaw", 0.0f).range(-180f, 180f);
        public static final FloatEntry MACRO_CUSTOM_YAW_HUMANIZATION = Config
                        .floatVal("macroCustomYawHumanization", 0.0f).range(0.0f, 10.0f);
        /** If true, the farming macro will use a Squeaky Mousemat before starting when the current rotation differs from the stored mousemat rotation. */
        public static final BooleanEntry SQUEAKY_MOUSEMAT = Config.bool("squeakyMousemat", false);
        /** Hold W while farming rows (A/D + W) instead of only strafing (A/D). */
        public static final BooleanEntry MACRO_HOLD_W_WHILE_FARMING = Config.bool("macroHoldWWhileFarming", false);
        /** Skip all /setspawn calls used by farming macro support flows. */
        public static final BooleanEntry MACRO_DISABLE_SETSPAWN = Config.bool("macroDisableSetspawn", false);
        /** Use configured lane boundaries to switch direction before movement stalls. */
        public static final BooleanEntry MACRO_FAST_LANE_SWITCH = Config.bool("macroFastLaneSwitch", false);
        public static final StringEntry MACRO_FAST_LANE_BOUNDARY_AXIS = Config.string("macroFastLaneBoundaryAxis", "X");
        public static final IntEntry MACRO_FAST_LANE_LEFT_BOUNDARY = Config.integer("macroFastLaneLeftBoundary", -48)
                        .range(-240, 240);
        public static final IntEntry MACRO_FAST_LANE_RIGHT_BOUNDARY = Config.integer("macroFastLaneRightBoundary", 48)
                        .range(-240, 240);
        /** Rotate after landing from a lower farm layer. */
        public static final BooleanEntry MACRO_ROTATE_ON_DROP = Config.bool("macroRotateOnDrop", false);
        /** Yaw delta applied after landing from a lower farm layer. */
        public static final IntEntry MACRO_DROP_ROTATION_DEGREES = Config.integer("macroDropRotationDegrees", 180)
                        .range(-180, 180);
        /** Post lane-switch delay in milliseconds before evaluating row-end checks again. */
        public static final IntEntry MACRO_LANE_SWITCH_DELAY_MIN = Config.integer("macroLaneSwitchDelayMin", 0)
                        .range(0, 1000);
        public static final IntEntry MACRO_LANE_SWITCH_DELAY_MAX = Config.integer("macroLaneSwitchDelayMax", 500)
                        .range(0, 1000);
        public static final BooleanEntry FAILSAFE_INVENTORY_SLOT_CHANGED = Config.bool("failsafeInventorySlotChanged", true);
        public static final BooleanEntry FAILSAFE_UNEXPECTED_INVENTORY_GUI = Config.bool("failsafeUnexpectedInventoryGui", true);
        public static final BooleanEntry FAILSAFE_BPS = Config.bool("failsafeBps", true);
        public static final IntEntry FAILSAFE_BPS_THRESHOLD = Config.integer("failsafeBpsThreshold", 10)
                        .range(5, 15);
        public static final IntEntry FAILSAFE_BPS_WINDOW_SECONDS = Config.integer("failsafeBpsWindowSeconds", 5)
                        .range(5, 30);
        public static final FloatEntry FAILSAFE_BPS_TRIGGER_DELAY_SECONDS = Config
                        .floatVal("failsafeBpsTriggerDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f);
        public static final BooleanEntry FAILSAFE_GHOST_BLOCK = Config.bool("failsafeGhostBlock", true);
        public static final IntEntry FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS = Config
                        .integer("failsafeGhostBlockWindowSeconds", 5)
                        .range(1, 30);
        public static final FloatEntry FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS = Config
                        .floatVal("failsafeGhostBlockTriggerDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f);
        public static final BooleanEntry FAILSAFE_DIRT_CHECK = Config.bool("failsafeDirtCheck", true);
        public static final FloatEntry FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS = Config
                        .floatVal("failsafeDirtCheckTriggerDelaySeconds", 2.0f)
                        .range(0.0f, 10.0f);
        public static final BooleanEntry FAILSAFE_ROTATION = Config.bool("failsafeRotation", true);
        public static final BooleanEntry FAILSAFE_WORLD_CHANGE = Config.bool("failsafeWorldChange", true);
        public static final IntEntry FAILSAFE_ROTATION_PITCH_THRESHOLD = Config.integer("failsafeRotationPitchThreshold", 10)
                        .range(5, 30);
        public static final IntEntry FAILSAFE_ROTATION_YAW_THRESHOLD = Config.integer("failsafeRotationYawThreshold", 10)
                        .range(5, 30);
        public static final FloatEntry FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS = Config
                        .floatVal("failsafeRotationTriggerDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f);
        public static final BooleanEntry FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER = Config
                        .bool("failsafeRotationTriggerDuringPestCleaner", true);
        public static final IntEntry FAILSAFE_ROTATION_PEST_CLEANER_DELAY_MS = Config
                        .integer("failsafeRotationPestCleanerDelayMs", 750)
                        .range(0, 5000);
        public static final IntEntry FAILSAFE_ROTATION_WARP_GRACE_MS = Config.integer("failsafeRotationWarpGraceMs", 2000)
                        .range(1000, 5000);
        public static final FloatEntry FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS = Config
                        .floatVal("failsafeAdditionalRandomDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f);
        public static final StringEntry FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION = Config
                        .string("failsafeInventorySlotChangedAction", "IGNORE");
        public static final StringEntry FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION = Config
                        .string("failsafeUnexpectedInventoryGuiAction", "IGNORE");
        public static final StringEntry FAILSAFE_BPS_ACTION = Config.string("failsafeBpsAction", "STOP");
        public static final StringEntry FAILSAFE_GHOST_BLOCK_ACTION = Config.string("failsafeGhostBlockAction",
                        "IGNORE");
        public static final StringEntry FAILSAFE_DIRT_CHECK_ACTION = Config.string("failsafeDirtCheckAction",
                        "IGNORE");
        public static final StringEntry FAILSAFE_ROTATION_ACTION = Config.string("failsafeRotationAction", "IGNORE");
        public static final StringEntry FAILSAFE_PEST_ROTATION_ACTION = Config.string("failsafePestRotationAction",
                        "STOP");
        public static final StringEntry FAILSAFE_WORLD_CHANGE_ACTION = Config.string("failsafeWorldChangeAction",
                        "IGNORE");
        public static final StringEntry FAILSAFE_INVENTORY_SLOT_CHANGED_CUSTOM_REPLAY = Config
                        .string("failsafeInventorySlotChangedCustomReplay", "Random");
        public static final StringEntry FAILSAFE_UNEXPECTED_INVENTORY_GUI_CUSTOM_REPLAY = Config
                        .string("failsafeUnexpectedInventoryGuiCustomReplay", "Random");
        public static final StringEntry FAILSAFE_BPS_CUSTOM_REPLAY = Config.string("failsafeBpsCustomReplay", "Random");
        public static final StringEntry FAILSAFE_GHOST_BLOCK_CUSTOM_REPLAY = Config
                        .string("failsafeGhostBlockCustomReplay", "Random");
        public static final StringEntry FAILSAFE_DIRT_CHECK_CUSTOM_REPLAY = Config
                        .string("failsafeDirtCheckCustomReplay", "Random");
        public static final StringEntry FAILSAFE_ROTATION_CUSTOM_REPLAY = Config
                        .string("failsafeRotationCustomReplay", "Random");
        public static final StringEntry FAILSAFE_PEST_ROTATION_CUSTOM_REPLAY = Config
                        .string("failsafePestRotationCustomReplay", "Random");
        public static final StringEntry FAILSAFE_WORLD_CHANGE_CUSTOM_REPLAY = Config
                        .string("failsafeWorldChangeCustomReplay", "Random");
        public static final StringEntry FAILSAFE_ACTION = Config.string("failsafeAction", "STOP");
        public static final BooleanEntry FAILSAFE_SOUND_ENABLED = Config.bool("failsafeSoundEnabled", true);
        public static final StringEntry FAILSAFE_SOUND_FILE = Config.string("failsafeSoundFile", "fnaf.mp3");
        // Per-action overrides. Blank = fall back to the shared FAILSAFE_SOUND_FILE above.
        public static final StringEntry FAILSAFE_SOUND_FILE_STOP = Config.string("failsafeSoundFileStop", "");
        public static final StringEntry FAILSAFE_SOUND_FILE_IGNORE = Config.string("failsafeSoundFileIgnore", "");
        // Playback volume as a 0.0-1.0 fraction (e.g. 0.05 = 5% to stay quietly audible).
        public static final FloatEntry FAILSAFE_SOUND_VOLUME = Config.floatVal("failsafeSoundVolume", 1.0f).range(0.0f, 1.0f);
        public static final BooleanEntry FAILSAFE_DESKTOP_NOTIFICATION_ENABLED = Config.bool("failsafeDesktopNotificationEnabled", false);
        public static final BooleanEntry FAILSAFE_AUTO_ALT_TAB = Config.bool("failsafeAutoAltTab", true);
        public static final FloatEntry FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS = Config
                        .floatVal("failsafeInventorySlotChangedDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f);
        public static final FloatEntry FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS = Config
                        .floatVal("failsafeUnexpectedInventoryGuiDelaySeconds", 2.0f)
                        .range(0.0f, 5.0f);
        public static final FloatEntry FAILSAFE_WORLD_CHANGE_RECOVERY_WAIT_SECONDS = Config
                        .floatVal("failsafeWorldChangeRecoveryWaitSeconds", 5.0f)
                        .range(0.0f, 30.0f);

        // -- BPS -------------------------------------------------------------------
        public static final IntEntry BPS_AVERAGE_WINDOW = Config.integer("bpsAverageWindow", 30).range(5, 60);

        // -- NICK HIDER ------------------------------------------------------------
        public static final BooleanEntry NICK_HIDER_MASTER_ENABLED = Config.bool("nickHiderMasterEnabled", true);
        public static final BooleanEntry NICK_HIDER_ENABLED = Config.bool("nickHiderEnabled", false);
        public static final BooleanEntry HIDE_SERVER_ID = Config.bool("hideServerId", false);
        public static final StringEntry CUSTOM_SERVER_ID = Config.string("customServerId", "typicalfarmingmacro.rip");
        public static final BooleanEntry COOP_HIDER_ENABLED = Config.bool("coopHiderEnabled", false);
        public static final ListEntry<String> COOP_NAMES = Config.list("coopNames", 
                        Arrays.asList("Coop1", "Coop2", "Coop3"), String.class);
        public static final StringEntry CUSTOM_USERNAME = Config.string("customUsername", "TfmUser");
        public static final StringEntry SERVER_NICK = Config.string("serverNick", "");
        public static final BooleanEntry HIDE_SKIN = Config.bool("hideSkin", false);
        public static final BooleanEntry SPOOF_VALUES_ENABLED = Config.bool("spoofValuesEnabled", true);
        public static final DoubleEntry PURSE_OFFSET = Config.doubleVal("purseOffset", 0.0);
        public static final DoubleEntry BITS_OFFSET = Config.doubleVal("bitsOffset", 0.0);
        public static final DoubleEntry COPPER_OFFSET = Config.doubleVal("copperOffset", 0.0);
        public static final DoubleEntry SAWDUST_OFFSET = Config.doubleVal("sawdustOffset", 0.0);
        public static final DoubleEntry FARMING_EXP_OFFSET = Config.doubleVal("farmingExpOffset", 0.0);
        public static final BooleanEntry CUSTOM_SB_LEVEL_ENABLED = Config.bool("customSbLevelEnabled", false);
        public static final IntEntry CUSTOM_SB_LEVEL = Config.integer("customSbLevel", 0);

        // -- FUN -----------------------------------------------------------------
        public static final BooleanEntry HAT_ENABLED = Config.bool("hatEnabled", true);
        public static final BooleanEntry HAT_FILLED = Config.bool("hatFilled", true);
        public static final BooleanEntry HAT_RENDER_FIRST_PERSON = Config.bool("hatRenderFirstPerson", false);
        public static final FloatEntry HAT_HEIGHT = Config.floatVal("hatHeight", 0.5f).range(0.1f, 3.0f);
        public static final FloatEntry HAT_RADIUS = Config.floatVal("hatRadius", 0.8f).range(0.1f, 3.0f);
        public static final IntEntry HAT_VERTICES = Config.integer("hatVertices", 20).range(3, 30);
        public static final FloatEntry HAT_Y_OFFSET = Config.floatVal("hatYOffset", 0.2f).range(0.0f, 3.0f);
        public static final BooleanEntry FUNNY_DYNAMIC_REST = Config.bool("funnyDynamicRest", false);
        public static final BooleanEntry FREECAM_ENABLED = Config.bool("freecamEnabled", true);
        public static final FloatEntry FREECAM_SPEED = Config.floatVal("freecamSpeed", 0.45f).range(0.1f, 2.5f);
        public static final BooleanEntry FREELOOK_ENABLED = Config.bool("freelookEnabled", true);
        public static final StringEntry FREELOOK_MODE = Config.string("freelookMode", "HOLD");
        public static final IntEntry PIP_WINDOW_WIDTH = Config.integer("pipWindowWidth", 480).range(240, 1920);
        public static final IntEntry PIP_WINDOW_HEIGHT = Config.integer("pipWindowHeight", 270).range(135, 1080);
        public static final BooleanEntry PIP_START_FLOATING = Config.bool("pipStartFloating", true);
        public static final BooleanEntry PIP_START_DECORATED = Config.bool("pipStartDecorated", true);
        public static final BooleanEntry PIP_ENABLE_ZOOM = Config.bool("pipEnableZoom", true);

        // -- GREENHOUSE ------------------------------------------------------------
        public static final BooleanEntry AUTO_GREENHOUSE = Config.bool("autoGreenhouse", false);
        public static final IntEntry AUTO_GREENHOUSE_INTERVAL_MINUTES = Config.integer("autoGreenhouseIntervalMinutes", 120)
                        .range(1, 1440);
        public static final BooleanEntry EQUIP_GREENHOUSE_CUSTOM_ITEM = Config.bool("equipNetherWartHoe", false);
        public static final StringEntry GREENHOUSE_CUSTOM_ITEM = Config.string("greenhouseCustomItem", "");
        public static final ListEntry<String> GREENHOUSE_PLOTS = Config.list("greenhousePlots", Collections.emptyList(),
                        String.class);
        public static final BooleanEntry HARVEST_ASHWREATH = Config.bool("harvestAshwreath", false);
        public static final BooleanEntry HARVEST_TURTELLINI = Config.bool("harvestTurtellini", false);
        public static final BooleanEntry HARVEST_GLASSCORN = Config.bool("harvestGlasscorn", false);

        // -- COMPOSTER -------------------------------------------------------------
        public static final BooleanEntry AUTO_COMPOSTER = Config.bool("autoComposter", false);
        public static final IntEntry AUTO_COMPOSTER_INTERVAL_MINUTES = Config.integer("autoComposterIntervalMinutes", 120)
                        .range(1, 1440);
        public static final IntEntry AUTO_COMPOSTER_X = Config.integer("autoComposterX", -11);
        public static final IntEntry AUTO_COMPOSTER_Y = Config.integer("autoComposterY", 72);
        public static final IntEntry AUTO_COMPOSTER_Z = Config.integer("autoComposterZ", -27);
        public static final BooleanEntry AUTO_COMPOSTER_HIGHLIGHT = Config.bool("autoComposterHighlight", true);
        public static final IntEntry AUTO_COMPOSTER_MIN_PURSE = Config.integer("autoComposterMinPurse", 1000000)
                        .range(0, 2000000000);
        public static final StringEntry AUTO_COMPOSTER_SOURCE_MODE = Config.string("autoComposterSourceMode", "SACKS");
        public static final StringEntry AUTO_COMPOSTER_CROP_MATERIAL = Config.string("autoComposterCropMaterial", "Box of Seeds");
        public static final IntEntry AUTO_COMPOSTER_CROP_AMOUNT = Config.integer("autoComposterCropAmount", 1)
                        .range(1, 2000000);
        public static final StringEntry AUTO_COMPOSTER_FUEL_MATERIAL = Config.string("autoComposterFuelMaterial", "Volta");
        public static final IntEntry AUTO_COMPOSTER_FUEL_AMOUNT = Config.integer("autoComposterFuelAmount", 1)
                        .range(1, 2000000);

        // -- SUPERCRAFT ------------------------------------------------------------
        public static final BooleanEntry AUTO_SUPERCRAFT = Config.bool("autoSupercraft", false);
        public static final IntEntry AUTO_SUPERCRAFT_INTERVAL_MINUTES = Config.integer("autoSupercraftIntervalMinutes", 120)
                        .range(1, 1440);
        public static final ListEntry<String> AUTO_SUPERCRAFT_ITEMS = Config.list("autoSupercraftItems",
                        DEFAULT_SUPERCRAFT_ITEMS,
                        String.class);

        // -- DEBUG -----------------------------------------------------------------
        public static final BooleanEntry SHOW_DEBUG = Config.bool("showDebug", false);
}
