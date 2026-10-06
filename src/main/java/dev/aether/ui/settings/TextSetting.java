package dev.aether.ui.settings;

import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

// text input backed by a getter/setter
public class TextSetting extends AbstractSetting<TextSetting> {

    private final String placeholder;
    private final Supplier<String> getter;
    private final Consumer<String> setter;
    private boolean multiline = false;
    private int visibleLines = 4;

    public TextSetting(String name, String placeholder,
                       Supplier<String> getter, Consumer<String> setter) {
        super(name);
        this.placeholder = AetherLang.localize(placeholder);
        this.getter = getter;
        this.setter = setter;
    }

    public String getValue() { return getter.get(); }
    public void setValue(String value) { setter.accept(value); }
    public String getPlaceholder() { return placeholder; }
    public boolean isMultiline() { return multiline; }
    public int getVisibleLines() { return visibleLines; }

    public TextSetting multiline() {
        return multiline(4);
    }

    public TextSetting multiline(int visibleLines) {
        this.multiline = true;
        this.visibleLines = Math.max(2, visibleLines);
        return this;
    }

    @Override public SettingType getType() { return SettingType.TEXT; }
}
