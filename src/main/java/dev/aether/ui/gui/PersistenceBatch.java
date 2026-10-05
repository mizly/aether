package dev.aether.ui.gui;

import dev.aether.config.AetherConfig;
import dev.aether.config.Config;
import dev.aether.ui.theme.Theme;

// held open while a pointer drag is captured, so slider and colour drags write the config, the active
// profile and the theme file once on release instead of on every step
public final class PersistenceBatch {
    private boolean open;

    public boolean isOpen() {
        return open;
    }

    public void begin() {
        if (open) {
            return;
        }
        open = true;
        Config.beginBatch();
        Theme.beginSaveBatch();
    }

    public void end() {
        if (!open) {
            return;
        }
        open = false;
        Theme.endSaveBatch();
        if (Config.endBatch()) {
            AetherConfig.flush();
        }
    }
}
