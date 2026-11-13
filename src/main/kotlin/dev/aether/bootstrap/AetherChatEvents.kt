package dev.aether.bootstrap

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.farming.BedrockPlotMaker
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.metaldetector.MetalDetectorSolver
import dev.aether.modules.misc.AutoCarnivalManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.modules.pest.helpers.AutoSprayonatorManager
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.profit.ProfitManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.session.RestartManager
import dev.aether.modules.session.RecoveryManager
import dev.aether.modules.visitor.VisitorManager
import dev.aether.util.BazaarUtils
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import java.util.regex.Pattern

class AetherChatEvents private constructor() {
    companion object {
        private val PLOT_PATTERN = Pattern.compile("Plot\\s*[\\-#:]\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
        private val SPAWN_COUNT_PATTERN = Pattern.compile("(?i)yuck!\\s*(\\d+)\\D+pest\\s+(?:have|has)\\s+spawned")
        private val BAZAAR_BUY_PATTERN = Pattern.compile("\\[Bazaar\\]\\s*Bought\\s+(\\d+)x\\s+.+?for\\s+([\\d,]+)\\s+coins", Pattern.CASE_INSENSITIVE)
        private var isHandlingMessage = false

        @JvmStatic
        fun register() {
            ClientReceiveMessageEvents.GAME.register { message, overlay ->
                val text = message.string
                val plainText = text.replace(Regex("(?i)[§&][0-9a-fk-or]"), "")
                val lowerText = text.lowercase()
                val lowerPlainText = plainText.lowercase()
                MetalDetectorSolver.onGameMessage(Minecraft.getInstance(), plainText, overlay)
                if (overlay) PestDestroyer.onLassoOverlayMessage(plainText)
                if (plainText.contains("Teleported you to The Barn!")) dev.aether.modules.visitor.VisitorsMacro.hasBarnTeleportMessage = true
                if (plainText.contains("Executing instant sell...")) BazaarUtils.detectedInstantSell = true
                if (plainText.contains("You don't have anything to sell!")) BazaarUtils.detectedNoItemsToSell = true
                if (!overlay) BazaarUtils.onBuyChatMessage(plainText)
                if (overlay || isHandlingMessage) return@register
                LoadoutManager.onChatMessage(plainText)
                CommandUtils.onChatMessage(plainText)
                try {
                    isHandlingMessage = true
                    BedrockPlotMaker.onChatMessage(plainText)
                    ProfitManager.handleChatMessage(message)
                    AutoCarnivalManager.handleChatMessage(Minecraft.getInstance(), message, plainText)
                    handleVisitorAcceptance(plainText)
                    handlePestDestroyerNoPests(lowerPlainText)
                    handlePestCaught(lowerPlainText)
                    handleAutoSellNpcCoinLimit(plainText)
                    if (handleProxyRestartMessage(text, plainText) || handleRestartMessage(text, plainText)) return@register
                    if (PestManager.isCleaningInProgress() || AutoPestExchangeManager.isRunning()) {
                        PestManager.handlePhillipMessage(Minecraft.getInstance(), text)
                        AutoSprayonatorManager.onChatMessage(plainText)
                        return@register
                    }
                    if (handleDisconnectMessage(text)) return@register
                    handleSprayBazaarBuy(plainText)
                    handleYuckDebug(lowerText, plainText)
                    handlePestChatTrigger(lowerText, plainText)
                    handleStashState(lowerText)
                    AutoSprayonatorManager.onChatMessage(plainText)
                    dev.aether.modules.CropFeverManager.handleChatMessage(Minecraft.getInstance(), plainText)
                } finally { isHandlingMessage = false }
            }
        }

        private fun handleVisitorAcceptance(text: String) { VisitorManager.extractAcceptedVisitorName(text)?.let { VisitorManager.onOfferAccepted(it) } }
        private fun handlePestCaught(text: String) {
            if (isPestCatchMessage(text)) { dev.aether.modules.visuals.PestDefeatEffects.onCatchChat(); PestDestroyer.onPestCaught() }
            PestDestroyer.onLassoEscapeMessage(text)
        }
        @JvmStatic fun isPestCatchMessage(text: String?): Boolean = text != null && text.contains("shard") && (text.contains("you caught ") || text.contains("you charmed ") || text.contains("captured its shard") || text.contains("captured a shard"))
        private fun handlePestDestroyerNoPests(text: String) {
            if (!text.contains("there are not any pests on your garden right now! keep farming!")) return
            if (PestDestroyer.isActive()) { ClientUtils.sendDebugMessage("[PestDestroyer] Detected 'No Pests' message. Finishing destroyer."); PestDestroyer.clearRememberedLeaveOnePlots(); PestDestroyer.finish(Minecraft.getInstance()) }
        }
        private fun handleAutoSellNpcCoinLimit(text: String) { if (text.contains("You've reached the daily limit of coins you may earn from NPC shops.")) AutoSellManager.handleNpcCoinLimit(Minecraft.getInstance()) }
        private fun handleProxyRestartMessage(text: String, plain: String): Boolean {
            if (!containsValidTrigger(text, "This proxy is restarting soon.") || plain.contains("[Aether]")) return false
            RestartManager.handleProxyRestartMessage(Minecraft.getInstance()); return true
        }
        private fun handleRestartMessage(text: String, plain: String): Boolean {
            val matches = containsValidTrigger(text, "[Important] This server will restart soon: Scheduled Reboot") || containsValidTrigger(text, "[Important] This server will restart soon: Game Update") || containsValidTrigger(text, "Evacuating to Hub...") || containsValidTrigger(text, "SERVER REBOOT!")
            if (!matches || plain.contains("[Aether]")) return false
            RestartManager.handleRestartMessage(Minecraft.getInstance(), containsValidTrigger(text, "Evacuating to Hub...") || containsValidTrigger(text, "SERVER REBOOT!")); return true
        }
        private fun handleDisconnectMessage(text: String): Boolean {
            if (!containsValidTrigger(text, "You were spawned in Limbo.") && !containsValidTrigger(text, "A disconnect occurred in your connection, so you were put in the SkyBlock Lobby!")) return false
            if (MacroStateManager.getCurrentState() != MacroState.State.OFF && MacroStateManager.getCurrentState() != MacroState.State.RECOVERING) { ClientUtils.sendMessage("Disconnect detected! Starting recovery sequence..."); RecoveryManager.beginLimboRecovery() }
            return true
        }
        private fun containsValidTrigger(text: String, trigger: String): Boolean { val index = text.indexOf(trigger); return index >= 0 && text.lastIndexOf(':', index - 1) < 0 }
        private fun handleSprayBazaarBuy(text: String) {
            if (!ProfitManager.isSprayPhaseActive || !containsValidTrigger(text, "[Bazaar]") || !text.contains("Bought")) return
            val matcher = BAZAAR_BUY_PATTERN.matcher(text); if (!matcher.find()) return
            try { val qty = matcher.group(1).toInt(); val coins = matcher.group(2).replace(",", "").toLong(); ProfitManager.addSprayCost(qty, coins); ClientUtils.sendDebugMessage("Spray buy detected: $qty items for $coins coins") } catch (_: NumberFormatException) {}
        }
        private fun handleYuckDebug(lower: String, plain: String) { if (lower.contains("yuck")) ClientUtils.sendDebugMessage("Diagnose: Seen YUCK in chat. Text: $plain") }
        private fun handlePestChatTrigger(lower: String, plain: String) {
            if (!PestManager.isPestDestroyerEnabled() || !lower.contains("yuck") || !lower.contains("plot")) return
            ClientUtils.sendDebugMessage("YUCK detected. State: ${MacroStateManager.getCurrentState()}")
            if ((!lower.contains("spawned") && !lower.contains("phillip")) || MacroStateManager.getCurrentState() != MacroState.State.FARMING) return
            val plotMatcher = PLOT_PATTERN.matcher(plain)
            if (!plotMatcher.find()) { ClientUtils.sendDebugMessage("Chat pest trigger failed regex on: $plain"); return }
            val plot = plotMatcher.group(1)
            if (lower.contains("spawned")) PestDestroyer.onPestsSpawnedInPlot(plot)
            var spawnedCount = 0
            val spawnMatcher = SPAWN_COUNT_PATTERN.matcher(plain)
            if (spawnMatcher.find()) try { spawnedCount = spawnMatcher.group(1).toInt() } catch (_: NumberFormatException) { spawnedCount = 0 }
            val triggerDelay = ConfigHelpers.getRandomizedDelay(AetherConfig.PEST_CHAT_TRIGGER_DELAY_MIN.get(), AetherConfig.PEST_CHAT_TRIGGER_DELAY_MAX.get())
            val ballsackTriggerDelay = ConfigHelpers.getRandomizedDelay(AetherConfig.BALLSACK_SHREDDER_TRIGGER_DELAY_MIN.get(), AetherConfig.BALLSACK_SHREDDER_TRIGGER_DELAY_MAX.get())
            PestManager.scheduleChatCleaningTrigger(plot, spawnedCount, triggerDelay.toLong(), ballsackTriggerDelay.toLong(), lower.contains("spawned"))
        }
        private fun handleStashState(text: String) {
            if (text.contains("stashed away!")) AetherTickHandlers.setPickingUpStash(true)
            if (text.contains("your stash isn't holding any items or materials!") || text.contains("couldn't unstash your")) AetherTickHandlers.setPickingUpStash(false)
        }
    }
}
