package dev.aether.ui.components;

import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;

import java.util.function.Consumer;

/**
 * A sliding pill toggle with smooth on/off animation.
 *
 * <pre>{@code
 * Toggle t = new Toggle("Enable macro", true, v -> config.enabled = v);
 * t.setBounds(x, y, 200, 20);
 * }</pre>
 *
 * <p>The component draws a label on the left and the pill track on the right.</p>
 */
public class Toggle extends Component {

    // -- Layout ----------------------------------------------------------------

    private static final float TRACK_W   = 38f;
    private static final float TRACK_H   = 20f;
    private static final float THUMB_PAD =  3f;
    private static final float FONT_SIZE = 13f;

    // -- Animation -------------------------------------------------------------

    private static final float ANIM_SPEED = 0.12f;

    private float slideAmt; // [0 = off, 1 = on]

    // -- State -----------------------------------------------------------------

    private String  label;
    private boolean value;
    private Consumer<Boolean> onChange;

    // -- Construction ----------------------------------------------------------

    public Toggle(String label, boolean initialValue, Consumer<Boolean> onChange) {
        this.label     = label;
        this.value     = initialValue;
        this.slideAmt  = initialValue ? 1f : 0f;
        this.onChange  = onChange;
        this.height    = TRACK_H;
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void render(NVGRenderer nvg) {
        if (!visible) return;

        // Animate slide
        float target = value ? 1f : 0f;
        slideAmt += (target - slideAmt) * (1f - (float) Math.pow(1f - ANIM_SPEED, 1f));
        // Use exponential ease: slideAmt = lerp(slideAmt, target, speed)
        slideAmt = slideAmt + (target - slideAmt) * ANIM_SPEED * 3f;

        // Clamp
        if (Math.abs(slideAmt - target) < 0.002f) slideAmt = target;

        // Track
        float trackX = x + width - TRACK_W;
        float trackY = y + (height - TRACK_H) / 2f;
        int trackColor = Colors.lerp(Colors.BORDER, Colors.ACCENT, slideAmt);
        if (!enabled) trackColor = Colors.withAlpha(trackColor, 0.5f);
        nvg.roundedRect(trackX, trackY, TRACK_W, TRACK_H, TRACK_H / 2f, trackColor);

        // Thumb
        float thumbD  = TRACK_H - THUMB_PAD * 2;
        float thumbTravel = TRACK_W - THUMB_PAD * 2 - thumbD;
        float thumbX  = trackX + THUMB_PAD + slideAmt * thumbTravel;
        float thumbY  = trackY + THUMB_PAD;
        int thumbColor = enabled ? Colors.WHITE : Colors.TEXT_DIM;
        nvg.circle(thumbX + thumbD / 2f, thumbY + thumbD / 2f, thumbD / 2f, thumbColor);

        // Label
        int labelColor = enabled ? Colors.TEXT : Colors.TEXT_OFF;
        float labelY = y + (height - FONT_SIZE) / 2f;
        nvg.text(Fonts.REGULAR, label, x, labelY, FONT_SIZE, labelColor);
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button == 0 && enabled && contains(mouseX, mouseY)) {
            setValue(!value);
            return true;
        }
        return false;
    }

    // -- API -------------------------------------------------------------------

    public boolean getValue() { return value; }

    public Toggle setValue(boolean v) {
        this.value = v;
        if (onChange != null) onChange.accept(v);
        return this;
    }

    public Toggle setLabel(String label)           { this.label    = label;    return this; }
    public Toggle setOnChange(Consumer<Boolean> h) { this.onChange = h;        return this; }
}
