package dev.aether.ui;

import dev.aether.macro.ReconnectScheduler;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NVGScreen;
import dev.aether.ui.components.Button;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * A NanoVG-rendered screen displayed during dynamic rest periods.
 *
 * <p>Shows a centered panel with:
 * <ul>
 *   <li>Rest countdown timer</li>
 *   <li>Progress bar</li>
 *   <li>Cancel button to abort rest and return to title screen</li>
 * </ul>
 */
public class DynamicRestScreen extends NVGScreen {
    private static final String DEFAULT_SCREEN_TITLE = "Dynamic Rest";
    private static final String DEFAULT_PANEL_TITLE = "Dynamic Rest";
    private static final String DEFAULT_STATUS_LABEL = "reconnecting in";
    private static final String DEFAULT_CANCEL_LABEL = "Cancel Rest & Exit to Menu";

    // -- Constants -------------------------------------------------------------

    private static final float PANEL_W = 260f;
    private static final float PANEL_H = 120f;
    private static final float RADIUS = 8f;
    private static final float BAR_H = 8f;
    private static final float BAR_RADIUS = 4f;

    // Colors matching the original DynamicRestScreen style
    private static final int BG_COLOR       = 0xFF141424;
    private static final int BAR_BG_COLOR   = 0xFF1A1A32;
    private static final int BAR_FILL_COLOR = Theme.ACCENT_PRIMARY;
    private static final int SEPARATOR_COLOR = Theme.ACCENT_PRIMARY;
    private static final int RECONNECT_COLOR = 0xFFAA00;

    // -- State ------------------------------------------------------------------

    private final long restEndTimeMs;
    private final long totalDurationMs;
    private final String panelTitle;
    private final String statusLabel;
    private final String cancelButtonLabel;

    private Button cancelButton;

    // -- Construction ----------------------------------------------------------

    public DynamicRestScreen(long restEndTimeMs, long totalDurationMs) {
        this(DEFAULT_SCREEN_TITLE, DEFAULT_PANEL_TITLE, DEFAULT_STATUS_LABEL, DEFAULT_CANCEL_LABEL,
                restEndTimeMs, totalDurationMs);
    }

    public DynamicRestScreen(
            String screenTitle,
            String panelTitle,
            String statusLabel,
            String cancelButtonLabel,
            long restEndTimeMs,
            long totalDurationMs
    ) {
        super(screenTitle);
        this.restEndTimeMs = restEndTimeMs;
        this.totalDurationMs = totalDurationMs;
        this.panelTitle = panelTitle;
        this.statusLabel = statusLabel;
        this.cancelButtonLabel = cancelButtonLabel;
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    protected void initNVG() {
        float btnW = 200f;
        float btnH = 24f;
        float btnX = (width - btnW) / 2f;
        float btnY = (height + PANEL_H) / 2f + 20f;

        cancelButton = addComponent(Button.builder(cancelButtonLabel, btnW, btnH)
                .accent(true)
                .onPress(() -> {
                    ReconnectScheduler.cancel();
                    if (minecraft != null) {
                        minecraft.setScreen(new TitleScreen());
                    }
                })
                .build());
        cancelButton.setPosition(btnX, btnY);
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    protected void renderNVG(NVGRenderer nvg) {
        // Dark background fill
        nvg.rect(0, 0, width, height, 0xFF0A0A14);

        // Calculate remaining time and progress
        long now = System.currentTimeMillis();
        long remainingMs = Math.max(0, restEndTimeMs - now);
        float progress = totalDurationMs > 0
                ? (float) (totalDurationMs - remainingMs) / (float) totalDurationMs
                : 1.0f;

        // Panel position (centered)
        float panelX = (width - PANEL_W) / 2f;
        float panelY = (height - PANEL_H) / 2f - 20f;

        // Draw panel background with shadow
        nvg.shadow(panelX, panelY, PANEL_W, PANEL_H, RADIUS, 12f, 0x40000000);
        nvg.roundedRect(panelX, panelY, PANEL_W, PANEL_H, RADIUS, BG_COLOR);

        // Title (centered)
        float titleSize = 16f;
        nvg.text(Fonts.BOLD, panelTitle,
                panelX + (PANEL_W - nvg.textWidth(Fonts.BOLD, panelTitle, titleSize)) / 2f,
                panelY + 12f,
                titleSize, Colors.WHITE);

        // Separator line
        nvg.rect(panelX + 10, panelY + 34, PANEL_W - 20, 1, SEPARATOR_COLOR);

        // Status row: "reconnecting in" label and time
        long totalSecs = remainingMs / 1000;
        String timeStr = String.format("%02d:%02d", (totalSecs / 60) % 60, totalSecs % 60);

        float labelSize = 13f;
        float timeSize = 13f;

        nvg.text(Fonts.REGULAR, statusLabel, panelX + 12, panelY + 48, labelSize, Colors.TEXT_DIM);
        nvg.text(Fonts.MONO, timeStr,
                panelX + PANEL_W - 12 - nvg.textWidth(Fonts.MONO, timeStr, timeSize),
                panelY + 48,
                timeSize, Colors.WHITE);

        // Progress bar
        float barX = panelX + 12;
        float barY = panelY + 72;
        float barW = PANEL_W - 24;

        // Bar background
        nvg.roundedRect(barX, barY, barW, BAR_H, BAR_RADIUS, BAR_BG_COLOR);

        // Bar fill (animated progress)
        float fillW = barW * Math.max(0f, Math.min(1f, progress));
        if (fillW > 0) {
            nvg.roundedRect(barX, barY, fillW, BAR_H, BAR_RADIUS, BAR_FILL_COLOR);
        }

        // "Reconnecting now..." message when timer expires
        if (remainingMs <= 0) {
            String reconnecting = "Reconnecting now...";
            float reconnectSize = 14f;
            nvg.text(Fonts.REGULAR, reconnecting,
                    (width - nvg.textWidth(Fonts.REGULAR, reconnecting, reconnectSize)) / 2f,
                    panelY + PANEL_H + 12,
                    reconnectSize, RECONNECT_COLOR);
        }
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
