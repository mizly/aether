package dev.aether.notification;

import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.util.AetherLang;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton manager for the notification system.
 *
 * <p>Handles notification queue, lifecycle, and provides convenience methods
 * for showing notifications from anywhere in the codebase.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 *   NotificationManager.info("Connected to server");
 *   NotificationManager.success("Item sold!", "Sold 64x Diamond for 1,024 coins");
 *   NotificationManager.warning("Low inventory space", "3 slots remaining");
 *   NotificationManager.error("Connection lost", "Reconnecting in 5 seconds...");
 * }</pre>
 */
public final class NotificationManager {

    private NotificationManager() {}

    // -- Configuration ---------------------------------------------------------

    /** Maximum number of notifications displayed at once. */
    public static int MAX_VISIBLE = 5;

    /** Default duration for auto-dismiss (ms). */
    public static long DEFAULT_DURATION = 4000;

    /** Animation duration for slide-in/out (ms). */
    public static long ANIMATION_DURATION_MS = 250;

    /** Spacing between notifications (px). */
    public static float SPACING = 8f;

    /** Margin from screen edge (px). */
    public static float MARGIN = 16f;

    // -- State ------------------------------------------------------------------

    private static final List<Notification> notifications = new CopyOnWriteArrayList<>();

    // Pre-calculated animation speed (progress per ms)
    public static final float ANIM_SPEED = 1f / ANIMATION_DURATION_MS;

    // -- Public API -------------------------------------------------------------

    /**
     * Shows a notification with full control over all parameters.
     */
    public static Notification show(String title, String message, Notification.Type type, long durationMs) {
        if (StreamerModeManager.isEnabled()) {
            return null;
        }
        Notification notification = new Notification(AetherLang.localize(title),
                message == null ? null : AetherLang.localize(message),
                type, durationMs, true);
        notifications.add(notification);
        return notification;
    }

    public static Notification show(String title, String message, Notification.Type type) {
        return show(title, message, type, DEFAULT_DURATION);
    }

    public static Notification replace(Notification existing, String title, String message, Notification.Type type) {
        if (StreamerModeManager.isEnabled()) {
            if (existing != null) {
                notifications.remove(existing);
            }
            return null;
        }

        if (existing != null && notifications.contains(existing) && !existing.isExpired() && !existing.isDismissing()) {
            existing.update(title, message, type);
            return existing;
        }

        if (existing != null) {
            notifications.remove(existing);
        }

        return show(title, message, type);
    }

    // -- Convenience methods ----------------------------------------------------

    public static Notification info(String title) {
        return show(title, null, Notification.Type.INFO);
    }

    public static Notification info(String title, String message) {
        return show(title, message, Notification.Type.INFO);
    }

    public static Notification info(String title, String message, long durationMs) {
        return show(title, message, Notification.Type.INFO, durationMs);
    }

    public static Notification success(String title) {
        return show(title, null, Notification.Type.SUCCESS);
    }

    public static Notification success(String title, String message) {
        return show(title, message, Notification.Type.SUCCESS);
    }

    public static Notification success(String title, String message, long durationMs) {
        return show(title, message, Notification.Type.SUCCESS, durationMs);
    }

    public static Notification warning(String title) {
        return show(title, null, Notification.Type.WARNING);
    }

    public static Notification warning(String title, String message) {
        return show(title, message, Notification.Type.WARNING);
    }

    public static Notification warning(String title, String message, long durationMs) {
        return show(title, message, Notification.Type.WARNING, durationMs);
    }

    public static Notification error(String title) {
        return show(title, null, Notification.Type.ERROR);
    }

    public static Notification error(String title, String message) {
        return show(title, message, Notification.Type.ERROR);
    }

    public static Notification error(String title, String message, long durationMs) {
        return show(title, message, Notification.Type.ERROR, durationMs);
    }

    // -- Management -------------------------------------------------------------

    public static List<Notification> getNotifications() {
        return notifications;
    }

    public static int getCount() {
        return notifications.size();
    }

    public static void clearAll() {
        for (Notification n : notifications) {
            n.dismiss();
        }
    }

    public static void remove(Notification notification) {
        notifications.remove(notification);
    }

    // -- Update (called each frame by renderer) ---------------------------------

