package dev.aether.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerVitalsTest {
    private static void assertHealth(long current, long max, String bar) {
        PlayerVitals.Health health = PlayerVitals.parseHealth(bar);
        assertNotNull(health, bar);
        assertEquals(current, health.current(), bar);
        assertEquals(max, health.max(), bar);
    }

    @Test
    void readsHealthFromTheFullActionBar() {
        assertHealth(1234, 2000, "1,234/2,000❤     456❈ Defense     789/1,000✎ Mana");
        PlayerVitals.onActionBar("§c1,234/2,000❤     §a456§a❈ Defense     §b789/1,000✎ Mana", 1_000L);
        assertEquals(0.617, PlayerVitals.healthFraction(null, 1_000L), 1e-9);
    }

    @Test
    void acceptsTheHeartOnEitherSide() {
        assertHealth(1234, 2000, "❤ 1234/2000");
        assertHealth(1234, 2000, "♥1,234 / 2,000");
        assertHealth(1234, 2000, "1,234/2,000");
        assertHealth(1234, 2000, " 1,234/2,000");
    }

    @Test
    void readsShorthandAndOverhealedValues() {
        assertHealth(12_500, 15_000, "12.5k/15k❤");
        assertEquals(1.0, PlayerVitals.parseHealth("2,500/2,000❤").fraction());
    }

    @Test
    void neverReadsManaAsHealth() {
        assertHealth(1234, 2000, "1,234/2,000❤ 789/1,000✎");
        assertNull(PlayerVitals.parseHealth("❤ 789/1,000✎ Mana"));
    }

    @Test
    void needsBothNumbersAndAMaximumAboveZero() {
        assertNull(PlayerVitals.parseHealth("1,000❤"));
        assertNull(PlayerVitals.parseHealth(",❤"));
        assertNull(PlayerVitals.parseHealth("0/0❤"));
        assertNull(PlayerVitals.parseHealth(""));
        assertNull(PlayerVitals.parseHealth(null));
    }

    @Test
    void aBrokenActionBarNeverThrows() {
        Component throwing = new Component() {
            @Override
            public Style getStyle() {
                return null;
            }

            @Override
            public ComponentContents getContents() {
                return null;
            }

            @Override
            public List<Component> getSiblings() {
                return List.of();
            }

            @Override
            public FormattedCharSequence getVisualOrderText() {
                return null;
            }

            @Override
            public String getString() {
                throw new IllegalStateException("unreadable action bar");
            }
        };
        assertDoesNotThrow(() -> PlayerVitals.onActionBar(throwing));
        assertDoesNotThrow(() -> PlayerVitals.onActionBar((Component) null));
        assertDoesNotThrow(() -> PlayerVitals.onActionBar(",❤", 0L));
    }

    @Test
    void aStaleReadingFallsBackToVanillaHealth() {
        PlayerVitals.Health health = new PlayerVitals.Health(500, 2000);
        assertEquals(0.25, PlayerVitals.healthFraction(health, 10_000L, 15_000L, 20f, 20f));
        assertEquals(0.5, PlayerVitals.healthFraction(health, 10_000L, 15_001L, 10f, 20f));
        assertEquals(0.5, PlayerVitals.healthFraction(null, 0L, 0L, 10f, 20f));
        assertEquals(1.0, PlayerVitals.healthFraction(null, 0L, 0L, 0f, 0f));
    }

    @Test
    void aLineWithoutHealthKeepsTheLastReading() {
        PlayerVitals.onActionBar("§c500/2,000❤", 50_000L);
        PlayerVitals.onActionBar("§b+5.2 Fishing (12.5%)", 51_000L);
        assertEquals(0.25, PlayerVitals.healthFraction(null, 52_000L));
        assertEquals(1.0, PlayerVitals.healthFraction(null, 55_001L));
    }
}
