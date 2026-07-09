package dev.aether.notification;

/**
 * Represents a single notification to be displayed.
 *
 * <p>Notifications have a title, optional message, type (info/success/warning/error),
 * and automatic dismissal after a configurable duration.</p>
 */
public class Notification {

    public enum Type {
        INFO(0xFF6366F1, "/assets/aether/icons/info.svg"),
        SUCCESS(0xFF10B981, "/assets/aether/icons/success.svg"),
        WARNING(0xFFF59E0B, "/assets/aether/icons/warning.svg"),
        ERROR(0xFFEF4444, "/assets/aether/icons/error.svg");

        public final int color;
        public final String iconPath;

        Type(int color, String iconPath) {
            this.color = color;
            this.iconPath = iconPath;
        }
    }

    private String title;
    private String message;
    private Type type;
    private long createdAt;
    private final long durationMs;
    private final boolean dismissible;

    // Animation state - using single float for smooth interpolation
    private float animProgress = 0f;
    private boolean dismissing = false;

    /**
     * Creates a new notification.
     *
     * @param title      The main title text
     * @param message    Optional subtitle/description (can be null or empty)
     * @param type       Notification type (determines color and icon)
     * @param durationMs Time before auto-dismiss (0 = no auto-dismiss)
     * @param dismissible Whether clicking dismisses the notification
     */
    public Notification(String title, String message, Type type, long durationMs, boolean dismissible) {
        this.title = title;
        this.message = message;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
        this.durationMs = durationMs;
        this.dismissible = dismissible;
    }

    // Convenience constructors
    public Notification(String title, String message, Type type, long durationMs) {
        this(title, message, type, durationMs, true);
    }

    public Notification(String title, String message, Type type) {
        this(title, message, type, 4000, true);
    }

    public Notification(String title, Type type) {
        this(title, null, type, 4000, true);
    }

    // -- Getters ---------------------------------------------------------------

    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public Type getType() { return type; }
    public long getCreatedAt() { return createdAt; }
    public long getDurationMs() { return durationMs; }
    public boolean isDismissible() { return dismissible; }
    public boolean hasMessage() { return message != null && !message.isEmpty(); }

    // -- Animation state (single progress value for efficiency) ----------------

    /** Returns animation progress (0-1 for entry, negative for dismiss) */
    public float getAnimProgress() { return animProgress; }
    public void setAnimProgress(float progress) { this.animProgress = progress; }

    public boolean isDismissing() { return dismissing; }
    public void setDismissing(boolean dismissing) { this.dismissing = dismissing; }

    // -- Lifecycle --------------------------------------------------------------

    /** @return true if this notification should be removed */
    public boolean isExpired() {
        if (durationMs <= 0) return false;
        return System.currentTimeMillis() - createdAt >= durationMs;
    }

    /** @return Progress through the notification's lifetime (0.0 - 1.0) */
    public float getLifetimeProgress() {
        if (durationMs <= 0) return 0f;
        return Math.min(1f, (float)(System.currentTimeMillis() - createdAt) / durationMs);
    }

    /** Starts the dismiss animation. */
    public void dismiss() {
        if (!dismissing) {
            dismissing = true;
        }
    }

    /** Updates the visible content in place without resetting the countdown. */
    public void update(String title, String message, Type type) {
        this.title = title;
        this.message = message;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
        this.dismissing = false;
    }
}