package dev.aether.modules.profit

import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.visitor.VisitorManager
import dev.aether.util.ClientUtils
import dev.aether.util.NumberUtils
import dev.aether.util.TablistUtils
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.util.Optional
import java.util.regex.Pattern

internal class ProfitChatParser {
    private val PEST_PATTERN = Pattern.compile("received\\s+(\\d+)x\\s+(.+?)\\s+for\\s+killing", Pattern.CASE_INSENSITIVE)
    private val RARE_DROP_PATTERN = Pattern.compile("(?:UNCOMMON|RARE|CRAZY RARE|PRAY TO RNGESUS) DROP!\\s+(?:You dropped\\s+)?(?:an?\\s+)?(?:(\\d+)x\\s+)?(.+?)(?=\\s*(?:\\u00A7[0-9a-fk-or])*\\s*[\\(!]|$)", Pattern.CASE_INSENSITIVE)
    private val PET_DROP_PATTERN = Pattern.compile("PET DROP!\\s+.*?\\u00A7([0-9a-f])(?:\\u00A7[0-9a-fk-or])*\\s*(?:(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC)\\s+(?:\\u00A7[0-9a-fk-or])*)?(.+?)(?=\\s*(?:\\u00A7[0-9a-fk-or])*\\s*[\\(!]|$)", Pattern.CASE_INSENSITIVE)
    private val RARE_CROP_PATTERN = Pattern.compile("RARE CROP!\\s+(.+?)(?=\\s*(?:\\u00A7[0-9a-fk-or])*\\s*[\\(!]|$)", Pattern.CASE_INSENSITIVE)
    private val OVERFLOW_DROP_PATTERN = Pattern.compile("OVERFLOW!\\s+.*?\\s+has\\s+just\\s+dropped\\s+(?:an?\\s+)?(?:(\\d+)x\\s+)?(.+?)(?=\\s*\\(!|!|$)", Pattern.CASE_INSENSITIVE)
    private val PEST_SHARD_PATTERN = Pattern.compile("(?:caught|captured|received|obtained|got|charmed)[^.!\\n]{0,100}?(?:(\\d+)\\s*[x×]?\\s*)?(?:(Fly|Cricket|Locust|Rat|Mosquito|Earthworm|Mite|Moth|Slug|Beetle|Firefly|Dragonfly|Praying Mantis)\\s+)?(?:its\\s+)?(?:a\\s+)?(?:Pest(?:'s)?\\s+)?Shards?", Pattern.CASE_INSENSITIVE)
    private val BAZAAR_BUY_PATTERN = Pattern.compile("\\[Bazaar\\] Bought (\\d+)x (.+?) for [\\d,]+ coins!", Pattern.CASE_INSENSITIVE)
    private val SPRAY_PATTERN = Pattern.compile("SPRAYONATOR! You sprayed Plot - \\d+ with (.+?)(?:!|$)", Pattern.CASE_INSENSITIVE)
    private val VISITOR_REWARD_PATTERN = Pattern.compile("^\\+?([\\d,.]+(?:\\.\\d+)?[kKmMbB]?)[xX]?\\s+(.+)$")
    private var lastBazaarSprayBuyTime = 0L
    private var trackingVisitorRewards = false
    private var awaitingVisitorRewards = false
    private var suppressNonVisitorRewardBlock = false

    fun resetSessionState() {
        lastBazaarSprayBuyTime = 0
        trackingVisitorRewards = false
        awaitingVisitorRewards = false
        suppressNonVisitorRewardBlock = false
    }

