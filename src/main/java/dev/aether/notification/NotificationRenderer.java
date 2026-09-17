package dev.aether.notification;

import dev.aether.hud.HudStyle;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NotificationRenderer {
    private static final float WIDTH = 320f;
    private static final float ICON_SIZE = 16f;
    private static final float ICON_TILE = 22f;
    private static final float ICON_TILE_X = HudStyle.PAD - 3f;
    private static final float ICON_TILE_Y = 11f;
    private static final float TEXT_X = ICON_TILE_X + ICON_TILE + 8f;
    private static final float TITLE_SIZE = 12f;
    private static final float TITLE_Y = ICON_TILE_Y + (ICON_TILE - TITLE_SIZE) / 2f;
    private static final float MESSAGE_SIZE = 11f;
    private static final float MESSAGE_Y = TITLE_Y + TITLE_SIZE + 3f;
    private static final float LINE_STEP = 15f;
    private static final float BOTTOM_PAD = 13f;
    private static final float TITLE_ONLY_HEIGHT = 44f;
    private static final Map<Notification, ToastLayout> layouts = new HashMap<>();
    private static long lastFrameNanos;

    private NotificationRenderer() {}

    public static void render(NVGRenderer nvg, float screenWidth, float screenHeight) {
        // Minecraft tick deltas and GUI partial ticks use different units; share one real-time clock.
        long now = System.nanoTime();
        float deltaTime = lastFrameNanos == 0L ? 0f
                : Math.clamp((now - lastFrameNanos) / 1_000_000_000f, 0f, 0.1f);
        lastFrameNanos = now;
        NotificationManager.update(deltaTime);

        List<Notification> notifications = NotificationManager.getNotifications();
        if (notifications.isEmpty()) {
            layouts.clear();
            lastFrameNanos = 0L;
            return;
        }

        float width = Math.min(WIDTH, screenWidth - NotificationManager.MARGIN * 2);
        if (width <= TEXT_X + HudStyle.PAD) return;
        float targetY = NotificationManager.MARGIN;
        float follow = 1f - (float) Math.exp(-18f * deltaTime);
        int visible = 0;
        for (Notification notification : notifications) {
            if (visible++ >= NotificationManager.MAX_VISIBLE) break;
            ToastLayout layout = layouts.get(notification);
            if (layout == null) {
                layout = new ToastLayout(targetY);
                layouts.put(notification, layout);
            }
            layout.measure(nvg, notification, width, screenHeight);
            layout.y += (targetY - layout.y) * follow;
            renderNotification(nvg, notification, layout, screenWidth);
            targetY += layout.height + NotificationManager.SPACING;
        }
        layouts.keySet().removeIf(n -> !notifications.contains(n));
    }

    private static void renderNotification(NVGRenderer nvg, Notification notification,
                                           ToastLayout layout, float screenWidth) {
        float progress = Math.clamp(notification.getAnimProgress(), 0f, 1f);
        float visibility = progress * progress * (3f - 2f * progress);
        if (visibility <= 0f) return;
        float x = screenWidth - NotificationManager.MARGIN - layout.width + (1f - visibility) * 28f;
        int accent = notification.getType().color();

        nvg.save();
        nvg.translate(x, layout.y);
        nvg.globalAlpha(visibility);
        HudStyle.panel(nvg, layout.width, layout.height);
        HudStyle.accent(nvg, layout.width, accent, accent);

        float iconInset = (ICON_TILE - ICON_SIZE) / 2f;
        nvg.roundedRect(ICON_TILE_X, ICON_TILE_Y, ICON_TILE, ICON_TILE, 5f, HudStyle.alpha(accent, 0.12f));
        nvg.renderSVG(notification.getType().iconPath, ICON_TILE_X + iconInset, ICON_TILE_Y + iconInset,
                ICON_SIZE, ICON_SIZE, accent);

        float textWidth = layout.width - TEXT_X - HudStyle.PAD;
        HudStyle.text(nvg, Fonts.BOLD, layout.title, TEXT_X, TITLE_Y, textWidth, TITLE_SIZE, Theme.HUD_TITLE);
        for (int i = 0; i < layout.lines.size(); i++) {
            HudStyle.text(nvg, Fonts.REGULAR, layout.lines.get(i), TEXT_X, MESSAGE_Y + i * LINE_STEP,
                    textWidth, MESSAGE_SIZE, Theme.HUD_LABEL);
        }

        if (notification.getDurationMs() > 0) {
            float barWidth = layout.width - HudStyle.PAD * 2;
            float barY = layout.height - 7f;
            nvg.roundedRect(HudStyle.PAD, barY, barWidth, 2f, 1f, Theme.HUD_BAR_BG);
            float filled = barWidth * (1f - notification.getLifetimeProgress());
            if (filled > 0f) nvg.roundedRect(HudStyle.PAD, barY, filled, 2f, 1f, HudStyle.alpha(accent, 0.7f));
        }
        nvg.restore();
    }

    private static float heightForLines(int lines) {
        if (lines <= 0) return TITLE_ONLY_HEIGHT;
        return MESSAGE_Y + MESSAGE_SIZE + (lines - 1) * LINE_STEP + BOTTOM_PAD;
    }

    private static final class ToastLayout {
        private float y;
        private float width;
        private float height;
        private float screenHeight;
        private String title;
        private String message;
        private List<String> lines = List.of();

        private ToastLayout(float y) { this.y = y; }

        private void measure(NVGRenderer nvg, Notification notification, float width, float screenHeight) {
            if (this.width == width && this.screenHeight == screenHeight
                    && java.util.Objects.equals(title, notification.getTitle())
                    && java.util.Objects.equals(message, notification.getMessage())) return;
            this.width = width;
            this.screenHeight = screenHeight;
            title = notification.getTitle();
            message = notification.getMessage();
            lines = notification.hasMessage()
                    ? nvg.wrapTextToWidth(Fonts.REGULAR, message, MESSAGE_SIZE, width - TEXT_X - HudStyle.PAD)
                    : List.of();
            float available = screenHeight - NotificationManager.MARGIN * 2 - heightForLines(1);
            int maxLines = Math.max(1, (int) (available / LINE_STEP) + 1);
            if (lines.size() > maxLines) {
                lines = new java.util.ArrayList<>(lines.subList(0, maxLines));
                lines.set(maxLines - 1, lines.getLast() + "...");
            }
            height = heightForLines(lines.size());
        }
    }
}
