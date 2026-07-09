package dev.aether.ui.settings;

import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

/**
 * Numeric slider setting backed by a getter/setter.
 *
 * Example:
 *   new SliderSetting("Pest Threshold", 1, 20,
 *       () -> (float) AetherConfig.pestThreshold,
 *       v -> AetherConfig.pestThreshold = v.intValue())
 *     .withDecimals(0).withSuffix(" pests")
 */
public class SliderSetting implements Setting {

    private final String name;
    private final String rawName;
    private final float min;
    private final float max;
    private final Supplier<Float> getter;
    private final Consumer<Float> setter;

    private int decimals = 1;
    private String suffix = "";
    private boolean percentage = false;
    private Supplier<Boolean> visibility = () -> true;

    public SliderSetting(String name, float min, float max,
                         Supplier<Float> getter, Consumer<Float> setter) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
    }

    public float getValue() { return getter.get(); }
    public void setValue(float value) { setter.accept(Math.max(min, Math.min(max, value))); }
    public float getMin() { return min; }
    public float getMax() { return max; }
    public int getDecimals() { return decimals; }
    public String getSuffix() { return suffix; }
    public boolean isPercentage() { return percentage; }

    public SliderSetting withDecimals(int decimals) { this.decimals = decimals; return this; }
    public SliderSetting withSuffix(String suffix) { this.suffix = suffix; return this; }
    public SliderSetting asPercentage() { this.percentage = true; return this; }

    public SliderSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibility = condition;
        return this;
    }

    @Override public String getName() { return name; }
    @Override public String getRawName() { return rawName; }
    @Override public SettingType getType() { return SettingType.SLIDER; }
    @Override public boolean isVisible() { return visibility.get(); }
}
