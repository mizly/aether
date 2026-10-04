package dev.aether.macro;

import dev.aether.bootstrap.AetherKeybindHandler;
import dev.aether.modules.failsafe.FailsafeColourFlashManager;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Optional;

// every macro the start menu can run, grouped by the type it is listed under
public final class MacroCatalog {

    public record Entry(String id, String displayName, String description, String type,
                        String settingsModule, Runnable start) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("farming", "Farming Macro", "Automatically farms crops", "Farming", "Farming Macro",
                    () -> AetherKeybindHandler.startFarmingMacro(Minecraft.getInstance())),
            new Entry("strider_fishing", "Strider Fishing",
                    "Fishes Stridersurfers out of lava and kills them", "Fishing", "Strider Fishing",
                    () -> AetherKeybindHandler.startStriderFishingMacro(Minecraft.getInstance())));

    private static volatile Entry lastStarted;

    private MacroCatalog() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static List<String> types() {
        return ENTRIES.stream().map(Entry::type).distinct().toList();
    }

    public static long countOfType(String type) {
        return ENTRIES.stream().filter(entry -> entry.type().equals(type)).count();
    }

    // opening the menu is the stop: nothing keeps running underneath it, so every card only ever starts
    public static void stopForMenu(Minecraft client) {
        FailsafeColourFlashManager.dismiss();
        if (MacroStateManager.isAutomationRunning()) {
            MacroStateManager.stopMacro(client, "Macro stopped by opening the macro menu", false);
        }
    }

    public static void start(Entry entry) {
        // the open menu is an automation stop screen, so it has to go before the macro starts
        Minecraft.getInstance().setScreen(null);
        recordStarted(entry.id());
        entry.start().run();
    }

    // remembered so the gui can offer Resume after opening it stopped the macro
    public static void recordStarted(String id) {
        ENTRIES.stream().filter(entry -> entry.id().equals(id)).findFirst().ifPresent(entry -> lastStarted = entry);
    }

    public static Optional<Entry> lastStarted() {
        return Optional.ofNullable(lastStarted);
    }
}
