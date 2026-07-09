package dev.aether.ui.settings;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

/**
 * Multi-select setting backed by a bitmask integer.
 * Option at index N maps to bit N (value 1 &lt;&lt; N).
 */
public class MultiDropdownSetting implements Setting {

    // Shared layout constants used by renderer and interaction handler
    public static final float CHIP_H       = 28f;
    public static final float CHIP_PAD_X   = 10f;
    public static final float CHIP_GAP     = 6f;
    public static final float CHIP_FONT_SZ = 12f;

    private final String name;
    private final String rawName;
    private final List<String> options;
    private final Supplier<Integer> getter;
    private final Consumer<Integer> setter;
    private Supplier<Boolean> visibility = () -> true;

    public MultiDropdownSetting(String name, List<String> options,
                                Supplier<Integer> getter, Consumer<Integer> setter) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.options = options.stream().map(AetherLang::localize).toList();
        this.getter = getter;
        this.setter = setter;
    }

    public List<String> getOptions() { return options; }

    public boolean isSelected(int index) {
        return (getter.get() & (1 << index)) != 0;
    }

    public void toggleOption(int index) {
        setter.accept(getter.get() ^ (1 << index));
    }

    /** Estimated total width of all chips, using font-size approximation (no NVG needed). */
    public float estimateTotalWidth() {
        float total = 0f;
        for (int i = 0; i < options.size(); i++) {
            total += options.get(i).length() * (CHIP_FONT_SZ * 0.52f) + CHIP_PAD_X * 2f;
            if (i > 0) total += CHIP_GAP;
        }
        return total;
    }

    public MultiDropdownSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibility = condition;
        return this;
    }

    @Override public String getName()      { return name; }
    @Override public String getRawName()   { return rawName; }
    @Override public SettingType getType() { return SettingType.MULTI_DROPDOWN; }
    @Override public boolean isVisible()   { return visibility.get(); }
}
