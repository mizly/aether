package dev.aether.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.Aether;
import dev.aether.notification.NotificationManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class AutoUpdateInstaller {
    private static final String MOD_ID = "aether";
    private static final String API_URL = "https://api.github.com/repos/mizly/aether/releases/latest";
    private static final String POPUP_TITLE = "Aether";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static volatile boolean installStarted;
    private static volatile String status = "Idle";

    private AutoUpdateInstaller() {
    }

    public static void checkAndInstallLatest() {
        if (installStarted) {
            return;
        }

        installStarted = true;
        status = "Checking for updates";
        CompletableFuture.runAsync(() -> {
            try {
                Release release = fetchLatestRelease();
                String currentVersion = currentVersion();
                String latestVersion = stripTagPrefix(release.tagName());
                if (!UpdateChecker.isNewer(latestVersion, stripTagPrefix(currentVersion))) {
                    status = "Current (" + currentVersion + ")";
                    Aether.LOGGER.info("[aether] AutoUpdate: already current ({})", currentVersion);
                    return;
                }

                status = "Downloading " + release.tagName();
                byte[] jarBytes = downloadReleaseJar(release);
                Install install = installUpdate(release, jarBytes);
                Path helperScript = schedulePostExitPrompt(install, currentVersion, release.tagName());
                status = "Installed: " + currentVersion + " -> " + release.tagName();
                Aether.LOGGER.info(
                        "[aether] AutoUpdate: installed update {} -> {}; newModPath={}, oldModDeleted={}, helper={}",
                        currentVersion,
                        release.tagName(),
                        install.newModPath(),
                        install.oldModDeleted(),
                        helperScript);

                Minecraft client = Minecraft.getInstance();
                client.execute(() -> NotificationManager.success(
                        "Aether Updated",
                        "The mod has been updated from version " + currentVersion + " to version "
                                + release.tagName() + "!",
                        10000));
            } catch (Exception e) {
                installStarted = false;
                status = "Failed: " + e.getMessage();
                Aether.LOGGER.warn("[aether] AutoUpdate: failed to install latest release", e);
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> NotificationManager.error("Aether Auto Update Failed", e.getMessage(), 8000));
            }
        });
    }

    public static String getStatus() {
        return status;
    }

    private static Release fetchLatestRelease() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "aether-mod")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub returned HTTP " + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String tagName = root.has("tag_name") ? root.get("tag_name").getAsString().trim() : "";
        if (tagName.isEmpty()) {
            throw new IOException("Latest GitHub release did not include a tag.");
        }

        JsonArray assets = root.has("assets") && root.get("assets").isJsonArray()
                ? root.getAsJsonArray("assets")
                : new JsonArray();
        for (JsonElement assetElement : assets) {
            if (!assetElement.isJsonObject()) {
                continue;
            }
            JsonObject asset = assetElement.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString().trim() : "";
            String downloadUrl = asset.has("browser_download_url")
                    ? asset.get("browser_download_url").getAsString().trim()
                    : "";
            if (name.toLowerCase(Locale.ROOT).endsWith(".jar") && !downloadUrl.isEmpty()) {
                return new Release(tagName, name, URI.create(downloadUrl));
            }
        }

        throw new IOException("Latest GitHub release did not include a jar asset.");
    }

    private static byte[] downloadReleaseJar(Release release) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(release.downloadUri())
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", "aether-mod")
                .GET()
                .build();
        HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null) {
            throw new IOException("Jar download returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static Install installUpdate(Release release, byte[] jarBytes) throws IOException {
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path modsDir = gameDir.resolve("mods").toAbsolutePath().normalize();
        Files.createDirectories(modsDir);

        Path oldModPath = currentModPath();
        Path newModPath = resolveInstallPath(modsDir, release, oldModPath);
        if (!newModPath.startsWith(modsDir)) {
            throw new IOException("Resolved update target escaped the mods directory.");
        }

        Path tempPath = modsDir.resolve(newModPath.getFileName().toString() + ".part").toAbsolutePath().normalize();
        Aether.LOGGER.info("[aether] AutoUpdate: writing update temp file {}", tempPath);
        Files.write(tempPath, jarBytes);
        try {
            Files.move(tempPath, newModPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Aether.LOGGER.info("[aether] AutoUpdate: moved update jar into place with atomic move.");
        } catch (IOException atomicMoveFailure) {
            Aether.LOGGER.warn("[aether] AutoUpdate: atomic move failed; retrying normal move: {}",
                    atomicMoveFailure.getMessage());
            Files.move(tempPath, newModPath, StandardCopyOption.REPLACE_EXISTING);
        }

        boolean oldModDeleted = deleteIfManagedOldMod(oldModPath, modsDir, newModPath);
        List<Path> staleTargets = deleteStaleAetherJars(modsDir, newModPath, oldModPath);
        return new Install(gameDir, modsDir, newModPath, oldModPath, oldModDeleted, staleTargets);
    }

    private static Path resolveInstallPath(Path modsDir, Release release, Path oldModPath) {
        String fileName = sanitizeFileName(release.assetName(), release.tagName());
        Path candidate = modsDir.resolve(fileName).toAbsolutePath().normalize();
        if (oldModPath == null || !candidate.equals(oldModPath.toAbsolutePath().normalize())) {
            return candidate;
        }

        String version = stripTagPrefix(release.tagName()).replaceAll("[^A-Za-z0-9._-]", "_");
        String suffix = Integer.toHexString(Math.abs(release.downloadUri().toString().hashCode()));
        return modsDir.resolve("aether-" + version + "-" + suffix + ".jar").toAbsolutePath().normalize();
    }

    private static boolean deleteIfManagedOldMod(Path oldModPath, Path modsDir, Path newModPath) {
        if (oldModPath == null) {
            return false;
        }

        Path normalizedOld = oldModPath.toAbsolutePath().normalize();
        if (normalizedOld.equals(newModPath)
                || !normalizedOld.startsWith(modsDir)
                || !Files.isRegularFile(normalizedOld)
                || !isManagedAetherJar(normalizedOld)) {
            Aether.LOGGER.info("[aether] AutoUpdate: old mod delete skipped; current mod is not a managed mods jar.");
            return false;
        }

        try {
            boolean deleted = Files.deleteIfExists(normalizedOld);
            Aether.LOGGER.info("[aether] AutoUpdate: old mod delete attempted; deleted={}", deleted);
            return deleted;
        } catch (IOException e) {
            Aether.LOGGER.warn("[aether] AutoUpdate: old mod could not be deleted immediately: {}", e.getMessage());
            return false;
        }
    }

    private static List<Path> deleteStaleAetherJars(Path modsDir, Path newModPath, Path oldModPath) throws IOException {
        List<Path> undeleted = new ArrayList<>();
        Path normalizedOld = oldModPath == null ? null : oldModPath.toAbsolutePath().normalize();
        try (var stream = Files.list(modsDir)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .map(candidate -> candidate.toAbsolutePath().normalize())
                    .filter(candidate -> !candidate.equals(newModPath))
                    .filter(candidate -> normalizedOld == null || !candidate.equals(normalizedOld))
                    .filter(AutoUpdateInstaller::isManagedAetherJar)
                    .toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    undeleted.add(path);
                    Aether.LOGGER.warn("[aether] AutoUpdate: stale mod could not be deleted immediately: {}", path);
                }
            }
        }
        return undeleted;
    }

    private static Path schedulePostExitPrompt(Install install, String fromVersion, String toVersion) throws IOException {
        Path updateStateDir = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("aether")
                .resolve("update")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(updateStateDir);

        long pid = ProcessHandle.current().pid();
        Path script = isWindows()
                ? writeWindowsPostExitScript(updateStateDir, pid, install, fromVersion, toVersion)
                : writeUnixPostExitScript(updateStateDir, pid, install, fromVersion, toVersion);

        ProcessBuilder builder = isWindows()
                ? new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                : new ProcessBuilder("sh", script.toString());
        builder.directory(install.gameDir().toFile());
        builder.start();
        return script;
    }

    private static Path writeWindowsPostExitScript(
            Path updateStateDir,
            long pid,
            Install install,
            String fromVersion,
            String toVersion
    ) throws IOException {
        Path script = updateStateDir.resolve("aether-update-" + System.currentTimeMillis() + ".ps1");
        StringBuilder content = new StringBuilder(1024);
        content.append("$ErrorActionPreference = 'Continue'\n");
        content.append("try { Wait-Process -Id ").append(pid).append(" -ErrorAction Stop } catch {}\n");
        content.append("Start-Sleep -Milliseconds 500\n");
        if (install.oldModPath() != null && !install.oldModDeleted()) {
            content.append("try { Remove-Item -LiteralPath ")
                    .append(powerShellString(install.oldModPath().toString()))
                    .append(" -Force -ErrorAction Stop } catch {}\n");
        }
        for (Path staleTarget : install.staleTargets()) {
            content.append("try { Remove-Item -LiteralPath ")
                    .append(powerShellString(staleTarget.toString()))
                    .append(" -Force -ErrorAction Stop } catch {}\n");
        }
        content.append("try { Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show(")
                .append(powerShellString(popupMessage(fromVersion, toVersion)))
                .append(", ")
                .append(powerShellString(POPUP_TITLE))
                .append(") | Out-Null } catch {}\n");
        content.append("Remove-Item -LiteralPath $PSCommandPath -Force\n");
        Files.writeString(script, content.toString(), StandardCharsets.UTF_8);
        return script;
    }

    private static Path writeUnixPostExitScript(
            Path updateStateDir,
            long pid,
            Install install,
            String fromVersion,
            String toVersion
    ) throws IOException {
        Path script = updateStateDir.resolve("aether-update-" + System.currentTimeMillis() + ".sh");
        String message = popupMessage(fromVersion, toVersion);
        StringBuilder content = new StringBuilder(1024);
        content.append("#!/bin/sh\n");
        content.append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 1; done\n");
        content.append("sleep 1\n");
        if (install.oldModPath() != null && !install.oldModDeleted()) {
            content.append("rm -f -- ").append(shellQuote(install.oldModPath().toString())).append(" >/dev/null 2>&1 || true\n");
        }
        for (Path staleTarget : install.staleTargets()) {
            content.append("rm -f -- ").append(shellQuote(staleTarget.toString())).append(" >/dev/null 2>&1 || true\n");
        }
        content.append("if command -v osascript >/dev/null 2>&1; then\n");
        content.append(" nohup osascript -e ")
                .append(shellQuote("display dialog \"" + message + "\" with title \"" + POPUP_TITLE
                        + "\" buttons {\"OK\"} default button 1"))
                .append(" >/dev/null 2>&1 &\n");
        content.append("elif command -v zenity >/dev/null 2>&1; then\n");
        content.append(" nohup zenity --info --title=").append(shellQuote(POPUP_TITLE))
                .append(" --text=").append(shellQuote(message)).append(" >/dev/null 2>&1 &\n");
        content.append("elif command -v kdialog >/dev/null 2>&1; then\n");
        content.append(" nohup kdialog --title ").append(shellQuote(POPUP_TITLE))
                .append(" --msgbox ").append(shellQuote(message)).append(" >/dev/null 2>&1 &\n");
        content.append("elif command -v xmessage >/dev/null 2>&1; then\n");
        content.append(" nohup xmessage -center ").append(shellQuote(message)).append(" >/dev/null 2>&1 &\n");
        content.append("fi\n");
        content.append("rm -f -- ").append(shellQuote(script.toString())).append(" >/dev/null 2>&1 || true\n");
        content.append("exit 0\n");
        Files.writeString(script, content.toString(), StandardCharsets.UTF_8);
        return script;
    }

    private static boolean isManagedAetherJar(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") && (name.equals("aether.jar") || name.startsWith("aether-") || name.startsWith("aether_"));
    }

    private static String sanitizeFileName(String fileName, String tagName) {
        String normalized = fileName == null ? "" : fileName.trim();
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            normalized = "aether-" + stripTagPrefix(tagName) + ".jar";
        }
        normalized = normalized.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.isBlank() || normalized.equals(".jar") ? "aether-" + stripTagPrefix(tagName) + ".jar" : normalized;
    }

    private static Path currentModPath() {
        try {
            Path fabricPath = FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .flatMap(container -> container.getRootPaths().stream()
                            .map(AutoUpdateInstaller::physicalPath)
                            .filter(path -> path != null && Files.isRegularFile(path))
                            .findFirst())
                    .orElse(null);
            if (fabricPath != null) {
                return fabricPath.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
        }

        try {
            CodeSource codeSource = AutoUpdateInstaller.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            Path path = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path physicalPath(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (Files.isRegularFile(path)) {
                return path;
            }
            URI uri = path.toUri();
            if (uri == null || !"jar".equalsIgnoreCase(uri.getScheme())) {
                return path;
            }
            String value = uri.getSchemeSpecificPart();
            int separator = value.indexOf("!/");
            if (separator >= 0) {
                value = value.substring(0, separator);
            }
            return value.isBlank() ? null : Path.of(URI.create(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String currentVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String stripTagPrefix(String tag) {
        if (tag == null) {
            return "";
        }
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    private static String popupMessage(String fromVersion, String toVersion) {
        return "Aether has been updated from version " + fromVersion + " to version " + toVersion
                + "!";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String powerShellString(String value) {
        String normalized = value == null ? "" : value;
        return "'" + normalized.replace("'", "''") + "'";
    }

    private record Release(String tagName, String assetName, URI downloadUri) {
    }

    private record Install(
            Path gameDir,
            Path modsDir,
            Path newModPath,
            Path oldModPath,
            boolean oldModDeleted,
            List<Path> staleTargets
    ) {
    }
}
