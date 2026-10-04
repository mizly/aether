package dev.aether.ui.settings;

import java.util.function.Supplier;

public class InfoSetting extends AbstractSetting<InfoSetting> {
    private final Supplier<String> valueSupplier;
    private boolean multiline;

    public InfoSetting(String name, Supplier<String> valueSupplier) {
        super(name);
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

    @Override
    public SettingType getType() {
        return SettingType.INFO;
    }
}
