package dev.aether.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.Aether;
import dev.aether.config.AetherConfig;
import dev.aether.notification.NotificationManager;
import dev.aether.ui.MainGUIRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AetherLanguageManager {
    private static final URI REPO_CONTENTS_URI = URI.create("https://api.github.com/repos/iceangelsaint/aether-language-packs/contents");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final Path LANGUAGE_DIR = FabricLoader.getInstance().getConfigDir()
            .resolve("aether")
            .resolve("lang");
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final AtomicBoolean REFRESH_IN_PROGRESS = new AtomicBoolean();

    private AetherLanguageManager() {
    }

    public static void init() {
        ensureLanguageDir();
        reloadSelectedLanguage();
        if (INITIALIZED.compareAndSet(false, true)) {
            refreshFromRemoteAsync(false);
        }
    }

    public static void onConfigLoaded() {
        ensureLanguageDir();
        reloadSelectedLanguage();
        refreshLocalizedUi();
    }

    public static List<String> getAvailableLanguageCodes() {
        ensureLanguageDir();
        List<String> codes = new ArrayList<>();
        addLanguageCode(codes, "en_us");
        addLanguageCode(codes, getSelectedLanguageCode());

        try (var stream = Files.list(LANGUAGE_DIR)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> normalizeLanguageCode(name.substring(0, name.length() - 5)))
                    .sorted(languageComparator())
                    .forEach(code -> addLanguageCode(codes, code));
        } catch (IOException e) {
            Aether.LOGGER.debug("Failed to list cached language packs: {}", e.getMessage());
        }

        codes.sort(languageComparator());
        return List.copyOf(codes);
    }

    public static String getSelectedLanguageCode() {
        return normalizeLanguageCode(AetherConfig.LANGUAGE_CODE.get());
    }

    public static int getSelectedLanguageIndex(List<String> options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }

        String selected = getSelectedLanguageCode();
        int index = options.indexOf(selected);
        return index >= 0 ? index : 0;
    }

    public static void selectLanguage(String languageCode) {
        String normalized = normalizeLanguageCode(languageCode);
        if (!normalized.equals(getSelectedLanguageCode())) {
            AetherConfig.LANGUAGE_CODE.set(normalized);
            AetherConfig.save();
        }

        reloadSelectedLanguage();
        refreshLocalizedUi();
        NotificationManager.success("Language Changed", normalized);
    }

    public static void refreshFromRemoteAsync(boolean notifyUser) {
        if (!REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
            if (notifyUser) {
                runOnClientThread(() -> NotificationManager.info("Language Pack Refresh", "Refresh already in progress"));
            }
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                int downloaded = refreshFromRemote();
                reloadSelectedLanguage();
                refreshLocalizedUi();
                if (notifyUser) {
                    String message = downloaded == 1
                            ? AetherLang.localize("Downloaded 1 language pack")
                            : String.format(Locale.US, AetherLang.localize("Downloaded %d language packs"), downloaded);
                    runOnClientThread(() -> NotificationManager.success("Language Pack Refresh", message));
                }
            } catch (Exception e) {
                Aether.LOGGER.warn("Failed to refresh language packs: {}", e.getMessage());
                if (notifyUser) {
                    runOnClientThread(() -> NotificationManager.error("Language Pack Refresh Failed",
                            e.getMessage() == null || e.getMessage().isBlank()
                                    ? "Unable to fetch language packs"
                                    : e.getMessage()));
                }
            } finally {
                REFRESH_IN_PROGRESS.set(false);
            }
        }, "aether-language-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    public static void reloadSelectedLanguage() {
        String selected = getSelectedLanguageCode();
        Map<String, String> translations = loadTranslations(selected);
        AetherLang.installExternalTranslations(selected, translations);
    }

    private static int refreshFromRemote() throws IOException, InterruptedException {
        ensureLanguageDir();
        JsonArray contents = fetchRepositoryContents();
        int downloaded = 0;

        for (JsonElement element : contents) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject entry = element.getAsJsonObject();
            String type = getAsString(entry, "type");
            String name = getAsString(entry, "name");
            String downloadUrl = getAsString(entry, "download_url");
            if (!"file".equals(type) || name.isBlank() || downloadUrl.isBlank() || !name.endsWith(".json")) {
                continue;
            }

            String body = fetchString(URI.create(downloadUrl));
            parseTranslationObject(body);
            writeLanguageFile(name, body);
            downloaded++;
        }

        return downloaded;
    }

    private static JsonArray fetchRepositoryContents() throws IOException, InterruptedException {
        String body = fetchString(REPO_CONTENTS_URI);
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            throw new IOException("GitHub contents response was not an array");
        }
        return root.getAsJsonArray();
    }

    private static String fetchString(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AetherClient")
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
    }

    private static void writeLanguageFile(String fileName, String body) throws IOException {
        Path target = LANGUAGE_DIR.resolve(fileName);
        Path temp = target.resolveSibling(fileName + ".tmp");
        Files.writeString(temp, body, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, String> loadTranslations(String languageCode) {
        Path path = LANGUAGE_DIR.resolve(normalizeLanguageCode(languageCode) + ".json");
        if (!Files.exists(path)) {
            return Map.of();
        }

        try {
            return parseTranslationObject(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Aether.LOGGER.warn("Failed to load language pack '{}': {}", languageCode, e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, String> parseTranslationObject(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Language pack root must be a JSON object");
        }

        Map<String, String> translations = new LinkedHashMap<>();
        JsonObject object = root.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                continue;
            }
            translations.put(entry.getKey(), entry.getValue().getAsString());
        }
        return translations;
    }

    private static void refreshLocalizedUi() {
        MainGUIRegistry.invalidate();
        MainGUIRegistry.refresh();
        runOnClientThread(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.screen != null) {
                client.screen.resize(client.getWindow().getGuiScaledWidth(),
                        client.getWindow().getGuiScaledHeight());
            }
        });
    }

    private static void runOnClientThread(Runnable action) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.execute(action);
    }

    private static void ensureLanguageDir() {
        try {
            Files.createDirectories(LANGUAGE_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create language directory", e);
        }
    }

    private static void addLanguageCode(List<String> codes, String code) {
        String normalized = normalizeLanguageCode(code);
        if (!codes.contains(normalized)) {
            codes.add(normalized);
        }
    }

    private static Comparator<String> languageComparator() {
        return Comparator.comparing((String code) -> !"en_us".equals(code))
                .thenComparing(code -> code, String.CASE_INSENSITIVE_ORDER);
    }

    private static String normalizeLanguageCode(String value) {
        if (value == null || value.isBlank()) {
            return "en_us";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String getAsString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
