package dev.aether.hud;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

final class ScoreboardText {
    static final float SIZE = 9f;
    static final float HEADING_SIZE = SIZE + 1f;
    private final List<Part> parts;
    private final boolean heading;

    private ScoreboardText(List<Part> parts, boolean heading) {
        this.parts = List.copyOf(parts);
        this.heading = heading;
    }

    static FormattedCharSequence customize(FormattedCharSequence original, boolean title, String titleText, String serverText) {
        List<Run> runs = split(original);
        String replacement = title ? titleText : isServerAddress(original) ? serverText : "";
        if (replacement == null || replacement.isBlank()) return original;
        Style style = runs.isEmpty() ? Style.EMPTY : runs.getFirst().style();
        return FormattedCharSequence.forward(replacement.replace('\n', ' ').replace('\r', ' '), style);
    }

    static ScoreboardText prepare(Font font, FormattedCharSequence text, int color, boolean shadow) {
        return prepare(font, text, color, shadow, false);
    }

    static boolean isServerAddress(FormattedCharSequence text) {
        StringBuilder plain = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            plain.appendCodePoint(codePoint);
            return true;
        });
        return plain.toString().strip().matches("(?i)(?:www\\.)?hypixel\\.net");
    }

    static ScoreboardText prepare(Font font, FormattedCharSequence text, int color, boolean shadow, boolean heading) {
        List<Part> parts = new ArrayList<>();
        for (Run run : split(text)) {
            if (heading) run = new Run(run.text(), run.style().withBold(true), run.nativeGlyph());
            FormattedCharSequence sequence = FormattedCharSequence.forward(run.text(), run.style());
            parts.add(new Part(run, color, heading ? HEADING_SIZE : SIZE,
                    run.nativeGlyph() ? font.prepareText(sequence, 0, 0, color, shadow, false, 0) : null,
                    run.nativeGlyph() ? font.width(sequence) : 0));
        }
        return new ScoreboardText(parts, heading);
    }

    boolean isHeading() { return heading; }

    static List<Run> split(FormattedCharSequence text) {
        List<Run> runs = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        Style[] currentStyle = {Style.EMPTY};
        boolean[] nativeGlyph = {false};
        text.accept((index, style, codePoint) -> {
            boolean useNative = usesNativeGlyph(style, codePoint);
            if (!value.isEmpty() && (!style.equals(currentStyle[0]) || useNative != nativeGlyph[0])) {
                runs.add(new Run(value.toString(), currentStyle[0], nativeGlyph[0]));
                value.setLength(0);
            }
            currentStyle[0] = style;
            nativeGlyph[0] = useNative;
            value.appendCodePoint(codePoint);
            return true;
        });
        if (!value.isEmpty()) runs.add(new Run(value.toString(), currentStyle[0], nativeGlyph[0]));
        return List.copyOf(runs);
    }

    private static boolean usesNativeGlyph(Style style, int codePoint) {
        if (style.isObfuscated() || !FontDescription.DEFAULT.equals(style.getFont())) return true;
        // The bundled Inter fonts do not cover every script that Minecraft can render.
        if (Character.isLetterOrDigit(codePoint) && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
                && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.COMMON) return true;
        return switch (Character.getType(codePoint)) {
            case Character.PRIVATE_USE, Character.UNASSIGNED, Character.OTHER_SYMBOL,
                 Character.MATH_SYMBOL, Character.MODIFIER_SYMBOL -> true;
            default -> false;
        };
    }

    float width(NVGRenderer nvg, boolean title) {
        float width = 0;
        for (Part part : parts) width += part.width(nvg, title);
        return width;
    }

    void render(NVGRenderer nvg, float x, float y, float maxWidth, boolean title) {
        float width = width(nvg, title);
        if (width <= 0 || maxWidth <= 0) return;
        nvg.save();
        try {
            nvg.translate(x, y);
            nvg.scale(Math.min(1f, maxWidth / width), 1f);
            float cursor = 0;
            for (Part part : parts) {
                part.render(nvg, cursor, title);
                cursor += part.width(nvg, title);
            }
        } finally {
            nvg.restore();
        }
    }

    static int textColor(Style style, int baseColor, boolean title) {
        int alpha = baseColor >>> 24;
        if (title) return HudStyle.alpha(Theme.HUD_TITLE, alpha / 255f);
        int rgb = style.getColor() == null ? baseColor & 0xFFFFFF : style.getColor().getValue();
        return switch (rgb) {
            case 0xFFFFFF -> HudStyle.alpha(Theme.HUD_VALUE, alpha / 255f);
            case 0xAAAAAA, 0x555555 -> HudStyle.alpha(Theme.HUD_LABEL, alpha / 255f);
            default -> alpha << 24 | rgb;
        };
    }

    record Run(String text, Style style, boolean nativeGlyph) { }

    private record Part(Run run, int color, float size, Font.PreparedText glyphs, float nativeWidth) {
        private String font(boolean title) {
            return run.style().isBold() ? Fonts.SCOREBOARD_BOLD : Fonts.REGULAR;
        }

        float width(NVGRenderer nvg, boolean title) {
            return glyphs != null ? nativeWidth * size / SIZE : nvg.textWidthLiteral(font(title), run.text(), size);
        }

        void render(NVGRenderer nvg, float x, boolean title) {
            nvg.save();
            try {
                nvg.translate(x, 0);
                if (glyphs != null) {
                    nvg.scale(size / SIZE, size / SIZE);
                    nvg.minecraftText(glyphs);
                    return;
                }
                int tint = textColor(run.style(), color, title);
                if (run.style().isItalic()) {
                    nvg.translate(size * 0.2f, 0);
                    nvg.skewX(-0.2f);
                }
                nvg.textLiteral(font(title), run.text(), 0, 0, size, tint);
                float width = width(nvg, title);
                if (run.style().isUnderlined()) nvg.rect(0, size - 1, width, 0.6f, tint);
                if (run.style().isStrikethrough()) nvg.rect(0, size * 0.5f, width, 0.6f, tint);
            } finally {
                nvg.restore();
            }
        }
    }
}
