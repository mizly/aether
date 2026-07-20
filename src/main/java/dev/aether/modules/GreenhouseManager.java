package dev.aether.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

public class GreenhouseManager {
    private static final int SKULL_SCAN_RADIUS = 25;
    public static final List<AABB> highlightedSkulls = new ArrayList<>();
    public static final List<Vec3> currentPath = new ArrayList<>();
    public static final List<Vec3> flyToPoints = new ArrayList<>();
    private static final List<String> configuredPlots = new ArrayList<>();
    private static int currentSkullIndex = -1;
    private static int currentPlotIndex = -1;
    private static boolean sneakLocked = false;
    private static long currentPlotStartedAt = 0L;
    private static volatile boolean running = false;
    private static volatile boolean autoSequence = false;
    private static volatile long lastAutoRunCompletedMs = System.currentTimeMillis();
    private static volatile Runnable completionCallback = null;
    private static final long PLOT_HANDOFF_MIN_MS = 3000L;
    private static final int TARGET_SETTLE_DELAY_MS = 100;
    private static final int ROTATION_ACK_TIMEOUT_MS = 1000;

    private static Minecraft client() {
        return Minecraft.getInstance();
    }

    public static boolean isRunning() {
        return running;
    }

    public static void reset() {
        highlightedSkulls.clear();
        currentPath.clear();
        flyToPoints.clear();
        configuredPlots.clear();
        currentSkullIndex = -1;
        currentPlotIndex = -1;
        currentPlotStartedAt = 0L;
        sneakLocked = false;
        running = false;
        autoSequence = false;
        completionCallback = null;
    }

    public static long getAutoGreenhouseElapsedMs() {
        return Math.max(0L, System.currentTimeMillis() - lastAutoRunCompletedMs);
    }

    public static boolean shouldRunAutoGreenhouse() {
        if (!AetherConfig.AUTO_GREENHOUSE.get() || running) {
            return false;
        }

        if (AetherConfig.GREENHOUSE_PLOTS.get().isEmpty()) {
            return false;
        }

        long intervalMs = Math.max(1L, AetherConfig.AUTO_GREENHOUSE_INTERVAL_MINUTES.get()) * 60_000L;
        return getAutoGreenhouseElapsedMs() >= intervalMs;
    }

    public static void runAutoGreenhouseIfDue(Runnable onComplete) {
        if (!shouldRunAutoGreenhouse()) {
            runCompletion(onComplete);
            return;
        }
        autoSequence = true;
        completionCallback = onComplete;
        harvest();
    }

