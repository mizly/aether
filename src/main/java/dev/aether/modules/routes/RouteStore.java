package dev.aether.modules.routes;

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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// one json file per route, grouped in a folder per macro under config/aether/routes
public final class RouteStore {
    public record Folder(String id, String displayName) {}

    public static final Folder STRIDER_FISHING = new Folder("strider_fishing", "Strider Fishing");
    public static final List<Folder> FOLDERS = List.of(STRIDER_FISHING);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String EXTENSION = ".json";

    private final Path root;

    public RouteStore(Path root) {
        this.root = root;
    }

    public static RouteStore config() {
        return new RouteStore(FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("routes"));
    }

    public Path folderPath(Folder folder) {
        return root.resolve(folder.id());
    }

    public List<String> list(Folder folder) {
        Path dir = folderPath(folder);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(file -> file.toLowerCase(Locale.ROOT).endsWith(EXTENSION))
                    .map(file -> file.substring(0, file.length() - EXTENSION.length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            Aether.LOGGER.warn("Could not list routes in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    public Route load(Folder folder, String name) {
        String safe = sanitizeName(name);
        if (safe.isEmpty()) {
            return null;
        }
        Path file = folderPath(folder).resolve(safe + EXTENSION);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return fromJson(safe, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Aether.LOGGER.warn("Could not read route {}: {}", file, e.getMessage());
            return null;
        }
    }

    public boolean save(Folder folder, Route route) {
        String safe = sanitizeName(route.name());
        if (safe.isEmpty()) {
            return false;
        }
        Path dir = folderPath(folder);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(safe + EXTENSION), toJson(route), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            Aether.LOGGER.warn("Could not save route {}: {}", safe, e.getMessage());
            return false;
        }
    }

    public boolean delete(Folder folder, String name) {
        String safe = sanitizeName(name);
        if (safe.isEmpty()) {
            return false;
        }
        try {
            return Files.deleteIfExists(folderPath(folder).resolve(safe + EXTENSION));
        } catch (IOException e) {
            Aether.LOGGER.warn("Could not delete route {}: {}", safe, e.getMessage());
            return false;
        }
    }

    public boolean rename(Folder folder, Route route, String newName) {
        String safe = sanitizeName(newName);
        if (safe.isEmpty() || safe.equalsIgnoreCase(route.name())) {
            return false;
        }
        if (list(folder).stream().anyMatch(existing -> existing.equalsIgnoreCase(safe))) {
            return false;
        }
        String oldName = route.name();
        route.rename(safe);
        if (!save(folder, route)) {
            route.rename(oldName);
            return false;
        }
        delete(folder, oldName);
        return true;
    }

    public String nextFreeName(Folder folder) {
        List<String> taken = list(folder);
        for (int i = 1; ; i++) {
            String candidate = "Route " + i;
            if (taken.stream().noneMatch(existing -> existing.equalsIgnoreCase(candidate))) {
                return candidate;
            }
        }
    }

    // file names double as route names, so anything a filesystem could choke on is dropped
    static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9 _\\-]", "").trim();
        return cleaned.length() > 32 ? cleaned.substring(0, 32).trim() : cleaned;
    }

    static String toJson(Route route) {
        JsonObject object = new JsonObject();
        object.addProperty("warp", route.warp());
        JsonArray legs = new JsonArray();
        for (Route.Waypoint waypoint : route.waypoints()) {
            JsonObject leg = new JsonObject();
            leg.addProperty("x", waypoint.x());
            leg.addProperty("y", waypoint.y());
            leg.addProperty("z", waypoint.z());
            leg.addProperty("type", waypoint.type().name());
            legs.add(leg);
        }
        object.add("waypoints", legs);
        return GSON.toJson(object);
    }

    static Route fromJson(String name, String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        String warp = object.has("warp") ? object.get("warp").getAsString() : Route.DEFAULT_WARP;
        Route route = new Route(name, warp);
        JsonElement legs = object.get("waypoints");
        if (legs == null || !legs.isJsonArray()) {
            return route;
        }
        for (JsonElement element : legs.getAsJsonArray()) {
            JsonObject leg = element.getAsJsonObject();
            route.add(new Route.Waypoint(
                    leg.get("x").getAsInt(),
                    leg.get("y").getAsInt(),
                    leg.get("z").getAsInt(),
                    parseType(leg.has("type") ? leg.get("type").getAsString() : "")));
        }
        return route;
    }

    private static Route.LegType parseType(String value) {
        try {
            return Route.LegType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Route.LegType.WALK;
        }
    }

    public List<Route> loadAll(Folder folder) {
        List<Route> routes = new ArrayList<>();
        for (String name : list(folder)) {
            Route route = load(folder, name);
            if (route != null) {
                routes.add(route);
            }
        }
        return routes;
    }
}
