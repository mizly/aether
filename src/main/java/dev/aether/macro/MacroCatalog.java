package dev.aether.macro;

import dev.aether.bootstrap.AetherKeybindHandler;
import dev.aether.macro.fishing.FishingMacroKind;
import dev.aether.modules.failsafe.FailsafeColourFlashManager;
import net.minecraft.client.Minecraft;

import java.util.List;

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
                    () -> AetherKeybindHandler.startStriderFishingMacro(Minecraft.getInstance())),
            new Entry("fishing_macro", "Fishing Macro",
                    "Fishes the lava or water next to you, or a hotspot, and fights what it catches", "Fishing", "Fishing Macro",
                    () -> AetherKeybindHandler.startFishingMacro(Minecraft.getInstance(), FishingMacroKind.GENERAL,
                            true)));

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
        entry.start().run();
    }
}
