package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// where each pest was when the player last actually saw it, all a turn toward a pest out of view may go on
final class PestSightings {
    private static final long FORGET_AFTER_MS = 30_000L;

    record Sighting(Vec3 eye, long ageMs) {
    }

    private record Seen(Vec3 eye, long at) {
    }

    // stopping the macro clears this off the client thread, possibly mid tick
    private final Map<Integer, Seen> seen = new ConcurrentHashMap<>();

    void record(int id, Vec3 eye, long now) {
        seen.put(id, new Seen(eye, now));
    }

    Sighting lastSeen(int id, long now, long maxAgeMs) {
        Seen last = seen.get(id);
        if (last == null) {
            return null;
        }
        long ageMs = Math.max(0L, now - last.at());
        return ageMs <= maxAgeMs ? new Sighting(last.eye(), ageMs) : null;
    }

    void prune(long now) {
        seen.values().removeIf(last -> now - last.at() > FORGET_AFTER_MS);
    }

    void clear() {
        seen.clear();
    }
}
