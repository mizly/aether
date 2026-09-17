package dev.aether.modules.pest.helpers;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PestDisplayValuesTest {
    @Test
    void readsServerNameplatesAndOptionalMaximumHealth() {
        assertEquals(new PestDisplayValues.Health(600, 0), PestDisplayValues.health("§c Locust 600❤"));
        assertEquals(new PestDisplayValues.Health(1250, 2000), PestDisplayValues.health("Moth 1.25k/2,000♥"));
        assertNull(PestDisplayValues.health("REEL"));
    }

    @Test
    void readsStruckThroughSpaceBarWithComponentStyles() {
        var bar = Component.empty()
                .append(Component.literal("      ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD, ChatFormatting.STRIKETHROUGH))
                .append(Component.literal("    ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD, ChatFormatting.STRIKETHROUGH));
        assertEquals(0.4f, PestDisplayValues.huntingProgress(bar), 0.0001f);
    }

    @Test
    void readsLegacyFormattedStaminaAndDoesNotConfuseItWithHealth() {
        assertEquals(0.5f, PestDisplayValues.huntingProgress(Component.literal("§a§l§m     §8§l§m     ")), 0.0001f);
        assertEquals(1f, PestDisplayValues.huntingProgress(Component.literal("§e§lREEL!")));
        assertEquals(-1f, PestDisplayValues.huntingProgress(Component.literal("§cLocust 600❤")));
        assertEquals(-1f, PestDisplayValues.huntingProgress(Component.literal("")));
    }

    @Test
    void numericProgressHasCorrectDirectionAndLimits() {
        assertEquals(0.75f, PestDisplayValues.huntingProgress(Component.literal("Stamina: 25/100")));
        assertEquals(0.75f, PestDisplayValues.huntingProgress(Component.literal("Progress: 75%")));
        assertEquals(0.75f, PestDisplayValues.huntingProgress(Component.literal("Stamina: 25%")));
        assertEquals(1f, PestDisplayValues.huntingProgress(Component.literal("Progress: 120%")));
        assertEquals(-1f, PestDisplayValues.huntingProgress(Component.literal("Stamina: 0/0")));
    }
}
