package dev.aether.util

import dev.aether.config.AetherConfig
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.util.regex.Matcher
import java.util.regex.Pattern

class NickHiderUtils {
    companion object {
        private const val SCORE_NUMBER_PATTERN = "(?:[0-9]{1,3}(?:,[0-9]{3})*|[0-9]+)(?:\\.[0-9]+)?"
        private const val COLOR_PATTERN = "(?:\u00A7[0-9a-fk-orq-z])"
        private val PURSE_PATTERN = Pattern.compile("(?i)(Purse:\\s*)($COLOR_PATTERN*?$SCORE_NUMBER_PATTERN)(.*)")
        private val BITS_PATTERN = Pattern.compile("(?i)(Bits:\\s*)($COLOR_PATTERN*?$SCORE_NUMBER_PATTERN)(.*)")
        private val COPPER_PATTERN = Pattern.compile("(?i)(Copper:\\s*)($COLOR_PATTERN*?$SCORE_NUMBER_PATTERN)(.*)")
        private val SAWDUST_PATTERN = Pattern.compile("(?i)(S[oa]wdust:\\s*)($COLOR_PATTERN*?$SCORE_NUMBER_PATTERN)($COLOR_PATTERN*?)([KMB]?)(.*)")
        // Farming exp: +7.8 Farming (426,756,875/0)
        private val FARMING_EXP_PATTERN = Pattern.compile("(?i)(Farming\\s*\\()($COLOR_PATTERN*?$SCORE_NUMBER_PATTERN)(.*)")
        private val SB_LEVEL_PATTERN = Pattern.compile("\\[([0-9]+)\\]")
        private val WORDS = arrayOf("Cheese", "Gobbler", "Pickle", "Snatcher", "Muffin", "Stomper", "Taco", "Runner", "Wizard", "Slayer", "Dragon", "Hunter", "Potato", "Farmer", "Shadow", "Walker", "Sparkle", "Bouncer", "Banana", "Grabber", "Cookie", "Monster", "Flying", "Turtle", "Golden", "Knight", "Silver", "Wolf", "Gamer", "Pro", "Epic", "Legend", "Master", "Chief", "Wonder", "Wander", "Storm", "Thunder", "Blaze", "Frost", "Spirit", "Ghost", "Ancient", "Modern", "Super", "Ultra", "Mega", "Hyper", "Ninja", "Samurai", "Pirate", "Viking", "Cactus", "Pigeon", "Dolphin", "Panda")
        private val cachedCoopNames = HashMap<String, String>()
        private var cachedPattern: Pattern? = null
        private var lastUsername: String? = null

        @JvmStatic
        fun getUsernamePattern(): Pattern {
            val username = Minecraft.getInstance().user.name
            if (cachedPattern == null || username != lastUsername) {
                lastUsername = username
                cachedPattern = Pattern.compile("(?i)" + Pattern.quote(username))
            }
            return cachedPattern!!
        }

        @JvmStatic
        fun replaceNames(input: String): String {
            if (!isNickHiderActive()) return input
            var text = input
            if (AetherConfig.NICK_HIDER_ENABLED.get()) {
                val replacement = AetherConfig.CUSTOM_USERNAME.get()
                if (replacement.isNotEmpty()) text = getUsernamePattern().matcher(text).replaceAll(Matcher.quoteReplacement(replacement))
            }
            if (AetherConfig.HIDE_SERVER_ID.get()) {
                val stripped = stripColorCodesWithMap(text)
                val replacements = ArrayList<ScoreTextReplacement>()
                addServerIdReplacements(stripped.stripped, stripped.origIndices, replacements)
                text = applyReplacements(text, replacements)
            }
            if (AetherConfig.COOP_HIDER_ENABLED.get()) for (target in AetherConfig.COOP_NAMES.get()) {
                val obfuscated = getObfuscatedName(target)
                text = Pattern.compile("(?i)" + Pattern.quote(target)).matcher(text).replaceAll(Matcher.quoteReplacement(obfuscated))
            }
            if (areSpoofValuesEnabled() && AetherConfig.CUSTOM_SB_LEVEL_ENABLED.get()) {
                val matcher = SB_LEVEL_PATTERN.matcher(text)
                if (matcher.find()) {
                    val level = AetherConfig.CUSTOM_SB_LEVEL.get()
                    val replacement = "\u00A77[${getLevelColor(level)}$level\u00A77]"
                    text = matcher.replaceFirst(Matcher.quoteReplacement(replacement))
                }
            }
            return text
        }

        @JvmStatic
        fun getLevelColor(level: Int): String = when {
            level >= 480 -> "\u00A74"; level >= 440 -> "\u00A7c"; level >= 400 -> "\u00A76"; level >= 360 -> "\u00A75"
            level >= 320 -> "\u00A7d"; level >= 280 -> "\u00A79"; level >= 240 -> "\u00A73"; level >= 200 -> "\u00A7b"
            level >= 160 -> "\u00A72"; level >= 120 -> "\u00A7a"; level >= 80 -> "\u00A7e"; level >= 40 -> "\u00A7f"; else -> "\u00A77"
        }

        private fun getObfuscatedName(original: String): String = cachedCoopNames.getOrPut(original.lowercase()) {
            val rand = java.util.Random()
            WORDS[rand.nextInt(WORDS.size)] + WORDS[rand.nextInt(WORDS.size)] + (rand.nextInt(99) + 1)
        }

        @JvmStatic
        fun transformString(text: String): String = if (!isNickHiderActive()) text else replaceNames(text)

        @JvmStatic
        fun containsFarmingExpText(text: String?): Boolean {
            if (text == null || text.isEmpty()) return false
            return stripColorCodesWithMap(text).stripped.contains("+")
        }

        @JvmStatic
        fun transformComponent(component: Component?): Component? {
            if (component == null) return null
            if (!isNickHiderActive()) return component
            val fullText = component.getString()
            val stripResult = stripColorCodesWithMap(fullText)
            val plainText = stripResult.stripped
            val map = stripResult.origIndices
            val replacements = ArrayList<ScoreTextReplacement>()
            if (areSpoofValuesEnabled()) {
                getOffsetReplacementMapped(plainText, map, PURSE_PATTERN, AetherConfig.PURSE_OFFSET.get(), replacements)
                getOffsetReplacementMapped(plainText, map, BITS_PATTERN, AetherConfig.BITS_OFFSET.get(), replacements)
                getOffsetReplacementMapped(plainText, map, COPPER_PATTERN, AetherConfig.COPPER_OFFSET.get(), replacements)
                getOffsetReplacementMapped(plainText, map, SAWDUST_PATTERN, AetherConfig.SAWDUST_OFFSET.get(), replacements)
                getOffsetReplacementMapped(plainText, map, FARMING_EXP_PATTERN, AetherConfig.FARMING_EXP_OFFSET.get(), replacements)
            }
            if (AetherConfig.HIDE_SERVER_ID.get()) addServerIdReplacements(plainText, map, replacements)
            if (areSpoofValuesEnabled() && AetherConfig.CUSTOM_SB_LEVEL_ENABLED.get()) {
                val m = SB_LEVEL_PATTERN.matcher(plainText)
                if (m.find()) {
                    val level = AetherConfig.CUSTOM_SB_LEVEL.get()
                    replacements.add(ScoreTextReplacement(map[m.start()], map[m.end() - 1] + 1, "\u00A77[${getLevelColor(level)}$level\u00A77]"))
                }
            }
            val customNick = AetherConfig.CUSTOM_USERNAME.get()
            if (AetherConfig.NICK_HIDER_ENABLED.get() && customNick.isNotEmpty()) addNameReplacement(plainText, map, Minecraft.getInstance().user.name, customNick, replacements)
            if (AetherConfig.COOP_HIDER_ENABLED.get()) for (target in AetherConfig.COOP_NAMES.get()) addNameReplacement(plainText, map, target, getObfuscatedName(target), replacements)
            if (replacements.isEmpty()) return component
            return replaceTextByIndex(component, fullText, replacements)
        }

        private fun isNickHiderActive(): Boolean = AetherConfig.NICK_HIDER_MASTER_ENABLED.get() &&
            (AetherConfig.NICK_HIDER_ENABLED.get() || AetherConfig.COOP_HIDER_ENABLED.get() || AetherConfig.HIDE_SERVER_ID.get() || areSpoofValuesEnabled())
        private fun areSpoofValuesEnabled(): Boolean = AetherConfig.SPOOF_VALUES_ENABLED.get()

        private fun addServerIdReplacements(plainText: String, map: IntArray, replacements: MutableList<ScoreTextReplacement>) {
            for (span in ServerIdHider.spans(plainText, AetherConfig.CUSTOM_SERVER_ID.get())) replacements.add(ScoreTextReplacement(map[span.start], map[span.end - 1] + 1, AetherConfig.CUSTOM_SERVER_ID.get()))
        }

        // Strips color codes and returns both the stripped string and a mapping from
        // stripped index to original index
        private fun stripColorCodesWithMap(text: String): ColorCodeStripResult {
            val sb = StringBuilder()
            val origIndices = ArrayList<Int>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\u00A7' && i + 1 < text.length) i += 2 else { sb.append(c); origIndices.add(i); i++ }
            }
            val map = IntArray(sb.length)
            for (j in 0 until sb.length) map[j] = origIndices[j]
            return ColorCodeStripResult(sb.toString(), map)
        }

