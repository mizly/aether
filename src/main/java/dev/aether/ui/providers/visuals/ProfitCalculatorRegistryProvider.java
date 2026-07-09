package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.config.PetInfo;
import dev.aether.config.PetRarity;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.modules.profit.ProfitPriceSource;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.ArrayList;
import java.util.List;

public final class ProfitCalculatorRegistryProvider extends AbstractVisualsRegistryProvider {
    private static final List<SettingGroup> PROFIT_GROUPS = new ArrayList<>();
    private static final List<SettingGroup> PET_TRACKER_GROUPS = new ArrayList<>();

    public ProfitCalculatorRegistryProvider() {
        super(0);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        return MainGUIRegistry.toggleSubTab(
                "Profit Tracker",
                "Tracks and displays farming earnings",
                () -> AetherConfig.PROFIT_HUD_ENABLED.get(),
                v -> {
                    AetherConfig.PROFIT_HUD_ENABLED.set(v);
                    AetherConfig.save();
                },
                buildGroups());
    }

    private static List<SettingGroup> buildGroups() {
        List<SettingGroup> profitGroups = PROFIT_GROUPS;
        profitGroups.clear();

        profitGroups.add(SettingGroup.of(
                        "Profit HUD",
                        "Displays session earnings",
                        () -> AetherConfig.PROFIT_HUD_ENABLED.get(),
                        v -> {
                            AetherConfig.PROFIT_HUD_ENABLED.set(v);
                            AetherConfig.save();
                        })
                .add(new ToggleSetting("Session Profit HUD",
                        () -> AetherConfig.SHOW_SESSION_PROFIT_HUD.get(),
                        v -> {
                            AetherConfig.SHOW_SESSION_PROFIT_HUD.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Lifetime HUD",
                        () -> AetherConfig.SHOW_LIFETIME_HUD.get(),
                        v -> {
                            AetherConfig.SHOW_LIFETIME_HUD.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Daily HUD",
                        () -> AetherConfig.SHOW_DAILY_HUD.get(),
                        v -> {
                            AetherConfig.SHOW_DAILY_HUD.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Compact Profit Calculator",
                        () -> AetherConfig.COMPACT_PROFIT_CALCULATOR.get(),
                        v -> {
                            AetherConfig.COMPACT_PROFIT_CALCULATOR.set(v);
                            AetherConfig.save();
                        }))
                .add(new DropdownSetting("Price Source",
                        java.util.Arrays.stream(ProfitPriceSource.values()).map(ProfitPriceSource::getLabel).toList(),
                        () -> ProfitPriceSource.fromConfig(AetherConfig.PROFIT_PRICE_SOURCE.get()).ordinal(),
                        i -> {
                            ProfitPriceSource[] values = ProfitPriceSource.values();
                            if (i < 0 || i >= values.length) {
                                return;
                            }
                            AetherConfig.PROFIT_PRICE_SOURCE.set(values[i].name());
                            AetherConfig.save();
                            ProfitManager.handlePriceSourceChanged();
                        }))
                .add(new ActionSetting("Reset Session",
                        dev.aether.macro.MacroStateManager::resetSession))
                .add(new ActionSetting("Reset Lifetime Profit", ProfitManager::resetLifetime)));

        profitGroups.add(SettingGroup.alwaysOn(
                        "Farming XP",
                        "Farming XP stats shown on the Session Profit HUD")
                .add(new ToggleSetting("Show Farming XP",
                        () -> AetherConfig.FARMING_XP_HUD.get(),
                        v -> {
                            AetherConfig.FARMING_XP_HUD.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Farming XP / hr",
                        () -> AetherConfig.FARMING_HUD_XP_RATE.get(),
                        v -> {
                            AetherConfig.FARMING_HUD_XP_RATE.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.FARMING_XP_HUD.get()))
                .add(new ToggleSetting("Time to Next Level",
                        () -> AetherConfig.FARMING_HUD_ETA_NEXT.get(),
                        v -> {
                            AetherConfig.FARMING_HUD_ETA_NEXT.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.FARMING_XP_HUD.get()))
                .add(new ToggleSetting("Time to Farming 60",
                        () -> AetherConfig.FARMING_HUD_ETA_MAX.get(),
                        v -> {
                            AetherConfig.FARMING_HUD_ETA_MAX.set(v);
                            AetherConfig.save();
                        })
                        .visibleWhen(() -> AetherConfig.FARMING_XP_HUD.get())));

        rebuildPetTrackerGroups();
        return profitGroups;
    }

    private static String defaultPetTrackerEntry() {
        return "Rose Dragon:200:650000000:1250000000:LEGENDARY";
    }

    private static PetInfo getPetTrackerInfo(int index) {
        List<String> entries = AetherConfig.PET_TRACKER_LIST.get();
        if (entries.isEmpty()) return new PetInfo(defaultPetTrackerEntry());
        int safeIndex = Math.max(0, Math.min(index, entries.size() - 1));
        return new PetInfo(entries.get(safeIndex));
    }

    private static void updatePetTrackerInfo(int index, java.util.function.Consumer<PetInfo> updater) {
        List<String> entries = new ArrayList<>(AetherConfig.PET_TRACKER_LIST.get());
        while (entries.size() <= index) entries.add(defaultPetTrackerEntry());
        PetInfo info = new PetInfo(entries.get(index));
        updater.accept(info);
        entries.set(index, info.toString());
        AetherConfig.PET_TRACKER_LIST.set(entries);
        AetherConfig.save();
        ProfitManager.reloadConfiguredPetXpPrices();
    }

    private static void addPetTrackerEntry() {
        List<String> entries = new ArrayList<>(AetherConfig.PET_TRACKER_LIST.get());
        entries.add(defaultPetTrackerEntry());
        AetherConfig.PET_TRACKER_LIST.set(entries);
        AetherConfig.save();
        ProfitManager.reloadConfiguredPetXpPrices();
        rebuildPetTrackerGroups();
    }

    private static void removePetTrackerEntry(int index) {
        List<String> entries = new ArrayList<>(AetherConfig.PET_TRACKER_LIST.get());
        if (entries.size() <= 1 || index < 0 || index >= entries.size()) return;
        entries.remove(index);
        AetherConfig.PET_TRACKER_LIST.set(entries);
        AetherConfig.save();
        ProfitManager.reloadConfiguredPetXpPrices();
        rebuildPetTrackerGroups();
    }

    private static SettingGroup buildPetTrackerGroup(int petIndex, List<String> petLevelOptions,
                                                     List<String> petRarityOptions) {
        String groupName = petIndex == 0 ? "Pet XP Tracker" : "Pet XP Tracker " + (petIndex + 1);
        SettingGroup petXpTracker = SettingGroup.alwaysOn(
                groupName,
                "Configure user-defined Pet XP pricing");
        petXpTracker.add(new ActionSetting("Remove Pet", () -> removePetTrackerEntry(petIndex))
                .visibleWhen(() -> false));
        petXpTracker.add(new TextSetting("Pet Name", "e.g. Rose Dragon",
                () -> getPetTrackerInfo(petIndex).name,
                v -> updatePetTrackerInfo(petIndex, info -> info.name = v.trim())));
        petXpTracker.add(new DropdownSetting("Max Level", petLevelOptions,
                () -> getPetTrackerInfo(petIndex).maxLevel >= 200 ? 1 : 0,
                idx -> updatePetTrackerInfo(petIndex,
                        info -> info.maxLevel = idx == 1 ? 200 : 100)));
        petXpTracker.add(new TextSetting("Level 1 Price", "e.g. 650000000",
                () -> String.valueOf(getPetTrackerInfo(petIndex).level1Price),
                v -> updatePetTrackerInfo(petIndex, info -> {
                    try {
                        info.level1Price = Long.parseLong(v.replaceAll("[^\\d]", ""));
                    } catch (Exception ignored) {
                    }
                })));
        petXpTracker.add(new TextSetting("Max Level Price", "e.g. 1250000000",
                () -> String.valueOf(getPetTrackerInfo(petIndex).maxLevelPrice),
                v -> updatePetTrackerInfo(petIndex, info -> {
                    try {
                        info.maxLevelPrice = Long.parseLong(v.replaceAll("[^\\d]", ""));
                    } catch (Exception ignored) {
                    }
                })));
        petXpTracker.add(new DropdownSetting("Rarity", petRarityOptions,
                () -> getPetTrackerInfo(petIndex).rarity.ordinal(),
                idx -> updatePetTrackerInfo(petIndex,
                        info -> info.rarity = PetRarity.values()[idx])));
        petXpTracker.add(new ActionSetting("Add Pet", ProfitCalculatorRegistryProvider::addPetTrackerEntry)
                .visibleWhen(() -> false));
        return petXpTracker;
    }

    private static void rebuildPetTrackerGroups() {
        PROFIT_GROUPS.removeAll(PET_TRACKER_GROUPS);
        PET_TRACKER_GROUPS.clear();

        List<String> petLevelOptions = List.of("100", "200");
        List<String> petRarityOptions = java.util.stream.Stream.of(PetRarity.values())
                .map(Enum::name)
                .toList();
        int petTrackerCount = Math.max(1, AetherConfig.PET_TRACKER_LIST.get().size());
        for (int i = 0; i < petTrackerCount; i++) {
            SettingGroup group = buildPetTrackerGroup(i, petLevelOptions, petRarityOptions);
            PET_TRACKER_GROUPS.add(group);
        }
        PROFIT_GROUPS.addAll(PET_TRACKER_GROUPS);
    }
}
