package dev.aether.modules.profit;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ProfitDisplayService {
    private final Map<String, ProfitViewCache> displayCaches = new HashMap<>();
    private final Function<String, Map<String, Long>> countsResolver;
    private final ProfitPricing pricing;
    private volatile long displayVersion = 1L;

    ProfitDisplayService(Function<String, Map<String, Long>> countsResolver, ProfitPricing pricing) {
        this.countsResolver = countsResolver;
        this.pricing = pricing;
    }

    void invalidate() {
        displayVersion++;
    }

    Map<String, Long> getActiveDrops(String mode) {
        ProfitViewCache cache = getDisplayCache(mode);
        long version = displayVersion;
        synchronized (cache) {
            if (cache.activeVersion != version) {
                cache.activeDrops = Collections.unmodifiableMap(buildActiveDrops(countsResolver.apply(mode)));
                cache.activeVersion = version;
            }
            return cache.activeDrops;
        }
    }

    Map<String, Long> getCompactDrops(String mode) {
        ProfitViewCache cache = getDisplayCache(mode);
        long version = displayVersion;
        synchronized (cache) {
            if (cache.compactVersion != version) {
                cache.compactDrops = Collections.unmodifiableMap(buildCompactDrops(countsResolver.apply(mode)));
                cache.compactVersion = version;
            }
            return cache.compactDrops;
        }
    }

    long getTotalProfit(String mode) {
        ProfitViewCache cache = getDisplayCache(mode);
        long version = displayVersion;
        synchronized (cache) {
            if (cache.totalVersion != version) {
                cache.totalProfit = buildTotalProfit(countsResolver.apply(mode));
                cache.totalVersion = version;
            }
            return cache.totalProfit;
        }
    }

    private ProfitViewCache getDisplayCache(String mode) {
        synchronized (displayCaches) {
            return displayCaches.computeIfAbsent(mode, ignored -> new ProfitViewCache());
        }
    }

    private Map<String, Long> buildActiveDrops(Map<String, Long> counts) {
        return normalizeCounts(counts).entrySet().stream()
                .sorted((left, right) -> {
                    double leftProfit = pricing.getItemValue(left.getKey(), left.getValue());
                    double rightProfit = pricing.getItemValue(right.getKey(), right.getValue());
                    return Double.compare(rightProfit, leftProfit);
                })
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (existing, ignored) -> existing,
                        LinkedHashMap::new));
    }

    private Map<String, Long> buildCompactDrops(Map<String, Long> counts) {
        Map<String, Long> normalizedCounts = normalizeCounts(counts);
        Map<String, Long> compact = new LinkedHashMap<>();
        compact.put("Crops", 0L);
        compact.put("Pest Items", 0L);
        compact.put("Pets", 0L);
        compact.put("Feast", 0L);
        compact.put("Misc Drops", 0L);
        compact.put("Visitor", 0L);
        compact.put("Costs", 0L);
        compact.put("Others", 0L);

        for (Map.Entry<String, Long> entry : normalizedCounts.entrySet()) {
            String name = entry.getKey();
            long count = entry.getValue();
            long profit = (long) pricing.getItemValue(name, count);

            if (pricing.isCrop(name)) {
                compact.put("Crops", compact.get("Crops") + profit);
            } else if (pricing.isPestItem(name)) {
                compact.put("Pest Items", compact.get("Pest Items") + profit);
            } else if (pricing.isPet(name)) {
                compact.put("Pets", compact.get("Pets") + profit);
            } else if (pricing.isFeastItem(name)) {
                compact.put("Feast", compact.get("Feast") + profit);
            } else if (pricing.isMiscDrop(name) || pricing.isPetXpEntry(name)) {
                compact.put("Misc Drops", compact.get("Misc Drops") + profit);
            } else if (name.equals("[Visitor] Visitor Cost") || name.equals("[Spray] Sprayonator")) {
                compact.put("Costs", compact.get("Costs") + profit);
            } else if (name.startsWith("[Visitor] ")) {
                compact.put("Visitor", compact.get("Visitor") + profit);
            } else if (profit < 0) {
                compact.put("Costs", compact.get("Costs") + profit);
            } else {
                compact.put("Others", compact.get("Others") + profit);
            }
        }

        return compact.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (existing, ignored) -> existing,
                        LinkedHashMap::new));
    }

    private long buildTotalProfit(Map<String, Long> counts) {
        double total = 0;
        for (Map.Entry<String, Long> entry : normalizeCounts(counts).entrySet()) {
            total += pricing.getItemValue(entry.getKey(), entry.getValue());
        }
        return (long) total;
    }

    private Map<String, Long> normalizeCounts(Map<String, Long> counts) {
        Map<String, Long> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String key = normalizeCountKey(entry.getKey());
            normalized.put(key, normalized.getOrDefault(key, 0L) + entry.getValue());
        }
        return normalized;
    }

    private String normalizeCountKey(String key) {
        if (key == null || key.isBlank()) {
            return "Unknown Item";
        }
        if ("[Spray] Sprayonator".equals(key)) {
            return key;
        }
        if (key.startsWith("[Visitor] ")) {
            String visitorItem = key.substring(10);
            if ("Visitor Cost".equalsIgnoreCase(visitorItem)) {
                return "[Visitor] Visitor Cost";
            }
            return "[Visitor] " + pricing.canonicalizeTrackedName(visitorItem);
        }
        return pricing.canonicalizeTrackedName(key);
    }

    private static final class ProfitViewCache {
        long activeVersion = -1L;
        long compactVersion = -1L;
        long totalVersion = -1L;
        Map<String, Long> activeDrops = Collections.emptyMap();
        Map<String, Long> compactDrops = Collections.emptyMap();
        long totalProfit = 0L;
    }
}
