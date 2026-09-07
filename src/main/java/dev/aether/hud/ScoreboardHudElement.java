package dev.aether.hud;

import dev.aether.Aether;
import dev.aether.config.AetherConfig;
import dev.aether.mixin.GuiAccessor;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public final class ScoreboardHudElement extends HudElement {
    private ScoreboardDrawList drawList = new ScoreboardDrawList();
    private boolean renderingFailed;

    @Override public float getX() {
        float screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        float x = AetherConfig.SCOREBOARD_HUD_X.get() < 0
                ? screenWidth - getWidth() * getScale() - 8 : AetherConfig.SCOREBOARD_HUD_X.get();
        return Math.max(0, Math.min(screenWidth - getWidth() * getScale(), x));
    }
    @Override public float getY() {
        float screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        float y = AetherConfig.SCOREBOARD_HUD_Y.get() < 0
                ? drawList.top() - ScoreboardDrawList.PADDING : AetherConfig.SCOREBOARD_HUD_Y.get();
        return Math.max(0, Math.min(screenHeight - getHeight() * getScale(), y));
    }
    @Override public void setX(float x) { AetherConfig.SCOREBOARD_HUD_X.set(Math.round(x)); }
    @Override public void setY(float y) { AetherConfig.SCOREBOARD_HUD_Y.set(Math.round(y)); }
    @Override public float getScale() { return AetherConfig.SCOREBOARD_HUD_SCALE.get(); }
    @Override public void setScale(float scale) { AetherConfig.SCOREBOARD_HUD_SCALE.set(scale); }
    @Override public float getWidth() { return drawList.panelWidth(); }
    @Override public float getHeight() { return drawList.panelHeight(); }
    @Override public String getName() { return "Custom Scoreboard"; }
    @Override public void savePosition() { AetherConfig.save(); }
    @Override public boolean isEnabled() { return AetherConfig.CUSTOM_SCOREBOARD.get(); }
    @Override public boolean rendersWithHud() { return false; }

    @Override
    public boolean isVisible() {
        if (AetherConfig.HUD_PANEL_FROSTED.get()) return false;
        Minecraft mc = Minecraft.getInstance();
        return isEnabled() && !renderingFailed && mc.player != null && mc.level != null
                && !mc.options.hideGui && !StreamerModeManager.isEnabled() && HudRegistry.canRenderInGameplay(mc);
    }

    public static void setEnabled(boolean enabled) {
        if (HudRegistry.scoreboardHud != null) HudRegistry.scoreboardHud.renderingFailed = false;
        AetherConfig.CUSTOM_SCOREBOARD.set(enabled);
        AetherConfig.save();
    }

    public static void resetLayout() {
        AetherConfig.SCOREBOARD_HUD_X.set(-1);
        AetherConfig.SCOREBOARD_HUD_Y.set(-1);
        AetherConfig.SCOREBOARD_HUD_SCALE.set(1f);
        AetherConfig.save();
    }

    public void extract(GuiGraphicsExtractor graphics, Consumer<GuiGraphicsExtractor> vanilla) {
        if (graphics instanceof ScoreboardGraphicsExtractor || !isVisible()) {
            vanilla.accept(graphics);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        ScoreboardGraphicsExtractor capture;
        try {
            capture = new ScoreboardGraphicsExtractor(mc, width, height);
            vanilla.accept(capture);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
            vanilla.accept(graphics);
            return;
        }

        drawList = capture.drawList();
        var level = mc.level;
        // Keep the sidebar below screens and tooltips, independently of the other HUDs' fade.
        AetherRenderQueue.enqueueBeforeGui(() -> {
            if (!isVisible() || mc.level != level) return;
            renderFrame(capture.drawList(), width, height);
        });
    }

    private void renderFrame(ScoreboardDrawList snapshot, int width, int height) {
        try {
            if (!NanoVGManager.isInitialized()) NanoVGManager.init();
            NanoVGManager.beginFrame(width, height);
            try {
                drawList = snapshot;
                render(NanoVGManager.getRenderer(), false);
            } finally {
                NanoVGManager.endFrame();
            }
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private void fail(Throwable error) {
        if (!renderingFailed) Aether.LOGGER.warn("Custom scoreboard unavailable; restoring vanilla rendering", error);
        renderingFailed = true;
    }

    @Override
    public void renderMinecraft(GuiGraphicsExtractor graphics, boolean editMode) {
        if (!editMode || !isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        ScoreboardGraphicsExtractor capture = new ScoreboardGraphicsExtractor(mc, graphics.guiWidth(), graphics.guiHeight());
        if (mc.level != null && mc.player != null) {
            ((GuiAccessor) mc.gui).aether$extractScoreboardSidebar(capture, mc.getDeltaTracker());
        }
        if (capture.drawList().isEmpty()) {
            Component title = Component.literal("Scoreboard").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
            Component row = Component.literal("Player").append(Component.literal("  \u2764").withStyle(ChatFormatting.RED));
            int width = Math.max(mc.font.width(title), mc.font.width(row) + mc.font.width(": 1"));
            int left = graphics.guiWidth() - width - 3;
            int top = graphics.guiHeight() / 2 - 16;
            capture.fill(left - 2, top, left + width + 2, top + 9, mc.options.getBackgroundColor(0.4f));
            capture.fill(left - 2, top + 9, left + width + 2, top + 19, mc.options.getBackgroundColor(0.3f));
            capture.text(mc.font, title, left + width / 2 - mc.font.width(title) / 2, top + 1, -1, false);
            capture.text(mc.font, row, left, top + 10, -1, false);
            capture.text(mc.font, Component.literal("1").withStyle(ChatFormatting.RED), left + width + 2 - mc.font.width("1"), top + 10, -1, false);
        }
        drawList = capture.drawList();
    }

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        if (renderingFailed) return;
        try {
            drawList.render(nvg);
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }
}
