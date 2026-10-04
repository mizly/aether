package dev.aether.ui.gui.preview;

import dev.aether.ui.gui.BoundKey;
import dev.aether.ui.gui.Clipboard;
import dev.aether.ui.gui.GuiHost;
import dev.aether.ui.gui.plot.GardenPlotData;
import dev.aether.ui.settings.KeybindSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// a deterministic stand-in for the game: fixed account, session and garden data, an in-memory clipboard,
// and counters instead of screens, so previews and tests never need a client. it never saves key options
public final class PreviewGuiHost implements GuiHost {
    private final List<String> keyChanges = new ArrayList<>();
    private final MemoryClipboard clipboard = new MemoryClipboard();
    private int closeRequests;
    private int macroMenuOpens;
    private int hudEditorOpens;
    private int clicks;
    private int resumes;
    private int logins;

    @Override
    public Clipboard clipboard() {
        return clipboard;
    }

    @Override
    public void closeScreen() {
        closeRequests++;
    }

    @Override
    public void openMacroMenu() {
        macroMenuOpens++;
    }

    @Override
    public void openHudEditor() {
        hudEditorOpens++;
    }

    @Override
    public void playClick() {
        clicks++;
    }

    @Override
    public int mcGuiScale() {
        return 3;
    }

    @Override
    public AccountInfo account() {
        return new AccountInfo(AccountInfo.Status.SIGNED_IN, "AetherDev", false, 4L * 3600L + 32L * 60L, () -> logins++);
    }

    @Override
    public SessionInfo session() {
        return new SessionInfo("Farming Macro", 72L * 60_000L, 23L * 60_000L, "Rotation", 3L * 60_000L, () -> resumes++);
    }

    @Override
    public void bindKey(KeybindSetting setting, BoundKey key) {
        keyChanges.add("bind " + setting.getRawName() + " " + key.type() + " " + key.code());
    }

    @Override
    public void clearKey(KeybindSetting setting) {
        keyChanges.add("clear " + setting.getRawName());
    }

    @Override
    public void resetKey(KeybindSetting setting) {
        keyChanges.add("reset " + setting.getRawName());
    }

    @Override
    public void saveKeyOptions() {
    }

    @Override
    public GardenPlotData plots() {
        return new GardenPlotData() {
            @Override
            public int currentPlot() {
                return 5;
            }

            @Override
            public Set<Integer> infestedPlots() {
                return Set.of(3, 12);
            }

            @Override
            public int pestCount() {
                return 4;
            }
        };
    }

    public List<String> keyChanges() {
        return keyChanges;
    }

    public int closeRequests() {
        return closeRequests;
    }

    public int macroMenuOpens() {
        return macroMenuOpens;
    }

    public int hudEditorOpens() {
        return hudEditorOpens;
    }

    public int clicks() {
        return clicks;
    }

    public int resumes() {
        return resumes;
    }

    public int logins() {
        return logins;
    }

    public static final class MemoryClipboard implements Clipboard {
        private String text = "";

        @Override
        public String read() {
            return text;
        }

        @Override
        public void write(String value) {
            text = value;
        }
    }
}
