package dev.aether.ui.settings;

import java.util.function.Consumer;
import java.util.function.Supplier;

// numeric slider backed by a getter/setter
public class SliderSetting extends AbstractSetting<SliderSetting> {

    private final float min;
    private final float max;
    private final Supplier<Float> getter;
    private final Consumer<Float> setter;

    private int decimals = 1;
    private String suffix = "";
    private boolean percentage = false;

    public SliderSetting(String name, float min, float max,
                         Supplier<Float> getter, Consumer<Float> setter) {
        super(name);
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

    @Override public SettingType getType() { return SettingType.SLIDER; }
}
