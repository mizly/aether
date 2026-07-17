package dev.aether.ui.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

/**
 * A setting that displays X, Y, Z coordinates and has a capture button.
 */
public class PositionSetting implements Setting {

    private final String name;
    private final String rawName;
    private final Supplier<Double> xGetter;
    private final Consumer<Double> xSetter;
    private final Supplier<Double> yGetter;
    private final Consumer<Double> ySetter;
    private final Supplier<Double> zGetter;
    private final Consumer<Double> zSetter;
    private final Supplier<Boolean> highlightGetter;
    private final Consumer<Boolean> highlightSetter;
    private final Runnable captureAction;
    private final List<ActionButton> actionButtons = new ArrayList<>();
    private Supplier<Boolean> visibility = () -> true;

    public PositionSetting(String name,
                           Supplier<Double> xGetter, Consumer<Double> xSetter,
                           Supplier<Double> yGetter, Consumer<Double> ySetter,
                           Supplier<Double> zGetter, Consumer<Double> zSetter,
                           Supplier<Boolean> highlightGetter, Consumer<Boolean> highlightSetter,
                           Runnable captureAction) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.xGetter = xGetter;
        this.xSetter = xSetter;
        this.yGetter = yGetter;
        this.ySetter = ySetter;
        this.zGetter = zGetter;
        this.zSetter = zSetter;
        this.highlightGetter = highlightGetter;
        this.highlightSetter = highlightSetter;
        this.captureAction = captureAction;
    }

    public double getX() { return xGetter.get(); }
    public void setX(double v) { xSetter.accept(v); }
    public double getY() { return yGetter.get(); }
    public void setY(double v) { ySetter.accept(v); }
    public double getZ() { return zGetter.get(); }
    public void setZ(double v) { zSetter.accept(v); }
    public boolean isHighlighted() { return highlightGetter.get(); }
    public void setHighlighted(boolean v) { highlightSetter.accept(v); }
    public void capture() { captureAction.run(); }
    public List<ActionButton> getActionButtons() { return actionButtons; }

    public PositionSetting addActionButton(String label, Runnable action, Supplier<Boolean> enabled) {
        actionButtons.add(new ActionButton(label, action, enabled));
        return this;
    }

    public PositionSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibility = condition;
        return this;
    }

    @Override public String getName() { return name; }
    @Override public String getRawName() { return rawName; }
    @Override public SettingType getType() { return SettingType.POSITION; }
    @Override public boolean isVisible() { return visibility.get(); }

    public record ActionButton(String label, Runnable action, Supplier<Boolean> enabled) {
        public boolean isEnabled() {
            return enabled == null || enabled.get();
        }

        public void execute() {
            if (isEnabled() && action != null) {
                action.run();
            }
        }
    }
}
