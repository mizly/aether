package dev.typicalfarmingmacro.bootstrap;

import dev.typicalfarmingmacro.config.TfmConfig;
import dev.typicalfarmingmacro.config.ConfigHelpers;
import dev.typicalfarmingmacro.macro.FarmingMacroManager;
import dev.typicalfarmingmacro.macro.MacroState;
import dev.typicalfarmingmacro.macro.MacroStateManager;
import dev.typicalfarmingmacro.bootstrap.TfmBootstrapHooks;
import dev.typicalfarmingmacro.modules.CropFeverManager;
import dev.typicalfarmingmacro.modules.discord.DiscordStatusManager;
import dev.typicalfarmingmacro.modules.gear.GearManager;
import dev.typicalfarmingmacro.modules.inventorymanager.AutoSellManager;
import dev.typicalfarmingmacro.modules.inventorymanager.BookCombineManager;
import dev.typicalfarmingmacro.modules.inventorymanager.BoosterCookieManager;
import dev.typicalfarmingmacro.modules.inventorymanager.GeorgeManager;
import dev.typicalfarmingmacro.modules.metaldetector.MetalDetectorSolver;
import dev.typicalfarmingmacro.modules.misc.AutoCarnivalManager;
import dev.typicalfarmingmacro.modules.inventorymanager.JunkManager;
import dev.typicalfarmingmacro.modules.pathfinding.PathfindingManager;
import dev.typicalfarmingmacro.modules.pathfinding.rotation.RotationExecutor;
import dev.typicalfarmingmacro.modules.pest.DynamicPestsManager;
import dev.typicalfarmingmacro.modules.pest.PestManager;
import dev.typicalfarmingmacro.modules.pest.helpers.AutoPestExchangeManager;
import dev.typicalfarmingmacro.modules.pest.helpers.AutoSprayonatorManager;
import dev.typicalfarmingmacro.modules.pest.helpers.PestAotvManager;
import dev.typicalfarmingmacro.modules.pest.helpers.PestBonusManager;
import dev.typicalfarmingmacro.modules.pest.helpers.PestDestroyer;
import dev.typicalfarmingmacro.modules.pest.helpers.PestReturnManager;
import dev.typicalfarmingmacro.modules.pest.helpers.VacuumParticleDebug;
import dev.typicalfarmingmacro.modules.profit.ProfitManager;
import dev.typicalfarmingmacro.modules.rewarp.RewarpManager;
import dev.typicalfarmingmacro.modules.rotation.RotationManager;
import dev.typicalfarmingmacro.modules.session.DynamicRestManager;
import dev.typicalfarmingmacro.modules.session.RecoveryManager;
import dev.typicalfarmingmacro.modules.session.RestartManager;
import dev.typicalfarmingmacro.modules.SupercraftManager;
import dev.typicalfarmingmacro.ui.theme.Theme;
import dev.typicalfarmingmacro.util.TfmResources;
import dev.typicalfarmingmacro.util.BpsTracker;
import dev.typicalfarmingmacro.util.ClientUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

public final class TfmAutomationTickHandler {
    private static boolean isPickingUpStash = false;
    private static long lastStashPickupTime = 0;

    private TfmAutomationTickHandler() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
                        if (client.player == null) {
                return;
            }

            if ((client.screen instanceof PauseScreen
                    || client.screen instanceof ChatScreen
                    || TfmBootstrapHooks.isBootstrapConfigScreen(client.screen))
                    && MacroStateManager.isMacroRunning()) {
                MacroStateManager.stopMacro(client);
            }

            handleContainerMenus(client);
            tickManagers(client);
            handleSneakForAotv(client);
            handleFlightStop(client);

            if (MacroStateManager.getCurrentState() == MacroState.State.RECOVERING
                    || RecoveryManager.isWorldChangeRecoveryActive()) {
                RecoveryManager.update(client);
                return;
            }