    fun handleChatMessage(component: Component?, sink: ProfitEventSink) {
        if (component == null) return
        val text = toLegacyText(component)
        val pet = PET_DROP_PATTERN.matcher(text)
        if (pet.find() && ProfitTrackingRules.isSystemMatch(text, pet.start())) {
            val color = pet.group(1).lowercase()
            val name = pet.group(2).trim()
            val finalName = when {
                name.equals("Slug", true) && (color == "5" || color == "d") -> "Epic Slug"
                name.equals("Slug", true) && color == "6" -> "Legendary Slug"
                name.equals("Rat", true) -> "Rat"
                else -> name
            }
            sink.addDrop(finalName, 1); return
        }
        val crop = RARE_CROP_PATTERN.matcher(text)
        if (crop.find() && ProfitTrackingRules.isSystemMatch(text, crop.start())) { sink.addDrop(crop.group(1).trim(), 1); return }
        val plain = TablistUtils.stripColors(text).trim()
        val shard = PEST_SHARD_PATTERN.matcher(plain)
        if (shard.find() && ProfitTrackingRules.isSystemMatch(plain, shard.start())) {
            try { sink.addDrop(shard.group(2)?.let { "$it Shard" } ?: "Pest Shard", shard.group(1)?.toLong() ?: 1L); return } catch (_: NumberFormatException) { }
        }
        if (MacroStateManager.getCurrentState() != MacroState.State.VISITING) {
            trackingVisitorRewards = false; awaitingVisitorRewards = false; suppressNonVisitorRewardBlock = false
        }
        if (MacroStateManager.getCurrentState() == MacroState.State.VISITING && VisitorManager.extractAcceptedVisitorName(plain) != null) {
            awaitingVisitorRewards = true; trackingVisitorRewards = false; suppressNonVisitorRewardBlock = false; return
        }
        if (isRewardBlockBoundary(plain)) { trackingVisitorRewards = false; suppressNonVisitorRewardBlock = false; return }
        if (isNonVisitorRewardHeader(plain)) {
            if (awaitingVisitorRewards || trackingVisitorRewards) { suppressNonVisitorRewardBlock = true; trackingVisitorRewards = false }
            return
        }
        val overflow = OVERFLOW_DROP_PATTERN.matcher(plain)
        if (overflow.find() && ProfitTrackingRules.isSystemMatch(plain, overflow.start())) {
            try { sink.addDrop(overflow.group(2).trim(), overflow.group(1)?.toLong() ?: 1L); return } catch (_: Exception) { }
        }
        val pest = PEST_PATTERN.matcher(plain)
        if (pest.find() && ProfitTrackingRules.isSystemMatch(plain, pest.start())) {
            try { sink.addDrop(pest.group(2).trim(), pest.group(1).toLong()); return } catch (_: Exception) { }
        }
        val rare = RARE_DROP_PATTERN.matcher(plain)
        if (rare.find() && ProfitTrackingRules.isSystemMatch(plain, rare.start())) {
            try { sink.addDrop(rare.group(2).trim(), rare.group(1)?.toLong() ?: 1L) } catch (_: Exception) { }
        }
        val bazaar = BAZAAR_BUY_PATTERN.matcher(plain)
        if (bazaar.find()) {
            if (!containsValidTrigger(plain, "[Bazaar]")) { ClientUtils.sendDebugMessage("Bazaar buy ignored (forwarded chat message)"); return }
            if (MacroStateManager.getCurrentState() == MacroState.State.VISITING) { ClientUtils.sendDebugMessage("Bazaar buy ignored (Visiting state)"); return }
            if (ProfitManager.isSprayPhaseActive) {
                lastBazaarSprayBuyTime = System.currentTimeMillis()
                ClientUtils.sendDebugMessage("Bazaar buy ignored (already recorded as spray cost)"); return
            }
            try {
                val count = bazaar.group(1).toLong(); val item = bazaar.group(2).trim()
                ClientUtils.sendDebugMessage("Bazaar buy detected: $count x $item")
                sink.addDrop(item, -count); lastBazaarSprayBuyTime = System.currentTimeMillis()
            } catch (_: Exception) { }
        }
        if (plain.equals("REWARDS", true)) {
            if (suppressNonVisitorRewardBlock) return
            trackingVisitorRewards = awaitingVisitorRewards && MacroStateManager.getCurrentState() == MacroState.State.VISITING
            awaitingVisitorRewards = false; return
        }
        if (trackingVisitorRewards) {
            if (plain.contains("[NPC]")) { trackingVisitorRewards = false; awaitingVisitorRewards = false; return }
            if (isNonVisitorRewardLine(plain)) return
            val reward = VISITOR_REWARD_PATTERN.matcher(plain)
            if (reward.matches()) {
                sink.addVisitorGain(reward.group(2).trim(), parseVisitorRewardCount(reward.group(1).replace(",", ""))); return
            }
            if (isLikelyVisitorRewardItemName(plain)) sink.addVisitorGain(plain, 1)
            return
        }
        val spray = SPRAY_PATTERN.matcher(plain)
        if (spray.find() && ProfitTrackingRules.isSystemMatch(plain, spray.start())) {
            val bait = spray.group(1).trim()
            if (System.currentTimeMillis() - lastBazaarSprayBuyTime < 15000) ClientUtils.sendDebugMessage("Sprayonator use ignored due to recent Bazaar buy.")
            else { ClientUtils.sendDebugMessage("Sprayonator use detected ($bait)."); sink.addDrop(bait, -1) }
        }
    }

    private fun isRewardBlockBoundary(text: String): Boolean {
        if (text.length < 20) return false
        val first = text.codePointAt(0)
        if (Character.isLetterOrDigit(first) || Character.isWhitespace(first)) return false
        var i = Character.charCount(first)
        while (i < text.length) { val cp = text.codePointAt(i); if (cp != first) return false; i += Character.charCount(cp) }
        return true
    }
    private fun isNonVisitorRewardHeader(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("skill level up") || lower.contains("garden milestone") || lower.contains("garden level up") || lower.contains("crop milestone")
    }
    private fun isNonVisitorRewardLine(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("skyblock xp", "health", "farmhand", "grants ", "chance for multiple crops", "milestone ", "garden experience", "farming experience", "farming xp").any(lower::contains)
    }
    private fun isLikelyVisitorRewardItemName(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return !text.contains('[') && !text.contains(']') && !text.contains('(') && !text.contains(')') && !text.contains(':') && !text.contains('\u279c') && !text.endsWith('.') && !lower.startsWith("milestone ") && !lower.contains(" in ")
    }
    private fun toLegacyText(component: Component): String {
        val builder = StringBuilder()
        component.visit({ style, part ->
            style.color?.let { color ->
                val code = when (color.value) {
                    16755200 -> "6"; 11141290 -> "5"; 5636095 -> "b"; 16733695 -> "d"; 5592405 -> "8"; 11184810 -> "7"; 5592575 -> "9"; 5635925 -> "a"; 16711680 -> "c"; 16777045 -> "e"; else -> "f"
                }
                builder.append('\u00A7').append(code)
            }
            if (style.isBold) builder.append("\u00A7l")
            if (style.isItalic) builder.append("\u00A7o")
            builder.append(part)
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return builder.toString()
    }
    private fun containsValidTrigger(text: String, trigger: String): Boolean {
        val index = text.indexOf(trigger)
        return index >= 0 && text.lastIndexOf(':', index - 1) < 0
    }
    private fun parseVisitorRewardCount(text: String) = try { NumberUtils.parseShorthand(text) } catch (_: Exception) { 1L }

    interface ProfitEventSink {
        fun addDrop(itemName: String, count: Long)
        fun addVisitorGain(itemName: String, count: Long)
    }
}
