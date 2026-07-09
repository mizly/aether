package dev.aether.modules.profit;

import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroState;
import dev.aether.util.TablistUtils;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProfitManager {
    private static final Map<String, Long> sessionCounts = new LinkedHashMap<>();
    private static final Map<String, Long> dailyCounts = new LinkedHashMap<>();
    private static final Map<String, Long> lifetimeCounts = new LinkedHashMap<>();

    private static final ProfitPersistence PERSISTENCE = new ProfitPersistence();
    private static final ProfitPricing PRICING = new ProfitPricing(ProfitManager::invalidateDisplayCaches);
    private static final ProfitDisplayService DISPLAYS = new ProfitDisplayService(ProfitManager::resolveCounts, PRICING);
    private static final ProfitChatParser CHAT_PARSER = new ProfitChatParser();
    private static final ProfitLiveTracker LIVE_TRACKER = new ProfitLiveTracker(PRICING::refreshBazaarPricesIfNeeded);
    private static final ProfitChatParser.ProfitEventSink CHAT_EVENT_SINK = new ProfitChatParser.ProfitEventSink() {
        @Override
        public void addDrop(String itemName, long count) {
            ProfitManager.addDrop(itemName, count);
        }

        @Override
        public void addVisitorGain(String itemName, long count) {
            ProfitManager.addVisitorGain(itemName, count);
        }
    };

    private static long spraySessionQuantity = 0L;
    private static long sprayDailyQuantity = 0L;
    private static long sprayLifetimeQuantity = 0L;
    private static String lastDailyResetDate = PERSISTENCE.getCurrentDateString();

    public static volatile boolean isSprayPhaseActive = false;

    private ProfitManager() {
    }

    public static void startSprayPhase() {
        isSprayPhaseActive = true;
    }

    public static void stopSprayPhase() {
        isSprayPhaseActive = false;
    }

    public static void handleChatMessage(Component component) {
        if (!isProfitTrackingActive()) {
            return;
        }
        CHAT_PARSER.handleChatMessage(component, CHAT_EVENT_SINK);
    }

    public static void addVisitorGain(String itemName, long count) {
        if (!isProfitTrackingActive()) {
            return;
        }
        String cleanName = sanitizeTrackedName(itemName).replace("+", "").trim();
        if (cleanName.isEmpty()) {
            return;
        }

        long multiplier = 1L;
        Matcher suffixMatcher = Pattern.compile("\\s+[xX](\\d+)$").matcher(cleanName);
        if (suffixMatcher.find()) {
            try {
                multiplier = Long.parseLong(suffixMatcher.group(1));
                cleanName = cleanName.substring(0, suffixMatcher.start()).trim();
            } catch (Exception ignored) {
            }
        }

        String keyName = PRICING.canonicalizeTrackedName(cleanName);
        String key = keyName.startsWith("[Visitor] ") ? keyName : "[Visitor] " + keyName;
        long totalCount = count * multiplier;

        checkDailyReset();
        sessionCounts.put(key, sessionCounts.getOrDefault(key, 0L) + totalCount);
        dailyCounts.put(key, dailyCounts.getOrDefault(key, 0L) + totalCount);
        lifetimeCounts.put(key, lifetimeCounts.getOrDefault(key, 0L) + totalCount);
        invalidateDisplayCaches();
        saveLifetime();
        saveDaily();
    }

    public static void addVisitorCost(long coinsSpent) {
        if (!isProfitTrackingActive()) {
            return;
        }
        String key = "[Visitor] Visitor Cost";
        checkDailyReset();
        sessionCounts.put(key, sessionCounts.getOrDefault(key, 0L) - coinsSpent);
        dailyCounts.put(key, dailyCounts.getOrDefault(key, 0L) - coinsSpent);
        lifetimeCounts.put(key, lifetimeCounts.getOrDefault(key, 0L) - coinsSpent);
        invalidateDisplayCaches();
        saveLifetime();
        saveDaily();
    }

    public static void addSprayCost(int quantity, long coins) {
        if (!isProfitTrackingActive()) {
            return;
        }
        String key = "[Spray] Sprayonator";
        checkDailyReset();
        spraySessionQuantity += quantity;
        sprayDailyQuantity += quantity;
        sprayLifetimeQuantity += quantity;
        sessionCounts.put(key, sessionCounts.getOrDefault(key, 0L) - coins);
        dailyCounts.put(key, dailyCounts.getOrDefault(key, 0L) - coins);
        lifetimeCounts.put(key, lifetimeCounts.getOrDefault(key, 0L) - coins);
        invalidateDisplayCaches();
        saveLifetime();
        saveDaily();
    }

    public static long getSprayQuantity(boolean lifetime) {
        return lifetime ? sprayLifetimeQuantity : spraySessionQuantity;
    }

    public static long getSprayQuantity(String mode) {
        if ("daily".equals(mode)) {
            return sprayDailyQuantity;
        }
        if ("lifetime".equals(mode)) {
            return sprayLifetimeQuantity;
        }
        return spraySessionQuantity;
    }

    public static String getCategorizedName(String name) {
        return PRICING.getCategorizedName(name);
    }

    public static String getCompactCategoryLabel(String category) {
        return PRICING.getCompactCategoryLabel(category);
    }

    public static Map<String, Long> getActiveDrops() {
        return getActiveDrops("session");
    }

    public static Map<String, Long> getActiveDrops(boolean lifetime) {
        return getActiveDrops(lifetime ? "lifetime" : "session");
    }

    public static Map<String, Long> getActiveDrops(String mode) {
        return DISPLAYS.getActiveDrops(mode);
    }

    public static Map<String, Long> getCompactDrops() {
        return getCompactDrops("session");
    }

    public static Map<String, Long> getCompactDrops(boolean lifetime) {
        return getCompactDrops(lifetime ? "lifetime" : "session");
    }

    public static Map<String, Long> getCompactDrops(String mode) {
        return DISPLAYS.getCompactDrops(mode);
    }

    public static void reset() {
        sessionCounts.clear();
        spraySessionQuantity = 0L;
        CHAT_PARSER.resetSessionState();
        LIVE_TRACKER.resetSessionState();
        invalidateDisplayCaches();
    }

    public static void resetDaily() {
        dailyCounts.clear();
        sprayDailyQuantity = 0L;
        lastDailyResetDate = PERSISTENCE.getCurrentDateString();
        invalidateDisplayCaches();
        saveDaily();
    }

    public static void resetLifetime() {
        lifetimeCounts.clear();
        invalidateDisplayCaches();
        saveLifetime();
    }

    public static long getTotalProfit() {
        return getTotalProfit("session");
    }

    public static long getTotalProfit(boolean lifetime) {
        return getTotalProfit(lifetime ? "lifetime" : "session");
    }

    public static long getTotalProfit(String mode) {
        return DISPLAYS.getTotalProfit(mode);
    }

    public static void loadLifetime() {
        PERSISTENCE.loadLifetime(lifetimeCounts, ProfitManager::invalidateDisplayCaches);
    }

    public static void loadDaily() {
        ProfitPersistence.DailySnapshot snapshot = PERSISTENCE.loadDaily();
        if (snapshot == null) {
            return;
        }

        lastDailyResetDate = snapshot.resetDate();
        if (!lastDailyResetDate.equals(PERSISTENCE.getCurrentDateString())) {
            resetDaily();
            return;
        }

        dailyCounts.clear();
        dailyCounts.putAll(snapshot.counts());
        sprayDailyQuantity = snapshot.sprayQuantity();
        invalidateDisplayCaches();
    }

    public static double getItemPrice(String itemName) {
        return PRICING.getItemPrice(itemName);
    }

    public static double getItemValue(String itemName, long count) {
        return PRICING.getItemValue(itemName, count);
    }

    public static boolean isPredefinedTrackedItem(String itemName) {
        return PRICING.isPredefinedTrackedItem(itemName);
    }

    public static void update(net.minecraft.client.Minecraft client) {
        LIVE_TRACKER.update(client, ProfitManager::addDrop);
    }

    public static void printPetXpPriceDebug(net.minecraft.client.Minecraft client) {
        PRICING.printPetXpPriceDebug(client);
    }

    public static void addPetXp(String petName, long xpAmount) {
        if (xpAmount <= 0L) {
            return;
        }
        addDrop("Pet XP (" + petName + ")", xpAmount);
    }

    public static void reloadConfiguredPetXpPrices() {
        PRICING.reloadConfiguredPetXpPrices();
    }

    public static void handlePriceSourceChanged() {
        PRICING.handlePriceSourceChanged();
    }

    public static void startStartupPriceFetch() {
        PRICING.startStartupPriceFetch();
    }

    public static String fetchIdByName(String name) {
        return PRICING.fetchIdByName(name);
    }

    private static void invalidateDisplayCaches() {
        if (DISPLAYS != null) {
            DISPLAYS.invalidate();
        }
    }

    private static Map<String, Long> resolveCounts(String mode) {
        if ("daily".equals(mode)) {
            return dailyCounts;
        }
        if ("lifetime".equals(mode)) {
            return lifetimeCounts;
        }
        return sessionCounts;
    }

    private static void addDrop(String itemName, long count) {
        if (!isProfitTrackingActive()) {
            return;
        }
        String processedName = sanitizeTrackedName(itemName);
        if (processedName.isEmpty()) {
            return;
        }

        long multiplier = 1L;

        Matcher suffixMatcher = Pattern.compile("\\s+[xX](\\d+)$").matcher(processedName);
        if (suffixMatcher.find()) {
            try {
                multiplier = Long.parseLong(suffixMatcher.group(1));
                processedName = processedName.substring(0, suffixMatcher.start()).trim();
            } catch (Exception ignored) {
            }
        }

        long finalCount = count * multiplier;
        if (processedName.toLowerCase().endsWith("vinyl")) {
            processedName = "Vinyl";
        }

        String matchedName = PRICING.canonicalizeTrackedName(processedName);

        checkDailyReset();
        sessionCounts.put(matchedName, sessionCounts.getOrDefault(matchedName, 0L) + finalCount);
        dailyCounts.put(matchedName, dailyCounts.getOrDefault(matchedName, 0L) + finalCount);
        lifetimeCounts.put(matchedName, lifetimeCounts.getOrDefault(matchedName, 0L) + finalCount);
        invalidateDisplayCaches();
        saveLifetime();
        saveDaily();
    }

    private static void saveLifetime() {
        PERSISTENCE.saveLifetime(lifetimeCounts);
    }

    private static void saveDaily() {
        PERSISTENCE.saveDaily(dailyCounts, sprayDailyQuantity, lastDailyResetDate);
    }

    private static void checkDailyReset() {
        String currentDate = PERSISTENCE.getCurrentDateString();
        if (!currentDate.equals(lastDailyResetDate)) {
            resetDaily();
        }
    }

    private static String sanitizeTrackedName(String itemName) {
        return TablistUtils.stripColors(itemName).trim();
    }

    public static boolean isProfitTrackingActive() {
        MacroState.State state = MacroStateManager.getCurrentState();
        return state != MacroState.State.OFF && state != MacroState.State.RECOVERING;
    }
}
