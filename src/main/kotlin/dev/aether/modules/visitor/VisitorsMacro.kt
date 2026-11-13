package dev.aether.modules.visitor

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.profit.ProfitManager
import dev.aether.mixin.MixinMinecraft
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.BazaarUtils
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import dev.aether.util.EntityUtils
import dev.aether.util.TablistUtils
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.List
import java.util.Locale
import java.util.Set
import java.util.regex.Matcher
import java.util.regex.Pattern

// plottp barn, scan tab for visitor names, then per npc: walk close, interact, read the accept offer lore, buy the items off bazaar, and accept
class VisitorsMacro private constructor() {
    private class ItemRequirement(val name: String, val count: Int)
    private class VisitorOffer(val requirements: java.util.List<ItemRequirement>, val copper: Int, val hasRareReward: Boolean)
    companion object {
        private val TIP_JAR_PATTERN = Pattern.compile("Copper:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE)
        private const val TIP_JAR_MAX_RANGE = 5.0
        private const val TIP_JAR_FILL_THRESHOLD = 0.50
        private const val TIP_JAR_FALLBACK_THRESHOLD = 50
        @JvmField @Volatile var isRunning = false
        @Volatile private var shouldStop = false
        @Volatile private var workerActive = false
        @Volatile private var runGeneration = 0
        @JvmField @Volatile var hasBarnTeleportMessage = false
        @Volatile private var compactorsPendingReenable = false
        private var wasRunningBefore = false
        @Volatile private var currentVisitorRetrySneak = false
        private val ITEM_PATTERN = Pattern.compile("^(.+?)\\s+x([\\d,]+)$")
        private val COPPER_PATTERN = Pattern.compile("([\\d,]+)\\s+Copper", Pattern.CASE_INSENSITIVE)
        private val RARE_REWARDS = Set.of("Dedication", "Cultivating", "Delicate", "Replenish", "Music Rune", "Green Bandana", "Overgrown Grass", "Space Helmet", "Copper Dye")
        private const val ACCEPT_OFFER_SLOT = 29
        private const val REJECT_OFFER_SLOT = 33
        private const val COMPACTOR_GUI_TIMEOUT_MS = 2500L
        private const val COMPACTOR_POST_OPEN_DELAY_MS = 500L
        private const val COMPACTOR_SLOT_TIMEOUT_MS = 2500L
        private const val VISITOR_INTERACTION_RANGE = 2.5f
        private const val VISITOR_RETRY_RANGE = 2.0f
        private const val MAX_VISITOR_INTERACTION_RETRIES = 3
        @JvmStatic fun start(client: Minecraft) = start(client, false)
        @JvmStatic @Synchronized fun start(client: Minecraft, forceNoResume: Boolean) {
            if (isRunning) { msg(client, "\u00A7cVisitors macro is already running."); return }
            if (client.player == null || VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) return
            wasRunningBefore = !forceNoResume && MacroStateManager.isMacroRunning(); isRunning = true; shouldStop = false
            val generation = ++runGeneration; msg(client, "\u00A7eStarting visitors macro...")
            MacroWorkerThread.getInstance().submit("VisitorsMacro") {
                workerActive = true
                try {
                    if (generation != runGeneration || !isRunning || shouldStop) return@submit
                    client.execute { FarmingMacroManager.disable(client) }
                    if (wasRunningBefore) MacroStateManager.setCurrentState(MacroState.State.VISITING)
                    run(client)
                } catch (e: Exception) { msg(client, "\u00A7cVisitors macro error: " + e.message); e.printStackTrace() }
                finally { workerActive = false; if (generation == runGeneration) { isRunning = false; shouldStop = false } }
            }
        }
        @JvmStatic @Synchronized fun stop(client: Minecraft) {
            if (!isRunning) return
            shouldStop = true; PathfindingManager.stop(); msg(client, "\u00A7eStopping visitors macro...")
            if (!workerActive) { runGeneration++; isRunning = false; shouldStop = false; compactorsPendingReenable = false }
        }
        private fun run(client: Minecraft) {
            if (client.player == null) return

            var compactorsDisabled = false
            try {
                // Step 0: check auto sell after wardrobe per user request (regardless of threshold)
                AutoSellManager.checkBeforeVisitors(client, true, wasRunningBefore)
                if (shouldStop) return

                // Step 1: TP to barn
                closeScreen(client)
                MacroWorkerThread.sleep(300)
                msg(client, "\u00A7eTeleporting to barn...")

                val posBefore = client.player!!.position()
                hasBarnTeleportMessage = false
                FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toLong())
                val barnTeleportWindow = CommandUtils.beginChatWindow()
                ClientUtils.sendCommand("/plottp barn")
                MacroWorkerThread.sleep(600)

                if (!waitForTeleport(client, posBefore, barnTeleportWindow, 5000L)) {
                    msg(client, "\u00A7cTeleport to barn timed out. Aborting.")
                    return
                }
                if (shouldStop) return
                MacroWorkerThread.sleep(800)

                // Ensure a farming tool is equipped and wardrobe is correct
                if (!ensureVisitorLoadout(client)) {
                    msg(client, "\u00A7cFailed to equip the visitor item setup. Aborting.")
                    return
                }
                MacroWorkerThread.sleep(150)
                ClientUtils.waitForGearAndGui()

                if (AetherConfig.DISABLE_COMPACTORS_DURING_VISITORS.get()) {
                    compactorsDisabled = setCompactorsEnabled(client, false)
                    if (shouldStop) return
                }

                // still standing at the desk from the barn teleport, so do this before walking off
                emptyFungalTipJar(client)
                if (shouldStop) return

                // Step 2+: Re-scan the queue each round and process from the back of the line.
                var totalServed = 0
                var roundsWithoutProgress = 0
                var round = 1
                val servedVisitorsThisRun = HashSet<String>()
                val skippedVisitorsThisRun = HashSet<String>()

                while (!shouldStop) {
                    val queueNames = getVisitorNamesFromTab(client)
                    if (queueNames.isEmpty()) {
                        msg(client, "\u00A7aNo visitors left in queue. Done.")
                        break
                    }

                    val eligibleVisitors = filterignoredVisitors(client, queueNames)
                    if (eligibleVisitors.isEmpty()) {
                        msg(client, "\u00A7eQueue contains only ignored visitors. Done.")
                        break
                    }

                    // Filter out already-served and already-skipped visitors
                    eligibleVisitors.removeIf { v ->
                        servedVisitorsThisRun.contains(normalizeVisitorName(v)) ||
                            skippedVisitorsThisRun.contains(normalizeVisitorName(v))
                    }

                    if (eligibleVisitors.isEmpty()) {
                        msg(client, "\u00A7aNo new visitors to process. Done.")
                        break
                    }

                    // Last visitor in line first.
                    Collections.reverse(eligibleVisitors)
                    msg(client, "\u00A7eRound $round \u00A76processing \u00A7e${eligibleVisitors.size} \u00A76visitor(s) (back to front): \u00A77${java.lang.String.join(", ", eligibleVisitors)}")

                    var servedThisRound = 0
                    for (visitorName in eligibleVisitors) {
                        if (shouldStop) return
                        val normalizedName = normalizeVisitorName(visitorName)

                        val success = if (isRejectedVisitor(visitorName)) {
                            processRejectedVisitor(client, visitorName)
                        } else {
                            processVisitor(client, visitorName)
                        }
                        if (success) {
                            totalServed++
                            servedThisRound++
                            servedVisitorsThisRun.add(normalizedName)
                        } else {
                            ClientUtils.sendDebugMessage("[VisitorsMacro] Failed to process visitor: $visitorName")
                            skippedVisitorsThisRun.add(normalizedName)
                        }
                        MacroWorkerThread.sleep(ConfigHelpers.getRandomizedDelay(
                            AetherConfig.VISITOR_DELAY_MIN.get(),
                            AetherConfig.VISITOR_DELAY_MAX.get()
                        ).toLong())
                    }

                    if (servedThisRound == 0) {
                        roundsWithoutProgress++
                        ClientUtils.sendDebugMessage("[VisitorsMacro] No visitors were served this round ($roundsWithoutProgress/3). Rescanning queue...")
                        if (roundsWithoutProgress >= 3) {
                            msg(client, "\u00A7cStopping visitors macro: no progress after 3 rounds. Remaining visitors may be unreachable.")
                            break
                        }
                    } else {
                        roundsWithoutProgress = 0
                    }

                    round++
                    MacroWorkerThread.sleep(700)
                }

                // Step 4: Done
                msg(client, "\u00A7aVisitors macro complete. Served \u00A7e$totalServed \u00A7avisitor(s).")
                if (!shouldStop && wasRunningBefore) {
                    if (compactorsDisabled) compactorsPendingReenable = true
                    VisitorManager.handleVisitorScriptFinished(client)
                } else {
                    MacroStateManager.setCurrentState(MacroState.State.OFF)
                }
            } finally {
                if (compactorsDisabled) {
                    if (!shouldStop && wasRunningBefore) {
                        compactorsPendingReenable = true
                    } else {
                        setCompactorsEnabled(client, true)
                    }
                }
            }
        }

        private fun processVisitor(client: Minecraft, visitorName: String): Boolean {
            if (shouldStop) return false
            msg(client, "\u00A7eLooking for visitor: \u00A7e$visitorName")

            var visitor = findVisitorEntity(client, visitorName) ?: run {
                msg(client, "\u00A7cCould not find entity for: $visitorName")
                return false
            }

            visitor = prepareVisitorForInteractionWithRetries(client, visitorName, visitor) ?: return false

            // Right-click the visitor repeatedly until GUI opens
            if (!interactUntilGui(client, visitorName, visitor, 15000L)) {
                msg(client, "\u00A7cVisitor GUI did not open for: $visitorName")
                return false
            }
            MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true))

