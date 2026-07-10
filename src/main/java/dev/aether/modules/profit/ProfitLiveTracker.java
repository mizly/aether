package dev.aether.modules.profit;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.profit.helpers.FarmingXpTracker;
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

    private final Map<String, Long> prevInventoryCounts = new LinkedHashMap<>();
    private final Runnable refreshPrices;

    private long lastCultivatingValue = -1L;
    private String currentFarmedCrop = "Wheat";
    private long lastPurseBalance = -1L;
    private boolean trackingLiveMetrics = false;

    ProfitLiveTracker(Runnable refreshPrices) {
        this.refreshPrices = refreshPrices;
    }

    void resetSessionState() {
        prevInventoryCounts.clear();
        lastCultivatingValue = -1L;
        lastPurseBalance = -1L;
        currentFarmedCrop = "Wheat";
        PetXpTracker.reset();
        FarmingXpTracker.reset();
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

        String detectedCrop = null;
        long maxIncrease = 0L;

        Map<String, Long> currentCounts = new LinkedHashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            String name = stack.getHoverName().getString().replaceAll("\u00A7[0-9a-fk-or]", "").trim();
            if (BASE_CROPS.contains(name)) {
                currentCounts.put(name, currentCounts.getOrDefault(name, 0L) + stack.getCount());
            }
        }

        for (Map.Entry<String, Long> entry : currentCounts.entrySet()) {
            long previous = prevInventoryCounts.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() > previous) {
                long diff = entry.getValue() - previous;
                if (diff > maxIncrease) {
                    maxIncrease = diff;
                    detectedCrop = entry.getKey();
                }
            }
        }
        prevInventoryCounts.clear();
        prevInventoryCounts.putAll(currentCounts);

        if (detectedCrop != null) {
            currentFarmedCrop = detectedCrop;
        }

        ItemStack held = client.player.getMainHandItem();
        if (held != null && !held.isEmpty()) {
            long newValue = -1L;
            CustomData custom = held.get(DataComponents.CUSTOM_DATA);
            if (custom != null) {
                net.minecraft.nbt.CompoundTag tag = custom.copyTag();
                if (tag.contains("farmed_cultivating")) {
                    newValue = tag.getLong("farmed_cultivating").get();
                }
            }

            if (newValue != -1L) {
                if (lastCultivatingValue != -1L && newValue > lastCultivatingValue) {
                    long delta = newValue - lastCultivatingValue;
                    if (delta <= MAX_CULTIVATING_DELTA && currentFarmedCrop != null) {
                        if (currentFarmedCrop.equalsIgnoreCase("Wheat")
                                || currentFarmedCrop.equalsIgnoreCase("Seeds")) {
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
                    } else if (delta > MAX_CULTIVATING_DELTA && AetherConfig.SHOW_DEBUG.get()) {
                        ClientUtils.sendDebugMessage("Dismissed large cultivating change: +" + delta);
                    }
                }
                lastCultivatingValue = newValue;
            } else {
                lastCultivatingValue = -1L;
            }
        } else {
            lastCultivatingValue = -1L;
        }

        if (client.player.tickCount % PURSE_SAMPLE_INTERVAL_TICKS == 0) {
            long currentPurse = ClientUtils.getPurse();
            if (currentPurse != -1L) {
                if (lastPurseBalance != -1L && currentPurse > lastPurseBalance
                        && MacroStateManager.getCurrentState() != MacroState.State.AUTOSELLING) {
                    long delta = currentPurse - lastPurseBalance;
                    if (delta <= 50000L) {
                        dropRecorder.accept("Purse", delta);
                    } else if (AetherConfig.SHOW_DEBUG.get()) {
                        ClientUtils.sendDebugMessage("Dismissed large purse change: +" + delta);
                    }
                }
                lastPurseBalance = currentPurse;
            }
        }

        if (client.player.tickCount % PET_XP_SAMPLE_INTERVAL_TICKS == 0 && PetXpTracker.hasTrackedPetsConfigured()) {
            PetXpTracker.update();
        }

        FarmingXpTracker.tick();
        if (client.player.tickCount % PET_XP_SAMPLE_INTERVAL_TICKS == 0) {
            FarmingXpTracker.updateFromTablist(client);
        }

        refreshPrices.run();
    }
}
