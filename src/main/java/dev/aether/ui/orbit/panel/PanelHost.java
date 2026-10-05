package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Clipboard;
import dev.aether.ui.gui.GuiCanvas;

import java.util.List;

// what aurora reads from and asks of the game; the in-game shell and the preview harness implement it
public interface PanelHost {

    // the game's own ui sounds, played on this client only
    enum Sound { CLICK }

    default void sound(Sound sound, float pitch) {
    }

    enum AuthState { SIGNED_IN, SIGNING_IN, SIGNED_OUT }

    record Account(String name, AuthState state, long totalSeconds) {
    }

    // macroName is null when no macro was started this session; restInMs is negative when no rest is planned
    record Session(String macroName, String macroItem, boolean stoppedByMenu, long sessionMs, long restInMs,
                   String failsafe, long failsafeAgoMs) {
        public boolean resumable() {
            return macroName != null;
        }
    }

    Account account();

    boolean streamerMode();

    Session session();

    List<String> profiles();

    String activeProfile();

    void loadProfile(String name);

    void beginLogin();

    void resume();

    void openMacroMenu();

    void openHudEditor();

    void close();

    void paintHead(GuiCanvas canvas, float x, float y, float size);

    Clipboard clipboard();
}