    public static void harvest() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            runCompletion(completionCallback);
            completionCallback = null;
            autoSequence = false;
            return;
        }
        if (running) {
            runCompletion(completionCallback);
            completionCallback = null;
            autoSequence = false;
            return;
        }

        List<String> plots = AetherConfig.GREENHOUSE_PLOTS.get().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (plots.isEmpty()) {
            ClientUtils.sendMessage("\u00A7cNo greenhouse plots configured!", false);
            return;
        }

        if (plots.isEmpty()) {
            ClientUtils.sendMessage("\u00A7cNo greenhouse plots configured!", false);
            return;
        }

        running = true;
        if (!equipHarvestTool()) {
            running = false;
            configuredPlots.clear();
            Runnable callback = completionCallback;
            completionCallback = null;
            autoSequence = false;
            runCompletion(callback);
            return;
        }
        configuredPlots.clear();
        configuredPlots.addAll(plots);
        currentPlotIndex = -1;
        clearSneakState();
        long elapsedMs = currentPlotStartedAt <= 0L
                ? PLOT_HANDOFF_MIN_MS
                : Math.max(0L, System.currentTimeMillis() - currentPlotStartedAt);
        long remainingDelayMs = Math.max(0L, PLOT_HANDOFF_MIN_MS - elapsedMs);
        currentPlotStartedAt = 0L;

        if (remainingDelayMs <= 0L) {
            startNextPlot();
            return;
        }

        MacroWorkerThread.getInstance().submit("GreenhouseNextPlotDelay", () -> {
            MacroWorkerThread.sleep((int) remainingDelayMs);
            mc.execute(GreenhouseManager::startNextPlot);
        });
    }

    private static void startHarvesting() {
        if (currentPath.isEmpty()) {
            String plot = currentPlotIndex >= 0 && currentPlotIndex < configuredPlots.size()
                    ? configuredPlots.get(currentPlotIndex)
                    : "Unknown";
            ClientUtils.sendDebugMessage("No targets found on plot " + plot + ", moving on.");
            onCurrentPlotFinished();
            return;
        }
        
        flyToPoints.clear();
        sneakLocked = false;
        for (Vec3 p : currentPath) {
            // Walk directly to the skull target.
            flyToPoints.add(p);
        }
        
        currentSkullIndex = 0;
        ClientUtils.sendMessage("\u00A7eStarting automated greenhouse harvest...", false);
        flyToNextSkull(true);
    }

    private static void flyToNextSkull(boolean isFirst) {
        Minecraft mc = client();
        if (mc == null || mc.player == null || currentSkullIndex < 0 || currentSkullIndex >= flyToPoints.size()) {
            releaseMovementKeys();
            ClientUtils.sendMessage("\u00A7aGreenhouse harvest complete!", false);
            currentSkullIndex = -1;
            onCurrentPlotFinished();
            return;
        }

        Vec3 target = flyToPoints.get(currentSkullIndex);
        PathfindingManager.startGreenhouseWalk(mc, target, () -> {
            sneakLocked = sneakLocked || isFirst || PathfindingManager.isWalkSneakLatched();
            PathfindingManager.setWalkSneakLatched(sneakLocked);
            currentSkullIndex++;
            MacroWorkerThread.getInstance().submit("GreenhouseNextTargetDelay", () -> {
                mc.execute(GreenhouseManager::releaseMovementKeys);
                MacroWorkerThread.sleep(TARGET_SETTLE_DELAY_MS);

                CountDownLatch rotationQueued = new CountDownLatch(1);
                final long[] rotationMs = new long[] {0L};
                mc.execute(() -> {
                    try {
                        releaseMovementKeys();
                        rotationMs[0] = rotateHumanizedToNextTarget(target);
                    } finally {
                        rotationQueued.countDown();
                    }
                });

                try {
                    if (!rotationQueued.await(ROTATION_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        ClientUtils.sendDebugMessage("Greenhouse rotation queue timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (rotationMs[0] > 0L) {
                    MacroWorkerThread.sleep((int) rotationMs[0]);
                }

                mc.execute(GreenhouseManager::leftClick);
                int delayMs = ThreadLocalRandom.current().nextInt(100, 501);
                MacroWorkerThread.sleep(delayMs);
                mc.execute(() -> flyToNextSkull(false));
            });
        }, isFirst);
    }

    private static void startNextPlot() {
        Minecraft mc = client();
        if (mc == null || mc.player == null) {
            finishSequence();
            return;
        }
        currentPlotIndex++;
        if (currentPlotIndex >= configuredPlots.size()) {
            finishSequence();
            return;
        }

        String targetPlot = configuredPlots.get(currentPlotIndex);
        currentPlotStartedAt = System.currentTimeMillis();
        clearSneakState();

        String currentPlot = ClientUtils.getCurrentPlot();
        if (targetPlot.equalsIgnoreCase(currentPlot)) {
            ClientUtils.sendDebugMessage("Already on plot " + targetPlot + ". Scanning...");
            detectSkulls();
            startHarvesting();
            return;
        }

        ClientUtils.sendDebugMessage("Teleporting to plot " + targetPlot + "...");
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        MacroWorkerThread.getInstance().submit("GreenhouseHarvest-Plot-" + targetPlot, () -> {
            boolean success = CommandUtils.plotTp(targetPlot);
            if (success) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                mc.execute(() -> {
                    clearSneakState();
                    detectSkulls(yaw, pitch);
                    startHarvesting();
                });
            } else {
                ClientUtils.sendDebugMessage("Failed to confirm teleport to plot " + targetPlot);
                mc.execute(GreenhouseManager::startNextPlot);
            }
        });
    }

    private static void onCurrentPlotFinished() {
        String plot = currentPlotIndex >= 0 && currentPlotIndex < configuredPlots.size()
                ? configuredPlots.get(currentPlotIndex)
                : "Unknown";
        ClientUtils.sendMessage("\u00A7aFinished plot " + plot + ".", false);
        startNextPlot();
    }

    private static void finishSequence() {
        clearSneakState();
        releaseMovementKeys();
        configuredPlots.clear();
        currentPlotIndex = -1;
        currentSkullIndex = -1;
        currentPlotStartedAt = 0L;
        running = false;
        if (autoSequence) {
            lastAutoRunCompletedMs = System.currentTimeMillis();
        }
        autoSequence = false;
        Runnable callback = completionCallback;
        completionCallback = null;
        runCompletion(callback);
        ClientUtils.sendMessage("\u00A7aGreenhouse harvest complete!", false);
    }

    private static void runCompletion(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    private static boolean equipHarvestTool() {
        Minecraft mc = client();
        if (AetherConfig.EQUIP_GREENHOUSE_CUSTOM_ITEM.get()) {
            String customItem = AetherConfig.GREENHOUSE_CUSTOM_ITEM.get().trim();
            if (customItem.isEmpty()) {
                ClientUtils.sendDebugMessage("\u00A7cGreenhouse: custom item name is blank.");
                return false;
            }

            boolean swapped = GearManager.swapToNamedHotbarItemSync(mc, customItem);
            if (!swapped) {
                ClientUtils.sendDebugMessage("\u00A7cGreenhouse: custom item not found in hotbar: " + customItem);
            }
            return swapped;
        }

        int slot = GearManager.findFarmingToolSlot(mc);
        if (slot == -1) {
            ClientUtils.sendDebugMessage("\u00A7cGreenhouse: no farming tool found in hotbar.");
            return false;
        }
        GearManager.swapToFarmingToolSync(mc);
        return true;
    }

    private static void clearSneakState() {
        Minecraft mc = client();
        sneakLocked = false;
        PathfindingManager.setWalkSneakLatched(false);
        if (mc != null && mc.options != null) {
            ClientUtils.setKeyMappingState(mc.options.keyShift, false);
        }
    }

    private static long rotateHumanizedToNextTarget(Vec3 nextTarget) {
        Minecraft mc = client();
        if (mc == null || mc.player == null) return 0L;

        Vec3 lookTarget = toAimTarget(nextTarget);
        RotationUtils.Rotation base = RotationUtils.calculateLookAt(mc.player.getEyePosition(), lookTarget);

        long rotationMs = ThreadLocalRandom.current().nextLong(300L, 601L);
        ClientUtils.sendDebugMessage(String.format(Locale.US,
                "Greenhouse aim target: %.3f, %.3f, %.3f",
                lookTarget.x,
                lookTarget.y,
                lookTarget.z));
        ClientUtils.sendDebugMessage(String.format(Locale.US,
                "Greenhouse rotation needed: yaw %.2f, pitch %.2f, duration %dms",
                base.yaw,
                base.pitch,
                rotationMs));
        RotationManager.rotateToYawPitch(mc, base.yaw, base.pitch, rotationMs);
        return rotationMs;
    }

    private static Vec3 toAimTarget(Vec3 target) {
        return new Vec3(target.x, Math.floor(target.y) + 0.5, target.z);
    }

    private static void leftClick() {
        Minecraft mc = client();
        if (mc == null || mc.options == null) return;
        ClientUtils.setKeyMappingState(mc.options.keyAttack, true);
        ClientUtils.clickKeyMapping(mc.options.keyAttack);
        ClientUtils.setKeyMappingState(mc.options.keyAttack, false);
    }

    private static void releaseMovementKeys() {
        Minecraft mc = client();
        if (mc == null || mc.options == null) {
            return;
        }

        ClientUtils.setKeyMappingState(mc.options.keyUp, false);
        ClientUtils.setKeyMappingState(mc.options.keyDown, false);
        ClientUtils.setKeyMappingState(mc.options.keyLeft, false);
        ClientUtils.setKeyMappingState(mc.options.keyRight, false);
        ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        ClientUtils.setKeyMappingState(mc.options.keySprint, false);
        ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLocked);
    }

    public static void detectSkulls() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        detectSkulls(mc.player != null ? mc.player.getYRot() : 0, mc.player != null ? mc.player.getXRot() : 0);
    }

    public static void detectSkulls(float yaw, float pitch) {
        Minecraft mc = client();
        if (mc == null) {
            return;
        }
        highlightedSkulls.clear();
        if (mc.player == null || mc.level == null) return;

        scanNearbySkulls(entry -> processProfile(entry.profile(), entry.x(), entry.y(), entry.z()));
        
        updatePath(yaw, pitch);
        ClientUtils.sendDebugMessage("Detected " + getHighlightedCount() + " matching skulls. Path generated.");
    }

    public static void debugScanSkulls() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }

        List<SkullScanEntry> entries = new ArrayList<>();
        scanNearbySkulls(entries::add);

        if (entries.isEmpty()) {
            ClientUtils.sendMessage(String.format(Locale.US,
                            "\u00A7e" + AetherLang.localize("No skulls with owner profiles found within %d blocks."),
                            SKULL_SCAN_RADIUS),
                    false);
            return;
        }

        entries.sort(Comparator.comparingDouble(entry ->
                mc.player.position().distanceToSqr(entry.x(), entry.y(), entry.z())));

        ClientUtils.sendMessage(String.format(Locale.US,
                        "\u00A7e" + AetherLang.localize("Scanned %d skulls within %d blocks."),
                        entries.size(),
                        SKULL_SCAN_RADIUS),
                false);

        for (SkullScanEntry entry : entries) {
            String line = String.format(Locale.US,
                    AetherLang.localize("Skull %s (%s) ID: %s at %.1f, %.1f, %.1f"),
                    entry.name(),
                    entry.sourceLabel(),
                    entry.skinId(),
                    entry.x(),
                    entry.y(),
                    entry.z());
            ClientUtils.sendMessage("\u00A77" + line, false);
        }
    }

    private static void updatePath(float yaw, float pitch) {
        Minecraft mc = client();
        if (mc == null) {
            currentPath.clear();
            return;
        }
        currentPath.clear();
        if (highlightedSkulls.isEmpty()) return;

        List<Vec3> points = highlightedSkulls.stream()
                .map(AABB::getCenter)
                .toList();

        // Directional vectors based on yaw
        float yawRad = (float) Math.toRadians(yaw);
        Vec3 forward = new Vec3(Math.sin(-yawRad), 0, Math.cos(yawRad));
        Vec3 right = new Vec3(Math.cos(-yawRad), 0, -Math.sin(-yawRad)); // Perp to forward

        // 1. Group by Y (threshold 0.5)
        // If pitch > 0 (looking down), we might want to start from the floor (ascending).
        // If pitch < 0 (looking up), start from the ceiling (descending).
        boolean yAscending = pitch >= 0;
        Map<Integer, List<Vec3>> layers = yAscending ? new TreeMap<>() : new TreeMap<>(Collections.reverseOrder());
        
        for (Vec3 p : points) {
            int yKey = (int) Math.round(p.y);
            layers.computeIfAbsent(yKey, k -> new ArrayList<>()).add(p);
        }

        List<Vec3> fullPath = new ArrayList<>();
        boolean reverseRow = false;
        
        for (int yKey : layers.keySet()) {
            List<Vec3> layerPoints = layers.get(yKey);
            
            // 2. Group layer points by "Forward" distance (Depth rows)
            // Use 1.0 block spacing as threshold for rows
            Map<Integer, List<Vec3>> rows = new TreeMap<>();
            for (Vec3 p : layerPoints) {
                double forwardDist = p.dot(forward);
                int rowKey = (int) Math.floor(forwardDist);
                rows.computeIfAbsent(rowKey, k -> new ArrayList<>()).add(p);
            }
            
            for (int rowKey : rows.keySet()) {
                List<Vec3> rowPoints = rows.get(rowKey);
                // Sort by "Right" axis
                rowPoints.sort(Comparator.comparingDouble(p -> p.dot(right)));
                if (reverseRow) Collections.reverse(rowPoints);
                
                fullPath.addAll(rowPoints);
                reverseRow = !reverseRow;
            }
            // Optional: add a tiny gap or logic to keep the zigzag continuous between layers 
            // but reversing Row works well enough.
        }

        // Ensure we start with the one closest to the player
        if (mc.player != null && !fullPath.isEmpty()) {
            double startDist = fullPath.get(0).distanceToSqr(mc.player.position());
            double endDist = fullPath.get(fullPath.size() - 1).distanceToSqr(mc.player.position());
            if (endDist < startDist) {
                Collections.reverse(fullPath);
            }
        }

        currentPath.addAll(fullPath);
    }

    private static final String ASHWREATH_SKIN_ID = "5890f50780fdecedaa85aa40bf3399e9439ee68594c6d022688165608171681d";
    private static final String TURTELLINI_SKIN_ID = "1d1bd06a6738d0da5053eae49a1362b89489d1ac004c222504536f7bcd07679d";
    private static final String GLASSCORN_SKIN_ID = "297de27338b9f876e570d1cc01fe1beccfc940467c5c97c467e93e79c81c25ee";

    private static void scanNearbySkulls(SkullScanConsumer consumer) {
        Minecraft mc = client();
        if (mc.player == null || mc.level == null) {
            return;
        }

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand armorStand)) {
                continue;
            }

            ItemStack headItem = armorStand.getItemBySlot(EquipmentSlot.HEAD);
            if (headItem.isEmpty()) {
                continue;
            }

            ResolvableProfile profile = headItem.get(DataComponents.PROFILE);
            if (profile != null) {
                consumer.accept(new SkullScanEntry(
                                profile,
                                armorStand.getX(),
                                armorStand.getY() + armorStand.getEyeHeight(),
                                armorStand.getZ(),
                                AetherLang.localize("armor stand")));
            }
        }

        BlockPos base = mc.player.blockPosition();
        for (int x = -SKULL_SCAN_RADIUS; x <= SKULL_SCAN_RADIUS; x++) {
            for (int y = -10; y <= 10; y++) {
                for (int z = -SKULL_SCAN_RADIUS; z <= SKULL_SCAN_RADIUS; z++) {
                    BlockPos pos = base.offset(x, y, z);
                    BlockEntity be = mc.level.getBlockEntity(pos);
                    if (!(be instanceof SkullBlockEntity skullBlock)) {
                        continue;
                    }

                    ResolvableProfile profile = skullBlock.getOwnerProfile();
                    if (profile != null) {
                        consumer.accept(new SkullScanEntry(
                                profile,
                                pos.getX() + 0.5,
                                pos.getY() + 0.25,
                                pos.getZ() + 0.5,
                                AetherLang.localize("skull block")));
                    }
                }
            }
        }
    }

    private static void processProfile(ResolvableProfile profile, double x, double y, double z) {
        String skinId = extractSkinId(profile);

        boolean shouldHighlight = false;
        if (AetherConfig.HARVEST_ASHWREATH.get() && skinId.equals(ASHWREATH_SKIN_ID)) {
            shouldHighlight = true;
        }
        if (AetherConfig.HARVEST_TURTELLINI.get() && skinId.equals(TURTELLINI_SKIN_ID)) {
            shouldHighlight = true;
        }
        if (AetherConfig.HARVEST_GLASSCORN.get() && skinId.equals(GLASSCORN_SKIN_ID)) {
            shouldHighlight = true;
        }

        if (shouldHighlight) {
            String name = profile.name().orElse("Skull");
            AABB box = new AABB(x - 0.25, y - 0.25, z - 0.25, x + 0.25, y + 0.25, z + 0.25);
            highlightedSkulls.add(box);

            ClientUtils.sendDebugMessage(String.format("Found highlighted: %s (ID: %s) at %.1f, %.1f, %.1f",
                            name, skinId, x, y, z));
        } else {
            // Still show debug in chat if debug mode is on, even if not highlighted
            String name = profile.name().orElse("Skull");
            ClientUtils.sendDebugMessage(String.format("Filtered: %s (ID: %s) at %.1f, %.1f, %.1f",
                            name, skinId, x, y, z));
        }
    }

    public static int getHighlightedCount() {
        return highlightedSkulls.size();
    }

    public static Vec3 getCurrentTarget() {
        if (currentSkullIndex >= 0 && currentSkullIndex < flyToPoints.size()) {
            return flyToPoints.get(currentSkullIndex);
        }
        return null;
    }

    public static Vec3 getCurrentAimTarget() {
        Vec3 target = getCurrentTarget();
        return target == null ? null : toAimTarget(target);
    }

    private static String extractSkinId(ResolvableProfile profile) {
        var properties = profile.partialProfile().properties();
        if (properties == null) {
            return "Unknown";
        }

        var textures = properties.get("textures");
        if (textures == null || textures.isEmpty()) {
            return "Unknown";
        }

        String value = textures.iterator().next().value();
        try {
            String decodedJson = new String(Base64.getDecoder().decode(value));

            JsonElement root = JsonParser.parseString(decodedJson);
            if (root.isJsonObject()) {
                JsonObject textureObject = root.getAsJsonObject()
                        .getAsJsonObject("textures");
                if (textureObject != null) {
                    JsonObject skinObject = textureObject.getAsJsonObject("SKIN");
                    if (skinObject != null && skinObject.has("url")) {
                        String url = skinObject.get("url").getAsString();
                        String extracted = extractSkinIdFromUrl(url);
                        if (!"Unknown".equals(extracted)) {
                            return extracted;
                        }
                    }
                }
            }

            int textureUrlIndex = decodedJson.indexOf("textures.minecraft.net/texture/");
            if (textureUrlIndex != -1) {
                int idStart = textureUrlIndex + "textures.minecraft.net/texture/".length();
                int idEnd = decodedJson.indexOf("\"", idStart);
                if (idEnd != -1 && idStart < idEnd) {
                    return decodedJson.substring(idStart, idEnd);
                }
            }
        } catch (Exception ignored) {
        }
        return "Unknown";
    }

    private static String extractSkinIdFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "Unknown";
        }

        int lastSlash = url.lastIndexOf('/');
        if (lastSlash == -1 || lastSlash >= url.length() - 1) {
            return "Unknown";
        }
        return url.substring(lastSlash + 1);
    }

    private record SkullScanEntry(ResolvableProfile profile, double x, double y, double z, String sourceLabel) {
        private String name() {
            return profile.name().orElse("Skull");
        }

        private String skinId() {
            return extractSkinId(profile);
        }
    }

    @FunctionalInterface
    private interface SkullScanConsumer {
        void accept(SkullScanEntry entry);
    }
}
