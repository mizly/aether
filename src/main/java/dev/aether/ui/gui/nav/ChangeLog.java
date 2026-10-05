package dev.aether.ui.gui.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.theme.Theme;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

// the last writes made through the gui, newest first, with the value before each so Home can list them
// and undo them; kept in the theme file's gui state, outside exports
public final class ChangeLog {
    public static final int LIMIT = 30;
    // a slider drag or a burst of typing on one setting is one change
    private static final long MERGE_MILLIS = 2000L;
    private static final String KEY = "changes";
    private static final ChangeLog SHARED = new ChangeLog(() -> Theme.GUI_STATE, Theme::saveTheme,
            () -> Instant.now().toEpochMilli());

    public record Change(SettingKey key, String before, String after, long atMillis) {
    }

    private final Supplier<JsonObject> state;
    private final Runnable save;
    private final LongSupplier epochMillis;

    public ChangeLog(Supplier<JsonObject> state, Runnable save, LongSupplier epochMillis) {
        this.state = state;
        this.save = save;
        this.epochMillis = epochMillis;
    }

    public static ChangeLog shared() {
        return SHARED;
    }

    public List<Change> recent() {
        List<Change> out = new ArrayList<>();
        JsonElement array = state.get().get(KEY);
        if (array == null || !array.isJsonArray()) {
            return out;
        }
        for (JsonElement element : array.getAsJsonArray()) {
            Change change = decode(element);
            if (change != null) {
                out.add(change);
            }
        }
        return out;
    }

    public void record(SettingKey key, String before, String after) {
        if (key == null || before == null || after == null || before.equals(after)) {
            return;
        }
        List<Change> changes = recent();
        long now = epochMillis.getAsLong();
        if (!changes.isEmpty()) {
            Change last = changes.getFirst();
            if (last.key().equals(key) && now - last.atMillis() <= MERGE_MILLIS) {
                changes.removeFirst();
                before = last.before();
            }
        }
        if (!before.equals(after)) {
            changes.addFirst(new Change(key, before, after, now));
        }
        while (changes.size() > LIMIT) {
            changes.removeLast();
        }
        write(changes);
    }

    // undoes the newest change; empty when there is none or its setting is gone (that entry is dropped)
    public Optional<Change> undoLast(NavModel model) {
        List<Change> changes = recent();
        return changes.isEmpty() ? Optional.empty() : undo(changes.getFirst(), model);
    }

    public Optional<Change> undo(Change change, NavModel model) {
        List<Change> changes = recent();
        if (!changes.remove(change)) {
            return Optional.empty();
        }
        write(changes);
        Optional<Setting> setting = model.setting(change.key());
        if (setting.isEmpty() || !SettingValues.write(setting.get(), change.before())) {
            return Optional.empty();
        }
        return Optional.of(change);
    }

    public void clear() {
        write(List.of());
    }

    private void write(List<Change> changes) {
        JsonArray array = new JsonArray();
        for (Change change : changes) {
            JsonObject obj = PinStore.encode(change.key());
            obj.addProperty("before", change.before());
            obj.addProperty("after", change.after());
            obj.addProperty("at", change.atMillis());
            array.add(obj);
        }
        state.get().add(KEY, array);
        save.run();
    }

    private static Change decode(JsonElement element) {
        SettingKey key = PinStore.decode(element);
        if (key == null) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        try {
            return new Change(key, Objects.requireNonNull(obj.get("before")).getAsString(),
                    Objects.requireNonNull(obj.get("after")).getAsString(), obj.get("at").getAsLong());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
