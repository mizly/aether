package dev.aether.ui.gui.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aether.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// settings pinned to Home, by stable key; stored in the theme file's gui state so they never travel with
// a shared theme or config profile
public final class PinStore {
    private static final String KEY = "pins";
    private static final PinStore SHARED = new PinStore(() -> Theme.GUI_STATE, Theme::saveTheme);

    private final Supplier<JsonObject> state;
    private final Runnable save;

    public PinStore(Supplier<JsonObject> state, Runnable save) {
        this.state = state;
        this.save = save;
    }

    public static PinStore shared() {
        return SHARED;
    }

    public List<SettingKey> pins() {
        List<SettingKey> out = new ArrayList<>();
        JsonElement array = state.get().get(KEY);
        if (array == null || !array.isJsonArray()) {
            return out;
        }
        for (JsonElement element : array.getAsJsonArray()) {
            SettingKey key = decode(element);
            if (key != null && !out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    public boolean isPinned(SettingKey key) {
        return pins().contains(key);
    }

    public void pin(SettingKey key) {
        List<SettingKey> pins = pins();
        if (!pins.contains(key)) {
            pins.add(key);
            write(pins);
        }
    }

    public void unpin(SettingKey key) {
        List<SettingKey> pins = pins();
        if (pins.remove(key)) {
            write(pins);
        }
    }

    // returns whether the setting is pinned afterwards
    public boolean toggle(SettingKey key) {
        if (isPinned(key)) {
            unpin(key);
            return false;
        }
        pin(key);
        return true;
    }

    private void write(List<SettingKey> pins) {
        JsonArray array = new JsonArray();
        pins.forEach(pin -> array.add(encode(pin)));
        state.get().add(KEY, array);
        save.run();
    }

    static JsonObject encode(SettingKey key) {
        JsonObject obj = new JsonObject();
        obj.addProperty("page", key.pageId());
        obj.addProperty("group", key.group().groupRawName());
        obj.addProperty("groupIndex", key.group().ordinal());
        obj.addProperty("setting", key.settingRawName());
        obj.addProperty("settingIndex", key.ordinal());
        return obj;
    }

    static SettingKey decode(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        try {
            return new SettingKey(new GroupKey(obj.get("page").getAsString(), obj.get("group").getAsString(),
                    obj.get("groupIndex").getAsInt()), obj.get("setting").getAsString(),
                    obj.get("settingIndex").getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
