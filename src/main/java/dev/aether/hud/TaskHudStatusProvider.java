package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.ComposterManager;
import dev.aether.modules.CropFeverManager;
import dev.aether.modules.GreenhouseManager;
import dev.aether.modules.SupercraftManager;
import dev.aether.modules.inventorymanager.AutoSellManager;
import dev.aether.modules.inventorymanager.BookCombineManager;
import dev.aether.modules.inventorymanager.GeorgeManager;
import dev.aether.modules.inventorymanager.JunkManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.pest.helpers.AutoSprayonatorManager;
import dev.aether.modules.pest.helpers.PestBonusManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.PestExchangeManager;
import dev.aether.modules.pest.helpers.PestTrapManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.visitor.VisitorManager;
import dev.aether.modules.visitor.VisitorsMacro;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TaskHudStatusProvider {

    private static final Pattern SPRAY_TIMER_PATTERN = Pattern.compile("(?i)Spray:\\s*.+?\\((?:(\\d+)m\\s*)?(?:(\\d+)s)?\\)");
    private static final EnumMap<TaskGroupHudElement.Group, List<TaskStatusRow>> CACHED_ROWS =
            new EnumMap<>(TaskGroupHudElement.Group.class);
    private static long cachedTick = Long.MIN_VALUE;

    private TaskHudStatusProvider() {}

    static List<TaskStatusRow> getRows(TaskGroupHudElement.Group group, Minecraft client) {
        refreshCache(client);
        return CACHED_ROWS.getOrDefault(group, List.of());
    }

    private static void refreshCache(Minecraft client) {
        long tick = resolveTick(client);
        if (tick == cachedTick) {
            return;
        }

        CACHED_ROWS.put(TaskGroupHudElement.Group.INTERMEDIARIES,
                Collections.unmodifiableList(getIntermediaries(client)));
        CACHED_ROWS.put(TaskGroupHudElement.Group.MID_FARMING_TASKS,
                Collections.unmodifiableList(getMidFarmingTasks(client)));
        CACHED_ROWS.put(TaskGroupHudElement.Group.FAILSAFES,
                Collections.unmodifiableList(getFailsafes(client)));
        cachedTick = tick;
    }

    private static long resolveTick(Minecraft client) {
        if (client != null && client.level != null) {
            return client.level.getGameTime();
        }
        return Long.MIN_VALUE + System.nanoTime();
    }

    static List<TaskStatusRow> getIntermediaries(Minecraft client) {
        List<TaskStatusRow> rows = new ArrayList<>();
        rows.add(buildPestDestroyer(client));
        rows.add(buildClearTraps(client));
        rows.add(buildRefillTraps(client));
        rows.add(buildGreenhouse(client));
        rows.add(buildComposter(client));
        rows.add(buildSupercraft(client));
        rows.add(buildVisitors(client));
        return rows;
    }

    static List<TaskStatusRow> getMidFarmingTasks(Minecraft client) {
        List<TaskStatusRow> rows = new ArrayList<>();
        rows.add(buildSprayonator(client));
        rows.add(buildPestExchange(client));
        rows.add(buildAutoSell(client));
        rows.add(buildJunkDropper(client));
        rows.add(buildAutoCombine(client));
        rows.add(buildGeorgeSell(client));
        return rows;
    }

    static List<TaskStatusRow> getFailsafes(Minecraft client) {
        List<TaskStatusRow> rows = new ArrayList<>();
        rows.add(buildInventorySlotChanged(client));
        rows.add(buildGuiOpenedFailsafe(client));
        rows.add(buildBpsFailsafe(client));
        rows.add(buildGhostBlockFailsafe(client));
        rows.add(buildDirtCheckFailsafe(client));
        rows.add(buildRotationFailsafe(client));
        return rows;
    }

    private static TaskStatusRow buildPestDestroyer(Minecraft client) {
        if (!PestManager.isPestDestroyerEnabled()) {
            return TaskStatusRow.disabled("Pest Destroyer", "disabled");
        }
        if (PestDestroyer.isActive()) {
            int alive = PestManager.getEffectiveAliveCountNow(client);
            return TaskStatusRow.running("Pest Destroyer", alive > 0 ? alive + " pests remaining" : "cleaning current cycle");
        }
        if (GreenhouseManager.isRunning()) {
            return TaskStatusRow.waiting("Pest Destroyer", "handoff complete");
        }

        int alive = PestManager.getEffectiveAliveCountNow(client);
        int threshold = AetherConfig.PEST_THRESHOLD.get();
        long cooldownMs = PestManager.getPestReentryCooldownRemainingMs();

        if (cooldownMs > 0 && alive >= threshold) {
            return TaskStatusRow.waiting("Pest Destroyer", "cooldown " + fmtSeconds(cooldownMs));
        }
        if (AetherConfig.AUTO_PEST_EXCHANGE.get() && PestBonusManager.isBonusInactive) {
            return TaskStatusRow.blocked("Pest Destroyer", "waiting for pest exchange");
        }
        if (AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() && CropFeverManager.isCropFeverActive) {
            return TaskStatusRow.blocked("Pest Destroyer", "crop fever active");
        }
        if (VisitorsMacro.isRunning) {
            return TaskStatusRow.blocked("Pest Destroyer", "visitors are running");
        }
        if (alive >= threshold || alive >= 8) {
            return TaskStatusRow.ready("Pest Destroyer", alive + "/" + threshold + " pests");
        }
        if (alive < 0) {
            return TaskStatusRow.waiting("Pest Destroyer", "waiting for pest tab data");
        }
        return TaskStatusRow.waiting("Pest Destroyer", alive + "/" + threshold + " pests");
    }

    private static TaskStatusRow buildVisitors(Minecraft client) {
        if (!AetherConfig.AUTO_VISITOR.get()) {
            return TaskStatusRow.disabled("Visitors", "auto visitor disabled");
        }
        if (VisitorsMacro.isRunning) {
            return TaskStatusRow.running("Visitors", "serving garden queue");
        }

        int count = VisitorManager.getVisitorCount(client);
        int threshold = AetherConfig.VISITOR_THRESHOLD.get();
        long cooldownMs = VisitorManager.getVisitorReentryCooldownRemainingMs();

        if (count >= threshold && cooldownMs > 0) {
            return TaskStatusRow.waiting("Visitors", count + "/" + threshold + " queued, cd " + fmtSeconds(cooldownMs));
        }
        if (count >= threshold) {
            return TaskStatusRow.ready("Visitors", count + "/" + threshold + " queued");
        }
        return TaskStatusRow.waiting("Visitors", count + "/" + threshold + " queued");
    }

    private static TaskStatusRow buildClearTraps(Minecraft client) {
        if (!PestManager.arePestTrapsEnabled()) {
            return TaskStatusRow.disabled("Clear Pest Traps", "pest traps disabled");
        }
        if (!AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()) {
            return TaskStatusRow.disabled("Clear Pest Traps", "clean pest traps disabled");
        }

        int fullTrapCount = PestTrapManager.getFullTrapsFromTab(client).size();
        if (PestTrapManager.isRunning && PestTrapManager.currentOperation == PestTrapManager.Operation.CLEAR) {
            return TaskStatusRow.running("Clear Pest Traps",
                    fullTrapCount > 0 ? fullTrapCount + " full traps queued" : "clearing current pass");
        }
        if (MacroStateManager.getCurrentState() != MacroState.State.FARMING && fullTrapCount > 0) {
            return TaskStatusRow.blocked("Clear Pest Traps", fullTrapCount + " full traps waiting");
        }
        if (fullTrapCount > 0) {
            return TaskStatusRow.ready("Clear Pest Traps", fullTrapCount + " full traps");
        }
        return TaskStatusRow.waiting("Clear Pest Traps", "0 full traps");
    }

    private static TaskStatusRow buildRefillTraps(Minecraft client) {
        if (!PestManager.arePestTrapsEnabled()) {
            return TaskStatusRow.disabled("Refill Pest Traps", "pest traps disabled");
        }
        if (!AetherConfig.AUTO_REFILL_PEST_TRAPS.get()) {
            return TaskStatusRow.disabled("Refill Pest Traps", "refill pest traps disabled");
        }

        int emptyTrapCount = PestTrapManager.getNoBaitTrapsFromTab(client).size();
        if (PestTrapManager.isRunning && PestTrapManager.currentOperation == PestTrapManager.Operation.REFILL) {
            return TaskStatusRow.running("Refill Traps",
                    emptyTrapCount > 0 ? emptyTrapCount + " empty traps queued" : "refilling current pass");
        }
        if (MacroStateManager.getCurrentState() != MacroState.State.FARMING && emptyTrapCount > 0) {
            return TaskStatusRow.blocked("Refill Traps", emptyTrapCount + " empty traps waiting");
        }
        if (emptyTrapCount > 0) {
            return TaskStatusRow.ready("Refill Traps", emptyTrapCount + " empty traps");
        }
        return TaskStatusRow.waiting("Refill Traps", "0 empty traps");
    }

    private static TaskStatusRow buildGreenhouse(Minecraft client) {
        if (!AetherConfig.AUTO_GREENHOUSE.get()) {
            return TaskStatusRow.disabled("Greenhouse", "auto greenhouse disabled");
        }
        if (GreenhouseManager.isRunning()) {
            return TaskStatusRow.running("Greenhouse", "harvesting configured plots");
        }

        long intervalMs = Math.max(1L, AetherConfig.AUTO_GREENHOUSE_INTERVAL_MINUTES.get()) * 60_000L;
        long elapsedMs = GreenhouseManager.getAutoGreenhouseElapsedMs();
        if (elapsedMs >= intervalMs) {
            return TaskStatusRow.ready("Greenhouse", fmtMinutes(elapsedMs) + "/" + fmtMinutes(intervalMs));
        }
        return TaskStatusRow.waiting("Greenhouse", fmtMinutes(elapsedMs) + "/" + fmtMinutes(intervalMs));
    }

    private static TaskStatusRow buildComposter(Minecraft client) {
        if (!AetherConfig.AUTO_COMPOSTER.get()) {
            return TaskStatusRow.disabled("Composter", "auto composter disabled");
        }
        if (ComposterManager.isRunning()) {
            return TaskStatusRow.running("Composter", "refilling resources");
        }

        long intervalMs = Math.max(1L, AetherConfig.AUTO_COMPOSTER_INTERVAL_MINUTES.get()) * 60_000L;
        long elapsedMs = ComposterManager.getAutoComposterElapsedMs();
        if (elapsedMs >= intervalMs) {
            return TaskStatusRow.ready("Composter", fmtMinutes(elapsedMs) + "/" + fmtMinutes(intervalMs));
        }
        return TaskStatusRow.waiting("Composter", fmtMinutes(elapsedMs) + "/" + fmtMinutes(intervalMs));
    }

    private static TaskStatusRow buildSupercraft(Minecraft client) {
        if (!AetherConfig.AUTO_SUPERCRAFT.get()) {
            return TaskStatusRow.disabled("Supercraft", "auto supercraft disabled");
        }
        if (SupercraftManager.isRunning()) {
            return TaskStatusRow.running("Supercraft", "crafting configured items");
        }
        if (AetherConfig.AUTO_SUPERCRAFT_ITEMS.get().stream().noneMatch(s -> s != null && !s.trim().isEmpty())) {
            return TaskStatusRow.disabled("Supercraft", "no supercraft items configured");
        }

        long intervalMs = Math.max(1L, AetherConfig.AUTO_SUPERCRAFT_INTERVAL_MINUTES.get()) * 60_000L;
        long elapsedMs = SupercraftManager.getAutoSupercraftElapsedMs();
        if (elapsedMs >= intervalMs) {
            return TaskStatusRow.ready("Supercraft", fmtMinutes(elapsedMs) + "/" + fmtMinutes(intervalMs));
        }
        return TaskStatusRow.waiting("Supercraft", fmtMinutes(elapsedMs) + "/" + fmtMinutes(intervalMs));
    }

    private static TaskStatusRow buildSprayonator(Minecraft client) {
        if (!AetherConfig.AUTO_SPRAYONATOR.get()) {
            return TaskStatusRow.disabled("Sprayonator", "auto spray disabled");
        }
        if (AutoSprayonatorManager.isRunning() || MacroStateManager.getCurrentState() == MacroState.State.SPRAYING) {
            return TaskStatusRow.running("Sprayonator", "spraying unsprayed plot");
        }

        long detectMs = AetherConfig.AUTO_SPRAYONATOR_DETECT_TIME.get() * 1000L;
        long elapsedMs = AutoSprayonatorManager.getSprayNeededElapsedMs(client);
        boolean sprayNeeded = AutoSprayonatorManager.isSprayNeededNow(client);

        if (!sprayNeeded) {
            return TaskStatusRow.waiting("Sprayonator", "Current Plot: " + getCurrentPlotSprayTimer(client));
        }
        if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return TaskStatusRow.blocked("Sprayonator", "waiting for farming state");
        }
        if (elapsedMs >= detectMs) {
            return TaskStatusRow.ready("Sprayonator", fmtSeconds(elapsedMs) + "/" + fmtSeconds(detectMs));
        }
        return TaskStatusRow.waiting("Sprayonator", fmtSeconds(elapsedMs) + "/" + fmtSeconds(detectMs));
    }

    private static TaskStatusRow buildPestExchange(Minecraft client) {
        if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) {
            return TaskStatusRow.disabled("Pest Exchange", "auto exchange disabled");
        }
        if (AutoPestExchangeManager.isRunning() || PestExchangeManager.isExchanging) {
            return TaskStatusRow.running("Pest Exchange", "heading to Phillip");
        }

        long cooldownMs = AutoPestExchangeManager.getRunCooldownRemainingMs();
        long inactiveMs = AutoPestExchangeManager.getBonusInactiveElapsedMs();

        if (!PestBonusManager.isBonusInactive) {
            return TaskStatusRow.waiting("Pest Exchange", "bonus is active");
        }
        if (cooldownMs > 0 && inactiveMs > 0) {
            return TaskStatusRow.waiting("Pest Exchange", "cooldown " + fmtSeconds(cooldownMs));
        }
        if (PestManager.isCleaningInProgress) {
            return TaskStatusRow.blocked("Pest Exchange", "waiting for pest cycle");
        }
        if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return TaskStatusRow.blocked("Pest Exchange", "waiting for farming state");
        }
        if (inactiveMs > 0) {
            return TaskStatusRow.ready("Pest Exchange", "ready");
        }
        return TaskStatusRow.waiting("Pest Exchange", "waiting for bonus trigger");
    }

    private static TaskStatusRow buildAutoSell(Minecraft client) {
        if (!AetherConfig.AUTO_SELL.get()) {
            return TaskStatusRow.disabled("AutoSell", "inventory sell disabled");
        }
        if (AutoSellManager.isPreparingToSell || AutoSellManager.isSelling) {
            return TaskStatusRow.running("AutoSell", "selling inventory");
        }

        double fullness = AutoSellManager.getInventoryFullnessRatio(client);
        int threshold = AetherConfig.AUTO_SELL_THRESHOLD.get();
        long elapsedMs = AutoSellManager.getThresholdMetElapsedMs();
        long requiredMs = AetherConfig.AUTO_SELL_TIME.get() * 1000L;
        long cooldownMs = AutoSellManager.getSellCooldownRemainingMs();

        if (fullness >= threshold && cooldownMs > 0) {
            return TaskStatusRow.waiting("AutoSell", pct(fullness) + ", cd " + fmtSeconds(cooldownMs));
        }
        if (fullness >= threshold && MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return TaskStatusRow.blocked("AutoSell", pct(fullness) + ", waiting for farming");
        }
        if (fullness >= threshold && elapsedMs >= requiredMs) {
            return TaskStatusRow.ready("AutoSell", pct(fullness) + " for " + fmtSeconds(elapsedMs));
        }
        if (fullness >= threshold) {
            return TaskStatusRow.waiting("AutoSell", pct(fullness) + " for " + fmtSeconds(elapsedMs) + "/" + fmtSeconds(requiredMs));
        }
        return TaskStatusRow.waiting("AutoSell", pct(fullness) + " / " + threshold + "%");
    }

    private static TaskStatusRow buildJunkDropper(Minecraft client) {
        if (!AetherConfig.AUTO_DROP_JUNK.get()) {
            return TaskStatusRow.disabled("Junk Dropper", "junk drop disabled");
        }
        if (JunkManager.isPreparingToDrop || JunkManager.isDropping) {
            return TaskStatusRow.running("Junk Dropper", "dropping junk items");
        }

        int count = JunkManager.countJunkItems(client);
        int threshold = AetherConfig.JUNK_THRESHOLD.get();
        if (count >= threshold && MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return TaskStatusRow.blocked("Junk Dropper", count + "/" + threshold + " junk items");
        }
        if (count >= threshold) {
            return TaskStatusRow.ready("Junk Dropper", count + "/" + threshold + " junk items");
        }
        return TaskStatusRow.waiting("Junk Dropper", count + "/" + threshold + " junk items");
    }

    private static TaskStatusRow buildAutoCombine(Minecraft client) {
        if (!AetherConfig.AUTO_BOOK_COMBINE.get()) {
            return TaskStatusRow.disabled("Auto Combine", "book combine disabled");
        }
        if (BookCombineManager.isPreparingToCombine || BookCombineManager.isCombining) {
            return TaskStatusRow.running("Auto Combine", "combining books");
        }

        int count = BookCombineManager.getBookCountInInventory(client);
        int threshold = AetherConfig.BOOK_THRESHOLD.get();
        if (count >= threshold && MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return TaskStatusRow.blocked("Auto Combine", count + "/" + threshold + " books");
        }
        if (count >= threshold) {
            return TaskStatusRow.ready("Auto Combine", count + "/" + threshold + " books");
        }
        return TaskStatusRow.waiting("Auto Combine", count + "/" + threshold + " books");
    }

    private static TaskStatusRow buildGeorgeSell(Minecraft client) {
        if (!AetherConfig.AUTO_GEORGE_SELL.get()) {
            return TaskStatusRow.disabled("George Sell", "george sell disabled");
        }
        if (GeorgeManager.isPreparingToSell || GeorgeManager.isSelling) {
            return TaskStatusRow.running("George Sell", GeorgeManager.isPreparingToSell ? "calling George" : "selling pests");
        }

        int count = GeorgeManager.getSellablePetCount(client);
        int threshold = AetherConfig.GEORGE_SELL_THRESHOLD.get();
        long cooldownMs = GeorgeManager.getGeorgeCooldownRemainingMs();

        if (count >= threshold && cooldownMs > 0) {
            return TaskStatusRow.waiting("George Sell", count + "/" + threshold + " pets, cd " + fmtSeconds(cooldownMs));
        }
        if (count >= threshold && MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return TaskStatusRow.blocked("George Sell", count + "/" + threshold + " pets");
        }
        if (count >= threshold) {
            return TaskStatusRow.ready("George Sell", count + "/" + threshold + " pets");
        }
        return TaskStatusRow.waiting("George Sell", count + "/" + threshold + " pets");
    }

    private static TaskStatusRow buildInventorySlotChanged(Minecraft client) {
        if (!AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.get()) {
            return TaskStatusRow.disabled("Inventory Slot Changed", "failsafe disabled");
        }

        int currentSlot = FailsafeManager.getCurrentSelectedSlot(client);
        int expectedSlot = FailsafeManager.getExpectedSelectedSlot();
        String slotDetail = "Current: " + fmtSlot(currentSlot) + " / Expected: " + fmtSlot(expectedSlot);

        return switch (FailsafeManager.getInventorySlotState(client)) {
            case TRIGGERED -> TaskStatusRow.triggered("Inventory Slot Changed", slotDetail);
            case WAIT -> TaskStatusRow.waiting("Inventory Slot Changed",
                    slotDetail + "\n" + "triggering in " + fmtTenthsSeconds(FailsafeManager.getInventorySlotTriggerRemainingMs()));
            case IDLE -> TaskStatusRow.ready("Inventory Slot Changed", slotDetail);
        };
    }

    private static TaskStatusRow buildGuiOpenedFailsafe(Minecraft client) {
        if (!AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get()) {
            return TaskStatusRow.disabled("GUI Opened", "failsafe disabled");
        }

        String guiDetail = ClientUtils.isInventoryScreenOpen()
                ? "Unexpected inventory GUI is currently open"
                : "No unexpected inventory GUI detected";

        return switch (FailsafeManager.getInventoryGuiState(client)) {
            case TRIGGERED -> TaskStatusRow.triggered("GUI Opened", guiDetail);
            case WAIT -> TaskStatusRow.waiting("GUI Opened",
                    guiDetail + "\n" + "triggering in " + fmtTenthsSeconds(FailsafeManager.getInventoryGuiTriggerRemainingMs()));
            case IDLE -> TaskStatusRow.ready("GUI Opened", guiDetail);
        };
    }

    private static TaskStatusRow buildBpsFailsafe(Minecraft client) {
        if (!AetherConfig.FAILSAFE_BPS.get()) {
            return TaskStatusRow.disabled("BPS", "failsafe disabled");
        }

        String detail = String.format(java.util.Locale.US,
                "Current: %.2f (%d / %.1fs) / Expected: >= %.2f",
                FailsafeManager.getCurrentBps(),
                FailsafeManager.getBpsBreakCount(),
                FailsafeManager.getBpsWindowSeconds(),
                FailsafeManager.getExpectedBps());

        return switch (FailsafeManager.getBpsState(client)) {
            case TRIGGERED -> TaskStatusRow.triggered("BPS", detail);
            case WAIT -> {
                long remainingMs = FailsafeManager.getBpsTriggerRemainingMs();
                yield remainingMs > 0L
                        ? TaskStatusRow.waiting("BPS", detail + "\n" + "triggering in " + fmtTenthsSeconds(remainingMs))
                        : TaskStatusRow.waiting("BPS", detail);
            }
            case IDLE -> TaskStatusRow.ready("BPS", detail);
        };
    }

    private static TaskStatusRow buildGhostBlockFailsafe(Minecraft client) {
        if (!AetherConfig.FAILSAFE_GHOST_BLOCK.get()) {
            return TaskStatusRow.disabled("Ghost Block", "failsafe disabled");
        }

        String detail = String.format(java.util.Locale.US,
                "Farming Exp Text: %s / Missing For: %.1fs / Window: %.1fs",
                FailsafeManager.isGhostBlockFarmingTextVisible(client) ? "visible" : "missing",
                FailsafeManager.getGhostBlockWindowSeconds(),
                FailsafeManager.getExpectedGhostBlockWindowSeconds());

        return switch (FailsafeManager.getGhostBlockState(client)) {
            case TRIGGERED -> TaskStatusRow.triggered("Ghost Block", detail);
            case WAIT -> {
                long remainingMs = FailsafeManager.getGhostBlockTriggerRemainingMs();
                yield remainingMs > 0L
                        ? TaskStatusRow.waiting("Ghost Block", detail + "\n" + "triggering in " + fmtTenthsSeconds(remainingMs))
                        : TaskStatusRow.waiting("Ghost Block", detail);
            }
            case IDLE -> TaskStatusRow.ready("Ghost Block", detail);
        };
    }

    private static TaskStatusRow buildDirtCheckFailsafe(Minecraft client) {
        if (!AetherConfig.FAILSAFE_DIRT_CHECK.get()) {
            return TaskStatusRow.disabled("Dirt Check", "failsafe disabled");
        }

        String detail = String.format(java.util.Locale.US,
                "Touching: %s / Tracked Blocks: %d",
                FailsafeManager.isTouchingDirtBlock(client) ? "yes" : "no",
                FailsafeManager.getDirtCheckTrackedBlockCount(client));

        return switch (FailsafeManager.getDirtCheckState(client)) {
            case TRIGGERED -> TaskStatusRow.triggered("Dirt Check", detail);
            case WAIT -> {
                long remainingMs = FailsafeManager.getDirtCheckTriggerRemainingMs();
                yield remainingMs > 0L
                        ? TaskStatusRow.waiting("Dirt Check", detail + "\n" + "triggering in " + fmtTenthsSeconds(remainingMs))
                        : TaskStatusRow.waiting("Dirt Check", detail);
            }
            case IDLE -> TaskStatusRow.ready("Dirt Check", detail);
        };
    }

    private static TaskStatusRow buildRotationFailsafe(Minecraft client) {
        if (!AetherConfig.FAILSAFE_ROTATION.get()) {
            return TaskStatusRow.disabled("Rotation", "failsafe disabled");
        }

        String detail = String.format(java.util.Locale.US,
                "Current Yaw/Pitch: %.1f / %.1f\nExpected Yaw/Pitch: %.1f / %.1f",
                client != null && client.player != null ? Mth.wrapDegrees(client.player.getYRot()) : 0.0f,
                client != null && client.player != null ? client.player.getXRot() : 0.0f,
                FailsafeManager.getExpectedYaw(),
                FailsafeManager.getExpectedPitch());

        return switch (FailsafeManager.getRotationState(client)) {
            case TRIGGERED -> TaskStatusRow.triggered("Rotation", detail);
            case WAIT -> {
                long remainingMs = FailsafeManager.getRotationTriggerRemainingMs();
                yield remainingMs > 0L
                        ? TaskStatusRow.waiting("Rotation", detail + "\n" + "triggering in " + fmtTenthsSeconds(remainingMs))
                        : TaskStatusRow.waiting("Rotation", detail);
            }
            case IDLE -> TaskStatusRow.ready("Rotation", detail);
        };
    }

    private static String getCurrentPlotSprayTimer(Minecraft client) {
        if (client == null || client.player == null || client.getConnection() == null) {
            return "-- / 30:00";
        }

        for (String line : TablistUtils.getRawTabLines(client)) {
            Matcher matcher = SPRAY_TIMER_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            int minutes = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : 0;
            int seconds = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            return String.format("%d:%02d / 30:00", minutes, seconds);
        }

        return "-- / 30:00";
    }

    private static String fmtSeconds(long ms) {
        long seconds = Math.max(0L, (ms + 999L) / 1000L);
        return seconds + "s";
    }

    private static String fmtMinutes(long ms) {
        long minutes = Math.max(0L, (ms + 59_999L) / 60_000L);
        return minutes + "m";
    }

    private static String pct(double value) {
        return String.format("%.0f%%", value);
    }

    private static String fmtTenthsSeconds(long ms) {
        return String.format(java.util.Locale.US, "%.1f seconds", Math.max(0L, ms) / 1000.0);
    }

    private static String fmtSlot(int slot) {
        return slot >= 0 ? String.valueOf(slot + 1) : "-";
    }

    static final class TaskStatusRow {
        final String name;
        final String detail;
        final String[] detailLines;
        final String badge;
        final int color;

        private TaskStatusRow(String name, String detail, String badge, int color) {
            this.name = name;
            this.detail = detail;
            this.detailLines = detail.isEmpty() ? new String[]{""} : detail.split("\\n");
            this.badge = badge;
            this.color = color;
        }

        static TaskStatusRow disabled(String name, String detail) {
            return new TaskStatusRow(name, detail, "OFF", 0xFF7E8798);
        }

        static TaskStatusRow waiting(String name, String detail) {
            return new TaskStatusRow(name, detail, "WAIT", 0xFFF6C453);
        }

        static TaskStatusRow blocked(String name, String detail) {
            return new TaskStatusRow(name, detail, "HOLD", 0xFFFF8A65);
        }

        static TaskStatusRow ready(String name, String detail) {
            return new TaskStatusRow(name, detail, "READY", 0xFF4ADE80);
        }

        static TaskStatusRow running(String name, String detail) {
            return new TaskStatusRow(name, detail, "LIVE", 0xFF5EEAD4);
        }

        static TaskStatusRow triggered(String name, String detail) {
            return new TaskStatusRow(name, detail, "TRIG", 0xFFFF5555);
        }
    }
}
