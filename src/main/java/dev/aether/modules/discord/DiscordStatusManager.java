package dev.aether.modules.discord;

import dev.aether.config.AetherConfig;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import dev.aether.modules.session.DynamicRestManager;

public class DiscordStatusManager {
    private static final String SCREENSHOT_ATTACHMENT_PLACEHOLDER = "__AETHER_SCREENSHOT__";
    private static long lastUpdateTime = System.currentTimeMillis();
    private static boolean isTakingScreenshot = false;
    private static long screenshotRequestTime = 0;
    private static volatile boolean forceStatusUpdatePending = false;
    private static volatile String pendingScreenshotPayloadJson = null;

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (!shouldSendStatus() && !forceStatusUpdatePending) {
            isTakingScreenshot = false;
            return;
        }

        long now = System.currentTimeMillis();
        // convert minutes to milliseconds for interval
        if (!isTakingScreenshot && now - lastUpdateTime >= AetherConfig.DISCORD_STATUS_UPDATE_TIME.get() * 60 * 1000L) {
            lastUpdateTime = now;
            takeAndSendScreenshot(client);
        }
    }

    public static boolean requestManualStatusUpdate() {
        Minecraft client = Minecraft.getInstance();
        String webhookUrl = AetherConfig.DISCORD_WEBHOOK_URL.get();
        if (client == null || webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }

        if (isTakingScreenshot) {
            return false;
        }

        forceStatusUpdatePending = true;
        pendingScreenshotPayloadJson = null;
        requestScreenshot(client);
        return true;
    }

    public static void sendFailsafeAlert(String reason, String actionDone) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || !isWebhookConfigured() || isTakingScreenshot) {
            return;
        }

        forceStatusUpdatePending = true;
        pendingScreenshotPayloadJson = buildFailsafePayload(reason, actionDone);
        requestScreenshot(client);
    }

    private static void takeAndSendScreenshot(Minecraft client) {
        if (!shouldSendStatus()) {
            return;
        }
        pendingScreenshotPayloadJson = null;
        requestScreenshot(client);
    }

    private static void requestScreenshot(Minecraft client) {
        isTakingScreenshot = true;
        screenshotRequestTime = System.currentTimeMillis();
        client.execute(() -> Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), (msg) -> {
            // Ignore the message
        }));

        new Thread(() -> {
            try {
                Thread.sleep(2000L);
                sendScreenshotAsync(client);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                isTakingScreenshot = false;
                forceStatusUpdatePending = false;
                pendingScreenshotPayloadJson = null;
            }
        }, "Aether-Discord-Screenshot").start();
    }

    private static void sendScreenshotAsync(Minecraft client) {
        new Thread(() -> {
            try {
                boolean forced = forceStatusUpdatePending;
                String payloadOverride = pendingScreenshotPayloadJson;
                if (!forced && !shouldSendStatus()) {
                    return;
                }
                File screenshotsDir = new File(client.gameDirectory, "screenshots");
                if (!screenshotsDir.exists())
                    return;

                File latestScreenshot = Files.list(screenshotsDir.toPath())
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".png"))
                        .filter(p -> System.currentTimeMillis() - p.toFile().lastModified() < 15000) // created in last
                                                                                                     // 15s
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                        .map(Path::toFile).orElse(null);

                if (latestScreenshot == null)
                    return;

                if (!forced && !shouldSendStatus()) {
                    return;
                }

                sendWebhook(AetherConfig.DISCORD_WEBHOOK_URL.get(), latestScreenshot, payloadOverride);
                try {
                    Files.deleteIfExists(latestScreenshot.toPath());
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isTakingScreenshot = false;
                forceStatusUpdatePending = false;
                pendingScreenshotPayloadJson = null;
            }
        }).start();
    }

    private static boolean shouldSendStatus() {
        if (!isWebhookConfigured()) {
            return false;
        }

        MacroState.State state = MacroStateManager.getCurrentState();
        return state != MacroState.State.OFF && state != MacroState.State.RECOVERING;
    }

    private static boolean isWebhookConfigured() {
        String webhookUrl = AetherConfig.DISCORD_WEBHOOK_URL.get();
        if (!AetherConfig.SEND_DISCORD_STATUS.get() || webhookUrl == null || webhookUrl.isEmpty()) {
            return false;
        }
        return true;
    }

    private static void sendWebhook(String webhookUrl, File imageFile, String payloadJsonOverride) throws Exception {
        String boundary = "===" + System.currentTimeMillis() + "===";
        URL url = new java.net.URI(webhookUrl).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("User-Agent", "Java-DiscordWebhook-Client");

        try (OutputStream outputStream = connection.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"), true)) {

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"payload_json\"\r\n");
            writer.append("Content-Type: application/json; charset=UTF-8\r\n\r\n");

            String jsonFileName = imageFile.getName();
            String json = payloadJsonOverride != null
                    ? payloadJsonOverride.replace(SCREENSHOT_ATTACHMENT_PLACEHOLDER, escapeJson(jsonFileName))
                    : buildStatusPayload(jsonFileName);

            writer.append(json).append("\r\n");

            writer.append("--").append(boundary).append("\r\n");
            writer.append(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"" + imageFile.getName() + "\"\r\n");
            writer.append("Content-Type: image/png\r\n\r\n");
            writer.flush();

            Files.copy(imageFile.toPath(), outputStream);
            outputStream.flush();

            writer.append("\r\n");
            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();
        }

        int responseCode = connection.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
            System.out.println("Discord webhook sent successfully.");
        } else {
            System.err.println("Discord webhook failed with response code: " + responseCode);
        }
    }

    private static String buildStatusPayload(String imageFileName) {
        String state = String.valueOf(MacroStateManager.getCurrentState());
        long sessionTotalSecs = MacroStateManager.getSessionRunningTime() / 1000;
        long sHours = sessionTotalSecs / 3600;
        long sMins = (sessionTotalSecs % 3600) / 60;
        long sSecs = sessionTotalSecs % 60;
        String sessionStr = sHours > 0
                ? String.format("%02d:%02d:%02d", sHours, sMins, sSecs)
                : String.format("%02d:%02d", sMins, sSecs);

        long nextRestTriggerMs = DynamicRestManager.getNextRestTriggerMs();
        String nextRestStr;
        if (nextRestTriggerMs > 0 && !DynamicRestManager.isRestPending()) {
            long remaining = nextRestTriggerMs - System.currentTimeMillis();
            if (remaining > 0) {
                long totalSecs = remaining / 1000;
                long rHours = totalSecs / 3600;
                long rMins = (totalSecs % 3600) / 60;
                long rSecs = totalSecs % 60;
                nextRestStr = rHours > 0 ? String.format("%02d:%02d:%02d", rHours, rMins, rSecs)
                        : String.format("%02d:%02d", rMins, rSecs);
            } else {
                nextRestStr = "Starting soon...";
            }
        } else if (DynamicRestManager.isRestPending()) {
            nextRestStr = "Resting now...";
        } else {
            nextRestStr = "Not scheduled";
        }

        return "{\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"Status Update\",\n" +
                "    \"description\": \"Here is your latest Aether status update! :rocket:\",\n" +
                "    \"color\": 5814783,\n" +
                "    \"fields\": [\n" +
                "      {\n" +
                "        \"name\": \"Current State\",\n" +
                "        \"value\": \"`" + escapeJson(state) + "`\",\n" +
                "        \"inline\": true\n" +
                "      },\n" +
                "      {\n" +
                "        \"name\": \"Session Time\",\n" +
                "        \"value\": \"`" + escapeJson(sessionStr) + "`\",\n" +
                "        \"inline\": true\n" +
                "      },\n" +
                "      {\n" +
                "        \"name\": \"Time Until Next Rest\",\n" +
                "        \"value\": \"`" + escapeJson(nextRestStr) + "`\",\n" +
                "        \"inline\": true\n" +
                "      }\n" +
                "    ],\n" +
                "    \"image\": {\n" +
                "      \"url\": \"attachment://" + escapeJson(imageFileName) + "\"\n" +
                "    }\n" +
                "  }]\n" +
                "}";
    }

    private static String buildFailsafePayload(String reason, String actionDone) {
        String state = String.valueOf(MacroStateManager.getCurrentState());
        long sessionTotalSecs = MacroStateManager.getSessionRunningTime() / 1000;
        long sHours = sessionTotalSecs / 3600;
        long sMins = (sessionTotalSecs % 3600) / 60;
        long sSecs = sessionTotalSecs % 60;
        String sessionStr = sHours > 0
                ? String.format("%02d:%02d:%02d", sHours, sMins, sSecs)
                : String.format("%02d:%02d", sMins, sSecs);

        return "{\n" +
                "  \"content\": \"@everyone\",\n" +
                "  \"allowed_mentions\": {\n" +
                "    \"parse\": [\"everyone\"]\n" +
                "  },\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"Failsafe Alert\",\n" +
                "    \"description\": \"Aether detected a failsafe and captured the current screen.\",\n" +
                "    \"color\": 15158332,\n" +
                "    \"fields\": [\n" +
                "      {\n" +
                "        \"name\": \"Current State\",\n" +
                "        \"value\": \"`" + escapeJson(state) + "`\",\n" +
                "        \"inline\": true\n" +
                "      },\n" +
                "      {\n" +
                "        \"name\": \"Session Time\",\n" +
                "        \"value\": \"`" + escapeJson(sessionStr) + "`\",\n" +
                "        \"inline\": true\n" +
                "      },\n" +
                "      {\n" +
                "        \"name\": \"Action Done\",\n" +
                "        \"value\": \"`" + escapeJson(actionDone) + "`\",\n" +
                "        \"inline\": true\n" +
                "      },\n" +
                "      {\n" +
                "        \"name\": \"Reason\",\n" +
                "        \"value\": \"" + escapeJson(reason) + "\",\n" +
                "        \"inline\": false\n" +
                "      }\n" +
                "    ],\n" +
                "    \"image\": {\n" +
                "      \"url\": \"attachment://" + SCREENSHOT_ATTACHMENT_PLACEHOLDER + "\"\n" +
                "    }\n" +
                "  }]\n" +
                "}";
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
