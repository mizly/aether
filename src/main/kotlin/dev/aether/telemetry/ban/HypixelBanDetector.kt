package dev.aether.telemetry.ban

import java.util.ArrayList
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

// pure classification of a disconnect reason: no client state, no Minecraft classes.
class HypixelBanDetector private constructor() {
    companion object {
        const val SIMULATED_BAN_ID = "AETHERSB"
        private val FORMATTING_CODE = Pattern.compile("(?i)\\u00A7[0-9A-FK-OR]")
        private val EXOTIC_SPACE = Pattern.compile("[\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]")
        private val TEMPORARY_FALLBACK = Pattern.compile("(?i)\\bbanned\\b.*\\bfor\\b.*\\bfrom this server\\b")
        private val DURATION_BOUNDED = Pattern.compile("(?i)banned for\\s+(.+?)\\s+from this server")
        private val DURATION_OPEN = Pattern.compile("(?i)banned for\\s+(.+?)\\s*$")
        private val REASON_LINE = Pattern.compile("(?i)^reason\\s*:\\s*(.*)$")
        private val BAN_ID_LINE = Pattern.compile("(?i)\\bban\\s*id\\s*:\\s*#?\\s*([A-Za-z0-9_-]+)")
        private val BAN_ID_LABEL = Pattern.compile("(?i)\\bban\\s*id\\s*:")
        private val MARKER_LINE = Pattern.compile("(?i)^(reason|find out more|ban id)\\s*:")

        @JvmStatic
        fun detect(rawReason: String?): DetectedBan? {
            val lines = normalizeLines(rawReason)
            if (lines.isEmpty()) return null
            val lower = java.lang.String.join("\n", lines).lowercase(Locale.ROOT)
            val temporary = classifyBanKind(lower) ?: return null
            if (!hasHypixelPunishmentMarker(lower)) return null
            return DetectedBan(temporary, parseReason(lines), if (temporary) parseDuration(lines) else "", SIMULATED_BAN_ID.equals(parseBanId(lines), ignoreCase = true))
        }

        @JvmStatic
        fun normalizeLines(rawReason: String?): List<String> {
            val lines = ArrayList<String>()
            if (rawReason == null || rawReason.isBlank()) return lines
            var stripped = FORMATTING_CODE.matcher(rawReason).replaceAll("")
            stripped = EXOTIC_SPACE.matcher(stripped).replaceAll(" ")
            for (line in stripped.split("\\R".toRegex())) {
                val collapsed = line.replace(Regex("\\s+"), " ").trim()
                if (collapsed.isNotEmpty()) lines.add(collapsed)
            }
            return lines
        }

        private fun classifyBanKind(lower: String): Boolean? {
            if (lower.contains("temporarily banned") || TEMPORARY_FALLBACK.matcher(lower).find()) return true
            if (lower.contains("permanently banned") || lower.contains("banned permanently")) return false
            return null
        }

        private fun hasHypixelPunishmentMarker(lower: String): Boolean = lower.contains("hypixel.net/appeal") || BAN_ID_LABEL.matcher(lower).find()

        private fun parseDuration(lines: List<String>): String {
            for (line in lines) {
                val bounded = DURATION_BOUNDED.matcher(line)
                if (bounded.find()) return cleanDuration(bounded.group(1))
            }
            for (line in lines) {
                val open = DURATION_OPEN.matcher(line)
                if (open.find()) return cleanDuration(open.group(1))
            }
            return ""
        }

        private fun cleanDuration(raw: String): String = raw.replace(Regex("[!.\\s]+$"), "").trim()

        private fun parseReason(lines: List<String>): String {
            for (i in lines.indices) {
                val matcher = REASON_LINE.matcher(lines[i])
                if (!matcher.matches()) continue
                val inline = matcher.group(1).trim()
                if (inline.isNotEmpty()) return inline
                // wrapped component: the reason text landed on the following line.
                if (i + 1 < lines.size && !MARKER_LINE.matcher(lines[i + 1]).find()) return lines[i + 1].trim()
                return ""
            }
            return ""
        }

        private fun parseBanId(lines: List<String>): String {
            for (line in lines) {
                val matcher: Matcher = BAN_ID_LINE.matcher(line)
                if (matcher.find()) return matcher.group(1)
            }
            return ""
        }
    }
}
