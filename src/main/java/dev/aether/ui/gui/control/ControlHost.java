package dev.aether.ui.gui.control;

import dev.aether.ui.gui.BoundKey;
import dev.aether.ui.gui.Clipboard;
import dev.aether.ui.settings.KeybindSetting;

// what controls need from outside the gui: the clipboard and minecraft's key bindings. the game host
// saves options on every bind; the preview host never touches them
public interface ControlHost {
    Clipboard clipboard();

    default String keyName(KeybindSetting setting) {
        return setting.getBoundKeyName();
    }

    default boolean keyIsDefault(KeybindSetting setting) {
        return setting.isDefault();
    }

    // another binding shares this key (KeyMapping.same)
    default boolean keyConflicts(KeybindSetting setting) {
        return false;
    }

    default void bindKey(KeybindSetting setting, BoundKey key) {
    }

    default void clearKey(KeybindSetting setting) {
    }

    default void resetKey(KeybindSetting setting) {
    }

    default void playClick() {
    }

    // an in-memory clipboard and no key bindings, for tests and previews
    static ControlHost detached() {
        return new ControlHost() {
            private final Clipboard clipboard = new Clipboard() {
                private String text = "";

                @Override
                public String read() {
                    return text;
                }

                @Override
                public void write(String value) {
                    text = value == null ? "" : value;
                }
            };

            @Override
            public Clipboard clipboard() {
                return clipboard;
            }
        };
    }
}
