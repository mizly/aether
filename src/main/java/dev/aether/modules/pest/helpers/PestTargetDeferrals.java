package dev.aether.modules.pest.helpers;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pests the destroyer gave up on. A timeout means unreachable right now, not
 * dead, so it has to expire: blacklisting for the whole run left the cleaner
 * sweeping a plot whose remaining pests it had made invisible to itself.
 */
final class PestTargetDeferrals {
    private static final long BASE_DEFER_MS = 8_000L;
    private static final long MAX_DEFER_MS = 30_000L;
    private static final int MAX_ATTEMPTS = 3;

    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();

    void defer(Entity entity) {
        if (entity == null) {
            return;
        }
        long now = System.currentTimeMillis();
        entries.compute(entity.getId(), (id, previous) -> {
            int attempts = previous == null ? 1 : previous.attempts() + 1;
            long until = attempts >= MAX_ATTEMPTS
                    ? Long.MAX_VALUE
                    : now + Math.min(MAX_DEFER_MS, BASE_DEFER_MS * attempts);
            return new Entry(attempts, until);
        });
    }

    boolean isDeferred(int entityId) {
        Entry entry = entries.get(entityId);
        return entry != null && System.currentTimeMillis() < entry.until();
    }

    /**
     * Ends the wait for every pest we have not given up on too many times, for
     * when a full plot sweep turned up nothing else to do.
     */
    boolean releaseRetryable() {
        boolean released = false;
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Entry> mapping : entries.entrySet()) {
            Entry entry = mapping.getValue();
            if (entry.attempts() < MAX_ATTEMPTS
                    && now < entry.until()
                    && entries.replace(mapping.getKey(), entry, new Entry(entry.attempts(), 0L))) {
                released = true;
            }
        }
        return released;
    }

    void clear() {
        entries.clear();
    }

    private record Entry(int attempts, long until) {
    }
}
