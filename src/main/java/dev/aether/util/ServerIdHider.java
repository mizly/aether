package dev.aether.util;

import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public final class ServerIdHider {
    private static final String SERVER_ID = "(?:mini|mega|lobby|m)\\d+[a-z0-9]*(?:[-_][a-z0-9]+)*";
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?iU)(\\d{1,2}/\\d{1,2}/(?:\\d{4}|\\d{2})\\s+)(" + SERVER_ID + ")(?=\\s|$)"),
            Pattern.compile("(?iU)((?:Server|Lobby)(?:\\s+ID)?\\s*:\\s*)(" + SERVER_ID + ")(?=\\s|$)"),
            Pattern.compile("(?iU)^\\s*(" + SERVER_ID + ")\\s*$"));

    private ServerIdHider() {
    }

    public record Span(int start, int end) {
    }

    public static List<Span> spans(String plainText) {
        List<Span> spans = new ArrayList<>();
        for (Pattern pattern : PATTERNS) {
            var matcher = pattern.matcher(plainText);
            while (matcher.find()) {
                Span span = new Span(matcher.start(matcher.groupCount()), matcher.end(matcher.groupCount()));
                if (spans.stream().noneMatch(existing -> existing.start() < span.end() && span.start() < existing.end())) {
                    spans.add(span);
                }
            }
        }
        spans.sort(Comparator.comparingInt(Span::start));
        return List.copyOf(spans);
    }

    public static List<Span> spans(String plainText, String replacement) {
        return spans(plainText).stream().filter(span -> {
            if (replacement.isEmpty()) return true;
            int existing = plainText.lastIndexOf(replacement, span.start());
            return existing < 0 || existing + replacement.length() < span.end();
        }).toList();
    }

    public static FormattedCharSequence replace(FormattedCharSequence text, String replacement) {
        StringBuilder plain = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        text.accept((index, style, codePoint) -> {
            plain.appendCodePoint(codePoint);
            for (int i = 0; i < Character.charCount(codePoint); i++) styles.add(style);
            return true;
        });
        List<Span> spans = spans(plain.toString(), replacement);
        if (spans.isEmpty()) return text;
        for (Span span : spans.reversed()) {
            Style style = styles.get(span.start());
            plain.replace(span.start(), span.end(), replacement);
            styles.subList(span.start(), span.end()).clear();
            styles.addAll(span.start(), java.util.Collections.nCopies(replacement.length(), style));
        }
        String result = plain.toString();
        List<Style> resultStyles = List.copyOf(styles);
        // Replay the visual order we captured; running bidirectional layout again would reverse RTL text twice.
        return sink -> {
            for (int i = 0; i < result.length();) {
                int codePoint = result.codePointAt(i);
                if (!sink.accept(i, resultStyles.get(i), codePoint)) return false;
                i += Character.charCount(codePoint);
            }
            return true;
        };
    }
}
