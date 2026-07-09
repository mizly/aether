package dev.aether.ui.components;

import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;

/**
 * A rounded button with lerped hover and press colour transitions.
 *
 * <h3>Builder usage</h3>
 * <pre>{@code
 * Button btn = Button.builder("Save", 120, 32)
 *         .accent(true)
 *         .onPress(() -> saveConfig())
 *         .build();
 * btn.setPosition(x, y);
 * }</pre>
 */
public class Button extends Component {

    // -- Animation -------------------------------------------------------------

    private static final float ANIM_SPEED = 0.15f;

    private float hoverAmt = 0f;   // [0, 1]
    private float pressAmt = 0f;   // [0, 1]

    // -- Style -----------------------------------------------------------------

    private static final float RADIUS    = 6f;
    private static final float FONT_SIZE = 13f;

    private String  label;
    private boolean accent;   // true -> use ACCENT bg, false -> SURFACE
    private Runnable onPress;

    private boolean hovered = false;
    private boolean pressed = false;

    // -- Construction ----------------------------------------------------------

    private Button(String label, float width, float height, boolean accent, Runnable onPress) {
        this.label   = label;
        this.width   = width;
        this.height  = height;
        this.accent  = accent;
        this.onPress = onPress;
    }

    // -- Builder ---------------------------------------------------------------

    public static Builder builder(String label, float width, float height) {
        return new Builder(label, width, height);
    }

    public static final class Builder {
        private final String label;
        private final float  width, height;
        private boolean  accent  = false;
        private Runnable onPress = () -> {};

        private Builder(String label, float width, float height) {
            this.label  = label;
            this.width  = width;
            this.height = height;
        }

        /** Makes the button use the accent (red) colour. */
        public Builder accent(boolean accent) { this.accent = accent; return this; }

        /** Callback invoked when the button is clicked. */
        public Builder onPress(Runnable onPress) { this.onPress = onPress; return this; }

        public Button build() { return new Button(label, width, height, accent, onPress); }
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void render(NVGRenderer nvg) {
        if (!visible) return;

        // Animate
        float hoverTarget = hovered ? 1f : 0f;
        float pressTarget = pressed ? 1f : 0f;
        hoverAmt += (hoverTarget - hoverAmt) * ANIM_SPEED;
        pressAmt += (pressTarget - pressAmt) * ANIM_SPEED;

        // Resolve colours
        int bg;
        if (accent) {
            bg = Colors.lerp(Colors.ACCENT, Colors.ACCENT_DARK, pressAmt);
            bg = Colors.lerp(bg, Colors.lerp(Colors.ACCENT, Colors.withAlpha(Colors.WHITE, 30), 0.15f), hoverAmt);
        } else {
            bg = Colors.lerp(Colors.SURFACE, Colors.HOVER,   hoverAmt);
            bg = Colors.lerp(bg,             Colors.ACTIVE,  pressAmt);
        }

        int border = Colors.lerp(Colors.BORDER, accent ? Colors.ACCENT_DARK : Colors.BORDER_HOV, hoverAmt);
        int text   = enabled ? Colors.TEXT : Colors.TEXT_OFF;

        nvg.roundedRect(x, y, width, height, RADIUS, bg);
        nvg.rectOutline(x, y, width, height, RADIUS, 1f, border);
        nvg.text(Fonts.REGULAR, label, x + (width - nvg.textWidth(Fonts.REGULAR, label, FONT_SIZE)) / 2f, y + (height - FONT_SIZE) / 2f, FONT_SIZE, text);
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button == 0 && enabled && contains(mouseX, mouseY)) {
            pressed = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pressed) {
            pressed = false;
            if (enabled && contains(mouseX, mouseY) && onPress != null) {
                onPress.run();
            }
            return true;
        }
        return false;
    }

    // -- Hover tracking (called from NVGScreen or parent) ----------------------

    public void updateHover(double mouseX, double mouseY) {
        hovered = enabled && contains(mouseX, mouseY);
    }

    // -- Fluent setters --------------------------------------------------------

    public Button setLabel(String label)       { this.label   = label;   return this; }
    public Button setAccent(boolean accent)    { this.accent  = accent;  return this; }
    public Button setOnPress(Runnable handler) { this.onPress = handler; return this; }
}
