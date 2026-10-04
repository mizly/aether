package dev.aether.ui.gui.plot;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlotMenuReaderTest {
    @Test
    void componentsGoBackToHypixelColourCodes() {
        Component name = Component.literal("Plot ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal("- ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("S 4").withStyle(ChatFormatting.AQUA));
        assertEquals("§aPlot §7- §bS 4", PlotMenuReader.legacy(name));
        assertEquals("S 4", PlotInfo.of(4, new PlotMenuSnapshot.Slot("minecraft:wheat", PlotMenuReader.legacy(name),
                java.util.List.of())).label());

        Component pests = Component.empty()
                .append(Component.literal("ൠ ").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                .append(Component.literal("This plot has ").withStyle(ChatFormatting.RED))
                .append(Component.literal("3").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal(" Pests").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal("!").withStyle(ChatFormatting.RED));
        String legacy = PlotMenuReader.legacy(pests);
        assertEquals("§4§lൠ §cThis plot has §23§2 Pests§c!", legacy);
        assertEquals(3, PlotInfo.of(4, new PlotMenuSnapshot.Slot("minecraft:wheat", "", java.util.List.of(legacy))).pests());
    }

    @Test
    void hexColoursSnapToTheNearestCode() {
        Component text = Component.literal("x").withStyle(style -> style.withColor(TextColor.fromRgb(0x56FF57)));
        assertEquals("§ax", PlotMenuReader.legacy(text));
        assertEquals("§rplain", PlotMenuReader.legacy(Component.literal("plain")));
    }

    @Test
    void theProfileIdComesFromChat() {
        PlotMenuReader.onChat("§aYou are playing on profile: §eBanana");
        PlotMenuReader.onChat("§8Profile ID: 0F3A2B1C-1111-2222-3333-444455556666");
        assertEquals("0f3a2b1c-1111-2222-3333-444455556666", PlotMenuReader.profileId());
    }
}
