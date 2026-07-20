package dev.aether.modules.pest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.config.AetherConfig;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pest.helpers.AutoSprayonatorManager;
import dev.aether.modules.pest.helpers.GardenTimeManager;
import dev.aether.modules.pest.helpers.VinylManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DynamicPestsManager {

    private static final String FEAST_API_URL = "https://api.elitebot.dev/harvest-feast/current";
    private static final long FEAST_FALLBACK_POLL_MS = 5 * 60 * 1000L;
    private static final long UPDATE_INTERVAL_MS = 5_000L;

    private static final String[] SPRAY_OPTIONS = {
        "Compost", "Honey Jar", "Dung", "Plant Matter", "Tasty Cheese", "Jelly"
    };
    private static final String[] VINYL_OPTIONS = {
        "Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm",
        "Mite", "Moth", "Slug", "Beetle", "Firefly", "Dragonfly", "Praying Mantis"
    };

    // Feast state
    private static volatile List<String> feastActiveCrops = List.of();
    private static volatile java.util.Map<String, Long> feastNextCropTimestamps = java.util.Map.of();
    private static volatile long feastNextFetchMs = 0L;
    private static volatile boolean feastActive = false;
    private static volatile boolean feastFetchInProgress = false;
    private static volatile boolean feastInitialFetchComplete = false;
    private static volatile long lastInitialFetchWaitDebugMs = 0L;

    // Jacob state
    private static volatile List<String> jacobContestCrops = List.of();
    private static volatile boolean jacobParsed = false;
    private static volatile boolean jacobCleared = true;
    private static volatile long lastJacobParseMs = 0L;

    // Apply state — "__none__" means never applied; forces apply on first tick
    private static final String NOT_APPLIED = "__none__";
    private static volatile String appliedCrop = NOT_APPLIED;
    private static volatile boolean isApplying = false;
    private static volatile long lastUpdateCheckMs = 0L;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();


    private DynamicPestsManager() {}

    public static void reset() {
        appliedCrop = NOT_APPLIED;
        isApplying = false;
        lastUpdateCheckMs = 0L;
        feastNextFetchMs = 0L;  // force re-poll on next macro start
        feastInitialFetchComplete = false;
        lastInitialFetchWaitDebugMs = 0L;
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) return;
        if (!AetherConfig.DYNAMIC_PESTS_ENABLED.get()) return;
        if (!MacroStateManager.isMacroRunning()) return;

        tickFeastData();
        tickJacob(client);

        if (shouldWaitForInitialFeastFetch()) {
            long now = System.currentTimeMillis();
            if (now - lastInitialFetchWaitDebugMs >= 5000L) {
                lastInitialFetchWaitDebugMs = now;
                ClientUtils.sendDebugMessage("DynamicPests: waiting for initial Feast data before applying fallback");
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastUpdateCheckMs < UPDATE_INTERVAL_MS) return;
        lastUpdateCheckMs = now;

        String targetCrop = resolveTargetCrop();
        applyIfChanged(client, targetCrop);
    }

    public static List<String> getAvailableCrops() {
        return List.copyOf(PestCropData.BY_CROP.keySet());
    }

    public static boolean triggerTestApply(Minecraft client, String cropName) {
        if (client == null || client.player == null || client.getConnection() == null) return false;

        String normalizedCrop = normalizeRequestedCrop(cropName);
        if (normalizedCrop == null) return false;
        if (isApplying || PestManager.isCleaningInProgress || AutoSprayonatorManager.isRunning()) return false;

        isApplying = true;
        MacroWorkerThread.getInstance().submit("DynamicPests-TestApply", () -> {
            try {
                if (shouldAbortApply(client, false)) return;
                if (PestManager.isCleaningInProgress) return;
                if (AutoSprayonatorManager.isRunning()) return;
                if (doApply(client, normalizedCrop, true, false)) {
                    appliedCrop = normalizedCrop;
                }
            } catch (Exception e) {
                ClientUtils.sendDebugMessage("DynamicPests test apply error: " + e.getMessage());
            } finally {
                isApplying = false;
            }
        });
        return true;
    }

    // -------------------------------------------------------------------------
    // Feast
    // -------------------------------------------------------------------------

    private static void tickFeastData() {
        if (feastFetchInProgress) return;
        if (System.currentTimeMillis() < feastNextFetchMs) return;

        feastFetchInProgress = true;
        Thread t = new Thread(() -> {
            try {
                fetchFeast();
            } finally {
                feastInitialFetchComplete = true;
                feastFetchInProgress = false;
            }
        }, "aether-feast-fetch");
        t.setDaemon(true);
        t.start();
    }

    private static void fetchFeast() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FEAST_API_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                feastNextFetchMs = System.currentTimeMillis() + FEAST_FALLBACK_POLL_MS;
                return;
            }

            parseFeastResponse(JsonParser.parseString(response.body()).getAsJsonObject());
        } catch (Exception e) {
            feastNextFetchMs = System.currentTimeMillis() + FEAST_FALLBACK_POLL_MS;
        }
    }

    private static void parseFeastResponse(JsonObject json) {
        boolean complete = json.has("complete") && json.get("complete").getAsBoolean();
        JsonArray currentArr = json.has("current") ? json.getAsJsonArray("current") : null;

        if (!complete || currentArr == null || currentArr.isEmpty()) {
            feastActive = false;
            feastActiveCrops = List.of();
            feastNextCropTimestamps = java.util.Map.of();
            feastNextFetchMs = System.currentTimeMillis() + FEAST_FALLBACK_POLL_MS;
            return;
        }

        List<String> crops = new ArrayList<>(3);
        for (JsonElement el : currentArr) {
            crops.add(normalizeApiCrop(el.getAsString()));
        }
        feastActiveCrops = List.copyOf(crops);
        feastActive = true;

        // Schedule next fetch at the earliest upcoming crop rotation
        long earliestMs = Long.MAX_VALUE;
        var nextMap = new java.util.LinkedHashMap<String, Long>();
        if (json.has("next")) {
            for (java.util.Map.Entry<String, JsonElement> entry : json.getAsJsonObject("next").entrySet()) {
                if (entry.getValue().isJsonNull()) continue;
                long tsMs = entry.getValue().getAsLong() * 1000L;
                nextMap.put(normalizeApiCrop(entry.getKey()), tsMs);
                if (tsMs > System.currentTimeMillis() && tsMs < earliestMs) {
                    earliestMs = tsMs;
                }
            }
        }
        feastNextCropTimestamps = java.util.Collections.unmodifiableMap(nextMap);

        feastNextFetchMs = earliestMs == Long.MAX_VALUE
                ? System.currentTimeMillis() + FEAST_FALLBACK_POLL_MS
                : earliestMs;
    }

    private static String normalizeApiCrop(String apiCrop) {
        return switch (apiCrop) {
            case "Melon" -> "Melon Slice";
            case "Mushroom" -> "Mushrooms";
            default -> apiCrop;
        };
    }

    // -------------------------------------------------------------------------
    // Jacob's Contest
    // -------------------------------------------------------------------------

    private static void tickJacob(Minecraft client) {
        LocalTime utc = LocalTime.now(ZoneOffset.UTC);
        int minute = utc.getMinute();
        int second = utc.getSecond();

        boolean inParseWindow = (minute == 14 && second >= 30) || (minute >= 15 && minute < 35);

        if (!inParseWindow) {
            if (!jacobCleared) {
                jacobContestCrops = List.of();
                jacobParsed = false;
                jacobCleared = true;
            }
            return;
        }

        jacobCleared = false;
        if (jacobParsed) return;

        long now = System.currentTimeMillis();
        if (now - lastJacobParseMs < 5000L) return;
        lastJacobParseMs = now;

        List<String> crops = parseJacobFromTablist(client);
        if (!crops.isEmpty()) {
            jacobContestCrops = List.copyOf(crops);
            jacobParsed = true;
        }
    }

    private static List<String> parseJacobFromTablist(Minecraft client) {
        List<String> lines = TablistUtils.getRawTabLines(client);
        List<String> crops = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (!line.startsWith("○ ") && !line.startsWith("☘ ")) continue;
            String name = line.substring(2).trim();
            String normalized = normalizeCropName(name);
            if (PestCropData.BY_CROP.containsKey(normalized)) {
                crops.add(normalized);
            }
        }

        return crops;
    }

    private static String normalizeCropName(String name) {
        return switch (name) {
            case "Mushroom"   -> "Mushrooms";
            case "Melon"      -> "Melon Slice";
            default           -> name;
        };
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    private static String resolveTargetCrop() {
        int mode = AetherConfig.DYNAMIC_PESTS_MODE.get();
        boolean jacobActive = !jacobContestCrops.isEmpty();

        return switch (mode) {
            case 0 -> feastActive
                    ? highestPriority(feastActiveCrops, AetherConfig.DYNAMIC_PESTS_FEAST_PRIORITY.get())
                    : null;
            case 1 -> jacobActive
                    ? highestPriority(jacobContestCrops, AetherConfig.DYNAMIC_PESTS_CONTEST_PRIORITY.get())
                    : null;
            case 2 -> {
                if (jacobActive) {
                    String jacobCrop = highestPriority(jacobContestCrops, AetherConfig.DYNAMIC_PESTS_CONTEST_PRIORITY.get());
                    if (jacobCrop != null) yield jacobCrop;
                }
                yield feastActive
                        ? highestPriority(feastActiveCrops, AetherConfig.DYNAMIC_PESTS_FEAST_PRIORITY.get())
                        : null;
            }
            default -> null;
        };
    }

    private static boolean shouldWaitForInitialFeastFetch() {
        if (feastInitialFetchComplete) {
            return false;
        }

        int mode = AetherConfig.DYNAMIC_PESTS_MODE.get();
        return mode == 0 || (mode == 2 && jacobContestCrops.isEmpty());
    }

    private static String highestPriority(List<String> eventCrops, List<String> priorityList) {
        if (eventCrops == null || eventCrops.isEmpty()) return null;
        if (priorityList == null || priorityList.isEmpty()) return eventCrops.get(0);

        for (String crop : priorityList) {
            if (eventCrops.contains(crop)) return crop;
        }
        // no configured priority matched an active crop; still target an active one
        return eventCrops.get(0);
    }

    private static String normalizeRequestedCrop(String cropName) {
        if (cropName == null || cropName.isBlank()) return null;

        String requested = cropName.trim();
        for (String crop : PestCropData.BY_CROP.keySet()) {
            if (crop.equalsIgnoreCase(requested)) {
                return crop;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Apply
    // -------------------------------------------------------------------------

    private static void applyIfChanged(Minecraft client, String targetCrop) {
        // null targetCrop = use fallback; represent as "null" string for comparison
        String targetKey = targetCrop != null ? targetCrop : "null";
        if (!NOT_APPLIED.equals(appliedCrop) && Objects.equals(targetKey, appliedCrop)) return;
        if (isApplying) return;
        if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) return;
        if (PestManager.isCleaningInProgress) return;
        if (AutoSprayonatorManager.isRunning()) return;

        isApplying = true;
        MacroWorkerThread.getInstance().submit("DynamicPests-Apply", () -> {
            try {
                // Re-check all guards at task start — state may have changed since enqueue
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) return;
                if (PestManager.isCleaningInProgress) return;
                if (AutoSprayonatorManager.isRunning()) return;
                if (doApply(client, targetCrop, false, true)) {
                    appliedCrop = targetKey;
                }
            } catch (Exception e) {
                ClientUtils.sendDebugMessage("DynamicPests apply error: " + e.getMessage());
            } finally {
                isApplying = false;
            }
        });
    }

    private static boolean doApply(Minecraft client, String crop, boolean force, boolean requireMacroRunning) {
        long guiDelay = ClientUtils.getGuiClickDelayMs(false);

        String sprayMaterial;
        String vinylName;

        if (crop == null) {
            int sprayIdx = clamp(AetherConfig.DYNAMIC_PESTS_FALLBACK_SPRAY.get(), SPRAY_OPTIONS.length);
            int vinylIdx = clamp(AetherConfig.DYNAMIC_PESTS_FALLBACK_VINYL.get(), VINYL_OPTIONS.length);
            sprayMaterial = SPRAY_OPTIONS[sprayIdx];
            PestCropData.PestProfile fallbackProfile = PestCropData.BY_PEST.get(VINYL_OPTIONS[vinylIdx]);
            vinylName = fallbackProfile != null ? fallbackProfile.vinyl() : null;
        } else {
            PestCropData.PestProfile profile = PestCropData.BY_CROP.get(crop);
            if (profile == null) return false;
            sprayMaterial = profile.spray();
            vinylName = profile.vinyl();
        }

        String currentSprayMaterial = AutoSprayonatorManager.getHotbarMaterial(client);
        boolean sprayAlreadyCorrect = currentSprayMaterial != null
                && currentSprayMaterial.equalsIgnoreCase(sprayMaterial);
        boolean vinylAlreadyCorrect = vinylName == null || VinylManager.isTargetVinylPlaying(client, vinylName);
        boolean gardenTimeAlreadyCorrect = isGardenTimeAlreadyCorrect(client, crop);
        if (!force && sprayAlreadyCorrect && vinylAlreadyCorrect && gardenTimeAlreadyCorrect) {
            ClientUtils.sendDebugMessage("DynamicPests: spray, vinyl, and garden time already match target, skipping apply");
            return true;
        }

        MacroState.State previousState = MacroStateManager.getCurrentState();
        boolean macroWasRunning = MacroStateManager.isMacroRunning();

        PestManager.isCleaningInProgress = true;
        if (macroWasRunning) {
            MacroStateManager.setCurrentState(MacroState.State.SPRAYING);
            client.execute(() -> FarmingMacroManager.disable(client));
            MacroWorkerThread.sleep(guiDelay);
        }

        try {
            if (shouldAbortApply(client, requireMacroRunning)) return false;

            faceStraightDown(client);
            if (shouldAbortApply(client, requireMacroRunning)) return false;

            boolean sprayApplied = false;
            boolean sprayChanged = false;
            if (AutoSprayonatorManager.holdSprayonator(client)) {
                if (shouldAbortApply(client, requireMacroRunning)) return false;
                String previousMaterial = AutoSprayonatorManager.getHeldMaterial(client);
                sprayApplied = AutoSprayonatorManager.ensureMaterial(client, sprayMaterial, guiDelay);
                String currentMaterial = AutoSprayonatorManager.getHeldMaterial(client);
                sprayChanged = previousMaterial == null
                        ? currentMaterial != null && currentMaterial.equalsIgnoreCase(sprayMaterial)
                        : !previousMaterial.equalsIgnoreCase(sprayMaterial)
                                && currentMaterial != null
                                && currentMaterial.equalsIgnoreCase(sprayMaterial);
            } else {
                ClientUtils.sendMessage("\u00A7cDynamic Pests: Sprayonator not found in hotbar.");
            }
            if (!sprayApplied) {
                ClientUtils.sendDebugMessage("DynamicPests: failed to set spray material " + sprayMaterial);
                return false;
            }

            if (sprayChanged) {
                boolean sprayTriggered = AutoSprayonatorManager.sprayHeldMaterialAndHandleMissing(client, guiDelay);
                if (!sprayTriggered) {
                    ClientUtils.sendDebugMessage("DynamicPests: failed to trigger spray after material swap");
                    return false;
                }
            }

            if (shouldAbortApply(client, requireMacroRunning)) return false;

            if (vinylName != null) {
                boolean vinylApplied = VinylManager.setVinyl(client, vinylName);
                if (!vinylApplied) {
                    ClientUtils.sendDebugMessage("DynamicPests: failed to set vinyl " + vinylName);
                    return false;
                }
            }

            if (shouldAbortApply(client, requireMacroRunning)) return false;

            if (!switchGardenTimeForCrop(client, crop)) {
                ClientUtils.sendDebugMessage("DynamicPests: failed to switch garden time for " + crop);
                return false;
            }

            if (shouldAbortApply(client, requireMacroRunning)) return false;

            String label = crop != null ? crop : "fallback";
            ClientUtils.sendMessage("\u00A7aDynamic Pests: Switched to \u00A7e" + label + "\u00A7a.");
            return true;
        } finally {
            GearManager.swapToFarmingToolSync(client);
            if (macroWasRunning) {
                // faceStraightDown() rotated us off the farming orientation; arm the mousemat
                // reapply so farming resume snaps us back, matching the other resume paths.
                SqueakyMousematManager.armReapplyAttempt();
                client.execute(() -> FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()));
                MacroStateManager.setCurrentState(MacroState.State.FARMING);
            } else if (MacroStateManager.getCurrentState() != previousState) {
                MacroStateManager.setCurrentState(previousState);
            }
            PestManager.isCleaningInProgress = false;
        }
    }

    private static boolean shouldAbortApply(Minecraft client, boolean requireMacroRunning) {
        return MacroWorkerThread.getInstance().isCancelled()
                || client == null
                || client.player == null
                || (requireMacroRunning && !MacroStateManager.isMacroRunning());
    }

    private static boolean isGardenTimeAlreadyCorrect(Minecraft client, String crop) {
        if ("Sunflower".equals(crop)) {
            return GardenTimeManager.isDaytime(client);
        }
        if ("Moonflower".equals(crop)) {
            return GardenTimeManager.isNightTime(client);
        }
        return true;
    }

    private static boolean switchGardenTimeForCrop(Minecraft client, String crop) {
        if ("Sunflower".equals(crop)) {
            return GardenTimeManager.switchToDaytime(client);
        }
        if ("Moonflower".equals(crop)) {
            return GardenTimeManager.switchToNightTime(client);
        }
        return true;
    }

    private static void faceStraightDown(Minecraft client) {
        if (client.player == null) return;

        client.execute(() -> RotationManager.rotateToYawPitch(
                client,
                client.player.getYRot(),
                90.0f,
                AetherConfig.ROTATION_TIME.get(),
                true));
        MacroWorkerThread.sleep(AetherConfig.ROTATION_TIME.get() + 50L);

        long deadline = System.currentTimeMillis() + 1500L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < deadline) {
            MacroWorkerThread.sleep(25);
        }
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

}
