package dev.aether.ui.components;

import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;

import java.util.function.Consumer;

/**
 * A horizontal slider with a draggable thumb.
 *
 * <p>Layout: one row - label on the left, formatted value on the right,
 * track filling the full width on the row below.</p>
 *
 * <pre>{@code
 * Slider s = new Slider("Speed", 0, 10, 1, 3, v -> config.speed = v.intValue());
 * s.setBounds(x, y, 240, 40);
 * }</pre>
 */
public class Slider extends Component {

    // -- Layout ----------------------------------------------------------------

    private static final float TRACK_H   =  4f;
    private static final float THUMB_R   =  7f;
    private static final float LABEL_H   = 16f;
    private static final float FONT_SIZE = 13f;

    // -- State -----------------------------------------------------------------

    private String label;
    private double min, max, step, value;
    private Consumer<Double> onChange;
    private boolean dragging = false;

    // Format string for the displayed value - override via setFormat()
    private String format = "%.1f";

    // -- Construction ----------------------------------------------------------

    /**
     * @param label    display label
     * @param min      minimum value (inclusive)
     * @param max      maximum value (inclusive)
     * @param step     snap increment (use {@code 1} for integer steps, {@code 0} for continuous)
     * @param initial  initial value
     * @param onChange callback invoked when the value changes
     */
    public Slider(String label, double min, double max, double step,
                  double initial, Consumer<Double> onChange) {
        this.label    = label;
        this.min      = min;
        this.max      = max;
        this.step     = step;
        this.value    = clamp(initial);
        this.onChange = onChange;
        this.height   = LABEL_H + 4f + THUMB_R * 2;
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void render(NVGRenderer nvg) {
        if (!visible) return;

        float labelY = y + (LABEL_H - FONT_SIZE) / 2f;

        // Label
        nvg.text(Fonts.REGULAR, label, x, labelY, FONT_SIZE, enabled ? Colors.TEXT : Colors.TEXT_OFF);

        // Value display
        String valueStr = String.format(format, value);
        nvg.text(Fonts.MONO, valueStr, x + width - nvg.textWidth(Fonts.MONO, valueStr, FONT_SIZE), labelY, FONT_SIZE, Colors.TEXT_DIM);

        // Track
        float trackY  = y + LABEL_H + 4f + THUMB_R - TRACK_H / 2f;
        float trackX0 = x + THUMB_R;
        float trackW  = width - THUMB_R * 2;

        nvg.roundedRect(trackX0, trackY, trackW, TRACK_H, TRACK_H / 2f, Colors.BORDER);

        // Fill
        float t       = (float) ((value - min) / (max - min));
        float fillW   = trackW * t;
        if (fillW > 0) {
            int fillColor = enabled ? Colors.ACCENT : Colors.withAlpha(Colors.ACCENT, 0.4f);
            nvg.roundedRect(trackX0, trackY, fillW, TRACK_H, TRACK_H / 2f, fillColor);
        }

        // Thumb
        float thumbCX = trackX0 + fillW;
        float thumbCY = trackY + TRACK_H / 2f;
        int thumbColor = enabled
                ? (dragging ? Colors.ACCENT : Colors.WHITE)
                : Colors.TEXT_OFF;
        nvg.circle(thumbCX, thumbCY, THUMB_R, thumbColor);
        if (enabled) {
            nvg.circle(thumbCX, thumbCY, THUMB_R - 2f, dragging ? Colors.ACCENT_DARK : Colors.SURFACE);
        }
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button == 0 && enabled && contains(mouseX, mouseY)) {
            dragging = true;
            setValueFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double deltaX, double deltaY) {
        if (button == 0 && dragging) {
            setValueFromMouse(mouseX);
            return true;
        }
        return false;
    }

    private void setValueFromMouse(double mouseX) {
        float trackX0 = x + THUMB_R;
        float trackW  = width - THUMB_R * 2;
        double t = (mouseX - trackX0) / trackW;
        double raw = min + t * (max - min);
        setValue(raw);
    }

    // -- API -------------------------------------------------------------------

    public double getValue() { return value; }

    public Slider setValue(double v) {
        double clamped = clamp(v);
        if (clamped != this.value) {
            this.value = clamped;
            if (onChange != null) onChange.accept(this.value);
        }
        return this;
    }

    /** Sets the printf-style format string used to display the value (default {@code "%.1f"}). */
    public Slider setFormat(String fmt) { this.format = fmt; return this; }

    public Slider setLabel(String label) { this.label = label; return this; }

    private double clamp(double v) {
        v = Math.max(min, Math.min(max, v));
        if (step > 0) v = Math.round(v / step) * step;
        return v;
    }
}
