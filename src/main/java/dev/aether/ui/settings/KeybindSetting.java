package dev.aether.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.function.Supplier;
import dev.aether.util.AetherLang;

public class KeybindSetting implements Setting {
    private final String name;
    private final String rawName;
    private final KeyMapping keyMapping;
    private Supplier<Boolean> visibility = () -> true;

    public KeybindSetting(String name, KeyMapping keyMapping) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.keyMapping = keyMapping;
    }

    public KeyMapping getKeyMapping() {
        return keyMapping;
    }

    public String getBoundKeyName() {
        return keyMapping.getTranslatedKeyMessage().getString();
    }

    public boolean isDefault() {
        return keyMapping.isDefault();
    }

    public void setBoundKey(InputConstants.Key key) {
        keyMapping.setKey(key);
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    public void resetToDefault() {
        setBoundKey(keyMapping.getDefaultKey());
    }

    public void clearBinding() {
        setBoundKey(InputConstants.UNKNOWN);
    }

    public KeybindSetting visibleWhen(Supplier<Boolean> condition) {
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
        return SettingType.KEYBIND;
    }

    @Override
    public boolean isVisible() {
        return visibility.get();
    }
}
