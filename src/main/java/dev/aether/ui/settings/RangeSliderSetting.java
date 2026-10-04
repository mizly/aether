package dev.aether.ui.settings;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class RangeSliderSetting extends AbstractSetting<RangeSliderSetting> {
    private final float min;
    private final float max;
    private final Supplier<Float> lowerGetter;
    private final Supplier<Float> upperGetter;
    private final BiConsumer<Float, Float> setter;

    private int decimals = 1;
    private String suffix = "";

    public RangeSliderSetting(String name, float min, float max,
                              Supplier<Float> lowerGetter,
                              Supplier<Float> upperGetter,
                              BiConsumer<Float, Float> setter) {
        super(name);
        this.min = min;
        this.max = max;
        this.lowerGetter = lowerGetter;
        this.upperGetter = upperGetter;
        this.setter = setter;
    }

    public float getLowerValue() { return lowerGetter.get(); }
    public float getUpperValue() { return upperGetter.get(); }
    public float getMin() { return min; }
    public float getMax() { return max; }
    public int getDecimals() { return decimals; }
    public String getSuffix() { return suffix; }

    public void setLowerValue(float value) {
        setValues(value, getUpperValue());
    }

    public void setUpperValue(float value) {
        setValues(getLowerValue(), value);
    }

    public void setValues(float lower, float upper) {
        float clampedLower = Math.max(min, Math.min(max, lower));
        float clampedUpper = Math.max(min, Math.min(max, upper));
        if (clampedLower > clampedUpper) {
            float swap = clampedLower;
            clampedLower = clampedUpper;
            clampedUpper = swap;
        }
        setter.accept(clampedLower, clampedUpper);
    }

    public RangeSliderSetting withDecimals(int decimals) { this.decimals = decimals; return this; }
    public RangeSliderSetting withSuffix(String suffix) { this.suffix = suffix; return this; }

    @Override public SettingType getType() { return SettingType.RANGE_SLIDER; }
}
