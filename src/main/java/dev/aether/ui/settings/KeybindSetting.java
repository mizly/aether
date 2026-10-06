package dev.aether.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public class KeybindSetting extends AbstractSetting<KeybindSetting> {
    private final KeyMapping keyMapping;

    public KeybindSetting(String name, KeyMapping keyMapping) {
        super(name);
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

    @Override
    public SettingType getType() {
        return SettingType.KEYBIND;
    }
}
