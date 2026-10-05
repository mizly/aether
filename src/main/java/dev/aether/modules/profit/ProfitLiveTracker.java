package dev.aether.modules.profit;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.profit.helpers.SkillXpTracker;
import dev.aether.modules.profit.helpers.PetXpTracker;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

final class ProfitLiveTracker {
    private static final Set<String> BASE_CROPS = Set.of(
            "Wheat", "Potato", "Carrot", "Melon Slice", "Pumpkin",
            "Sugar Cane", "Cactus", "Nether Wart", "Cocoa Beans",
            "Red Mushroom", "Brown Mushroom",
            "Sunflower", "Moonflower", "Wild Rose", "Seeds");

    private static final int PURSE_SAMPLE_INTERVAL_TICKS = 5;
    private static final int PET_XP_SAMPLE_INTERVAL_TICKS = 5;
    private static final long MAX_CULTIVATING_DELTA = 50000L;
    private static final long MAX_PURSE_DELTA = 50000L;
    /**
     * Purse gains are ignored for this many ticks after the macro was doing anything other than farming.
     * The scoreboard updates a moment after a sale, so NPC/George/autosell coins used to land after the
     * state flipped back to FARMING and were counted on top of the crops/pets they came from.
     */
    private static final int PURSE_SETTLE_TICKS = 100;
    private static final Set<String> MUSHROOMS = Set.of("Red Mushroom", "Brown Mushroom");

    private final Map<String, Long> prevInventoryCounts = new LinkedHashMap<>();
    private final Runnable refreshPrices;

    private long lastCultivatingValue = -1L;
    private String currentFarmedCrop = "Wheat";
    private String lastCultivatingToolUuid = null;
    private long lastPurseBalance = -1L;
    private int ticksSinceNonFarming = 0;
    private boolean trackingLiveMetrics = false;

    ProfitLiveTracker(Runnable refreshPrices) {
        this.refreshPrices = refreshPrices;
    }

    void resetSessionState() {
        prevInventoryCounts.clear();
        lastCultivatingValue = -1L;
        lastCultivatingToolUuid = null;
        lastPurseBalance = -1L;
        ticksSinceNonFarming = 0;
        currentFarmedCrop = "Wheat";
        PetXpTracker.reset();
        if (AetherConfig.PERSIST_SESSION_TIMER.get()) {
            SkillXpTracker.resetAllLiveState();
        } else {
            SkillXpTracker.resetAll();
        }
        trackingLiveMetrics = false;
    }