        private class ColorCodeStripResult(val stripped: String, val origIndices: IntArray)

        // Like getOffsetReplacement, but works on stripped text and maps indices back
        // to original
        private fun getOffsetReplacementMapped(plainText: String, map: IntArray, pattern: Pattern, offset: Double, replacements: MutableList<ScoreTextReplacement>) {
            if (offset <= 0.001 && offset >= -0.001) return
            val m = pattern.matcher(plainText)
            if (m.find()) try {
                val fullValueStr = m.group(2)
                val valueStr = fullValueStr.replace(Regex("\u00A7[0-9a-fk-orq-z]"), "").replace(",", "")
                var suffix = ""
                var end = m.end(2)
                val suffixGroup = if (pattern === SAWDUST_PATTERN) 4 else 3
                if (m.groupCount() >= suffixGroup && isAbbreviationSuffix(m.group(suffixGroup))) { suffix = m.group(suffixGroup); end = m.end(suffixGroup) }
                val origStart = map[m.start(2)]
                val origEnd = map[end - 1] + 1
                if (suffix.isNotEmpty()) replacements.add(ScoreTextReplacement(origStart, origEnd, formatAbbreviated(parseAbbreviated(valueStr + suffix) + offset)))
                else replacements.add(ScoreTextReplacement(origStart, origEnd, formatNumber(java.lang.Double.parseDouble(valueStr) + offset)))
            } catch (_: Exception) { }
        }

