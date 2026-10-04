package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.FocusHandler;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.NumberText;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.settings.RangeSliderSetting;
import org.lwjgl.glfw.GLFW;

// a two-handle slider: a press grabs the nearer handle (and jumps it there); dragging one handle past the
// other swaps them, and the drag carries on with the handle now under the pointer
public final class RangeSlider {
    public enum Handle { LOWER, UPPER }

    private RangeSlider() {
    }

    public interface Model {
        double min();

        double max();

        double lower();

        double upper();

        // the setting orders the pair, so callers may pass them crossed
        void set(double lower, double upper);

        int decimals();

        String suffix();
    }

    public static Model of(RangeSliderSetting s) {
        return new Model() {
            @Override
            public double min() {
                return s.getMin();
            }

            @Override
            public double max() {
                return s.getMax();
            }

            @Override
            public double lower() {
                return s.getLowerValue();
            }

            @Override
            public double upper() {
                return s.getUpperValue();
            }

            @Override
            public void set(double lower, double upper) {
                s.setValues((float) lower, (float) upper);
            }

            @Override
            public int decimals() {
                return s.getDecimals();
            }

            @Override
            public String suffix() {
                return s.getSuffix();
            }
        };
    }

    // the handle nearer to x; on a tie (handles together) the side of the press decides
    public static Handle nearest(double value, double lower, double upper) {
        double toLower = Math.abs(value - lower);
        double toUpper = Math.abs(value - upper);
        if (toLower < toUpper) {
            return Handle.LOWER;
        }
        if (toUpper < toLower) {
            return Handle.UPPER;
        }
        return value < lower ? Handle.LOWER : Handle.UPPER;
    }

    // moves one handle to value; returns the handle that is under the pointer afterwards (swapped if they crossed)
    public static Handle move(Model m, Handle handle, double value) {
        double lower = m.lower();
        double upper = m.upper();
        if (handle == Handle.LOWER) {
            m.set(value, upper);
            return value > upper ? Handle.UPPER : Handle.LOWER;
        }
        m.set(lower, value);
        return value < lower ? Handle.LOWER : Handle.UPPER;
    }

    public static String format(Model m) {
        return NumberText.format(m.lower(), m.decimals()) + " – " + NumberText.format(m.upper(), m.decimals()) + m.suffix();
    }

    public static void render(UiContext ui, Object id, Rect track, Rect valueBox, Model m, boolean enabled) {
        Object trackId = Part.of(id, "track");
        float hoverT = enabled ? ui.anim().hover(Part.of(id, "hover"), ui.hits().hovered(trackId)) : 0f;
        boolean dragging = ui.hits().active(trackId);
        float lo = Slider.fraction(m.lower(), m.min(), m.max());
        float hi = Slider.fraction(m.upper(), m.min(), m.max());
        if (!dragging) {
            lo = ui.anim().spring(Part.of(id, "lo"), lo);
            hi = ui.anim().spring(Part.of(id, "hi"), hi);
        }
        ui.skin().sliderTrack(ui.sc(), track, lo, hi, hoverT, enabled);
        Handle active = ui.memory().peek(Part.of(id, "handle"));
        ui.skin().sliderKnob(ui.sc(), track.x() + track.w() * lo, track.centerY(), hoverT,
                dragging && active == Handle.LOWER, enabled);
        ui.skin().sliderKnob(ui.sc(), track.x() + track.w() * hi, track.centerY(), hoverT,
                dragging && active == Handle.UPPER, enabled);
        if (valueBox != null) {
            ui.skin().field(ui.sc(), valueBox, false, 0f, false);
            ui.skin().textCentered(ui.sc(), FontRole.VALUE, format(m), valueBox.inset(6f, 0f, 6f, 0f),
                    ui.palette().textValue());
        }
        if (!enabled) {
            return;
        }
        ControlSupport.region(ui, trackId, track, new TrackHandler(ui, id, m), Cursor.HAND);
        for (Handle handle : Handle.values()) {
            Object focusId = Part.of(id, handle.name());
            float x = track.x() + track.w() * (handle == Handle.LOWER ? lo : hi);
            float knob = ui.metrics().sliderKnobSize();
            Rect knobRect = new Rect(x - knob / 2f, track.centerY() - knob / 2f, knob, knob);
            ControlSupport.focusable(ui, focusId, knobRect, new FocusHandler() {
                @Override
                public void activate() {
                }

                @Override
                public void adjust(int direction, boolean large) {
                    double current = handle == Handle.LOWER ? m.lower() : m.upper();
                    move(m, handle, Slider.stepped(current, direction, large, m.min(), m.max(), m.decimals()));
                }
            });
            ControlSupport.ring(ui, focusId, knobRect, knob / 2f);
        }
    }

    private static final class TrackHandler implements HitHandler {
        private final UiContext ui;
        private final Object id;
        private final Model model;
        private Handle handle;

        private TrackHandler(UiContext ui, Object id, Model model) {
            this.ui = ui;
            this.id = id;
            this.model = model;
        }

        @Override
        public boolean press(PointerEvent e) {
            if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return false;
            }
            double value = Slider.valueAt(e.localX(), e.pressRect(), model.min(), model.max(), model.decimals());
            handle = move(model, nearest(value, model.lower(), model.upper()), value);
            ui.memory().put(Part.of(id, "handle"), handle);
            return true;
        }

        @Override
        public void drag(PointerEvent e) {
            if (handle == null) {
                return;
            }
            double value = Slider.valueAt(e.localX(), e.pressRect(), model.min(), model.max(), model.decimals());
            handle = move(model, handle, value);
            ui.memory().put(Part.of(id, "handle"), handle);
        }

        @Override
        public void release(PointerEvent e) {
            ui.memory().remove(Part.of(id, "handle"));
        }
    }
}
