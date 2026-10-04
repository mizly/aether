package dev.aether.ui.gui.plot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.Aether;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// config/aether/garden_plots.json: the last Configure Plots menu read on each skyblock profile, since every
// profile has its own garden. lastProfile answers until hypixel names the profile after a restart
public final class PlotMenuStore {
    public static final String UNKNOWN_PROFILE = "unknown";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int VERSION = 1;

    private final Path file;
    private final Map<String, PlotMenuSnapshot> profiles = new LinkedHashMap<>();
    private String lastProfile;
    private boolean loaded;

    public PlotMenuStore(Path file) {
        this.file = file;
    }

    public static PlotMenuStore config() {
        return new PlotMenuStore(FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("garden_plots.json"));
    }

    // the profile's menu, or the last one read when hypixel has not said which profile this is yet
    public synchronized PlotMenuSnapshot current(String profileId) {
        load();
        PlotMenuSnapshot snapshot = profileId == null ? null : profiles.get(profileId);
        if (snapshot == null && (profileId == null || profileId.equals(UNKNOWN_PROFILE)) && lastProfile != null) {
            snapshot = profiles.get(lastProfile);
        }
        return snapshot;
    }

    // true when it differs from what was stored
    public synchronized boolean put(String profileId, PlotMenuSnapshot snapshot) {
        load();
        String key = profileId == null ? UNKNOWN_PROFILE : profileId;
        boolean changed = !snapshot.sameSlots(profiles.get(key)) || !key.equals(lastProfile);
        profiles.put(key, snapshot);
        lastProfile = key;
        return changed;
    }

    public synchronized void save() {
        load();
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        if (lastProfile != null) {
            root.addProperty("lastProfile", lastProfile);
        }
        JsonObject all = new JsonObject();
        profiles.forEach((profile, snapshot) -> all.add(profile, toJson(snapshot)));
        root.add("profiles", all);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Aether.LOGGER.warn("Could not save garden plots to {}: {}", file, e.getMessage());
        }
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("lastProfile")) {
                lastProfile = root.get("lastProfile").getAsString();
            }
            JsonObject all = root.has("profiles") ? root.getAsJsonObject("profiles") : new JsonObject();
            for (Map.Entry<String, JsonElement> profile : all.entrySet()) {
                profiles.put(profile.getKey(), fromJson(profile.getValue().getAsJsonObject()));
            }
        } catch (IOException | RuntimeException e) {
            Aether.LOGGER.warn("Could not read garden plots from {}: {}", file, e.getMessage());
        }
    }

    private static JsonObject toJson(PlotMenuSnapshot snapshot) {
        JsonObject json = new JsonObject();
        json.addProperty("readAt", snapshot.readAtMillis());
        JsonObject plots = new JsonObject();
        snapshot.plots().forEach((plot, item) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", item.itemId());
            entry.addProperty("name", item.name());
            JsonArray lore = new JsonArray();
            item.lore().forEach(lore::add);
            entry.add("lore", lore);
            plots.add(Integer.toString(plot), entry);
        });
        json.add("plots", plots);
        return json;
    }

    private static PlotMenuSnapshot fromJson(JsonObject json) {
        Map<Integer, PlotMenuSnapshot.Slot> items = new HashMap<>();
        JsonObject plots = json.getAsJsonObject("plots");
        for (Map.Entry<String, JsonElement> plot : plots.entrySet()) {
            JsonObject entry = plot.getValue().getAsJsonObject();
            List<String> lore = new ArrayList<>();
            if (entry.has("lore")) {
                entry.getAsJsonArray("lore").forEach(line -> lore.add(line.getAsString()));
            }
            items.put(Integer.parseInt(plot.getKey()), new PlotMenuSnapshot.Slot(entry.get("item").getAsString(),
                    entry.has("name") ? entry.get("name").getAsString() : "", lore));
        }
        return new PlotMenuSnapshot(items, json.has("readAt") ? json.get("readAt").getAsLong() : 0L);
    }
}
