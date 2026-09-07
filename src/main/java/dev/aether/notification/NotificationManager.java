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
    public static long ANIMATION_DURATION_MS = 320;

    /** Spacing between notifications (px). */
    public static float SPACING = 8f;

    /** Margin from screen edge (px). */
    public static float MARGIN = 16f;

    // -- State ------------------------------------------------------------------

    private static final List<Notification> notifications = new CopyOnWriteArrayList<>();

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
            existing.update(AetherLang.localize(title), message == null ? null : AetherLang.localize(message), type);
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
        float deltaMs = Math.max(0f, deltaTime) * 1000f;
        float progressDelta = deltaMs / Math.max(1L, ANIMATION_DURATION_MS);

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

}
