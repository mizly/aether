package dev.aether.modules.visitor

import dev.aether.config.AetherConfig
import dev.aether.util.TablistUtils
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.util.CommandUtils
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import dev.aether.modules.gear.GearManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.PestPrepSwapManager
import dev.aether.modules.pest.helpers.PestLifecycleManager
import dev.aether.modules.pest.helpers.PestReturnManager
import dev.aether.modules.profit.ProfitManager

class VisitorManager private constructor() {
    class VisitorOffer {
        @JvmField var visitorName: String? = null
        @JvmField var totalCost: Long = 0
    }
    companion object {
        private val VISITORS_PATTERN = Pattern.compile("Visitors:\\s*\\(?(\\d+)\\)?")
        private val OFFER_ACCEPTED_PATTERN = Pattern.compile("^OFFER ACCEPTED with (.+?) \\(([A-Z]+(?: [A-Z]+)*)\\)$")
        private val REQUIREMENT_PATTERN = Pattern.compile("(.+?)\\s+x(\\d+)$")
        private const val VISITOR_REENTRY_COOLDOWN_MS = 60_000L
        private var pendingOffer: VisitorOffer? = null
        @Volatile private var visitorReentryCooldownUntilMs = 0L

        @JvmStatic fun getVisitorCount(client: Minecraft): Int {
            if (!AetherConfig.AUTO_VISITOR.get() || client.level == null) return 0
            if (!client.isSameThread) {
                val future = CompletableFuture<Int>()
                client.execute { future.complete(getVisitorCount(client)) }
                return try { future.get(1, TimeUnit.SECONDS) } catch (_: Exception) { 0 }
            }
            try {
                for (line in TablistUtils.getRawTabLines(client)) {
                    val m = VISITORS_PATTERN.matcher(line)
                    if (m.find()) return Integer.parseInt(m.group(1))
                }
            } catch (e: Exception) { e.printStackTrace() }
            return 0
        }

        @JvmStatic fun handleVisitorScriptFinished(client: Minecraft) {
            startVisitorReentryCooldown(client)
            ClientUtils.sendMessage("\u00A7aVisitor sequence complete. Returning to farm...", true)
            MacroWorkerThread.getInstance().submit("VisitorFinished-ReturnToFarm") {
                try {
                    if (MacroWorkerThread.shouldAbortTask(client)) return@submit
                    ClientUtils.sendDebugMessage("Warping to garden...")
                    CommandUtils.warpGarden()
                    if (!AetherConfig.SUNSET_PESTS_NIGHT_BEFORE_SPAWN.get()) PestLifecycleManager.restorePendingSunsetPestsNight(client)
                    MacroWorkerThread.sleep(1000)
                    VisitorsMacro.reenableCompactorsIfPending(client)
                    PestReturnManager.setReturningFromPestVisitor(true)
                    if (MacroWorkerThread.shouldAbortTask(client)) return@submit
                    ClientUtils.sendDebugMessage("Finalizing return to farm...")
                    finalizeReturnToFarm(client)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        @JvmStatic fun finalizeReturnToFarm(client: Minecraft) {
            if (MacroWorkerThread.shouldAbortTask(client)) return
            ClientUtils.sendDebugMessage("finalizeReturnToFarm triggered. State: " + MacroStateManager.getCurrentState())
            if (MacroStateManager.getCurrentState() == MacroState.State.OFF) return
            val visitors = getVisitorCount(client)
            if (visitors >= AetherConfig.VISITOR_THRESHOLD.get() && shouldSkipVisitorsDuringJacobsContest(client, true)) {
                ClientUtils.sendDebugMessage("Visitor threshold met, but Jacob's Contest window is active. Continuing farming.")
            } else if (visitors >= AetherConfig.VISITOR_THRESHOLD.get() && !isVisitorReentryCooldownActive(client, true)) {
                ClientUtils.sendMessage("\u00A7eVisitor threshold met (" + visitors + "). Redirecting to Visitors...", true)
                client.execute { VisitorsMacro.start(client) }
                return
            }
            if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()) ClientUtils.sendDebugMessage("Visitor threshold met, but re-entry cooldown is active. Continuing farming.")
            client.execute { GearManager.swapToFarmingTool(client) }
            MacroWorkerThread.sleep(250)
            if (AetherConfig.AUTO_LOADOUT_ENABLED.get() && AetherConfig.LOADOUT_SLOT_FARMING.get() > 0 && LoadoutManager.trackedLoadoutSlot != AetherConfig.LOADOUT_SLOT_FARMING.get()) {
                ClientUtils.sendMessage("\u00A7eRestoring farming loadout (slot " + AetherConfig.LOADOUT_SLOT_FARMING.get() + ")...", true)
                GearManager.ensureLoadoutSlot(client, AetherConfig.LOADOUT_SLOT_FARMING.get())
                if (LoadoutManager.isSwappingLoadout) {
                    ClientUtils.sendDebugMessage("finalizeReturnToFarm: Waiting for loadout GUI...")
                    ClientUtils.waitForWardrobeGui()
                    ClientUtils.sendDebugMessage("finalizeReturnToFarm: Loadout GUI detected, waiting for swap to complete...")
                    while (LoadoutManager.isSwappingLoadout) MacroWorkerThread.sleep(50)
                    while (LoadoutManager.loadoutCleanupTicks > 0) MacroWorkerThread.sleep(50)
                    MacroWorkerThread.sleep(350)
                    ClientUtils.sendDebugMessage("finalizeReturnToFarm: Loadout swap fully complete.")
                }
            }
            ClientUtils.waitForGearAndGui()
            ClientUtils.waitForGearAndGui()
            PestReturnManager.setReturningFromPestVisitor(false)
            PestReturnManager.setReturnToLocationActive(false)
            restartFarmingAfterVisitors(client)
            PestPrepSwapManager.clearCycleState()
            PestManager.setCleaningInProgress(false)
        }

        private fun restartFarmingAfterVisitors(client: Minecraft) {
            ClientUtils.sendMessage("\u00A7aRestarting farming...", true)
            ClientUtils.sendDebugMessage("Restarting farming macro after visitor sequence.")
            client.execute {
                if (client.player == null) return@execute
                FarmingMacroManager.disable(client)
                MacroStateManager.setCurrentState(MacroState.State.FARMING)
                GearManager.swapToFarmingTool(client)
                FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig())
            }
        }

        @Suppress("rawtypes")
        @JvmStatic fun scanVisitorGui(client: Minecraft, screen: AbstractContainerScreen<*>) {
            if (!MacroStateManager.isMacroRunning() || client.player == null) return
            val title = screen.title.string.trim()
            val slotIndex = 29
            if (screen.menu.slots.size <= slotIndex) return
            val slot = screen.menu.getSlot(slotIndex)
            if (slot == null || !slot.hasItem()) return
            val stack = slot.item
            val name = stack.hoverName.string
            if (!name.contains("Accept Offer")) return
            val offer = VisitorOffer()
            offer.visitorName = title
            val costBreakdown = StringBuilder("\u00A7d[Aether] \u00A77Costs: ")
            val loreCmp = stack.get(DataComponents.LORE)
            if (loreCmp == null) { ClientUtils.sendMessage("\u00A7cNo lore found on the Accept Offer button!", false); return }
            var parsingRequirements = false
            for (line in loreCmp.lines()) {
                val text = line.string.replace(Regex("\u00A7[0-9a-fk-or]"), "").trim()
                if (text.isEmpty()) continue
                if (text.contains("Items Required:")) { parsingRequirements = true; continue }
                if (text.contains("Rewards:")) { parsingRequirements = false; continue }
                if (parsingRequirements && !text.contains("Farming XP") && !text.contains("Garden Experience")) {
                    parseRequirement(client, text, offer, stack, costBreakdown)
                }
            }
            if (offer.totalCost > 0) pendingOffer = offer
        }

        private fun parseRequirement(client: Minecraft, text: String, offer: VisitorOffer, stack: ItemStack, breakdown: StringBuilder) {
            val m = REQUIREMENT_PATTERN.matcher(text)
            val itemName: String
            var count = 1L
            if (m.find()) { itemName = m.group(1).trim(); count = java.lang.Long.parseLong(m.group(2)) } else itemName = text.trim()
            val id = resolveId(itemName, stack)
            val price = ProfitManager.getItemPrice(if (id != null) id else itemName)
            if (price > 0) {
                val total = (price * count).toLong()
                offer.totalCost += total
                breakdown.append("\u00A7e").append(count).append("x ").append(itemName).append(" \u00A77(\u00A7c").append(total).append("\u00A77), ")
            } else {
                breakdown.append("\u00A7c?x ").append(itemName).append(" (price unknown), ")
                System.out.println("[Aether] Unknown Visitor Cost Item: " + itemName + " (ID: " + id + ")")
            }
        }

        @Suppress("unchecked")
        private fun resolveId(name: String, scannerStack: ItemStack): String? {
            try {
                val customData = scannerStack.get(DataComponents.CUSTOM_DATA)
                if (customData != null) {
                    val tag = customData.copyTag()
                    if (tag.contains("ExtraAttributes")) {
                        val eaOpt = tag.getCompound("ExtraAttributes")
                        if (eaOpt.isPresent) {
                            val ea = eaOpt.get()
                            if (ea.contains("id")) {
                                val nbtId = ea.getString("id").orElse("")
                                if (nbtId.isNotEmpty()) return nbtId
                            }
                        }
                    }
                }
            } catch (e: Exception) { System.err.println("[Aether] NBT lookup failed for '" + name + "': " + e.message) }
            return ProfitManager.fetchIdByName(name)
        }

        @JvmStatic fun onOfferAccepted(visitorName: String) {
            val offer = pendingOffer ?: return
            val pendingName = offer.visitorName ?: return
            val cleanPending = normalizeVisitorName(pendingName)
            val cleanAccepted = normalizeVisitorName(visitorName)
            if (cleanAccepted == cleanPending) {
                ProfitManager.addVisitorCost(offer.totalCost)
                pendingOffer = null
            }
        }

        @JvmStatic fun extractAcceptedVisitorName(plainText: String?): String? {
            if (plainText == null) return null
            val matcher = OFFER_ACCEPTED_PATTERN.matcher(plainText.trim())
            if (!matcher.matches()) return null
            return matcher.group(1).trim()
        }

        @JvmStatic fun clearPendingOffer() { pendingOffer = null }

        private fun normalizeVisitorName(name: String): String {
            var normalized = TablistUtils.stripColors(name).trim()
            normalized = normalized.replace(Regex("\\s*\\((COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL)\\)\\s*$"), "")
            return normalized.trim().lowercase()
        }

        @JvmStatic fun startVisitorReentryCooldown(client: Minecraft) {
            visitorReentryCooldownUntilMs = System.currentTimeMillis() + VISITOR_REENTRY_COOLDOWN_MS
            ClientUtils.sendDebugMessage("Visitor re-entry cooldown started (1 minute).")
        }

        @JvmStatic fun getVisitorReentryCooldownRemainingMs(): Long = Math.max(0L, visitorReentryCooldownUntilMs - System.currentTimeMillis())

        @JvmStatic fun isVisitorReentryCooldownActive(client: Minecraft, showMessage: Boolean): Boolean {
            val remainingMs = visitorReentryCooldownUntilMs - System.currentTimeMillis()
            if (remainingMs <= 0) return false
            val remainingSeconds = (remainingMs + 999L) / 1000L
            ClientUtils.sendDebugMessage("Visitor re-entry cooldown active (" + remainingSeconds + "s remaining). Skipping visitor macro.")
            if (showMessage && client.player != null) ClientUtils.sendMessage("\u00A7eVisitor cooldown active (" + remainingSeconds + "s). Staying on farm.", true)
            return true
        }

        @JvmStatic fun shouldSkipVisitorsDuringJacobsContest(client: Minecraft, showMessage: Boolean): Boolean {
            if (!AetherConfig.DISABLE_VISITORS_DURING_JACOBS_CONTEST.get()) return false
            if (ClientUtils.getJacobsContestRemainingMs() <= 0) return false
            ClientUtils.sendDebugMessage("Jacob's Contest visitor skip window active (:15-:35). Skipping visitors.")
            if (showMessage && client.player != null) ClientUtils.sendMessage("\u00A7eJacob's Contest window active (:15-:35). Skipping visitors.", true)
            return true
        }
    }
}
