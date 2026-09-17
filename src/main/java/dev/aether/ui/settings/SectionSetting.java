package dev.aether.ui.settings;

import dev.aether.util.AetherLang;

import java.util.function.Supplier;

public final class SectionSetting implements Setting {
    private final String name;
    private final String rawName;
    private final String description;
    private Supplier<Boolean> visibility = () -> true;

    public SectionSetting(String name, String description) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.description = description == null ? "" : AetherLang.localize(description);
    }

    public String getDescription() {
        return description;
    }

    public SectionSetting visibleWhen(Supplier<Boolean> condition) {
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
        return SettingType.SECTION;
    }

    @Override
    public boolean isVisible() {
        return visibility.get();
    }
}
