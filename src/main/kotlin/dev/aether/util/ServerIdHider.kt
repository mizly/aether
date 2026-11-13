package dev.aether.util

import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import java.util.ArrayList
import java.util.Comparator
import java.util.regex.Pattern

class ServerIdHider private constructor() {
    class Span(val start: Int, val end: Int) {
        override fun equals(other: Any?): Boolean = other is Span && start == other.start && end == other.end
        override fun hashCode(): Int = java.util.Objects.hash(start, end)
        override fun toString(): String = "Span[start=$start, end=$end]"
    }

    companion object {
        private const val SERVER_ID = "(?:mini|mega|lobby|m)\\d+[a-z0-9]*(?:[-_][a-z0-9]+)*"
        private val PATTERNS: List<Pattern> = listOf(
            Pattern.compile("(?iU)(\\d{1,2}/\\d{1,2}/(?:\\d{4}|\\d{2})\\s+)($SERVER_ID)(?=\\s|$)"),
            Pattern.compile("(?iU)((?:Server|Lobby)(?:\\s+ID)?\\s*:\\s*)($SERVER_ID)(?=\\s|$)"),
            Pattern.compile("(?iU)^\\s*($SERVER_ID)\\s*$")
        )

        @JvmStatic
        fun spans(plainText: String): List<Span> {
            val spans = ArrayList<Span>()
            for (pattern in PATTERNS) {
                val matcher = pattern.matcher(plainText)
                while (matcher.find()) {
                    val span = Span(matcher.start(matcher.groupCount()), matcher.end(matcher.groupCount()))
                    if (spans.stream().noneMatch { existing -> existing.start < span.end && span.start < existing.end }) spans.add(span)
                }
            }
            spans.sortWith(Comparator.comparingInt { it.start })
            return java.util.List.copyOf(spans)
        }

        @JvmStatic
        fun spans(plainText: String, replacement: String): List<Span> = spans(plainText).filter { span ->
            if (replacement.isEmpty()) true else {
                val existing = plainText.lastIndexOf(replacement, span.start)
                existing < 0 || existing + replacement.length < span.end
            }
        }

        @JvmStatic
        fun replace(text: FormattedCharSequence, replacement: String): FormattedCharSequence {
            val plain = StringBuilder()
            val styles = ArrayList<Style>()
            text.accept { index, style, codePoint ->
                plain.appendCodePoint(codePoint)
                repeat(Character.charCount(codePoint)) { styles.add(style) }
                true
            }
            val spans = spans(plain.toString(), replacement)
            if (spans.isEmpty()) return text
            for (span in spans.asReversed()) {
                val style = styles[span.start]
                plain.replace(span.start, span.end, replacement)
                styles.subList(span.start, span.end).clear()
                styles.addAll(span.start, java.util.Collections.nCopies(replacement.length, style))
            }
            val result = plain.toString()
            val resultStyles = java.util.List.copyOf(styles)
            return FormattedCharSequence { sink ->
                var i = 0
                while (i < result.length) {
                    val codePoint = result.codePointAt(i)
                    if (!sink.accept(i, resultStyles[i], codePoint)) return@FormattedCharSequence false
                    i += Character.charCount(codePoint)
                }
                true
            }
        }
    }
}
