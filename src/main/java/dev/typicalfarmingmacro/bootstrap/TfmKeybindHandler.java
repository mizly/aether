package dev.typicalfarmingmacro.bootstrap;

import dev.typicalfarmingmacro.config.TfmConfig;
import dev.typicalfarmingmacro.bootstrap.TfmBootstrapHooks;
import dev.typicalfarmingmacro.bootstrap.TfmUiActions;
import dev.typicalfarmingmacro.macro.FarmingMacroManager;
import dev.typicalfarmingmacro.macro.MacroState;
import dev.typicalfarmingmacro.macro.MacroStateManager;
import dev.typicalfarmingmacro.modules.CropFeverManager;
import dev.typicalfarmingmacro.modules.farming.SqueakyMousematManager;
import dev.typicalfarmingmacro.modules.gear.GearManager;
import dev.typicalfarmingmacro.modules.inventorymanager.AutoSellManager;
import dev.typicalfarmingmacro.modules.inventorymanager.BookCombineManager;
import dev.typicalfarmingmacro.modules.inventorymanager.GeorgeManager;
import dev.typicalfarmingmacro.modules.inventorymanager.JunkManager;
import dev.typicalfarmingmacro.modules.pest.PestManager;
import dev.typicalfarmingmacro.modules.profit.ProfitManager;
import dev.typicalfarmingmacro.modules.session.DynamicRestManager;
import dev.typicalfarmingmacro.modules.session.RecoveryManager;
import dev.typicalfarmingmacro.modules.visuals.PipManager;
import dev.typicalfarmingmacro.modules.visuals.UngrabMouseManager;
import dev.typicalfarmingmacro.util.TfmResources;
import dev.typicalfarmingmacro.util.ClientUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class TfmKeybindHandler {
    private static boolean tickHandlerRegistered;

    private TfmKeybindHandler() {
    }

    public static void register() {
        TfmKeybindRegistry.register();
        if (tickHandlerRegistered) {
            return;
        }
        tickHandlerRegistered = true;
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
                        while (TfmKeybindRegistry.getClickGuiKey().consumeClick()) {
                TfmUiActions.toggleMainGui(client);
            }

            if (client.player == null) {
                return;
            }

            while (TfmKeybindRegistry.getMacroToggleKey().consumeClick()) {
                handleMacroToggle(client);
            }

            // Freecam + its teleport bind are polled directly from the physical key state in
            // FreecamManager (like Freelook), so they are not consumed here.

            while (TfmKeybindRegistry.getPipKey().consumeClick()) {
                PipManager.toggle(client);
            }

            while (TfmKeybindRegistry.getUngrabMouseKey().consumeClick()) {
                UngrabMouseManager.toggle(client);
            }
        });
    }

    public static List<RegisteredKeybind> getRegisteredKeybinds() {
        return TfmKeybindRegistry.getRegisteredKeybinds().stream()
                .map(registeredKeybind -> new RegisteredKeybind(
                        registeredKeybind.name(),
                        registeredKeybind.description(),
                        registeredKeybind.mapping()))
                .toList();
    }

    public static KeyMapping getFreecamKey() {
        return TfmKeybindRegistry.getFreecamKey();
    }

    public static KeyMapping getFreecamTeleportToPlayerKey() {
        return TfmKeybindRegistry.getFreecamTeleportToPlayerKey();
    }

    public static KeyMapping getPipKey() {
        return TfmKeybindRegistry.getPipKey();
    }

    public static KeyMapping getUngrabMouseKey() {
        return TfmKeybindRegistry.getUngrabMouseKey();
    }

    private static void handleMacroToggle(Minecraft client) {
        if (MacroStateManager.getCurrentState() == MacroState.State.OFF) {
            startFarmingMacro(client, false);
            return;
        }

        if (!TfmConfig.PERSIST_SESSION_TIMER.get()) {
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
        TfmBootstrapHooks.resetFailsafeRuntimeState();
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