        private fun replaceTextByIndex(component: Component, fullText: String, replacements: MutableList<ScoreTextReplacement>): Component {
            val segments = ArrayList<StyledTextSegment>()
            component.visit({ style, part -> if (part.isNotEmpty()) segments.add(StyledTextSegment(part, style)); java.util.Optional.empty<Any>() }, Style.EMPTY)
            if (segments.isEmpty()) return Component.literal(applyReplacements(fullText, replacements)).also { it.setStyle(component.getStyle()) }
            val styles = ArrayList<Style>(fullText.length)
            for (segment in segments) repeat(segment.text.length) { styles.add(segment.style) }
            if (styles.size != fullText.length) return Component.literal(applyReplacements(fullText, replacements)).also { it.setStyle(component.getStyle()) }
            val ordered = ArrayList(replacements)
            ordered.sortWith(Comparator.comparingInt<ScoreTextReplacement> { it.start }.reversed())
            val transformed = StringBuilder(fullText)
            for (replacement in ordered) {
                if (replacement.start < 0 || replacement.end > transformed.length || replacement.start >= replacement.end) continue
                val replacementStyle = if (replacement.start < styles.size) styles[replacement.start] else component.getStyle()
                transformed.replace(replacement.start, replacement.end, replacement.replacement)
                styles.subList(replacement.start, replacement.end).clear()
                repeat(replacement.replacement.length) { styles.add(replacement.start + it, replacementStyle) }
            }
            val rebuilt = Component.literal("")
            rebuilt.setStyle(component.getStyle())
            var index = 0
            while (index < transformed.length) {
                val style = styles[index]
                var next = index + 1
                while (next < transformed.length && java.util.Objects.equals(style, styles[next])) next++
                val piece = Component.literal(transformed.substring(index, next))
                piece.setStyle(style)
                rebuilt.append(piece)
                index = next
            }
            return rebuilt
        }

