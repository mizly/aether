package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NVGScreen;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

// a single centred panel on the aether canvas, scaled and animated in the same way as the macro menu
abstract class CanvasPanelScreen extends NVGScreen {
    static final float HEADER_H = 52f;
    static final float PAD = 18f;
    static final float FIELD_H = 32f;

    private final List<Hit> hits = new ArrayList<>();
    private float alpha;
    private float animScale = 0.96f;
    private float animOffsetY = 120f;
    private float pr = 1f;
    private float rawMouseX;
    private float rawMouseY;

    record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(float mx, float my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    // single line text box; the screen routes keys to whichever one is focused
    static final class Field {
        final StringBuilder text = new StringBuilder();
        final int maxLength;
        boolean focused;

        Field(String initial, int maxLength) {
            this.maxLength = maxLength;
            text.append(initial == null ? "" : initial);
        }

        String value() {
            return text.toString().trim();
        }

        void set(String value) {
            text.setLength(0);
            text.append(value == null ? "" : value);
        }
    }

    CanvasPanelScreen(String title) {
        super(title);
    }

    abstract float panelWidth();

    abstract float panelHeight(float canvasH);

    abstract void renderPanel(NVGRenderer nvg, float px, float py, float pw, float ph, float mx, float my);

    // the text box keys go to, or null when none is focused
    Field focusedField() {
        return null;
    }

    void onFieldSubmit(Field field) {
        field.focused = false;
    }

    void onFieldCancel(Field field) {
        field.focused = false;
    }

    void onClickAnywhere() {
    }

    void afterClick() {
    }

    void onScroll(float amount) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void renderNVG(NVGRenderer nvg) {
        syncPixelRatio();
        float step = Math.min(1f, Theme.animationFactor() * 6f);
        alpha += (1f - alpha) * step;
        animScale += (1f - animScale) * step * 1.1f;
        animOffsetY += (0f - animOffsetY) * step * 1.1f;
        hits.clear();

        nvg.save();
        nvg.scale(MainGUI.uiScale / pr, MainGUI.uiScale / pr);
        nvg.setTextScale(MainGUI.uiTextScale);

        float canvasW = width * pr / MainGUI.uiScale;
        float canvasH = height * pr / MainGUI.uiScale;
        nvg.rect(0f, 0f, canvasW, canvasH, Theme.withAlpha(0xFF000000, (int) (alpha * 170)));

        float pw = panelWidth();
        float ph = panelHeight(canvasH);
        float px = (canvasW - pw) / 2f;
        float py = (canvasH - ph) / 2f;
        float scx = canvasW / 2f;
        float scy = canvasH / 2f;

        nvg.save();
        nvg.translate(scx, scy);
        nvg.scale(animScale, animScale);
        nvg.translate(-scx, -scy);
        nvg.translate(0f, animOffsetY);
        nvg.globalAlpha(alpha);
        nvg.shadow(px, py, pw, ph, MainGUI.RADIUS, 26f, Theme.withAlpha(0xFF000000, 0.75f));
        nvg.roundedRect(px, py, pw, ph, MainGUI.RADIUS, Theme.PANEL_BG);
        nvg.rectOutline(px, py, pw, ph, MainGUI.RADIUS, 1f, Theme.BORDER_DEFAULT);
        nvg.restore();

        // text keeps its final size through the zoom so it never jitters, matching MainGUI
        nvg.save();
        nvg.translate(0f, animOffsetY);
        nvg.globalAlpha(alpha);
        renderPanel(nvg, px, py, pw, ph, canvasMouseX(), canvasMouseY());
        nvg.restore();

        nvg.restore();
    }

    void addHit(float x, float y, float w, float h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action));
    }

    static boolean hovered(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    void renderHeader(NVGRenderer nvg, float px, float py, float pw, String title, String subtitle) {
        nvg.text(Fonts.BOLD, title, px + PAD, py + (HEADER_H - 15f) / 2f, 15f, Theme.TEXT_PRIMARY);
        if (subtitle != null && !subtitle.isEmpty()) {
            float titleW = nvg.textWidth(Fonts.BOLD, title, 15f);
            float sepX = px + PAD + titleW + 10f;
            nvg.rect(sepX, py + (HEADER_H - 19f) / 2f, 1f, 19f, Theme.SEPARATOR);
            nvg.text(Fonts.REGULAR, subtitle, sepX + 10f, py + (HEADER_H - 12f) / 2f, 12f, Theme.TEXT_MUTED);
        }
        nvg.rect(px, py + HEADER_H, pw, 1f, Theme.SEPARATOR);
    }

    void renderField(NVGRenderer nvg, Field field, float x, float y, float w, String prefix, String placeholder) {
        float radius = FIELD_H / 2f;
        nvg.roundedRect(x, y, w, FIELD_H, radius, Theme.BG_FIELD);
        nvg.rectOutlineSolid(x, y, w, FIELD_H, radius, 1f,
                field.focused ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);

        float textX = x + 14f;
        float textY = y + (FIELD_H - 12f) / 2f;
        if (prefix != null && !prefix.isEmpty()) {
            nvg.text(Fonts.MONO, prefix, textX, textY, 12f, Theme.TEXT_MUTED);
            textX += nvg.textWidth(Fonts.MONO, prefix, 12f);
        }
        boolean empty = field.text.isEmpty();
        nvg.pushScissor(textX, y, x + w - 14f - textX, FIELD_H);
        nvg.text(Fonts.MONO, empty ? placeholder : field.text.toString(), textX, textY, 12f,
                empty ? Theme.withAlpha(Theme.TEXT_MUTED, 0.6f) : Theme.TEXT_PRIMARY);
        if (field.focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            float caretX = textX + (empty ? 0f : nvg.textWidth(Fonts.MONO, field.text.toString(), 12f)) + 1f;
            nvg.rect(caretX, y + 9f, 1f, FIELD_H - 18f, Theme.ACCENT_PRIMARY);
        }
        nvg.popScissor();
        addHit(x, y, w, FIELD_H, () -> field.focused = true);
    }

    static String fit(NVGRenderer nvg, String text, String font, float size, float maxW) {
        if (nvg.textWidth(font, text, size) <= maxW) {
            return text;
        }
        String trimmed = text;
        while (!trimmed.isEmpty() && nvg.textWidth(font, trimmed + "...", size) > maxW) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "...";
    }

    private void syncPixelRatio() {
        try {
            var target = Minecraft.getInstance().getMainRenderTarget();
            pr = width > 0 ? (float) target.width / width : 1f;
        } catch (Exception ignored) {
            pr = 1f;
        }
    }

    private float canvasMouseX() {
        return rawMouseX * pr / MainGUI.uiScale;
    }

    private float canvasMouseY() {
        return rawMouseY * pr / MainGUI.uiScale;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        rawMouseX = mouseX;
        rawMouseY = mouseY;
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() != 0) {
            return true;
        }
        float mx = (float) click.x() * pr / MainGUI.uiScale;
        float my = (float) click.y() * pr / MainGUI.uiScale;
        Field focused = focusedField();
        if (focused != null) {
            focused.focused = false;
        }
        onClickAnywhere();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mx, my)) {
                hit.action().run();
                break;
            }
        }
        afterClick();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double verticalScroll) {
        onScroll((float) verticalScroll);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        Field field = focusedField();
        if (field != null) {
            switch (input.key()) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (!field.text.isEmpty()) {
                        field.text.deleteCharAt(field.text.length() - 1);
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    onFieldSubmit(field);
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    onFieldCancel(field);
                    return true;
                }
                default -> {
                }
            }
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        Field field = focusedField();
        if (field != null) {
            char typed = (char) input.codepoint();
            if (typed >= ' ' && field.text.length() < field.maxLength) {
                field.text.append(typed);
            }
            return true;
        }
        return super.charTyped(input);
    }
}
