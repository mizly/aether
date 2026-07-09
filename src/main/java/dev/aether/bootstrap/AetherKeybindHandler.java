package dev.aether.bootstrap;

import dev.aether.config.AetherConfig;
import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.bootstrap.AetherUiActions;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.CropFeverManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.inventorymanager.AutoSellManager;
import dev.aether.modules.inventorymanager.BookCombineManager;
import dev.aether.modules.inventorymanager.GeorgeManager;
import dev.aether.modules.inventorymanager.JunkManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.modules.session.RecoveryManager;
import dev.aether.modules.visuals.PipManager;
import dev.aether.modules.visuals.UngrabMouseManager;
import dev.aether.util.AetherResources;
import dev.aether.util.ClientUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class AetherKeybindHandler {
    private static boolean tickHandlerRegistered;

    private AetherKeybindHandler() {
    }

    public static void register() {
        AetherKeybindRegistry.register();
        if (tickHandlerRegistered) {
            return;
        }
        tickHandlerRegistered = true;
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
                        while (AetherKeybindRegistry.getClickGuiKey().consumeClick()) {
                AetherUiActions.toggleMainGui(client);
            }

            if (client.player == null) {
                return;
            }

            while (AetherKeybindRegistry.getMacroToggleKey().consumeClick()) {
                handleMacroToggle(client);
            }

            // Freecam + its teleport bind are polled directly from the physical key state in
            // FreecamManager (like Freelook), so they are not consumed here.

            while (AetherKeybindRegistry.getPipKey().consumeClick()) {
                PipManager.toggle(client);
            }

            while (AetherKeybindRegistry.getUngrabMouseKey().consumeClick()) {
                UngrabMouseManager.toggle(client);
            }
        });
    }

    public static List<RegisteredKeybind> getRegisteredKeybinds() {
        return AetherKeybindRegistry.getRegisteredKeybinds().stream()
                .map(registeredKeybind -> new RegisteredKeybind(
                        registeredKeybind.name(),
                        registeredKeybind.description(),
                        registeredKeybind.mapping()))
                .toList();
    }

    public static KeyMapping getFreecamKey() {
        return AetherKeybindRegistry.getFreecamKey();
    }

    public static KeyMapping getFreecamTeleportToPlayerKey() {
        return AetherKeybindRegistry.getFreecamTeleportToPlayerKey();
    }

    public static KeyMapping getPipKey() {
        return AetherKeybindRegistry.getPipKey();
    }

    public static KeyMapping getUngrabMouseKey() {
        return AetherKeybindRegistry.getUngrabMouseKey();
    }

    private static void handleMacroToggle(Minecraft client) {
        if (MacroStateManager.getCurrentState() == MacroState.State.OFF) {
            startFarmingMacro(client, false);
            return;
        }

        if (!AetherConfig.PERSIST_SESSION_TIMER.get()) {
            DynamicRestManager.reset();
        }
        MacroStateManager.stopMacro(client);
    }

    public static void startFarmingMacro(Minecraft client) {
        startFarmingMacro(client, true);
    }

    public static void startFarmingMacro(Minecraft client, boolean announce) {
        if (client == null) {
            return;
        }

        PestManager.reset();
        CropFeverManager.reset();
        AetherBootstrapHooks.resetFailsafeRuntimeState();
        GearManager.reset();
        GeorgeManager.reset();
        AutoSellManager.reset();
        BookCombineManager.reset();
        JunkManager.reset();
        RecoveryManager.reset();
        SqueakyMousematManager.armReapplyAttempt();
        MacroStateManager.setCurrentState(MacroState.State.FARMING);
        ProfitManager.startStartupPriceFetch();
        ProfitManager.printPetXpPriceDebug(client);
        DynamicRestManager.scheduleNextRest();
        client.execute(() -> FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()));
        if (announce) {
            ClientUtils.sendMessage(client, "\u00A7aFarming macro started.", false);
        }
    }

    public record RegisteredKeybind(String name, String description, KeyMapping mapping) {
    }

}