    /**
     * Updates all notifications' animation states and removes completed ones.
     * Optimized for minimal allocations and calculations.
     *
     * @param deltaTime Frame delta time in seconds
     */
    public static void update(float deltaTime) {
        if (notifications.isEmpty()) return;

        // Convert to ms for animation calculations
        float deltaMs = deltaTime * 1000f;
        float progressDelta = deltaMs * ANIM_SPEED;

        // Use a list to collect items to remove (CopyOnWriteArrayList doesn't support iterator.remove)
        List<Notification> toRemove = new java.util.ArrayList<>();

        for (Notification n : notifications) {
            float progress = n.getAnimProgress();

            if (n.isDismissing()) {
                // Dismiss animation - progress goes from current to 0
                progress -= progressDelta;
                if (progress <= 0f) {
                    toRemove.add(n);
                    continue;
                }
            } else {
                // Entry animation - progress goes from 0 to 1
                if (progress < 1f) {
                    progress = Math.min(1f, progress + progressDelta);
                }
                // Check for auto-dismiss after entry animation completes
                if (progress >= 1f && n.isExpired()) {
                    n.setDismissing(true);
                }
            }

            n.setAnimProgress(progress);
        }

        // Remove completed notifications
        if (!toRemove.isEmpty()) {
            notifications.removeAll(toRemove);
        }
    }

    // -- Layout helpers (pre-calculated for performance) ------------------------

    private static final float BASE_WIDTH = 280f;
    private static final float BASE_HEIGHT = 48f;
    private static final float MESSAGE_HEIGHT = 20f;
    private static final float MESSAGE_LINE_STEP = 14f;
    private static final float MAX_WIDTH = 350f;
    private static final float PADDING_X = 14f;
    private static final float ICON_SIZE = 18f;
    private static final float ICON_MARGIN = 10f;
    private static final float TEXT_LEFT_OFFSET = PADDING_X + ICON_SIZE + ICON_MARGIN;
    private static final float TEXT_RIGHT_PADDING = PADDING_X;
    private static final float MESSAGE_FONT_SIZE = 11f;

    public static float calculateWidth(Notification n) {
        if (n.hasMessage() && n.getMessage().length() > 30) {
            return Math.min(MAX_WIDTH, BASE_WIDTH - 80f + n.getMessage().length() * 3f);
        }
        return BASE_WIDTH;
    }

    public static float calculateHeight(Notification n) {
        if (!n.hasMessage()) {
            return BASE_HEIGHT;
        }

        float width = calculateWidth(n);
        float availableTextWidth = Math.max(0f, width - TEXT_LEFT_OFFSET - TEXT_RIGHT_PADDING);
        int lineCount = estimateWrappedLineCount(n.getMessage(), availableTextWidth, MESSAGE_FONT_SIZE);
        return BASE_HEIGHT + MESSAGE_HEIGHT + Math.max(0, lineCount - 1) * MESSAGE_LINE_STEP;
    }

    private static int estimateWrappedLineCount(String text, float maxWidth, float fontSize) {
        if (text == null || text.isBlank() || maxWidth <= 0f) {
            return 1;
        }

        float avgCharWidth = fontSize * 0.52f;
        int maxChars = Math.max(1, (int) Math.floor(maxWidth / avgCharWidth));
        int lines = 0;

        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                lines++;
                continue;
            }

            int paragraphLines = 1;
            int current = 0;
            for (String word : paragraph.trim().split("\\s+")) {
                int wordLength = word.length();
                if (current == 0) {
                    current = wordLength;
                    paragraphLines += Math.max(0, (wordLength - 1) / maxChars);
                    current = ((wordLength - 1) % maxChars) + 1;
                } else if (current + 1 + wordLength <= maxChars) {
                    current += 1 + wordLength;
                } else {
                    paragraphLines++;
                    paragraphLines += Math.max(0, (wordLength - 1) / maxChars);
                    current = ((wordLength - 1) % maxChars) + 1;
                }
            }

            lines += paragraphLines;
        }

        return Math.max(1, lines);
    }

    // -- Easing function (optimized) ---------------------------------------------

    /** Fast ease-out cubic for smooth animations */
    public static float easeOutCubic(float t) {
        float f = t - 1f;
        return f * f * f + 1f;
    }
}
