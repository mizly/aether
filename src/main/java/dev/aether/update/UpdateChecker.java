package dev.aether.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.Aether;
import dev.aether.notification.NotificationManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public final class UpdateChecker {
    private static final String MOD_ID = "aether";
    private static final String API_URL = "https://api.github.com/repos/mizly/aether/releases/latest";
    private static final String RELEASES_URL = "https://github.com/mizly/aether/releases/latest";
    private static final int TIMEOUT_MS = 8_000;

    private static volatile String cachedLatestVersion;
    private static volatile boolean fetchStarted;

    private UpdateChecker() {
    }

    public static void checkAndNotify() {
        Minecraft client = Minecraft.getInstance();
        if (!fetchStarted) {
            fetchStarted = true;
            CompletableFuture.runAsync(() -> {
                String latest = fetchLatestTag();
                cachedLatestVersion = latest != null ? latest : "";
                if (latest != null) {
                    notifyIfOutdated(latest);
                }
            });
            return;
        }

        if (cachedLatestVersion != null && !cachedLatestVersion.isEmpty()) {
            notifyIfOutdated(cachedLatestVersion);
        }
    }

    public static String getCachedLatestVersion() {
        return cachedLatestVersion;
    }

    private static String fetchLatestTag() {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(API_URL).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "aether-mod");
            connection.setRequestProperty("Accept", "application/vnd.github+json");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Aether.LOGGER.warn("[aether] UpdateChecker: GitHub API returned HTTP {}", status);
                return null;
            }

            try (InputStream in = connection.getInputStream();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                if (obj.has("tag_name")) {
                    return obj.get("tag_name").getAsString().trim();
                }
            }
        } catch (IOException | IllegalStateException e) {
            Aether.LOGGER.warn("[aether] UpdateChecker: failed to reach GitHub: {}", e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private static void notifyIfOutdated(String latestTag) {
        Minecraft client = Minecraft.getInstance();
        String currentVersion = getCurrentVersion();
        String normalizedLatest = normalizeTag(latestTag);
        String normalizedCurrent = normalizeTag(currentVersion);

        if (!isNewer(normalizedLatest, normalizedCurrent)) {
            Aether.LOGGER.info("[aether] UpdateChecker: up to date ({})", currentVersion);
            return;
        }

        Aether.LOGGER.info("[aether] UpdateChecker: update available: current={} latest={}",
                currentVersion, latestTag);

        client.execute(() -> {
            if (client.player == null) {
                return;
            }

            NotificationManager.warning("Aether Update Available",
                    "Current: " + currentVersion + " Latest: " + latestTag,
                    8000);
            client.player.sendSystemMessage(createUpdateMessage(currentVersion, latestTag));
        });
    }

    private static MutableComponent createUpdateMessage(String currentVersion, String latestTag) {
        MutableComponent prefix = Component.literal("[Aether] ")
                .withStyle(ChatFormatting.DARK_GRAY);
        MutableComponent message = Component.literal("Update available! Current: ")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(currentVersion).withStyle(ChatFormatting.RED))
                .append(Component.literal(" -> Latest: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(latestTag).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" ").withStyle(ChatFormatting.GRAY));
        MutableComponent link = Component.literal("[Download]")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(RELEASES_URL)))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Open GitHub releases page"))));

        return prefix.append(message).append(link);
    }

    private static String getCurrentVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String normalizeTag(String tag) {
        if (tag == null) {
            return "";
        }
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    static boolean isNewer(String candidate, String current) {
        VersionParts candidateParts = parseVersion(candidate);
        VersionParts currentParts = parseVersion(current);

        for (int i = 0; i < Math.max(candidateParts.numbers.length, currentParts.numbers.length); i++) {
            int candidateNumber = i < candidateParts.numbers.length ? candidateParts.numbers[i] : 0;
            int currentNumber = i < currentParts.numbers.length ? currentParts.numbers[i] : 0;
            if (candidateNumber > currentNumber) {
                return true;
            }
            if (candidateNumber < currentNumber) {
                return false;
            }
        }

        return candidateParts.revision > currentParts.revision;
    }

    private static VersionParts parseVersion(String version) {
        if (version == null || version.isEmpty()) {
            return new VersionParts(new int[]{0}, 0);
        }

        String clean = version.split("\\+")[0];
        int revision = 0;

        int revisionMarker = clean.lastIndexOf("-r");
        if (revisionMarker >= 0) {
            String revisionPart = clean.substring(revisionMarker + 2);
            try {
                revision = Integer.parseInt(revisionPart);
                clean = clean.substring(0, revisionMarker);
            } catch (NumberFormatException ignored) {
                // Leave revision at 0 if the suffix is malformed.
            }
        } else {
            int suffixMarker = clean.indexOf('-');
            if (suffixMarker >= 0) {
                clean = clean.substring(0, suffixMarker);
            }
        }

        String[] parts = clean.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return new VersionParts(numbers, revision);
    }

    private record VersionParts(int[] numbers, int revision) {
    }
}
