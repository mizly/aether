package dev.aether.ui.settings;

import java.util.function.Supplier;
import dev.aether.util.AetherLang;

public class InfoSetting implements Setting {
    private final String name;
    private final String rawName;
    private final Supplier<String> valueSupplier;
    private Supplier<Boolean> visibility = () -> true;
    private boolean multiline;

    public InfoSetting(String name, Supplier<String> valueSupplier) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.valueSupplier = valueSupplier;
    }

    public String getValue() {
        String value = valueSupplier.get();
        return value == null ? "" : value;
    }

    public boolean isMultiline() {
        return multiline;
    }

    public InfoSetting multiline() {
        this.multiline = true;
        return this;
    }

    public InfoSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibility = condition;
        return this;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getRawName() {
        return rawName;
    }

    @Override
    public SettingType getType() {
        return SettingType.INFO;
    }

    @Override
    public boolean isVisible() {
        return visibility.get();
    }
}
