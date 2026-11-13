package dev.aether.util

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.modules.failsafe.FailsafeManager
import net.minecraft.client.Minecraft
import java.util.ArrayDeque
import java.util.Iterator
import java.util.Queue
import java.util.function.Predicate

class CommandUtils private constructor() {
    class ChatWindow internal constructor(val startSequence: Long, val startTimeMs: Long)
    private class ChatEvent(val sequence: Long, val timeMs: Long, val message: String)
    companion object {
        private const val MAX_CHAT_QUEUE_SIZE = 256
        private const val MAX_CHAT_EVENT_AGE_MS = 15_000L
        private const val PLOT_CHAT_FRESHNESS_MS = 10_000L
        private val chatMessageQueue: Queue<ChatEvent> = ArrayDeque(MAX_CHAT_QUEUE_SIZE)
        private const val MESSAGE_TIMEOUT_MS = 10000L
        private var lastPlotTpTime = 0L
        private var nextChatSequence = 0L
        @JvmField @Volatile var lastKnownPlotChat: String = "Unknown"
        @JvmField @Volatile var lastKnownPlotChatAt: Long = 0L

        @JvmStatic fun beginChatWindow(): ChatWindow = synchronized(chatMessageQueue) { val now = System.currentTimeMillis(); pruneExpiredEventsLocked(now); ChatWindow(nextChatSequence, now) }
        @JvmStatic fun onChatMessage(message: String?) {
            val plain = message?.trim() ?: ""
            val now = System.currentTimeMillis()
            if (plain.contains("Teleported you to Plot - ")) {
                var plot = plain.substring(plain.indexOf("-") + 1).trim()
                if (plot.endsWith("!")) plot = plot.substring(0, plot.length - 1)
                lastKnownPlotChat = plot
                lastKnownPlotChatAt = now
            }
            synchronized(chatMessageQueue) { pruneExpiredEventsLocked(now); if (chatMessageQueue.size >= MAX_CHAT_QUEUE_SIZE) chatMessageQueue.poll(); chatMessageQueue.add(ChatEvent(++nextChatSequence, now, plain)); (chatMessageQueue as Object).notifyAll() }
        }
        @JvmStatic fun waitForChatMessage(messageSubstring: String) = waitForChatMessage(null, messageSubstring, MESSAGE_TIMEOUT_MS)
        @JvmStatic fun waitForChatMessage(window: ChatWindow?, messageSubstring: String, timeoutMs: Long) = waitForChatMessageMatching(window, Predicate { it.contains(messageSubstring) }, timeoutMs)
        private fun waitForChatMessageMatchingInternal(window: ChatWindow?, matcher: Predicate<String>, timeoutMs: Long): Boolean {
            val startTime = System.currentTimeMillis()
            synchronized(chatMessageQueue) {
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    pruneExpiredEventsLocked(System.currentTimeMillis())
                    val it = chatMessageQueue.iterator()
                    while (it.hasNext()) { val event = it.next(); if (matchesWindow(event, window) && matcher.test(event.message)) { it.remove(); return true } }
                    try { val remainingTime = timeoutMs - (System.currentTimeMillis() - startTime); if (remainingTime > 0) (chatMessageQueue as Object).wait(Math.min(remainingTime, 100L)) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
                }
            }
            return false
        }
        @JvmStatic fun hasReceivedMessage(messageSubstring: String) = hasReceivedMessage(null, messageSubstring)
        @JvmStatic fun hasReceivedMessage(window: ChatWindow?, messageSubstring: String) = hasReceivedMessageMatching(window, Predicate { it.contains(messageSubstring) })
        @JvmStatic fun hasReceivedMessageMatching(matcher: Predicate<String>) = hasReceivedMessageMatching(null, matcher)
        @JvmStatic fun hasReceivedMessageMatching(window: ChatWindow?, matcher: Predicate<String>): Boolean {
            synchronized(chatMessageQueue) { pruneExpiredEventsLocked(System.currentTimeMillis()); val it = chatMessageQueue.iterator(); while (it.hasNext()) { val event = it.next(); if (matchesWindow(event, window) && matcher.test(event.message)) { it.remove(); return true } } }
            return false
        }
        @JvmStatic fun waitForChatMessageMatching(matcher: Predicate<String>, timeoutMs: Long) = waitForChatMessageMatching(null, matcher, timeoutMs)
        @JvmStatic fun waitForChatMessageMatching(window: ChatWindow?, matcher: Predicate<String>, timeoutMs: Long) = waitForChatMessageMatchingInternal(window, matcher, timeoutMs)
        @JvmStatic fun isMessageReceived(messageSubstring: String) = hasReceivedMessage(messageSubstring)
        @JvmStatic fun shouldSkipSetSpawn() = AetherConfig.MACRO_DISABLE_SETSPAWN.get()
        @JvmStatic fun setSpawn(): Boolean = setSpawn(MESSAGE_TIMEOUT_MS)
        @JvmStatic fun setSpawn(timeoutMs: Long): Boolean { if (shouldSkipSetSpawn()) return true; val window = beginChatWindow(); ClientUtils.sendCommand("/setspawn"); val success = waitForChatMessage(window, "Your spawn location has been set!", timeoutMs); if (success) ClientUtils.sendDebugMessage("Spawn set has been detected"); return success }
        @JvmStatic fun initiateSetSpawn() { if (!shouldSkipSetSpawn()) ClientUtils.sendCommand("/setspawn") }
        @JvmStatic fun hasSpawnBeenSet(): Boolean = hasSpawnBeenSet(null)
        @JvmStatic fun hasSpawnBeenSet(window: ChatWindow?) = shouldSkipSetSpawn() || hasReceivedMessage(window, "Your spawn location has been set!")
        @JvmStatic fun hasPlotTp(plotNumber: String) = hasPlotTp(null, plotNumber)
        @JvmStatic fun hasPlotTp(window: ChatWindow?, plotNumber: String) = hasReceivedMessage(window, "Teleported you to Plot - $plotNumber")
        @JvmStatic fun warpGarden(): Boolean {
            val client = Minecraft.getInstance(); if (client.player == null) return false
            val startPos = client.player!!.position(); val window = beginChatWindow(); FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toLong()); ClientUtils.sendCommand("/warp garden")
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < MESSAGE_TIMEOUT_MS) {
                if (hasWarpedGarden(window)) { ClientUtils.sendDebugMessage("/warp garden success (chat)"); return true }
                if (client.player != null) { val dist = client.player!!.position().distanceTo(startPos); if (dist > 10) { if (ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN) { ClientUtils.sendDebugMessage("/warp garden success (pos fallback, dist: " + String.format("%.1f", dist) + ")"); return true } } }
                try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
            return false
        }
        @JvmStatic fun initiateWarpGarden() { FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toLong()); ClientUtils.sendCommand("/warp garden") }
        @JvmStatic fun hasWarpedGarden(): Boolean = hasWarpedGarden(null)
        @JvmStatic fun hasWarpedGarden(window: ChatWindow?) = hasReceivedMessage(window, "Warping...")
        @JvmStatic fun plotTp(plotNumber: String): Boolean {
            val client = Minecraft.getInstance(); if (client.player == null) return false
            val startPos = client.player!!.position(); val window = beginChatWindow()
            if (System.currentTimeMillis() - lastPlotTpTime > 5000) { FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toLong()); ClientUtils.sendCommand("/plottp $plotNumber"); lastPlotTpTime = System.currentTimeMillis() } else ClientUtils.sendDebugMessage("Skipping plottp (cooldown)")
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < MESSAGE_TIMEOUT_MS) {
                if (hasPlotTp(window, plotNumber)) { ClientUtils.sendDebugMessage("plottp success (chat)"); return true }
                if (client.player != null) { val dist = client.player!!.position().distanceTo(startPos); if (dist > 10) { ClientUtils.sendDebugMessage("plottp success (pos fallback, dist: " + String.format("%.1f", dist) + ")"); return true } }
                try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
            return false
        }
        @JvmStatic fun initiatePlotTp(plotNumber: String) { if (System.currentTimeMillis() - lastPlotTpTime > 1000) { FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toLong()); ClientUtils.sendCommand("/plottp $plotNumber"); lastPlotTpTime = System.currentTimeMillis() } else ClientUtils.sendDebugMessage("Skipping initiatePlotTp (cooldown)") }
        @JvmStatic fun hasPlotTp(): Boolean = hasPlotTp(null)
        @JvmStatic fun hasPlotTp(window: ChatWindow?) = hasReceivedMessage(window, "Teleported you to Plot")
        @JvmStatic fun getFreshKnownPlotChat() = getFreshKnownPlotChat(PLOT_CHAT_FRESHNESS_MS)
        @JvmStatic fun getFreshKnownPlotChat(maxAgeMs: Long): String? { val plot = lastKnownPlotChat; val seenAt = lastKnownPlotChatAt; if (plot.isBlank() || plot.equals("Unknown", true) || System.currentTimeMillis() - seenAt > maxAgeMs) return null; return plot }
        @JvmStatic fun isFreshKnownPlotChat(plotNumber: String) = isFreshKnownPlotChat(plotNumber, PLOT_CHAT_FRESHNESS_MS)
        @JvmStatic fun isFreshKnownPlotChat(plotNumber: String, maxAgeMs: Long) = getFreshKnownPlotChat(maxAgeMs)?.equals(plotNumber, true) ?: false
        @JvmStatic fun clearMessageQueue() { synchronized(chatMessageQueue) { chatMessageQueue.clear() } }
        private fun matchesWindow(event: ChatEvent, window: ChatWindow?) = window == null || event.sequence > window.startSequence && event.timeMs >= window.startTimeMs
        private fun pruneExpiredEventsLocked(now: Long) { while (!chatMessageQueue.isEmpty()) { val oldest = chatMessageQueue.peek(); if (oldest == null || now - oldest.timeMs <= MAX_CHAT_EVENT_AGE_MS) break; chatMessageQueue.poll() } }
    }
}
