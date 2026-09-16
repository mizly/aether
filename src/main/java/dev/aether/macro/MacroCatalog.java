package dev.aether.macro;

import dev.aether.bootstrap.AetherKeybindHandler;
import dev.aether.modules.failsafe.FailsafeColourFlashManager;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.function.BooleanSupplier;

// every macro the start menu can run, grouped by the type it is listed under
public final class MacroCatalog {

    public record Entry(String id, String displayName, String description, String type,
                        String settingsModule, BooleanSupplier running, Runnable start) {

        public boolean isRunning() {
            return running.getAsBoolean();
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("farming", "Farming Macro", "Automatically farms crops", "Farming", "Farming Macro",
                    () -> MacroStateManager.getCurrentState() == MacroState.State.FARMING,
                    () -> AetherKeybindHandler.startFarmingMacro(Minecraft.getInstance())),
            new Entry("strider_fishing", "Strider Fishing",
                    "Fishes Stridersurfers out of lava and kills them", "Fishing", "Strider Fishing",
                    () -> MacroStateManager.getCurrentState() == MacroState.State.FISHING,
                    () -> AetherKeybindHandler.startStriderFishingMacro(Minecraft.getInstance())));

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

    // a flashing failsafe counts as something to stop, so the same button clears it
    public static boolean isStoppable() {
        return MacroStateManager.isAutomationRunning() || FailsafeColourFlashManager.isActive();
    }

    public static void stopEverything() {
        FailsafeColourFlashManager.dismiss();
        MacroStateManager.stopMacro();
    }

    public static void toggle(Entry entry) {
        if (isStoppable()) {
            stopEverything();
            return;
        }
        // the open Aether menu is an automation stop screen, so it has to go before the macro starts
        Minecraft.getInstance().setScreen(null);
        entry.start().run();
    }
}
