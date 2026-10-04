package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.FocusHandler;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.NumberText;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.settings.SliderSetting;
import org.lwjgl.glfw.GLFW;

// a slider with an optional inline value field. the track jumps to a press and follows the drag, measured
// against the track as it was at the press; values snap to the step the field displays (10^-decimals)
public final class Slider {
    private Slider() {
    }

    public interface Model {
        double min();

        double max();

        double value();

        void set(double value);

        int decimals();

        String suffix();
    }

    public static Model of(SliderSetting s) {
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
            public double value() {
                return s.getValue();
            }

            @Override
            public void set(double value) {
                s.setValue((float) value);
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

    // -- maths -----------------------------------------------------------------

    public static double step(int decimals) {
        return Math.pow(10.0, -Math.max(0, decimals));
    }

    // rounds to the displayed step, then clamps
    public static double snap(double value, double min, double max, int decimals) {
        double step = step(decimals);
        double snapped = Math.round(value / step) * step;
        snapped = Double.parseDouble(NumberText.format(snapped, decimals));
        return Math.max(Math.min(min, max), Math.min(Math.max(min, max), snapped));
    }

    public static float fraction(double value, double min, double max) {
        if (max <= min) {
            return 0f;
        }
        return (float) Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
    }

    // the value under local x on a track laid out as track
    public static double valueAt(float localX, Rect track, double min, double max, int decimals) {
        double t = track.w() <= 0f ? 0.0 : Math.max(0.0, Math.min(1.0, (localX - track.x()) / track.w()));
        return snap(min + (max - min) * t, min, max, decimals);
    }

    // one keyboard step in a direction; large (shift) moves ten steps
    public static double stepped(double value, int direction, boolean large, double min, double max, int decimals) {
        double step = step(decimals) * (large ? 10.0 : 1.0);
        return snap(value + Math.signum(direction) * step, min, max, decimals);
    }

    public static String format(Model m, double value) {
        return NumberText.format(value, m.decimals()) + m.suffix();
    }

    // -- control ---------------------------------------------------------------

    // valueBox may be null for a slider without an inline field
    public static void render(UiContext ui, Object id, Rect track, Rect valueBox, Model m, boolean enabled) {
        Object trackId = Part.of(id, "track");
        float hoverT = enabled ? ui.anim().hover(Part.of(id, "hover"), ui.hits().hovered(trackId)) : 0f;
        boolean dragging = ui.hits().active(trackId);
        float t = fraction(m.value(), m.min(), m.max());
        float shown = ui.anim().spring(Part.of(id, "t"), t);
        if (dragging) {
            shown = t;
        }
        ui.skin().sliderTrack(ui.sc(), track, 0f, shown, hoverT, enabled);
        float knobX = track.x() + track.w() * shown;
        ui.skin().sliderKnob(ui.sc(), knobX, track.centerY(), hoverT, dragging, enabled);
        if (valueBox != null) {
            TextField.render(ui, Part.of(id, "value"), valueBox, valueField(m), enabled);
        }
        if (!enabled) {
            return;
        }
        ControlSupport.region(ui, trackId, track, new TrackHandler(m), Cursor.HAND);
        ControlSupport.focusable(ui, trackId, track, new FocusHandler() {
            @Override
            public void activate() {
                if (valueBox != null) {
                    TextField.focus(ui, Part.of(id, "value"), valueField(m), true);
                }
            }

            @Override
            public void adjust(int direction, boolean large) {
                m.set(stepped(m.value(), direction, large, m.min(), m.max(), m.decimals()));
            }
        });
        if (ui.focus().ringVisible(trackId)) {
            float knob = ui.metrics().sliderKnobSize();
            ui.skin().focusRing(ui.sc(), new Rect(knobX - knob / 2f, track.centerY() - knob / 2f, knob, knob), knob / 2f);
        }
    }

    public static TextField.Model valueField(Model m) {
        return new TextField.Model() {
            @Override
            public String text() {
                return NumberText.format(m.value(), m.decimals());
            }

            @Override
            public String display() {
                return format(m, m.value());
            }

            @Override
            public void commit(String text) {
                NumberText.parse(text).ifPresent(v -> m.set(snap(v, m.min(), m.max(), m.decimals())));
            }

            @Override
            public boolean numeric() {
                return true;
            }

            @Override
            public FontRole font() {
                return FontRole.VALUE;
            }

            @Override
            public boolean centered() {
                return true;
            }
        };
    }

    private static final class TrackHandler implements HitHandler {
        private final Model model;

        private TrackHandler(Model model) {
            this.model = model;
        }

        @Override
        public boolean press(PointerEvent e) {
            if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return false;
            }
            model.set(valueAt(e.localX(), e.pressRect(), model.min(), model.max(), model.decimals()));
            return true;
        }

        @Override
        public void drag(PointerEvent e) {
            model.set(valueAt(e.localX(), e.pressRect(), model.min(), model.max(), model.decimals()));
        }
    }
}
