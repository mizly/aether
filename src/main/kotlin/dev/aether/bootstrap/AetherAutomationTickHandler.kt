package dev.aether.bootstrap

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.CropFeverManager
import dev.aether.modules.discord.DiscordStatusManager
import dev.aether.modules.farming.BedrockPlotMaker
import dev.aether.modules.gear.GearManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.inventorymanager.BookCombineManager
import dev.aether.modules.inventorymanager.BoosterCookieManager
import dev.aether.modules.inventorymanager.GeorgeManager
import dev.aether.modules.inventorymanager.JunkManager
import dev.aether.modules.metaldetector.MetalDetectorSolver
import dev.aether.modules.misc.AutoCarnivalManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pathfinding.rotation.RotationExecutor
import dev.aether.modules.pest.DynamicPestsManager
import dev.aether.modules.pest.ManualPestManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.modules.pest.helpers.AutoSprayonatorManager
import dev.aether.modules.pest.helpers.PestAotvManager
import dev.aether.modules.pest.helpers.PestBonusManager
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.pest.helpers.PestOnTheTrackManager
import dev.aether.modules.pest.helpers.PestReturnManager
import dev.aether.modules.pest.helpers.PestTrackerAbility
import dev.aether.modules.pest.helpers.VacuumParticleDebug
import dev.aether.modules.profit.ProfitManager
import dev.aether.modules.rewarp.RewarpManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.modules.session.DynamicRestManager
import dev.aether.modules.session.RecoveryManager
import dev.aether.modules.session.RestartManager
import dev.aether.modules.SupercraftManager
import dev.aether.modules.TablistSetupManager
import dev.aether.ui.theme.Theme
import dev.aether.util.BpsTracker
import dev.aether.util.ClientUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

class AetherAutomationTickHandler private constructor() {
    companion object {
        private var isPickingUpStash = false
        private var lastStashPickupTime = 0L

        @JvmStatic
        fun register() {
            ClientTickEvents.END_CLIENT_TICK.register { client ->
                PestTrackerAbility.tick(client)
                if (client.player == null) return@register
                val automationStopScreen = client.gui.screen() is PauseScreen || client.gui.screen() is ChatScreen || AetherBootstrapHooks.isBootstrapConfigScreen(client.gui.screen())
                if (automationStopScreen) {
                    if (MacroStateManager.isAutomationRunning() && !ManualPestManager.isActive()) MacroStateManager.stopMacro(client, "Automation interrupted by screen", false)
                    if (BedrockPlotMaker.isRunning()) BedrockPlotMaker.stop(client)
                }
                handleContainerMenus(client)
                tickManagers(client)
                handleSneakForAotv(client)
                handleFlightStop(client)
                if (MacroStateManager.getCurrentState() == MacroState.State.RECOVERING || RecoveryManager.isWorldChangeRecoveryActive()) {
                    RecoveryManager.update()
                    return@register
                }
                handleStashPickup(client)
                RewarpManager.handle(client)
            }
        }

        @JvmStatic fun setPickingUpStash(pickingUpStash: Boolean) { isPickingUpStash = pickingUpStash }

        private fun handleContainerMenus(client: Minecraft) {
            val currentScreen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
            GearManager.handleLoadoutMenu(client, currentScreen)
            if (client.gui.screen() == currentScreen) GeorgeManager.handleGeorgeMenu(client, currentScreen)
            if (client.gui.screen() == currentScreen) AutoSellManager.handleMenu(client, currentScreen)
            if (client.gui.screen() == currentScreen) BoosterCookieManager.handleBoosterCookieMenu(client, currentScreen)
            if (client.gui.screen() == currentScreen) BookCombineManager.handleAnvilMenu(client, currentScreen)
            if (client.gui.screen() == currentScreen) JunkManager.handleInventoryMenu(client, currentScreen)
            if (client.gui.screen() == currentScreen) MetalDetectorSolver.handleContainerMenu(client, currentScreen)
            if (client.gui.screen() == currentScreen) SupercraftManager.handleRecipeGui(currentScreen)
        }

        private fun tickManagers(client: Minecraft) {
            GeorgeManager.update()
            AutoSellManager.update()
            BookCombineManager.update()
            JunkManager.update()

            DynamicRestManager.update()
            SupercraftManager.update()
            PestBonusManager.updateFromTab()
            AutoPestExchangeManager.update()
            PestManager.update()
            ManualPestManager.update()
            CropFeverManager.update()
            AutoSprayonatorManager.update()
            DynamicPestsManager.update()
            AetherBootstrapHooks.tickFailsafes(client)
            GearManager.cleanupTick()
            RotationManager.update()
            RotationExecutor.update()
            BedrockPlotMaker.update(client)
            PestOnTheTrackManager.getInstance().update(client)
            if (MacroStateManager.getCurrentState() == MacroState.State.FARMING && !PestOnTheTrackManager.getInstance().isBlockingFarming()) {
                FarmingMacroManager.tick(client)
            }
            MacroStateManager.periodicUpdate()
            ProfitManager.update()
            BpsTracker.tick()
            DiscordStatusManager.update()
            Theme.tickRainbow()
            PathfindingManager.update()
            RestartManager.update()
            AutoCarnivalManager.update()
            TablistSetupManager.update(client)

            PestAotvManager.updatePreparationAotv(client)
            PestDestroyer.update()
            MetalDetectorSolver.update()
            VacuumParticleDebug.onClientTick()
        }

        private fun handleSneakForAotv(client: Minecraft) {
            if (PestAotvManager.isSneakingForAotv() && client.options != null) {
                ClientUtils.setKeyMappingState(client.options.keyShift, true)
            }
        }

        private fun handleFlightStop(client: Minecraft) {
            PestReturnManager.updateFlightStop(client)
        }

        private fun handleStashPickup(client: Minecraft) {
            if (!AetherConfig.AUTO_STASH_MANAGER.get() || !isPickingUpStash || client.player == null) {
                return
            }
            val stashState = MacroStateManager.getCurrentState()
            if (client.gui.screen() != null || stashState == MacroState.State.VISITING || stashState == MacroState.State.CLEANING || stashState == MacroState.State.SPRAYING) {
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastStashPickupTime >= ConfigHelpers.getRandomizedDelay(AetherConfig.PICK_UP_STASH_DELAY_MIN.get(), AetherConfig.PICK_UP_STASH_DELAY_MAX.get())) {
                lastStashPickupTime = now
                ClientUtils.sendCommand("/pickupstash")
            }
        }
    }
}
