package dev.aether.ui.settings;

import dev.aether.util.AetherLang;

public final class SectionSetting extends AbstractSetting<SectionSetting> {
    private final String description;

    public SectionSetting(String name, String description) {
        super(name);
        this.description = description == null ? "" : AetherLang.localize(description);
        if (description != null && !description.isBlank()) {
            describe(description);
        }
    }

    public String getDescription() {
        return description;
    }

    @Override
    public SettingType getType() {
        return SettingType.SECTION;
    }
}
