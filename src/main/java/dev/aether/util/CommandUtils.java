package dev.aether.util;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.modules.failsafe.FailsafeManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.function.Predicate;

public class CommandUtils {
    private static final int MAX_CHAT_QUEUE_SIZE = 256;
    private static final long MAX_CHAT_EVENT_AGE_MS = 15_000L;
    private static final long PLOT_CHAT_FRESHNESS_MS = 10_000L;
    private static final Queue<ChatEvent> chatMessageQueue = new ArrayDeque<>(MAX_CHAT_QUEUE_SIZE);
    private static final long MESSAGE_TIMEOUT_MS = 10000; // 10 second timeout
    private static long lastPlotTpTime = 0;
    private static long nextChatSequence = 0;
    /** Tracked from chat messages like "Teleported you to Plot - 6!" */
    public static volatile String lastKnownPlotChat = "Unknown";
    public static volatile long lastKnownPlotChatAt = 0L;

    public static final class ChatWindow {
        private final long startSequence;
        private final long startTimeMs;

        private ChatWindow(long startSequence, long startTimeMs) {
            this.startSequence = startSequence;
            this.startTimeMs = startTimeMs;
        }
    }

    private static final class ChatEvent {
        private final long sequence;
        private final long timeMs;
        private final String message;

        private ChatEvent(long sequence, long timeMs, String message) {
            this.sequence = sequence;
            this.timeMs = timeMs;
            this.message = message;
        }
    }

    public static ChatWindow beginChatWindow() {
        synchronized (chatMessageQueue) {
            long now = System.currentTimeMillis();
            pruneExpiredEventsLocked(now);
            return new ChatWindow(nextChatSequence, now);
        }
    }

    /**
     * Register a chat message to the queue.
     * This is called from AetherClient when a chat message is received.
     */
    public static void onChatMessage(String message) {
        String plain = message == null ? "" : message.trim();
        long now = System.currentTimeMillis();
        if (plain.contains("Teleported you to Plot - ")) {
            String plot = plain.substring(plain.indexOf("-") + 1).trim();
            if (plot.endsWith("!")) plot = plot.substring(0, plot.length() - 1);
            lastKnownPlotChat = plot;
            lastKnownPlotChatAt = now;
        }

        synchronized (chatMessageQueue) {
            pruneExpiredEventsLocked(now);
            if (chatMessageQueue.size() >= MAX_CHAT_QUEUE_SIZE) {
                chatMessageQueue.poll();
            }
            chatMessageQueue.add(new ChatEvent(++nextChatSequence, now, plain));
            chatMessageQueue.notifyAll();
        }
    }

    /**
     * Wait for a chat message containing the specified substring.
     * Blocks until the message is found or timeout is reached.
     *
     * @param client           The Minecraft instance
     * @param messageSubstring The substring to search for in chat messages
     * @return true if the message was found, false if timeout occurred
     */
    public static boolean waitForChatMessage(String messageSubstring) {
        return waitForChatMessage(null, messageSubstring, MESSAGE_TIMEOUT_MS);
    }

    public static boolean waitForChatMessage(ChatWindow window, String messageSubstring, long timeoutMs) {
        return waitForChatMessageMatching(window, msg -> msg.contains(messageSubstring), timeoutMs);
    }