            // Read required items from "Accept Offer" lore
            val offer = readOffer(client) ?: run {
                msg(client, "\u00A7cCould not read requirements for: $visitorName")
                closeScreen(client)
                return false
            }
            val requirements = offer.requirements

            if (!shouldAcceptOffer(offer)) {
                clickRejectOffer(client)
                MacroWorkerThread.sleep(300)
                closeScreen(client)
                return false
            }

            // Check if we already have items (lore says "Click to give")
            val hasItems = checkHasItems(client)

            if (!hasItems && requirements.isNotEmpty()) {
                for (req in requirements) {
                    if (!isPurchaseWithinLimit(client, req)) {
                        clickRejectOffer(client)
                        MacroWorkerThread.sleep(300)
                        closeScreen(client)
                        return false
                    }
                }

                // Close visitor GUI, buy from Bazaar
                closeScreen(client)
                MacroWorkerThread.sleep(800)

                for (req in requirements) {
                    if (shouldStop) return false
                    msg(client, "\u00A7eBuying \u00A7e${req.count}x ${req.name} \u00A76from Bazaar...")

                    // Call executeBuy directly - we're already on the worker thread,
                    // so buyAsync() would deadlock waiting for the same thread.
                    val buySuccess = BazaarUtils.executeBuy(client, req.name, req.count)

                    if (!buySuccess) {
                        msg(client, "\u00A7cFailed to buy: ${req.name}. Refusing visitor.")
                        rejectVisitor(client, visitorName)
                        return false
                    }
                    MacroWorkerThread.sleep(500)
                }

                // Re-open the visitor GUI to accept
                if (shouldStop) return false

                if (!reopenVisitorGui(client, visitorName)) {
                    return false
                }
            }