            handleStashPickup(client);
            RewarpManager.handle(client);
        });
    }

    public static void setPickingUpStash(boolean pickingUpStash) {
        isPickingUpStash = pickingUpStash;
    }

    private static void handleContainerMenus(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> currentScreen)) {
            return;
        }

        GearManager.handleLoadoutMenu(client, currentScreen);
        if (client.screen == currentScreen) {
            GeorgeManager.handleGeorgeMenu(client, currentScreen);
        }
        if (client.screen == currentScreen) {
            AutoSellManager.handleMenu(client, currentScreen);
        }
        if (client.screen == currentScreen) {
            BoosterCookieManager.handleBoosterCookieMenu(client, currentScreen);
        }
        if (client.screen == currentScreen) {
            BookCombineManager.handleAnvilMenu(client, currentScreen);
        }
        if (client.screen == currentScreen) {
            JunkManager.handleInventoryMenu(client, currentScreen);
        }
        if (client.screen == currentScreen) {
            MetalDetectorSolver.handleContainerMenu(client, currentScreen);
        }
        if (client.screen == currentScreen) {
            SupercraftManager.handleRecipeGui(client, currentScreen);
        }
    }

    private static void tickManagers(Minecraft client) {
        GeorgeManager.update(client);
        AutoSellManager.update(client);
        BookCombineManager.update(client);
        JunkManager.update(client);

        DynamicRestManager.update(client);
        SupercraftManager.update(client);
        PestBonusManager.updateFromTab(client);
        AutoPestExchangeManager.update(client);
        PestManager.update(client);
        CropFeverManager.update(client);
        AutoSprayonatorManager.update(client);
        DynamicPestsManager.update(client);
        TfmBootstrapHooks.tickFailsafes(client);
        GearManager.cleanupTick(client);
        RotationManager.update(client);
        RotationExecutor.update(client);
        if (MacroStateManager.getCurrentState() == MacroState.State.FARMING) {
            FarmingMacroManager.tick(client);
        }
        MacroStateManager.periodicUpdate();
        ProfitManager.update(client);
        BpsTracker.tick();
        DiscordStatusManager.update(client);
        Theme.tickRainbow();
        PathfindingManager.update(client);
        RestartManager.update(client);
        AutoCarnivalManager.update(client);

        PestDestroyer.update(client);
        MetalDetectorSolver.update(client);
        VacuumParticleDebug.onClientTick(client);
    }

    private static void handleSneakForAotv(Minecraft client) {
        if (PestAotvManager.isSneakingForAotv && client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyShift, true);
        }
    }

    private static void handleFlightStop(Minecraft client) {
        if (!PestReturnManager.isStoppingFlight) {
            return;
        }

        PestReturnManager.flightStopTicks++;
        switch (PestReturnManager.flightStopStage) {
            case 0:
                if (client.options.keyJump != null) {
                    ClientUtils.setKeyMappingState(client.options.keyJump, true);
                }
                if (PestReturnManager.flightStopTicks >= 2) {
                    PestReturnManager.flightStopStage = 1;
                    PestReturnManager.flightStopTicks = 0;
                }
                break;
            case 1:
                if (client.options.keyJump != null) {
                    ClientUtils.setKeyMappingState(client.options.keyJump, false);
                }
                if (PestReturnManager.flightStopTicks >= 3) {
                    PestReturnManager.flightStopStage = 2;
                    PestReturnManager.flightStopTicks = 0;
                }
                break;
            case 2:
                if (client.options.keyJump != null) {
                    ClientUtils.setKeyMappingState(client.options.keyJump, true);
                }
                if (PestReturnManager.flightStopTicks >= 2) {
                    PestReturnManager.flightStopStage = 3;
                    PestReturnManager.flightStopTicks = 0;
                }
                break;
            case 3:
                if (client.options.keyJump != null) {
                    ClientUtils.setKeyMappingState(client.options.keyJump, false);
                }
                PestReturnManager.isStoppingFlight = false;
                break;
            default:
                break;
        }
    }

    private static void handleStashPickup(Minecraft client) {
        if (!TfmConfig.AUTO_STASH_MANAGER.get() || !isPickingUpStash || client.player == null) {
            return;
        }

        MacroState.State stashState = MacroStateManager.getCurrentState();
        if (client.screen != null
                || stashState == MacroState.State.VISITING
                || stashState == MacroState.State.CLEANING
                || stashState == MacroState.State.SPRAYING) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastStashPickupTime >= ConfigHelpers.getRandomizedDelay(
                TfmConfig.PICK_UP_STASH_DELAY_MIN.get(),
                TfmConfig.PICK_UP_STASH_DELAY_MAX.get())) {
            lastStashPickupTime = now;
            ClientUtils.sendCommand(client, "/pickupstash");
        }
    }

}

