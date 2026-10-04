package dev.aether.ui.gui;

import dev.aether.ui.gui.plot.GardenPlotData;
import dev.aether.ui.settings.KeybindSetting;

// everything minecraft-specific the gui needs, so the view never touches the game: the screen shell
// implements it in game and the preview harness fakes it
public interface GuiHost {
    Clipboard clipboard();

    void closeScreen();

    void openMacroMenu();

    void openHudEditor();

    void playClick();

    // minecraft's own gui scale, for styles that draw vanilla textures pixel for pixel
    int mcGuiScale();

    AccountInfo account();

    SessionInfo session();

    void bindKey(KeybindSetting setting, BoundKey key);

    void clearKey(KeybindSetting setting);

    void resetKey(KeybindSetting setting);

    void saveKeyOptions();

    GardenPlotData plots();

    // name is already "Hidden" under Streamer Mode, with nameHidden set so a style can say why
    record AccountInfo(Status status, String name, boolean nameHidden, long playedSeconds, Runnable login) {
        public enum Status { SIGNED_OUT, SIGNING_IN, SIGNED_IN }
    }

    // the session strip: opening the gui stops automation, so this says what stopped and offers it back.
    // stoppedMacro and resume are null when nothing was running; negative times mean none
    record SessionInfo(String stoppedMacro, long sessionMillis, long nextRestMillis, String lastFailsafe,
                       long failsafeAgoMillis, Runnable resume) {
    }
}
