package dev.aether.modules.visuals;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DefeatEffectPool {
    public static final int MAX_ACTIVE = 8;
    public static final long DURATION_NANOS = 1_250_000_000L;
    private static final long DEDUP_NANOS = 5_000_000_000L;
    private final List<Burst> active = new ArrayList<>(MAX_ACTIVE);
    private final List<Burst> activeView = java.util.Collections.unmodifiableList(active);
    private final Map<UUID, Long> recent = new LinkedHashMap<>();

    public boolean spawn(UUID id, Vec3 position, int style, float scale, int particles, long now) {
        prune(now);
        if (recent.containsKey(id)) return false;
        recent.put(id, now);
        if (recent.size() > 64) recent.remove(recent.keySet().iterator().next());
        if (active.size() == MAX_ACTIVE) active.removeFirst();
        active.add(new Burst(position, Math.clamp(style, 0, 2), Math.clamp(scale, 0.5f, 2f),
                Math.clamp(particles, 8, 24), (id.hashCode() & 65535) / 65535f * 6.283185f, now));
        return true;
    }

    public void prune(long now) {
        active.removeIf(burst -> now - burst.startedAt >= DURATION_NANOS);
        recent.values().removeIf(start -> now - start >= DEDUP_NANOS);
    }

    public List<Burst> active() { return activeView; }
    public void clear() { active.clear(); recent.clear(); }

    public record Burst(Vec3 position, int style, float scale, int particles, float seed, long startedAt) {
        public float age(long now) { return Math.clamp((now - startedAt) / (float) DURATION_NANOS, 0f, 1f); }
    }
}