        private fun applyReplacements(text: String, replacements: MutableList<ScoreTextReplacement>): String {
            val ordered = ArrayList(replacements)
            ordered.sortWith(Comparator.comparingInt<ScoreTextReplacement> { it.start }.reversed())
            val transformed = StringBuilder(text)
            for (replacement in ordered) if (replacement.start >= 0 && replacement.end <= transformed.length && replacement.start < replacement.end) transformed.replace(replacement.start, replacement.end, replacement.replacement)
            return transformed.toString()
        }

        private fun addNameReplacement(plainText: String, map: IntArray, target: String?, replacement: String, replacements: MutableList<ScoreTextReplacement>) {
            if (target == null || target.isEmpty()) return
            val m = Pattern.compile("(?i)" + Pattern.quote(target)).matcher(plainText)
            while (m.find()) replacements.add(ScoreTextReplacement(map[m.start()], map[m.end() - 1] + 1, replacement))
        }

        private fun getOffsetReplacement(text: String, pattern: Pattern, offset: Double, replacements: MutableList<ScoreTextReplacement>) {
            if (offset <= 0.001 && offset >= -0.001) return
            val m = pattern.matcher(text)
            if (m.find()) try {
                val fullValueStr = m.group(2)
                val valueStr = fullValueStr.replace(Regex("\u00A7[0-9a-fk-orq-z]"), "").replace(",", "")
                var suffix = ""
                var end = m.end(2)
                val suffixGroup = if (pattern === SAWDUST_PATTERN) 4 else 3
                if (m.groupCount() >= suffixGroup && isAbbreviationSuffix(m.group(suffixGroup))) { suffix = m.group(suffixGroup); end = m.end(suffixGroup) }
                if (suffix.isNotEmpty()) replacements.add(ScoreTextReplacement(m.start(2), end, formatAbbreviated(parseAbbreviated(valueStr + suffix) + offset)))
                else replacements.add(ScoreTextReplacement(m.start(2), end, formatNumber(java.lang.Double.parseDouble(valueStr) + offset)))
            } catch (_: Exception) { }
        }

        private fun isAbbreviationSuffix(value: String?): Boolean = value != null && (value.equals("K", true) || value.equals("M", true) || value.equals("B", true))
        private fun parseAbbreviated(value: String): Double {
            val str = value.uppercase().replace(",", "")
            if (str.endsWith("K")) return java.lang.Double.parseDouble(str.substring(0, str.length - 1)) * 1000
            if (str.endsWith("M")) return java.lang.Double.parseDouble(str.substring(0, str.length - 1)) * 1000000
            if (str.endsWith("B")) return java.lang.Double.parseDouble(str.substring(0, str.length - 1)) * 1000000000
            return java.lang.Double.parseDouble(str)
        }
        private fun formatAbbreviated(value: Double): String = when {
            value >= 1000000000 -> String.format("%.1fB", value / 1000000000.0)
            value >= 1000000 -> String.format("%.1fM", value / 1000000.0)
            value >= 1000 -> String.format("%.1fk", value / 1000.0)
            else -> String.format("%.0f", value)
        }
        private fun formatNumber(value: Double): String = String.format("%,.0f", value)

        private class ScoreTextReplacement(val start: Int, val end: Int, val replacement: String)
        private class StyledTextSegment(val text: String, val style: Style)
    }
}
