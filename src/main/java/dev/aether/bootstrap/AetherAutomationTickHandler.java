package dev.aether.bootstrap;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.modules.CropFeverManager;
import dev.aether.modules.discord.DiscordStatusManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.inventorymanager.AutoSellManager;
import dev.aether.modules.inventorymanager.BookCombineManager;
import dev.aether.modules.inventorymanager.BoosterCookieManager;
import dev.aether.modules.inventorymanager.GeorgeManager;
import dev.aether.modules.metaldetector.MetalDetectorSolver;
import dev.aether.modules.misc.AutoCarnivalManager;
import dev.aether.modules.inventorymanager.JunkManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pathfinding.rotation.RotationExecutor;
import dev.aether.modules.pest.DynamicPestsManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.pest.helpers.AutoSprayonatorManager;
import dev.aether.modules.pest.helpers.PestAotvManager;
import dev.aether.modules.pest.helpers.PestBonusManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.PestReturnManager;
import dev.aether.modules.pest.helpers.VacuumParticleDebug;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.modules.rewarp.RewarpManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.modules.session.RecoveryManager;
import dev.aether.modules.session.RestartManager;
import dev.aether.modules.SupercraftManager;
import dev.aether.ui.theme.Theme;
import dev.aether.util.AetherResources;
import dev.aether.util.BpsTracker;
import dev.aether.util.ClientUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

public final class AetherAutomationTickHandler {
    private static boolean isPickingUpStash = false;
    private static long lastStashPickupTime = 0;

    private AetherAutomationTickHandler() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
                        if (client.player == null) {
                return;
            }

            if ((client.screen instanceof PauseScreen
                    || client.screen instanceof ChatScreen
                    || AetherBootstrapHooks.isBootstrapConfigScreen(client.screen))
                    && MacroStateManager.isMacroRunning()) {
                MacroStateManager.stopMacro(client);
            }

            handleContainerMenus(client);
            tickManagers(client);
            handleSneakForAotv(client);
            handleFlightStop(client);

            if (MacroStateManager.getCurrentState() == MacroState.State.RECOVERING
                    || RecoveryManager.isWorldChangeRecoveryActive()) {
                RecoveryManager.update();
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
            SupercraftManager.handleRecipeGui(currentScreen);
        }
    }

    private static void tickManagers(Minecraft client) {
        GeorgeManager.update();
        AutoSellManager.update();
        BookCombineManager.update();
        JunkManager.update();

        DynamicRestManager.update();
        SupercraftManager.update();
        PestBonusManager.updateFromTab();
        AutoPestExchangeManager.update();
        PestManager.update();
        CropFeverManager.update();
        AutoSprayonatorManager.update();
        DynamicPestsManager.update();
        AetherBootstrapHooks.tickFailsafes(client);
        GearManager.cleanupTick();
        RotationManager.update();
        RotationExecutor.update();
        if (MacroStateManager.getCurrentState() == MacroState.State.FARMING) {
            FarmingMacroManager.tick(client);
        }
        MacroStateManager.periodicUpdate();
        ProfitManager.update();
        BpsTracker.tick();
        DiscordStatusManager.update();
        Theme.tickRainbow();
        PathfindingManager.update();
        RestartManager.update();
        AutoCarnivalManager.update();

        PestDestroyer.update();
        MetalDetectorSolver.update();
        VacuumParticleDebug.onClientTick();
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
        if (!AetherConfig.AUTO_STASH_MANAGER.get() || !isPickingUpStash || client.player == null) {
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
                AetherConfig.PICK_UP_STASH_DELAY_MIN.get(),
                AetherConfig.PICK_UP_STASH_DELAY_MAX.get())) {
            lastStashPickupTime = now;
            ClientUtils.sendCommand(client, "/pickupstash");
        }
    }

}