            // Click "Accept Offer"
            val offerAcceptedWindow = CommandUtils.beginChatWindow()
            if (!clickAcceptOffer(client)) {
                msg(client, "\u00A7cCould not click Accept Offer for: $visitorName")
                closeScreen(client)
                return false
            }

            val acceptedByChat = waitForOfferAcceptedChat(offerAcceptedWindow, visitorName, 3000L)
            if (!acceptedByChat) {
                ClientUtils.sendDebugMessage("[VisitorsMacro] Chat confirmation missing for $visitorName, using fallback checks...")
                if (!waitForVisitorCompletionFallback(client, visitorName, 3000L)) {
                    msg(client, "\u00A7cCould not confirm offer acceptance for: $visitorName")
                    closeScreen(client)
                    return false
                }
            }

            closeScreen(client)

            // Track cost for profit manager
            VisitorManager.onOfferAccepted(visitorName)
            // After successfully serving a visitor, reset the per-visitor retry-sneak
            // flag so the next visitor will start with a normal (non-sneak) pathfind.
            currentVisitorRetrySneak = false
            msg(client, "\u00A7aServed visitor: \u00A7e$visitorName")
            return true
        }


        private fun isPurchaseWithinLimit(client: Minecraft, req: ItemRequirement): Boolean {
            val limit = AetherConfig.VISITOR_MAX_PURCHASE_LIMIT.get(); val cost = estimatePurchaseCost(req)
            if (cost <= 0L) { msg(client, "\u00A7cCould not verify Bazaar cost for ${req.count}x ${req.name}. Refusing visitor."); ClientUtils.sendDebugMessage("VisitorsMacro: purchase price unknown for ${req.count}x ${req.name}"); return false }
            if (cost > limit.toLong()) { msg(client, "\u00A7cSkipping ${req.count}x ${req.name}: estimated cost ${formatCoins(cost)} exceeds limit ${formatCoins(limit.toLong())}."); ClientUtils.sendDebugMessage("VisitorsMacro: purchase exceeds limit: ${req.count}x ${req.name} costs $cost, limit $limit"); return false }
            return true
        }
        private fun estimatePurchaseCost(req: ItemRequirement): Long { var value = ProfitManager.getItemValue(req.name, -req.count.toLong()); if (value == 0.0) value = ProfitManager.getItemPrice(req.name) * req.count; return Math.ceil(Math.abs(value)).toLong() }
        private fun shouldAcceptOffer(offer: VisitorOffer): Boolean {
            val coinsEnabled = AetherConfig.VISITOR_COINS_PER_COPPER.get(); val onlyRare = AetherConfig.VISITOR_ONLY_RARE_DROPS.get()
            if (!coinsEnabled && !onlyRare) return true
            if (onlyRare && offer.hasRareReward) return true
            var cost = 0.0
            for (req in offer.requirements) { val price = ProfitManager.getNpcPrice(req.name); if (price <= 0.0) { ClientUtils.sendDebugMessage("[VisitorsMacro] Unknown NPC price: ${req.name}"); return false }; cost += price * req.count }
            val perCopper = calculatePricePerCopper(cost, offer.copper)
            val accepted = shouldAcceptOffer(coinsEnabled, AetherConfig.VISITOR_COINS_PER_COPPER_LIMIT.get(), onlyRare, offer.hasRareReward, offer.copper, perCopper)
            if (!accepted) ClientUtils.sendDebugMessage("[VisitorsMacro] Rejecting offer: $perCopper coins/copper, copper=${offer.copper}, rare=${offer.hasRareReward}")
            return accepted
        }
        @JvmStatic fun calculatePricePerCopper(totalPrice: Double, copper: Int): Int = if (copper <= 0) 0 else (totalPrice / copper).toInt()
        @JvmStatic fun shouldAcceptOffer(coinsEnabled: Boolean, limit: Int, onlyRare: Boolean, hasRare: Boolean, copper: Int, perCopper: Int): Boolean { if (onlyRare && hasRare) return true; if (coinsEnabled) return copper > 0 && perCopper <= limit; return !onlyRare }
        private fun formatCoins(coins: Long): String = String.format("%,d coins", coins)
        private fun reopenVisitorGui(client: Minecraft, name: String): Boolean { var entity = findVisitorEntity(client, name) ?: run { msg(client, "\u00A7cLost visitor entity: $name"); return false }; entity = prepareVisitorForInteractionWithRetries(client, name, entity) ?: return false; if (!interactUntilGui(client, name, entity, 15000L)) { msg(client, "\u00A7cCould not reopen visitor GUI for: $name"); return false }; MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true)); return true }
        private fun rejectVisitor(client: Minecraft, name: String) { if (!reopenVisitorGui(client, name)) return; clickRejectOffer(client); MacroWorkerThread.sleep(300); closeScreen(client); MacroWorkerThread.sleep(300) }
        private fun waitForOfferAcceptedChat(window: CommandUtils.ChatWindow, name: String, timeout: Long): Boolean { val target = normalizeVisitorName(name); return CommandUtils.waitForChatMessageMatching(window, { text -> val raw = VisitorManager.extractAcceptedVisitorName(text); raw != null && normalizeVisitorName(raw) == target }, timeout) }
        private fun waitForVisitorCompletionFallback(client: Minecraft, name: String, timeout: Long): Boolean { val deadline = System.currentTimeMillis() + timeout; val target = normalizeVisitorName(name); while (System.currentTimeMillis() < deadline && !shouldStop) { var inTab = false; for (tabName in getVisitorNamesFromTab(client)) if (normalizeVisitorName(tabName) == target) { inTab = true; break }; if (!inTab || findVisitorEntity(client, name) == null) return true; MacroWorkerThread.sleep(120) }; return false }
        private fun normalizeVisitorName(name: String): String = stripColors(name).trim().replace(Regex("\\s*\\((COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL)\\)\\s*$"), "").trim().lowercase()
        private fun filterignoredVisitors(client: Minecraft, names: List<String>): MutableList<String> { val result = ArrayList<String>(); val ignored = AetherConfig.VISITOR_ignore.get(); for (name in names) { var skip = false; for (bl in ignored) if (normalizeVisitorName(name) == normalizeVisitorName(bl)) { msg(client, "\u00A7eSkipping ignored visitor: \u00A7c$name"); skip = true; break }; if (!skip) result.add(name) }; return result }
        private fun isRejectedVisitor(name: String): Boolean { for (rejected in AetherConfig.VISITOR_REJECT.get()) if (normalizeVisitorName(name) == normalizeVisitorName(rejected)) return true; return false }
        private fun processRejectedVisitor(client: Minecraft, name: String): Boolean { if (shouldStop) return false; msg(client, "\u00A7eActively rejecting visitor: \u00A7e$name"); if (!reopenVisitorGui(client, name)) return false; if (!clickRejectOffer(client)) { msg(client, "\u00A7cCould not click Refuse Offer for: $name"); closeScreen(client); return false }; MacroWorkerThread.sleep(500); closeScreen(client); MacroWorkerThread.sleep(300); msg(client, "\u00A7aRejected visitor: \u00A7e$name"); return true }
        private fun getVisitorNamesFromTab(client: Minecraft): List<String> { val names = ArrayList<String>(); if (client.connection == null) return names as List<String>; try { var found = false; for (line in TablistUtils.getTabLines(client)) { if (line.contains("Visitors:")) { found = true; continue }; if (found) { if (line.isEmpty() || line.contains("Next Visitor") || names.size >= 5) break; val name = line.replace("NEW!", "").trim(); if (name.isNotEmpty()) names.add(name) } } } catch (e: Exception) { e.printStackTrace() }; return names as List<String> }
        private fun emptyFungalTipJar(client: Minecraft) {
            if (!AetherConfig.VISITOR_EMPTY_TIP_JAR.get() || client.player == null) return
            val label = findTipJarLabel(client) ?: run { ClientUtils.sendDebugMessage("[VisitorsMacro] Fungal Tip Jar label not found nearby; skipping."); return }
            val copper = readTipJarCopper(label); val threshold = tipJarThreshold(label)
            if (copper < threshold) { ClientUtils.sendDebugMessage("[VisitorsMacro] Fungal Tip Jar at $copper/${readTipJarCapacity(label)}, below the $threshold threshold; skipping."); return }
            val pot = findTipJarPot(client) ?: run { ClientUtils.sendDebugMessage("[VisitorsMacro] Fungal Tip Jar pot not found nearby; skipping."); return }
            msg(client, "\u00A7eEmptying Fungal Tip Jar (\u00A76$copper \u00A7ecopper)...")
            val aim = Vec3(pot.x + 0.5, pot.y + 0.25, pot.z + 0.5)
            client.execute { RotationManager.initiateRotation(client, aim, AetherConfig.ROTATION_TIME.get().toLong(), AetherConfig.VISITOR_FOV_RANGE.get()) }
            MacroWorkerThread.sleep(AetherConfig.ROTATION_TIME.get().toLong() + 50L); waitForRotationToFinish()
            client.execute { (client as MixinMinecraft).`aether$startUseItem`() }; MacroWorkerThread.sleep(600)
        }
        private fun findTipJarPot(client: Minecraft): BlockPos? {
            if (client.level == null || client.player == null) return null
            val radius = Math.ceil(TIP_JAR_MAX_RANGE).toInt(); val base = client.player!!.blockPosition(); var best: BlockPos? = null; var bestDistSq = TIP_JAR_MAX_RANGE * TIP_JAR_MAX_RANGE
            for (x in -radius..radius) for (y in -radius..radius) for (z in -radius..radius) { val pos = base.offset(x, y, z); if (client.level!!.getBlockState(pos).block !is FlowerPotBlock) continue; val distSq = client.player!!.eyePosition.distanceToSqr(pos.x + 0.5, pos.y + 0.25, pos.z + 0.5); if (distSq < bestDistSq) { bestDistSq = distSq; best = pos } }
            return best
        }
        private fun findTipJarLabel(client: Minecraft): Entity? {
            if (client.level == null || client.player == null) return null
            var best: Entity? = null; var bestDistSq = Double.MAX_VALUE
            for (entity in client.level!!.entitiesForRendering()) { if (entity == client.player!! || readTipJarCopper(entity) < 0) continue; val distSq = entity.distanceToSqr(client.player!!); if (distSq < bestDistSq) { bestDistSq = distSq; best = entity } }
            return best
        }
        private fun readTipJarCopper(entity: Entity): Int = readTipJarGroup(entity, 1)
        private fun readTipJarCapacity(entity: Entity): Int = readTipJarGroup(entity, 2)
        private fun tipJarThreshold(entity: Entity): Int { val capacity = readTipJarCapacity(entity); return if (capacity > 0) Math.ceil(capacity * TIP_JAR_FILL_THRESHOLD).toInt() else TIP_JAR_FALLBACK_THRESHOLD }
        private fun readTipJarGroup(entity: Entity?, group: Int): Int {
            if (entity == null || entity.displayName == null) return -1
            val matcher = TIP_JAR_PATTERN.matcher(stripColors(entity.displayName.string)); if (!matcher.find()) return -1
            return try { Integer.parseInt(matcher.group(group).replace(",", "")) } catch (_: NumberFormatException) { -1 }
        }
        private fun findVisitorEntity(client: Minecraft, visitorName: String): Entity? = EntityUtils.findEntity(client, visitorName)
        private fun walkToEntity(client: Minecraft, entity: Entity, retryRange: Float) {
            if (client.player == null) return

            var target = findBestWalkingTarget(client, entity, retryRange)
            if (target == null) target = entity.blockPosition()

            val x = target.x
            val y = target.y
            val z = target.z

            ClientUtils.sendDebugMessage("[VisitorsMacro] Walking to destination near visitor: $x, $y, $z")
            // Start the pathfind and then set the walk-sneak latch on the main thread.
            // Latch decision is per-visitor: `currentVisitorRetrySneak` is false for
            // the first attempt (walk normally), and set to true if we need to retry
            // for the same visitor (in which case we want to sneak).
            try {
                val latchSneak = currentVisitorRetrySneak
                client.execute {
                    PathfindingManager.startPathfind(client, x, y, z, false, entity)
                    PathfindingManager.setWalkSneakLatched(latchSneak)
                }
            } catch (_: Exception) {
            }
            MacroWorkerThread.sleep(500)

            val deadline = System.currentTimeMillis() + 15000L
            while (PathfindingManager.isNavigating() && System.currentTimeMillis() < deadline && !shouldStop) {
                val screen = client.gui.screen()
                if (screen is AbstractContainerScreen<*> && isVisitorScreen(screen)) {
                    ClientUtils.sendDebugMessage("[VisitorsMacro] Closing visitor GUI opened during pathing.")
                    closeScreen(client)
                    MacroWorkerThread.sleep(250)
                }
                MacroWorkerThread.sleep(200)
            }

            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop()
            }
            // Clear the sneak latch now that navigation has finished/stopped.
            try {
                client.execute { PathfindingManager.setWalkSneakLatched(false) }
            } catch (_: Exception) {
            }
            MacroWorkerThread.sleep(200)
        }

        private fun findBestWalkingTarget(client: Minecraft, entity: Entity, retryRange: Float): BlockPos? {
            if (client.level == null || client.player == null) return null
            val base = entity.blockPosition(); var best: BlockPos? = null; var bestDistSq = Double.MAX_VALUE; val retryRangeSqr = retryRange * retryRange
            for (x in -3..3) for (z in -3..3) for (y in -2..2) { val pos = base.offset(x, y, z); if (pos.distToCenterSqr(entity.position()) > retryRangeSqr) continue; if (isWalkable(client, pos)) { val dist = pos.distToCenterSqr(client.player!!.position()); if (dist < bestDistSq) { bestDistSq = dist; best = pos } } }
            return best
        }
        private fun isWalkable(client: Minecraft, pos: BlockPos): Boolean { val level = client.level ?: return false; return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty }
        private fun isLookingAt(client: Minecraft, entity: Entity, tolerance: Float): Boolean { val player = client.player ?: return false; return RotationUtils.isLookingAt(player.yRot, player.xRot, player.eyePosition, entity.eyePosition, tolerance) }
        private fun faceVisitorForInteraction(client: Minecraft, entity: Entity) { rotateToEntity(client, entity); waitForRotationToFinish(); if (!isLookingAt(client, entity, AetherConfig.VISITOR_FOV_RANGE.get())) { rotateToEntity(client, entity); waitForRotationToFinish() } }
        private fun rotateToEntity(client: Minecraft, entity: Entity) { client.execute { RotationManager.initiateRotation(client, Vec3(entity.x, entity.eyeY, entity.z), AetherConfig.ROTATION_TIME.get().toLong(), AetherConfig.VISITOR_FOV_RANGE.get()) }; MacroWorkerThread.sleep(AetherConfig.ROTATION_TIME.get().toLong() + 50L) }
        private fun waitForRotationToFinish() { val deadline = System.currentTimeMillis() + 1500L; while (RotationManager.isRotating() && System.currentTimeMillis() < deadline) MacroWorkerThread.sleep(25) }
        private fun interactWithEntity(client: Minecraft, entity: Entity): Boolean { if (client.player == null || client.options == null) return false; client.execute { if (client.player != null) (client as MixinMinecraft).`aether$startAttack`() }; MacroWorkerThread.sleep(100); return true }
        private fun prepareVisitorForInteraction(client: Minecraft, visitorName: String, initial: Entity, retryRange: Float): Entity? {
            if (client.player == null) return null
            var visitor = findVisitorEntity(client, visitorName) ?: initial
            if (!isVisitorInRetryRange(client, visitor, retryRange)) { walkToEntity(client, visitor, retryRange); if (shouldStop) return null; visitor = findVisitorEntity(client, visitorName) ?: visitor }
            if (!isVisitorInInteractionRange(client, visitor)) return null
            faceVisitorForInteraction(client, visitor); return visitor
        }
        private fun prepareVisitorForInteractionWithRetries(client: Minecraft, visitorName: String, initial: Entity): Entity? {
            var visitor = initial; currentVisitorRetrySneak = false
            for (attempt in 0..MAX_VISITOR_INTERACTION_RETRIES) { if (shouldStop) break; if (attempt > 0) currentVisitorRetrySneak = true; val retryRange = getRetryRangeForAttempt(attempt); visitor = findVisitorEntity(client, visitorName) ?: visitor; val prepared = prepareVisitorForInteraction(client, visitorName, visitor, retryRange); if (prepared != null) return prepared; if (attempt < MAX_VISITOR_INTERACTION_RETRIES) { ClientUtils.sendDebugMessage("[VisitorsMacro] Initial prep retry (${attempt + 1}/$MAX_VISITOR_INTERACTION_RETRIES) for $visitorName"); MacroWorkerThread.sleep(250) } }
            msg(client, "\u00A7cVisitor is still out of range for interaction: $visitorName"); return null
        }

        // right-clicks every 500ms until a container gui opens or it times out
        private fun isVisitorInInteractionRange(client: Minecraft, visitor: Entity): Boolean = client.player?.distanceTo(visitor)?.let { it <= VISITOR_INTERACTION_RANGE } ?: false
        private fun isVisitorInRetryRange(client: Minecraft, visitor: Entity, range: Float): Boolean = client.player?.distanceTo(visitor)?.let { it <= range } ?: false

        private fun interactUntilGui(client: Minecraft, visitorName: String, initial: Entity, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            var prepRetries = 0
            var entity = initial
            while (System.currentTimeMillis() < deadline && !shouldStop) {
                entity = findVisitorEntity(client, visitorName) ?: entity

                val retryRange = getRetryRangeForAttempt(prepRetries)
                if (!isVisitorInRetryRange(client, entity, retryRange) ||
                    !isLookingAt(client, entity, AetherConfig.VISITOR_FOV_RANGE.get())) {
                    if (prepRetries >= MAX_VISITOR_INTERACTION_RETRIES) {
                        ClientUtils.sendDebugMessage("[VisitorsMacro] Visitor interaction retries exceeded for $visitorName")
                        return false
                    }

                    prepRetries++
                    ClientUtils.sendDebugMessage("[VisitorsMacro] Re-prepping visitor interaction ($prepRetries/$MAX_VISITOR_INTERACTION_RETRIES) for $visitorName")
                    entity = prepareVisitorForInteraction(client, visitorName, entity, getRetryRangeForAttempt(prepRetries))
                        ?: continue
                }

                interactWithEntity(client, entity)
                // Wait up to 500ms, checking for GUI every 100ms
                for (n in 0 until 5) {
                    MacroWorkerThread.sleep(100)
                    val screen = client.gui.screen()
                    if (screen is AbstractContainerScreen<*>) {
                        if (isWrongVisitorScreen(screen, visitorName)) {
                            ClientUtils.sendDebugMessage("[VisitorsMacro] Closing wrong visitor GUI while targeting $visitorName.")
                            closeScreen(client)
                            MacroWorkerThread.sleep(250)
                            prepRetries++
                            if (prepRetries > MAX_VISITOR_INTERACTION_RETRIES) {
                                return false
                            }

                            // A wrong GUI means the last ray trace hit another NPC.
                            // Force a fresh path even if we are inside the normal
                            // interaction radius; otherwise the next click can hit
                            // the same NPC again indefinitely.
                            val targetVisitor = findVisitorEntity(client, visitorName)
                                ?: continue
                            currentVisitorRetrySneak = true
                            walkToEntity(client, targetVisitor, VISITOR_RETRY_RANGE)
                            if (shouldStop) {
                                return false
                            }
                            entity = findVisitorEntity(client, visitorName) ?: continue
                            faceVisitorForInteraction(client, entity)
                            break
                        }
                        return true
                    }
                    if (shouldStop) return false
                }
            }
            val screen = client.gui.screen()
            if (screen is AbstractContainerScreen<*> && isWrongVisitorScreen(screen, visitorName)) {
                closeScreen(client)
                return false
            }
            return screen is AbstractContainerScreen<*>
        }
        private fun isVisitorScreen(screen: AbstractContainerScreen<*>): Boolean = hasNamedSlot(screen, ACCEPT_OFFER_SLOT, "Accept Offer") || hasNamedSlot(screen, REJECT_OFFER_SLOT, "Refuse Offer")
        private fun hasNamedSlot(screen: AbstractContainerScreen<*>, slotIndex: Int, fragment: String): Boolean { if (slotIndex >= screen.menu.slots.size) return false; val slot = screen.menu.slots[slotIndex]; return slot.hasItem() && stripColors(slot.item.hoverName.string).contains(fragment) }
        private fun isWrongVisitorScreen(screen: AbstractContainerScreen<*>, visitorName: String): Boolean = isVisitorScreen(screen) && normalizeVisitorName(screen.title.string) != normalizeVisitorName(visitorName)
        private fun readOffer(client: Minecraft): VisitorOffer? {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return null
            if (ACCEPT_OFFER_SLOT >= screen.menu.slots.size) return null
            val slot = screen.menu.slots[ACCEPT_OFFER_SLOT]; if (!slot.hasItem()) return null
            val stack = slot.item; if (!stripColors(stack.hoverName.string).contains("Accept Offer")) return null
            val lore = stack.get(DataComponents.LORE) ?: return null
            val requirements = ArrayList<ItemRequirement>(); var parsingRequired = false; var parsingRewards = false; var copper = 0; var hasRareReward = false
            for (line in lore.lines()) {
                val text = stripColors(line.string).trim(); if (text.isEmpty()) continue
                if (text.contains("Required:") || text.contains("Items Required:")) { parsingRequired = true; parsingRewards = false; continue }
                if (text.contains("Rewards:")) { parsingRequired = false; parsingRewards = true; continue }
                if (parsingRequired) {
                    if (text.contains("Farming XP") || text.contains("Garden Experience")) continue
                    val m = ITEM_PATTERN.matcher(text)
                    if (m.matches()) { val itemName = m.group(1).trim(); val count = Integer.parseInt(m.group(2).replace(",", "")); requirements.add(ItemRequirement(itemName, count)); ClientUtils.sendDebugMessage("[VisitorsMacro] Required: $itemName x$count") }
                    else if (text.isNotEmpty()) { requirements.add(ItemRequirement(text.trim(), 1)); ClientUtils.sendDebugMessage("[VisitorsMacro] Required: ${text.trim()} x1") }
                } else if (parsingRewards) { copper = Math.max(copper, parseCopperReward(text)); hasRareReward = hasRareReward || hasRareReward(text) }
            }
            return VisitorOffer(requirements as java.util.List<ItemRequirement>, copper, hasRareReward)
        }
        @JvmStatic fun parseCopperReward(text: String): Int { val matcher = COPPER_PATTERN.matcher(text); return if (matcher.find()) Integer.parseInt(matcher.group(1).replace(",", "")) else 0 }
        @JvmStatic fun hasRareReward(text: String): Boolean = RARE_REWARDS.stream().anyMatch { reward -> text.lowercase(Locale.ROOT).contains(reward.lowercase(Locale.ROOT)) }
        private fun checkHasItems(client: Minecraft): Boolean {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            if (ACCEPT_OFFER_SLOT >= screen.menu.slots.size) return false
            val slot = screen.menu.slots[ACCEPT_OFFER_SLOT]; if (!slot.hasItem()) return false
            for (line in slot.item.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)) if (stripColors(line.string).contains("Click to give")) return true
            return false
        }
        private fun clickAcceptOffer(client: Minecraft): Boolean = clickOfferSlot(client, ACCEPT_OFFER_SLOT, "Accept Offer")
        private fun clickRejectOffer(client: Minecraft): Boolean = clickOfferSlot(client, REJECT_OFFER_SLOT, "Refuse Offer")
        private fun clickOfferSlot(client: Minecraft, index: Int, fragment: String): Boolean {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            if (index >= screen.menu.slots.size) return false
            val slot = screen.menu.slots[index]; if (!slot.hasItem() || !stripColors(slot.item.hoverName.string).contains(fragment)) return false
            client.execute { val current = client.gui.screen(); if (current is AbstractContainerScreen<*>) ClientUtils.performSlotClick(current, index, 0, ContainerInput.PICKUP) }
            return true
        }
        private fun waitForTeleport(client: Minecraft, posBefore: Vec3, window: CommandUtils.ChatWindow, timeout: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeout
            while (System.currentTimeMillis() < deadline && !shouldStop) { if (hasBarnTeleportMessage) return true; if (CommandUtils.hasReceivedMessageMatching(window) { message -> message.contains("Teleported you to The Barn!") || message.contains("Teleported you to the Barn!") }) return true; if (client.player?.position()?.distanceTo(posBefore)?.let { it > 5 } == true) return true; MacroWorkerThread.sleep(100) }
            return false
        }
        private fun waitForContainer(client: Minecraft, timeout: Long): Boolean = waitForContainer(client, timeout, false)
        private fun waitForContainer(client: Minecraft, timeout: Long, ignoreStop: Boolean): Boolean { val deadline = System.currentTimeMillis() + timeout; while (System.currentTimeMillis() < deadline && (ignoreStop || !shouldStop)) { if (client.gui.screen() is AbstractContainerScreen<*>) return true; MacroWorkerThread.sleep(100) }; return false }
        private fun closeScreen(client: Minecraft) { client.execute { if (client.player != null && client.gui.screen() != null) client.player!!.closeContainer() } }
        private fun ensureVisitorLoadout(client: Minecraft): Boolean {
            if (AetherConfig.AUTO_LOADOUT_ENABLED.get() && AetherConfig.LOADOUT_SLOT_VISITOR.get() > 0 && LoadoutManager.trackedLoadoutSlot != AetherConfig.LOADOUT_SLOT_VISITOR.get()) {
                msg(client, "\u00A7eSwapping to visitor loadout (Slot ${AetherConfig.LOADOUT_SLOT_VISITOR.get()})...")
                GearManager.ensureLoadoutSlot(client, AetherConfig.LOADOUT_SLOT_VISITOR.get())
                if (LoadoutManager.isSwappingLoadout) { ClientUtils.waitForWardrobeGui(); while (LoadoutManager.isSwappingLoadout) MacroWorkerThread.sleep(50); while (LoadoutManager.loadoutCleanupTicks > 0) MacroWorkerThread.sleep(50); MacroWorkerThread.sleep(250) }
            }
            return equipVisitorTool(client)
        }
        private fun equipVisitorTool(client: Minecraft): Boolean {
            if (AetherConfig.EQUIP_VISITOR_CUSTOM_ITEM.get()) { val customItem = AetherConfig.VISITOR_CUSTOM_ITEM.get().trim(); if (customItem.isEmpty()) { ClientUtils.sendDebugMessage("VisitorsMacro: custom item name is blank."); return false }; val swapped = GearManager.swapToNamedHotbarItemSync(client, customItem); if (!swapped) ClientUtils.sendDebugMessage("VisitorsMacro: custom item not found in hotbar: $customItem"); return swapped }
            if (GearManager.findFarmingToolSlot(client) == -1) { ClientUtils.sendDebugMessage("VisitorsMacro: no farming tool found in hotbar."); return false }
            GearManager.swapToFarmingToolSync(client); return true
        }
        private fun setCompactorsEnabled(client: Minecraft, enabled: Boolean): Boolean {
            if (client.player == null) return false
            val slots = findCompactorHotbarSlots(client); if (slots.isEmpty()) { ClientUtils.sendDebugMessage("VisitorsMacro: no compactors found in hotbar."); return false }
            val originalSlot = client.player!!.inventory.selectedSlot; var changedAny = false
            for (slot in slots) { if (!enabled && shouldStop) break; changedAny = setCompactorEnabled(client, slot, enabled) || changedAny; MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false)) }
            closeScreen(client); if (originalSlot in 0..8) { client.execute { FailsafeManager.selectHotbarSlot(client, originalSlot) }; waitForSelectedHotbarSlot(client, originalSlot, 1000L) }; return changedAny
        }
        @JvmStatic fun reenableCompactorsIfPending(client: Minecraft) { if (!compactorsPendingReenable) return; if (setCompactorsEnabled(client, true)) { compactorsPendingReenable = false; return }; if (client.player == null || findCompactorHotbarSlots(client).isEmpty()) compactorsPendingReenable = false }
        private fun findCompactorHotbarSlots(client: Minecraft): MutableList<Int> { val slots = ArrayList<Int>(); val player = client.player ?: return slots; for (i in 0 until 9) { val stack = player.inventory.getItem(i); if (!stack.isEmpty && stripColors(stack.hoverName.string).contains("Compactor")) slots.add(i) }; return slots }
        private fun setCompactorEnabled(client: Minecraft, hotbarSlot: Int, enabled: Boolean): Boolean {
            if (client.player == null) return false
            closeScreen(client); client.execute { FailsafeManager.selectHotbarSlot(client, hotbarSlot) }
            if (!waitForSelectedHotbarSlot(client, hotbarSlot, 1500L)) { ClientUtils.sendDebugMessage("VisitorsMacro: failed to select compactor slot $hotbarSlot"); return false }
            val currentItem = client.player!!.mainHandItem
            if (currentItem.isEmpty || !stripColors(currentItem.hoverName.string).contains("Compactor")) { ClientUtils.sendDebugMessage("VisitorsMacro: selected item is not a compactor."); return false }
            ClientUtils.performUseClick(); if (!waitForContainer(client, COMPACTOR_GUI_TIMEOUT_MS, true)) { ClientUtils.sendDebugMessage("VisitorsMacro: compactor GUI did not open."); return false }
            MacroWorkerThread.sleep(COMPACTOR_POST_OPEN_DELAY_MS); val statusSlotIndex = waitForCompactorStatusSlot(client, COMPACTOR_SLOT_TIMEOUT_MS)
            if (statusSlotIndex < 0) { ClientUtils.sendDebugMessage("VisitorsMacro: compactor status slot was not found."); closeScreen(client); return false }
            val statusScreen = client.gui.screen() as AbstractContainerScreen<*>; val statusSlot = statusScreen.menu.slots[statusSlotIndex]; val currentEnabled = readCompactorEnabled(client, statusSlot.item)
            if (currentEnabled == null) { ClientUtils.sendDebugMessage("VisitorsMacro: compactor status could not be read."); closeScreen(client); return false }
            if (currentEnabled == enabled) { closeScreen(client); return false }
            ClientUtils.sendDebugMessage("VisitorsMacro: " + (if (enabled) "enabling" else "disabling") + " compactor in hotbar slot " + (hotbarSlot + 1))
            client.execute { val screen = client.gui.screen(); if (screen is AbstractContainerScreen<*>) ClientUtils.performSlotClick(screen, statusSlotIndex, 0, ContainerInput.PICKUP) }
            MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false)); closeScreen(client); return true
        }
        private fun waitForCompactorStatusSlot(client: Minecraft, timeout: Long): Int { val deadline = System.currentTimeMillis() + timeout; while (System.currentTimeMillis() < deadline) { val screen = client.gui.screen(); if (screen is AbstractContainerScreen<*> && isCompactorScreen(screen)) for (i in screen.menu.slots.indices) { val slot = screen.menu.slots[i]; if (slot.hasItem() && stripColors(slot.item.hoverName.string).contains("Compactor Currently")) return i }; MacroWorkerThread.sleep(50) }; return -1 }
        private fun isCompactorScreen(screen: AbstractContainerScreen<*>): Boolean = stripColors(screen.title.string).contains("Compactor")
        private fun readCompactorEnabled(client: Minecraft, stack: ItemStack): Boolean? { val name = stripColors(stack.hoverName.string).uppercase(Locale.ROOT); if (name.contains("ON")) return true; if (name.contains("OFF")) return false; for (line in stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)) { val text = stripColors(line.string).uppercase(Locale.ROOT); if (text.contains("ON")) return true; if (text.contains("OFF")) return false }; return null }
        private fun waitForSelectedHotbarSlot(client: Minecraft, slot: Int, timeout: Long): Boolean { val deadline = System.currentTimeMillis() + timeout; while (System.currentTimeMillis() < deadline) { if (client.player?.inventory?.selectedSlot == slot) return true; MacroWorkerThread.sleep(10) }; return client.player?.inventory?.selectedSlot == slot }
        private fun stripColors(value: String): String = TablistUtils.stripColors(value)
        private fun msg(client: Minecraft, text: String) { ClientUtils.sendMessage(text) }
        private fun getRetryRangeForAttempt(attempt: Int): Float = Math.max(0.0f, VISITOR_RETRY_RANGE - attempt)
    }
}
