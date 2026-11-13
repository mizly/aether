package dev.aether.bootstrap

import dev.aether.config.AetherConfig
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.CropFeverManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.inventorymanager.BookCombineManager
import dev.aether.modules.inventorymanager.GeorgeManager
import dev.aether.modules.inventorymanager.JunkManager
import dev.aether.modules.pest.ManualPestManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.profit.ProfitManager
import dev.aether.modules.session.DynamicRestManager
import dev.aether.modules.session.RecoveryManager
import dev.aether.modules.visuals.PipManager
import dev.aether.modules.visuals.UngrabMouseManager
import dev.aether.util.ClientUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

class AetherKeybindHandler private constructor() {
    companion object {
        private var tickHandlerRegistered = false

        @JvmStatic fun register() {
            AetherKeybindRegistry.register()
            if (tickHandlerRegistered) return
            tickHandlerRegistered = true
            ClientTickEvents.START_CLIENT_TICK.register { client ->
                while (AetherKeybindRegistry.getClickGuiKey().consumeClick()) AetherUiActions.toggleMainGui()
                if (client.player == null) return@register
                while (AetherKeybindRegistry.getMacroToggleKey().consumeClick()) handleMacroToggle(client)
                while (AetherKeybindRegistry.getPipKey().consumeClick()) PipManager.toggle()
                while (AetherKeybindRegistry.getUngrabMouseKey().consumeClick()) UngrabMouseManager.toggle()
                while (AetherKeybindRegistry.getManualPestEarlyFinishKey().consumeClick()) ManualPestManager.requestEarlyFinish(client)
            }
        }

        @JvmStatic
        fun getRegisteredKeybinds(): List<RegisteredKeybind> = AetherKeybindRegistry.getRegisteredKeybinds().stream()
            .map { RegisteredKeybind(it.name, it.description, it.mapping) }
            .toList()
        @JvmStatic fun getFreecamKey(): KeyMapping = AetherKeybindRegistry.getFreecamKey()
        @JvmStatic fun getFreecamTeleportToPlayerKey(): KeyMapping = AetherKeybindRegistry.getFreecamTeleportToPlayerKey()
        @JvmStatic fun getPipKey(): KeyMapping = AetherKeybindRegistry.getPipKey()
        @JvmStatic fun getUngrabMouseKey(): KeyMapping = AetherKeybindRegistry.getUngrabMouseKey()

        private fun handleMacroToggle(client: Minecraft) {
            if (!MacroStateManager.isAutomationRunning()) {
                startFarmingMacro(client, false)
                return
            }
            if (!AetherConfig.PERSIST_SESSION_TIMER.get()) DynamicRestManager.reset()
            MacroStateManager.stopMacro()
        }

        @JvmStatic fun startFarmingMacro(client: Minecraft) { startFarmingMacro(client, true) }

        @JvmStatic
        fun startFarmingMacro(client: Minecraft?, announce: Boolean) {
            if (client == null) return
            PestManager.reset(); CropFeverManager.reset(); AetherBootstrapHooks.resetFailsafeRuntimeState(); GearManager.reset(); GeorgeManager.reset(); AutoSellManager.reset(); BookCombineManager.reset(); JunkManager.reset(); RecoveryManager.reset()
            SqueakyMousematManager.armReapplyAttempt()
            MacroStateManager.setCurrentState(MacroState.State.FARMING)
            ProfitManager.startStartupPriceFetch()
            ProfitManager.printPetXpPriceDebug()
            DynamicRestManager.scheduleNextRest()
            client.execute { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) }
            if (announce) ClientUtils.sendMessage("\u00A7aFarming macro started.", false)
        }
    }

    class RegisteredKeybind(val name: String, val description: String, val mapping: KeyMapping) {
        override fun equals(other: Any?): Boolean = other is RegisteredKeybind && name == other.name && description == other.description && mapping == other.mapping
        override fun hashCode(): Int = java.util.Objects.hash(name, description, mapping)
        override fun toString(): String = "RegisteredKeybind[name=$name, description=$description, mapping=$mapping]"
    }
}