    void update(Minecraft client, BiConsumer<String, Long> dropRecorder) {
        if (client.player == null) {
            if (trackingLiveMetrics) {
                resetSessionState();
            }
            return;
        }

        if (!ProfitManager.isProfitTrackingActive()) {
            if (trackingLiveMetrics) {
                resetSessionState();
            }
            return;
        }

        if (!trackingLiveMetrics) {
            resetSessionState();
            trackingLiveMetrics = true;
        }

        ItemStack held = client.player.getMainHandItem();
        net.minecraft.nbt.CompoundTag heldTag = null;
        if (held != null && !held.isEmpty()) {
            CustomData custom = held.get(DataComponents.CUSTOM_DATA);
            if (custom != null) {
                heldTag = custom.copyTag();
            }
        }
        String heldId = "";
        if (heldTag != null) {
            net.minecraft.nbt.CompoundTag tag = heldTag;
            heldId = tag.getString("id").orElseGet(() -> tag.getCompound("ExtraAttributes")
                    .flatMap(attributes -> attributes.getString("id")).orElse(""));
        }

        String inventoryCrop = detectCropFromInventory(client, ProfitTrackingRules.isMushroomTool(heldId));
        String toolCrop = ProfitTrackingRules.cropForToolId(heldId);
        if (toolCrop != null) {
            // Crop-specific tools decide the crop outright. Inventory detection alone failed whenever crops
            // went straight to sacks: nothing changed in the inventory, so everything stayed "Wheat".
            currentFarmedCrop = toolCrop;
        } else if (inventoryCrop != null) {
            currentFarmedCrop = inventoryCrop;
        }

        if (heldTag != null && heldTag.contains("farmed_cultivating")) {
            long newValue = heldTag.getLong("farmed_cultivating").orElse(-1L);
            String toolUuid = heldTag.getString("uuid").orElse(heldId);
            boolean sameTool = toolUuid.equals(lastCultivatingToolUuid);

            // Only diff counters from the same physical tool. Swapping between two tools used to diff one
            // tool's counter against the other's, and any gap under 50k was booked as crops.
            if (sameTool && lastCultivatingValue != -1L && newValue > lastCultivatingValue) {
                long delta = newValue - lastCultivatingValue;
                if (delta <= MAX_CULTIVATING_DELTA && currentFarmedCrop != null) {
                    recordCrops(delta, dropRecorder);
                } else if (delta > MAX_CULTIVATING_DELTA) {
                    ClientUtils.sendDebugMessage("Dismissed large cultivating change: +" + delta);
                }
            }
            lastCultivatingValue = newValue;
            lastCultivatingToolUuid = toolUuid;
        } else {
            lastCultivatingValue = -1L;
            lastCultivatingToolUuid = null;
        }

        if (MacroStateManager.getCurrentState() == MacroState.State.FARMING) {
            if (ticksSinceNonFarming < PURSE_SETTLE_TICKS) {
                ticksSinceNonFarming++;
            }
        } else {
            ticksSinceNonFarming = 0;
        }

        if (client.player.tickCount % PURSE_SAMPLE_INTERVAL_TICKS == 0) {
            long currentPurse = ClientUtils.getPurse();
            if (currentPurse != -1L) {
                boolean purseCountable = ticksSinceNonFarming >= PURSE_SETTLE_TICKS;
                if (purseCountable && lastPurseBalance != -1L && currentPurse > lastPurseBalance) {
                    long delta = currentPurse - lastPurseBalance;
                    if (delta <= MAX_PURSE_DELTA) {
                        dropRecorder.accept("Purse", delta);
                    } else {
                        ClientUtils.sendDebugMessage("Dismissed large purse change: +" + delta);
                    }
                }
                lastPurseBalance = currentPurse;
            }
        }

        if (client.player.tickCount % PET_XP_SAMPLE_INTERVAL_TICKS == 0 && PetXpTracker.hasTrackedPetsConfigured()) {
            PetXpTracker.update();
        }

        if (client.player.tickCount % PET_XP_SAMPLE_INTERVAL_TICKS == 0) {
            SkillXpTracker.updateAllFromTablist(client);
        }

        refreshPrices.run();
    }

    private void recordCrops(long delta, BiConsumer<String, Long> dropRecorder) {
        if (currentFarmedCrop.equalsIgnoreCase("Wheat") || currentFarmedCrop.equalsIgnoreCase("Seeds")) {
            long wheatDelta = Math.round(delta / 2.5);
            long seedsDelta = delta - wheatDelta;
            if (wheatDelta > 0) {
                dropRecorder.accept("Wheat", wheatDelta);
            }
            if (seedsDelta > 0) {
                dropRecorder.accept("Seeds", seedsDelta);
            }
        } else {
            dropRecorder.accept(currentFarmedCrop, delta);
        }
    }

    private String detectCropFromInventory(Minecraft client, boolean mushroomsOnly) {
        Map<String, Long> currentCounts = new LinkedHashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = stack.getHoverName().getString().replaceAll("\u00A7[0-9a-fk-or]", "").trim();
            if (BASE_CROPS.contains(name) && (!mushroomsOnly || MUSHROOMS.contains(name))) {
                currentCounts.put(name, currentCounts.getOrDefault(name, 0L) + stack.getCount());
            }
        }

        String detectedCrop = null;
        long maxIncrease = 0L;
        for (Map.Entry<String, Long> entry : currentCounts.entrySet()) {
            long previous = prevInventoryCounts.getOrDefault(entry.getKey(), 0L);
            long diff = entry.getValue() - previous;
            if (diff > maxIncrease) {
                maxIncrease = diff;
                detectedCrop = entry.getKey();
            }
        }
        prevInventoryCounts.clear();
        prevInventoryCounts.putAll(currentCounts);
        return detectedCrop;
    }
}
