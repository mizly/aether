package dev.aether.notification;

import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders all active notifications using NanoVG.
 *
 * <p>Notifications are rendered in the top-right corner of the screen with
 * smooth slide-in/out animations, shadows, and a progress bar for auto-dismiss.</p>
 *
 * <p>Optimized for FPS: minimal state changes, pre-calculated values, and
 * batched rendering where possible.</p>
 */
public final class NotificationRenderer {

    private NotificationRenderer() {}

    // -- Layout constants  ---------------------

    private static final float PADDING_X = 14f;
    private static final float PADDING_Y = 10f;
    private static final float CORNER_RADIUS = 8f;
    private static final float ICON_SIZE = 18f;
    private static final float ICON_MARGIN = 10f;
    private static final float PROGRESS_BAR_HEIGHT = 2f;
    private static final float ACCENT_WIDTH = 3f;

    private static final float MESSAGE_FONT_SIZE = 11f;
    private static final float MESSAGE_LINE_STEP = 14f;
    private static final float TEXT_LEFT_OFFSET = PADDING_X + ICON_SIZE + ICON_MARGIN;

    // -- Pre-calculated colors  --------------

    private static final int BG_COLOR = 0xE6151515;
    private static final int SHADOW_COLOR = 0x30000000;
    private static final int TEXT_TITLE = 0xFFFFFFFF;
    private static final int TEXT_MESSAGE = 0xB0FFFFFF;
    private static final int PROGRESS_TRACK = 0x15FFFFFF;

    // -- Animated Y positions for smooth repositioning --------------------------

    private static final Map<Notification, Float> animatedYPositions = new HashMap<>();

    // -- Rendering --------------------------------------------------------------

    /**
     * Renders all active notifications. Must be called within an active NanoVG frame.
     *
     * @param nvg          The active NVGRenderer
     * @param screenWidth  Current screen width
     * @param screenHeight Current screen height (unused but kept for API consistency)
     * @param deltaTime    Frame delta time in seconds
     */
    public static void render(NVGRenderer nvg, float screenWidth, float screenHeight, float deltaTime) {
        // Update animation states first
        NotificationManager.update(deltaTime);

        List<Notification> notifications = NotificationManager.getNotifications();
        if (notifications.isEmpty()) {
            animatedYPositions.clear();
            return;
        }

        float startY = NotificationManager.MARGIN;
        int visibleCount = 0;
        int maxVisible = NotificationManager.MAX_VISIBLE;

        // Calculate target Y positions and animate towards them
        float currentY = startY;
        for (Notification n : notifications) {
            if (visibleCount >= maxVisible) break;

            float height = NotificationManager.calculateHeight(n);
            float targetY = currentY;
            
            // Get or initialize animated Y position
            float animatedY = animatedYPositions.getOrDefault(n, targetY);
            
            // Smoothly interpolate towards target Y
            float animSpeed = Math.min(1f, NotificationManager.ANIM_SPEED * 3f * deltaTime * 60f);
            animatedY += (targetY - animatedY) * animSpeed;
            animatedYPositions.put(n, animatedY);

            renderNotification(nvg, n, animatedY, screenWidth);
            
            currentY += height + NotificationManager.SPACING;
            visibleCount++;
        }

        // Clean up old entries
        animatedYPositions.keySet().removeIf(n -> !notifications.contains(n));
    }

    /**
     * Renders a single notification with minimal state changes.
     */
    private static void renderNotification(NVGRenderer nvg, Notification n, float y, float screenWidth) {
        float width = NotificationManager.calculateWidth(n);
        float height = NotificationManager.calculateHeight(n);

        // Calculate animation progress with easing
        float animProgress = NotificationManager.easeOutCubic(n.getAnimProgress());

        // Calculate position with slide animation
        float x = screenWidth - NotificationManager.MARGIN - width;
        float slideOffset = (1f - animProgress) * (width + NotificationManager.MARGIN);
        x += slideOffset;

        // Skip rendering if completely off-screen
        if (animProgress <= 0f) return;

        nvg.save();

        // Apply global alpha for fade effect
        if (animProgress < 1f) {
            nvg.globalAlpha(animProgress);
        }

        // -- Shadow (render first, underneath everything) ------------------------
        nvg.shadow(x, y, width, height, CORNER_RADIUS, 10f, SHADOW_COLOR);

        // -- Background (rounded) -------------------------------------------------
        nvg.roundedRect(x, y, width, height, CORNER_RADIUS, BG_COLOR);

        // -- Accent bar (left side, rounded on left corners only) -----------------
        int accentColor = n.getType().color;
        nvg.roundedRect(x, y + 10f, ACCENT_WIDTH, height - 20f, ACCENT_WIDTH / 2, accentColor);

        // -- Icon (using SVG) -----------------------------------------------------
        float iconX = x + PADDING_X;
        float iconY = y + PADDING_Y;
        nvg.renderSVG(n.getType().iconPath, iconX, iconY, ICON_SIZE, ICON_SIZE, accentColor);

        // -- Title ----------------------------------------------------------------
        float titleX = iconX + ICON_SIZE + ICON_MARGIN;
        float titleY = y + PADDING_Y;
        nvg.text(Fonts.BOLD, n.getTitle(), titleX, titleY, 13f, TEXT_TITLE);

        // -- Message (if present) -------------------------------------------------
        if (n.hasMessage()) {
            float messageY = titleY + 18f;
            float messageWidth = Math.max(0f, width - TEXT_LEFT_OFFSET - PADDING_X);
            List<String> messageLines = nvg.wrapTextToWidth(Fonts.REGULAR, n.getMessage(), MESSAGE_FONT_SIZE, messageWidth);
            for (int i = 0; i < messageLines.size(); i++) {
                nvg.text(Fonts.REGULAR, messageLines.get(i), titleX, messageY + (i * MESSAGE_LINE_STEP), MESSAGE_FONT_SIZE, TEXT_MESSAGE);
            }
        }

        // -- Progress bar (for auto-dismiss) --------------------------------------
        if (n.getDurationMs() > 0 && !n.isDismissing()) {
            float progress = n.getLifetimeProgress();
            float barY = y + height - PROGRESS_BAR_HEIGHT - 3f;
            float barWidth = width - PADDING_X * 2;

            // Background track
            nvg.roundedRect(x + PADDING_X, barY, barWidth, PROGRESS_BAR_HEIGHT, 1f, PROGRESS_TRACK);

            // Progress fill (shrinks from right to left)
            float fillWidth = barWidth * (1f - progress);
            if (fillWidth > 1f) {
                int progressColor = (0x60 << 24) | (accentColor & 0x00FFFFFF);
                nvg.roundedRect(x + PADDING_X, barY, fillWidth, PROGRESS_BAR_HEIGHT, 1f, progressColor);
            }
        }

        nvg.restore();
    }
}