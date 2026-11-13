package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.ComposterManager
import dev.aether.modules.SupercraftManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.inventorymanager.BookCombineManager
import dev.aether.modules.inventorymanager.GeorgeManager
import dev.aether.modules.pest.helpers.GardenTimeManager
import dev.aether.modules.pest.helpers.PestExchangeManager
import dev.aether.modules.pest.helpers.PestTrapManager
import dev.aether.modules.visitor.VisitorsMacro
import dev.aether.notification.NotificationManager
import dev.aether.util.BazaarUtils
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.util.Locale

internal object UnexpectedInventoryFailsafe {
    enum class State { IDLE, WAIT, TRIGGERED }
    @Volatile private var inventoryOpenSince = 0L
    @Volatile private var inventoryOpenRandomDelayMs = 0L
    @Volatile private var triggered = false

    fun reset() { inventoryOpenSince = 0L; inventoryOpenRandomDelayMs = 0L; triggered = false }
    fun tick(client: Minecraft?) {
        if (client?.player == null) { reset(); return }
        if (!AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get()) { inventoryOpenSince = 0L; inventoryOpenRandomDelayMs = 0L; triggered = false; return }
        val state = MacroStateManager.getCurrentState()
        if (state != MacroState.State.FARMING && state != MacroState.State.CLEANING) { inventoryOpenSince = 0L; inventoryOpenRandomDelayMs = 0L; triggered = false; return }
        if (!ClientUtils.isInventoryScreenOpen()) { inventoryOpenSince = 0L; inventoryOpenRandomDelayMs = 0L; return }
        if (isExpectedInventoryGuiOpen()) { inventoryOpenSince = 0L; inventoryOpenRandomDelayMs = 0L; triggered = false; return }
        val now = System.currentTimeMillis()
        if (inventoryOpenSince == 0L) { inventoryOpenSince = now; inventoryOpenRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs(); return }
        val delay = Math.round(AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.get() * 1000.0f).toLong() + inventoryOpenRandomDelayMs
        if (now - inventoryOpenSince >= delay) trigger(client, state)
    }
    fun getState(client: Minecraft?): State {
        if (triggered) return State.TRIGGERED
        if (client?.player == null || !AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get()) return State.IDLE
        val state = MacroStateManager.getCurrentState()
        if ((state != MacroState.State.FARMING && state != MacroState.State.CLEANING) || !ClientUtils.isInventoryScreenOpen() || isExpectedInventoryGuiOpen()) return State.IDLE
        return if (inventoryOpenSince == 0L) State.IDLE else State.WAIT
    }
    fun getTriggerRemainingMs(): Long {
        if (inventoryOpenSince == 0L) return 0L
        val delay = Math.round(AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.get() * 1000.0f).toLong() + inventoryOpenRandomDelayMs
        return maxOf(0L, delay - (System.currentTimeMillis() - inventoryOpenSince))
    }
    private fun trigger(client: Minecraft, state: MacroState.State) {
        if (triggered) return
        val action = FailsafeManager.getUnexpectedInventoryGuiAction(); triggered = true
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), "Unexpected inventory GUI detected.")
        FailsafeManager.handleConfiguredAction(client, action, FailsafeCustomReplayManager.FailsafeReplayType.GUI_OPENED,
            "unexpected inventory GUI detected during ${state.name.lowercase(Locale.ROOT)}.",
            "UnexpectedInventoryFailsafe: unexpected inventory GUI detected during ${state.name}")
        reset()
    }
    private fun isExpectedInventoryGuiOpen() = LoadoutManager.isSwappingLoadout || PestExchangeManager.isExchanging() || PestTrapManager.isRunning() || ComposterManager.isRunning() || SupercraftManager.isRunning() || BookCombineManager.isPreparingToCombine || BookCombineManager.isCombining || GardenTimeManager.isSwitchingGardenTime() || isPestTrapGuiOpen() || VisitorsMacro.isRunning || GeorgeManager.isPreparingToSell || GeorgeManager.isSelling || AutoSellManager.isPreparingToSell || AutoSellManager.isSelling || BazaarUtils.isSellingBazaar || BazaarUtils.isBuying
    private fun isPestTrapGuiOpen(): Boolean {
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return false
        return screen.title.string.lowercase(Locale.ROOT).contains("trap")
    }
}
