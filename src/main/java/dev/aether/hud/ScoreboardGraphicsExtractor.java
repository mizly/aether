package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.util.ServerIdHider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.util.FormattedCharSequence;

final class ScoreboardGraphicsExtractor extends GuiGraphicsExtractor {
    private final int width;
    private final int height;
    private final ScoreboardDrawList drawList = new ScoreboardDrawList();

    ScoreboardGraphicsExtractor(Minecraft minecraft, int width, int height) {
        super(minecraft, new GuiRenderState(), 0, 0);
        this.width = width;
        this.height = height;
    }

    @Override public int guiWidth() { return width; }
    @Override public int guiHeight() { return height; }
    @Override public void nextStratum() { }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int color) {
        drawList.fill(x1, y1, x2, y2, color);
    }

    @Override
    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if ((color >>> 24) == 0) return;
        boolean heading = !drawList.hasText() || ScoreboardText.isServerAddress(text);
        FormattedCharSequence display = ScoreboardText.customize(text, !drawList.hasText(),
                AetherConfig.SCOREBOARD_TITLE_TEXT.get(), AetherConfig.SCOREBOARD_SERVER_TEXT.get());
        if (AetherConfig.NICK_HIDER_MASTER_ENABLED.get() && AetherConfig.HIDE_SERVER_ID.get()) {
            display = ServerIdHider.replace(display, AetherConfig.CUSTOM_SERVER_ID.get());
        }
        int displayWidth = heading ? (int) Math.ceil(font.width(display) * ScoreboardText.HEADING_SIZE / ScoreboardText.SIZE)
                : display == text ? 0 : font.width(display);
        drawList.text(ScoreboardText.prepare(font, display, color, shadow, heading), x, y, font.width(text),
                Math.min(displayWidth, Math.max(0, width - 32)));
    }

    ScoreboardDrawList drawList() { return drawList; }
}
