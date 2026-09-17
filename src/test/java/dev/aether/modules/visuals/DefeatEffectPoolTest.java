package dev.aether.modules.visuals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DefeatEffectPoolTest {
    @Test
    void deathCatchAndMacroConfirmationProduceOnlyOneBurst() {
        var pool = new DefeatEffectPool();
        UUID pest = UUID.randomUUID();
        assertTrue(pool.spawn(pest, Vec3.ZERO, 0, 1, 18, 0));
        assertFalse(pool.spawn(pest, Vec3.ZERO, 0, 1, 18, 50_000_000));
        pool.prune(DefeatEffectPool.DURATION_NANOS);
        assertTrue(pool.active().isEmpty());
        assertFalse(pool.spawn(pest, Vec3.ZERO, 0, 1, 18, 2_000_000_000L));
        pool.clear();
        assertTrue(pool.spawn(pest, Vec3.ZERO, 0, 1, 18, 2_100_000_000L));
    }

    @Test
    void burstStormStaysBoundedAndRetainsNewestEffects() {
        var pool = new DefeatEffectPool();
        for (int i = 0; i < 200; i++) {
            pool.spawn(new UUID(0, i), new Vec3(i, 0, 0), 50, 10, 1000, i);
            assertTrue(pool.active().size() <= DefeatEffectPool.MAX_ACTIVE);
        }
        assertEquals(192, pool.active().getFirst().position().x);
        assertEquals(199, pool.active().getLast().position().x);
        assertEquals(24, pool.active().getLast().particles());
        assertEquals(2, pool.active().getLast().style());
        assertEquals(2f, pool.active().getLast().scale());
        pool.prune(DefeatEffectPool.DURATION_NANOS + 200);
        assertTrue(pool.active().isEmpty());
    }
}
