package dev.aether.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScoreboardTextTest {
    @Test
    void customTitleInheritsTheServerWeightAndEmptySettingsKeepVanilla() {
        var original = Component.literal("SKYBLOCK").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD).getVisualOrderText();
        assertSame(original, ScoreboardText.customize(original, true, "", "custom.net"));
        var runs = ScoreboardText.split(ScoreboardText.customize(original, true, "My Garden", "custom.net"));
        assertEquals("My Garden", runs.getFirst().text());
        assertTrue(runs.getFirst().style().isBold());
    }

    @Test
    void serverOverrideOnlyReplacesTheAddressAndStaysOnOneLine() {
        var footer = Component.literal("www.hypixel.net").withStyle(ChatFormatting.YELLOW).getVisualOrderText();
        var runs = ScoreboardText.split(ScoreboardText.customize(footer, false, "Title", "My\nServer"));
        assertEquals("My Server", runs.getFirst().text());
        assertEquals(ChatFormatting.YELLOW.getColor(), runs.getFirst().style().getColor().getValue());
        var row = Component.literal("Wave 10").getVisualOrderText();
        assertSame(row, ScoreboardText.customize(row, false, "Title", "My Server"));
        assertSame(footer, ScoreboardText.customize(footer, false, "Title", ""));
    }

    @Test
    void ordinaryTextUsesHudTypographyWhileSymbolsKeepTheirGlyphs() {
        var runs = ScoreboardText.split(FormattedCharSequence.forward("Plot  \u23e3 5 \uE000", Style.EMPTY));
        assertEquals(4, runs.size());
        assertEquals("Plot  ", runs.get(0).text());
        assertFalse(runs.get(0).nativeGlyph());
        assertEquals("\u23e3", runs.get(1).text());
        assertTrue(runs.get(1).nativeGlyph());
        assertEquals(" 5 ", runs.get(2).text());
        assertFalse(runs.get(2).nativeGlyph());
        assertTrue(runs.get(3).nativeGlyph());
    }

    @Test
    void colorAndWeightChangesSurviveTheTextSplit() {
        var text = Component.literal("Purse: ").append(Component.literal("12,126,571").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        var runs = ScoreboardText.split(text.getVisualOrderText());
        assertEquals(2, runs.size());
        assertEquals("Purse: ", runs.get(0).text());
        assertEquals("12,126,571", runs.get(1).text());
        assertTrue(runs.get(1).style().isBold());
        assertEquals(ChatFormatting.GOLD.getColor(), runs.get(1).style().getColor().getValue());
        assertFalse(runs.get(1).nativeGlyph());
    }

    @Test
    void customFontsObfuscationAndUnsupportedScriptsKeepMinecraftRendering() {
        Style custom = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("server", "icons")));
        assertTrue(ScoreboardText.split(FormattedCharSequence.forward("A", custom)).getFirst().nativeGlyph());
        assertTrue(ScoreboardText.split(FormattedCharSequence.forward("secret", Style.EMPTY.withObfuscated(true))).getFirst().nativeGlyph());
        assertTrue(ScoreboardText.split(FormattedCharSequence.forward("\u65e5\u672c\u8a9e", Style.EMPTY)).getFirst().nativeGlyph());
    }
}