    private static boolean waitForChatMessageMatchingInternal(ChatWindow window, Predicate<String> matcher, long timeoutMs) {
        long startTime = System.currentTimeMillis();

        synchronized (chatMessageQueue) {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                pruneExpiredEventsLocked(System.currentTimeMillis());
                Iterator<ChatEvent> it = chatMessageQueue.iterator();
                while (it.hasNext()) {
                    ChatEvent event = it.next();
                    if (matchesWindow(event, window) && matcher.test(event.message)) {
                        it.remove(); // Remove the matched message
                        return true;
                    }
                }

                try {
                    long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
                    if (remainingTime > 0) {
                        chatMessageQueue.wait(Math.min(remainingTime, 100));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * Non-blocking method to check if a message has been received.
     * Returns immediately with true if the message was found, false otherwise.
     *
     * @param messageSubstring The substring to search for
     * @return true if the message has been received, false otherwise
     */
    public static boolean hasReceivedMessage(String messageSubstring) {
        return hasReceivedMessage(null, messageSubstring);
    }

    public static boolean hasReceivedMessage(ChatWindow window, String messageSubstring) {
        return hasReceivedMessageMatching(window, msg -> msg.contains(messageSubstring));
    }

    /**
     * Non-blocking method to check if a queued message matches a predicate.
     * Returns immediately with true if found, false otherwise.
     *
     * @param matcher The predicate to match queued messages
     * @return true if a matching message was found, false otherwise
     */
    public static boolean hasReceivedMessageMatching(Predicate<String> matcher) {
        return hasReceivedMessageMatching(null, matcher);
    }

    public static boolean hasReceivedMessageMatching(ChatWindow window, Predicate<String> matcher) {
        synchronized (chatMessageQueue) {
            pruneExpiredEventsLocked(System.currentTimeMillis());
            Iterator<ChatEvent> it = chatMessageQueue.iterator();
            while (it.hasNext()) {
                ChatEvent event = it.next();
                if (matchesWindow(event, window) && matcher.test(event.message)) {
                    it.remove(); // Remove the matched message
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Wait for a queued message to match a predicate.
     * Blocks until found or timeout.
     *
     * @param matcher   The predicate to match queued messages
     * @param timeoutMs Timeout in milliseconds
     * @return true if matched message found before timeout
     */
    public static boolean waitForChatMessageMatching(Predicate<String> matcher, long timeoutMs) {
        return waitForChatMessageMatching(null, matcher, timeoutMs);
    }

    public static boolean waitForChatMessageMatching(ChatWindow window, Predicate<String> matcher, long timeoutMs) {
        return waitForChatMessageMatchingInternal(window, matcher, timeoutMs);
    }

    /**
     * Check if a message receipt is being awaited (non-blocking check for async
     * operations).
     *
     * @param messageSubstring The substring to check
     * @return true if the message has been received, false otherwise
     */
    public static boolean isMessageReceived(String messageSubstring) {
        return hasReceivedMessage(messageSubstring);
    }

    public static boolean shouldSkipSetSpawn() {
        return AetherConfig.MACRO_DISABLE_SETSPAWN.get();
    }

    /**
     * Execute /setspawn and wait for the confirmation message.
     * Blocks until the spawn location is confirmed or timeout occurs.
     *
     * @param client The Minecraft instance
     * @return true if spawn was set successfully, false if timeout occurred
     */
    public static boolean setSpawn() {
        if (shouldSkipSetSpawn()) {
            return true;
        }

        ChatWindow window = beginChatWindow();
        ClientUtils.sendCommand("/setspawn");

        boolean success = waitForChatMessage(window, "Your spawn location has been set!", MESSAGE_TIMEOUT_MS);

        if (success) {
            ClientUtils.sendDebugMessage("Spawn set has been detected");
        }

        return success;
    }

    /**
     * Initiate /setspawn command (non-blocking).
     * Check result with hasSpawnBeenSet().
     *
     * @param client The Minecraft instance
     */
    public static void initiateSetSpawn() {
        if (shouldSkipSetSpawn()) {
            return;
        }

        ClientUtils.sendCommand("/setspawn");
    }

    /**
     * Check if /setspawn has been confirmed (non-blocking).
     *
     * @return true if the spawn confirmation message was received
     */
    public static boolean hasSpawnBeenSet() {
        return hasSpawnBeenSet(null);
    }

    public static boolean hasSpawnBeenSet(ChatWindow window) {
        if (shouldSkipSetSpawn()) {
            return true;
        }

        return hasReceivedMessage(window, "Your spawn location has been set!");
    }

    /**
     * Check if /plottp has been confirmed with a specific plot number (non-blocking).
     *
     * @param plotNumber The plot number to check for
     * @return true if the warp confirmation message for that plot was received
     */
    public static boolean hasPlotTp(String plotNumber) {
        return hasPlotTp(null, plotNumber);
    }

    public static boolean hasPlotTp(ChatWindow window, String plotNumber) {
        return hasReceivedMessage(window, "Teleported you to Plot - " + plotNumber);
    }

    /**
     * Execute /warp garden and wait for the confirmation message or position
     * change.
     * Blocks until the warp is confirmed, a significant position change is
     * detected, or timeout occurs.
     *
     * @param client The Minecraft instance
     * @return true if warp was successful, false if timeout occurred
     */
    public static boolean warpGarden() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return false;

        net.minecraft.world.phys.Vec3 startPos = client.player.position();
        ChatWindow window = beginChatWindow();
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        ClientUtils.sendCommand("/warp garden");

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < MESSAGE_TIMEOUT_MS) {
            // Priority 1: Chat confirmation
            if (hasWarpedGarden(window)) {
                ClientUtils.sendDebugMessage("/warp garden success (chat)");
                return true;
            }

            // Priority 2: Position fallback (moved > 10 blocks and in Garden)
            if (client.player != null) {
                double dist = client.player.position().distanceTo(startPos);
                if (dist > 10) {
                    MacroState.Location loc = ClientUtils.getCurrentLocation();
                    if (loc == MacroState.Location.GARDEN) {
                        ClientUtils.sendDebugMessage("/warp garden success (pos fallback, dist: " + String.format("%.1f", dist) + ")");
                        return true;
                    }
                }
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    /**
     * Initiate /warp garden command (non-blocking).
     * Check result with hasWarpedGarden().
     *
     * @param client The Minecraft instance
     */
    public static void initiateWarpGarden() {
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        ClientUtils.sendCommand("/warp garden");
    }

    /**
     * Check if /warp garden has been confirmed (non-blocking).
     *
     * @return true if the warp confirmation message was received
     */
    public static boolean hasWarpedGarden() {
        return hasWarpedGarden(null);
    }

    public static boolean hasWarpedGarden(ChatWindow window) {
        return hasReceivedMessage(window, "Warping...");
    }

    /**
     * Execute /plottp and wait for the confirmation message or position change.
     * Blocks until the warp is confirmed, a significant position change is
     * detected, or timeout occurs.
     *
     * @param client     The Minecraft instance
     * @param plotNumber The plot number to warp to
     * @return true if warp was successful, false if timeout occurred
     */
    public static boolean plotTp(String plotNumber) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return false;

        net.minecraft.world.phys.Vec3 startPos = client.player.position();
        ChatWindow window = beginChatWindow();
        if (System.currentTimeMillis() - lastPlotTpTime > 5000) {
            FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
            ClientUtils.sendCommand("/plottp " + plotNumber);
            lastPlotTpTime = System.currentTimeMillis();
        } else {
            ClientUtils.sendDebugMessage("Skipping plottp (cooldown)");
        }

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < MESSAGE_TIMEOUT_MS) {
            // Priority 1: Chat confirmation
            if (hasPlotTp(window, plotNumber)) {
                ClientUtils.sendDebugMessage("plottp success (chat)");
                return true;
            }

            // Priority 2: Position fallback (moved > 10 blocks)
            if (client.player != null) {
                double dist = client.player.position().distanceTo(startPos);
                if (dist > 10) {
                    ClientUtils.sendDebugMessage("plottp success (pos fallback, dist: " + String.format("%.1f", dist) + ")");
                    return true;
                }
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    /**
     * Initiate /plottp command (non-blocking).
     * Check result with hasPlotTp().
     *
     * @param client     The Minecraft instance
     * @param plotNumber The plot number to warp to
     */
    public static void initiatePlotTp(String plotNumber) {
        if (System.currentTimeMillis() - lastPlotTpTime > 1000) {
            FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
            ClientUtils.sendCommand("/plottp " + plotNumber);
            lastPlotTpTime = System.currentTimeMillis();
        } else {
            ClientUtils.sendDebugMessage("Skipping initiatePlotTp (cooldown)");
        }
    }

    /**
     * Check if /plottp has been confirmed (non-blocking).
     *
     * @return true if the warp confirmation message was received
     */
    public static boolean hasPlotTp() {
        return hasPlotTp((ChatWindow) null);
    }

    public static boolean hasPlotTp(ChatWindow window) {
        return hasReceivedMessage(window, "Teleported you to Plot");
    }

    public static String getFreshKnownPlotChat() {
        return getFreshKnownPlotChat(PLOT_CHAT_FRESHNESS_MS);
    }

    public static String getFreshKnownPlotChat(long maxAgeMs) {
        String plot = lastKnownPlotChat;
        long seenAt = lastKnownPlotChatAt;
        if (plot == null || plot.isBlank() || plot.equalsIgnoreCase("Unknown")) {
            return null;
        }
        if (System.currentTimeMillis() - seenAt > maxAgeMs) {
            return null;
        }
        return plot;
    }

    public static boolean isFreshKnownPlotChat(String plotNumber) {
        return isFreshKnownPlotChat(plotNumber, PLOT_CHAT_FRESHNESS_MS);
    }

    public static boolean isFreshKnownPlotChat(String plotNumber, long maxAgeMs) {
        String freshPlot = getFreshKnownPlotChat(maxAgeMs);
        return freshPlot != null && freshPlot.equalsIgnoreCase(plotNumber);
    }

    /**
     * Clear any pending chat messages from the queue.
     */
    public static void clearMessageQueue() {
        synchronized (chatMessageQueue) {
            chatMessageQueue.clear();
        }
    }

    private static boolean matchesWindow(ChatEvent event, ChatWindow window) {
        if (window == null) {
            return true;
        }
        return event.sequence > window.startSequence && event.timeMs >= window.startTimeMs;
    }

    private static void pruneExpiredEventsLocked(long now) {
        while (!chatMessageQueue.isEmpty()) {
            ChatEvent oldest = chatMessageQueue.peek();
            if (oldest == null || now - oldest.timeMs <= MAX_CHAT_EVENT_AGE_MS) {
                break;
            }
            chatMessageQueue.poll();
        }
    }
}
