package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.gear.helpers.BudgetAutopetManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import dev.aether.util.RotationUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.phys.Vec3
import java.util.HashSet
import java.util.function.Supplier
import java.util.regex.Pattern

class PestTrapManager private constructor() {

    enum class Operation {
        NONE,
        CLEAR,
        REFILL
    }

    companion object {
        private val FULL_TRAPS_PATTERN = Pattern.compile("(?i)Full Traps:\\s*(.*)")
        private val NO_BAIT_PATTERN = Pattern.compile("(?i)No Bait:\\s*(.*)")
        private const val TRAP_AIM_TOLERANCE_DEGREES = 3.0f
        private const val RELEASED_PEST_KILL_TIMEOUT_MS = 5_000L
        @Volatile private var isRunning = false
        @Volatile private var cancelRequested = false
        @Volatile private var currentOperation = Operation.NONE

        @JvmStatic
        fun isRunning(): Boolean {
            return isRunning
        }

        @JvmStatic
        fun getCurrentOperation(): Operation {
            return currentOperation
        }

        @Synchronized
        private fun beginOperation(operation: Operation): Boolean {
            if (isRunning) {
                return false
            }
            cancelRequested = false
            isRunning = true
            currentOperation = operation
            return true
        }

        private fun finishOperation() {
            isRunning = false
            currentOperation = Operation.NONE
            cancelRequested = false
            PathfindingManager.stop()
        }

        @JvmStatic
        fun start(client: Minecraft) {
            startAsync(client, Operation.CLEAR)
        }

        @JvmStatic
        fun startRefill(client: Minecraft) {
            startAsync(client, Operation.REFILL)
        }

        private fun startAsync(client: Minecraft, operation: Operation) {
            if (!PestManager.arePestTrapsEnabled()) {
                ClientUtils.sendMessage("\u00A7cPest traps are disabled.", false)
                return
            }
            if (isBlockedByPestExchange()) {
                ClientUtils.sendDebugMessage(
                    "PestTrapManager: skipping " + operationName(operation) +
                        " while pest exchange is active."
                )
                return
            }
            if (!beginOperation(operation)) {
                ClientUtils.sendMessage("\u00A7cPest traps sequence is already running.", false)
                return
            }

            val plot = AetherConfig.PEST_TRAPS_PLOT.get()
            ClientUtils.sendMessage(
                "\u00A7eStarting pest traps " + operationName(operation) +
                    " sequence for plot " + plot, false
            )
            MacroWorkerThread.getInstance().submit(
                "PestTraps-" + operationName(operation)
            ) { runClaimedOperation(client, plot, operation, true) }
        }

        @JvmStatic
        fun runBlocking(client: Minecraft, plot: String, operation: Operation): Boolean {
            if (!PestManager.arePestTrapsEnabled() || isBlockedByPestExchange() || !beginOperation(operation)) {
                return false
            }
            runClaimedOperation(client, plot, operation, false)
            return true
        }

        private fun runClaimedOperation(
            client: Minecraft,
            plot: String,
            operation: Operation,
            notifyCompletion: Boolean
        ) {
            try {
                if (operation == Operation.CLEAR) {
                    runSequence(client, plot)
                } else if (operation == Operation.REFILL) {
                    runRefillSequence(client, plot)
                }
            } catch (error: Exception) {
                ClientUtils.sendDebugMessage(
                    "PestTrapManager " + operationName(operation) +
                        " error: " + error.message
                )
                error.printStackTrace()
            } finally {
                val cancelled = cancelRequested
                finishOperation()
                if (notifyCompletion && !cancelled) {
                    PestClientThread.run(client, Runnable {
                        if (client.player != null) {
                            ClientUtils.sendMessage(
                                "\u00A7aPest traps " + operationName(operation) +
                                    " sequence finished.", false
                            )
                        }
                    })
                }
            }
        }

        private fun operationName(operation: Operation): String {
            return operation.name.lowercase()
        }

        @JvmStatic
        fun cancel(client: Minecraft) {
            cancelRequested = true
            isRunning = false
            currentOperation = Operation.NONE
            ensureGuiClosed(client)
            PathfindingManager.stop()
            RotationManager.cancelRotation()
        }

        @JvmStatic
        @Throws(InterruptedException::class)
        fun runSequence(client: Minecraft, plot: String) {
            currentOperation = Operation.CLEAR
            val clearedTrapIds = HashSet<Int>()
            if (shouldAbort() || abortForPestExchange(client, "clear")) {
                return
            }

            AutoSellManager.checkBeforePestTraps(client, true, false)
            if (shouldAbort()) {
                return
            }

            teleportToTrapPlotIfNeeded(client, plot)
            if (shouldAbort()) {
                return
            }

            walkToTrapsIfConfigured(client)
            if (shouldAbort()) {
                return
            }

            client.execute {
                val slot = findVacuumHotbarSlot(client)
                if (slot != -1) {
                    FailsafeManager.selectHotbarSlot(client, slot)
                }
            }

            while (isRunning && !shouldAbort()) {
                if (abortForPestExchange(client, "clear")) {
                    return
                }
                ensureGuiClosed(client)
                val fullTraps = getFullTrapsFromTab(client).filter { trapId -> !clearedTrapIds.contains(trapId) }
                if (fullTraps.isEmpty()) {
                    ClientUtils.sendDebugMessage(
                        if (clearedTrapIds.isEmpty())
                            "No more full traps detected in tablist."
                        else
                            "No uncleared full traps detected in tablist."
                    )
                    break
                }

                ClientUtils.sendMessage("Found " + fullTraps.size + " full traps: " + fullTraps)
                var clearedThisPass = 0

                for (trapId in fullTraps) {
                    if (!isRunning || shouldAbort() || abortForPestExchange(client, "clear")) {
                        return
                    }

                    ensureGuiClosed(client)
                    ClientUtils.sendDebugMessage("Looking for trap #" + trapId)
                    val trapTarget = PestClientThread.call(
                        client, Supplier { findTrapTarget(client, trapId) }, null
                    )
                    if (trapTarget == null) {
                        ClientUtils.sendDebugMessage("Could not find armor stand for trap #" + trapId)
                        continue
                    }

                    if (AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.get()) {
                        ensureMosquitoEquipped(client)
                        if (!isRunning || shouldAbort()) {
                            return
                        }
                    }

                    val trapEyePos = trapTarget.eyePosition()
                    ClientUtils.sendDebugMessage(
                        "Interacting with trap entity #" + trapId +
                            " (dist=" + String.format("%.2f", trapTarget.distance()) + ")"
                    )

                    val guiOpened = openTrapGui(client, trapId, trapEyePos)

                    if (shouldAbort()) {
                        return
                    }

                    if (guiOpened) {
                        MacroWorkerThread.sleep(500)
                        val releaseSlot = PestClientThread.call(
                            client,
                            Supplier {
                                val screen = client.gui.screen()
                                if (screen is AbstractContainerScreen<*>) {
                                    findReleasePestsSlot(screen)
                                } else {
                                    -1
                                }
                            },
                            -1
                        ) ?: -1
                        var released = false
                        var preReleasePestIds: Set<Int> = java.util.Set.of()
                        if (releaseSlot != -1) {
                            ClientUtils.sendDebugMessage("Clicking 'Release All Pests' button at slot " + releaseSlot)
                            preReleasePestIds = snapshotLivePestIds(client)
                            clickCurrentScreenSlot(client, releaseSlot)
                            clearedTrapIds.add(trapId)
                            clearedThisPass++
                            released = true
                        } else {
                            ClientUtils.sendDebugMessage("Could not find 'Release All Pests' button.")
                        }

                        MacroWorkerThread.sleep(200)
                        waitForTrapGuiClosed(client)
                        ensurePetEquippedAfterTrapOpen(client)
                        if (released) {
                            awaitReleasedPestKill(client, preReleasePestIds)
                        } else {
                            MacroWorkerThread.sleep(200)
                        }
                    } else {
                        ClientUtils.sendDebugMessage(
                            "Failed to open trap GUI for #" + trapId +
                                " (dist=" + String.format("%.2f", trapTarget.distance()) + ")"
                        )
                    }
                }

                if (clearedThisPass == 0) {
                    ClientUtils.sendDebugMessage("No traps cleared this pass - stopping.")
                    break
                }

                MacroWorkerThread.sleep(500)
            }
        }

        @JvmStatic
        @Throws(InterruptedException::class)
        fun runRefillSequence(client: Minecraft, plot: String) {
            currentOperation = Operation.REFILL
            if (shouldAbort() || abortForPestExchange(client, "refill")) {
                return
            }

            val emptyTraps = getNoBaitTrapsFromTab(client)
            if (emptyTraps.isEmpty()) {
                ClientUtils.sendDebugMessage("No empty traps found (no bait).")
                return
            }

            val baitMaterial = AetherConfig.PEST_TRAPS_BAIT_MATERIAL.get()
            val baitAmount = Math.max(1, AetherConfig.PEST_TRAPS_BAIT_AMOUNT.get())
            val baitNeeded = emptyTraps.size * baitAmount
            ClientUtils.sendDebugMessage("Buying " + baitNeeded + " " + baitMaterial + "...")
            val bought = dev.aether.util.BazaarUtils.executeBuy(client, baitMaterial, baitNeeded)
            if (!bought || shouldAbort()) {
                if (!shouldAbort()) {
                    ClientUtils.sendDebugMessage("Failed to buy " + baitMaterial + ". Aborting.")
                }
                return
            }

            teleportToTrapPlotIfNeeded(client, plot)
            if (shouldAbort()) {
                return
            }
            walkToTrapsIfConfigured(client)

            while (isRunning && !shouldAbort()) {
                if (abortForPestExchange(client, "refill")) {
                    return
                }
                ensureGuiClosed(client)
                val targets = getNoBaitTrapsFromTab(client)
                if (targets.isEmpty()) {
                    ClientUtils.sendDebugMessage("No more empty traps (no bait) detected in tablist.")
                    break
                }

                var clearedThisPass = 0

                for (trapId in targets) {
                    if (!isRunning || shouldAbort() || abortForPestExchange(client, "refill")) {
                        return
                    }

                    ensureGuiClosed(client)
                    ClientUtils.sendDebugMessage("Looking for empty trap #" + trapId)
                    val trapTarget = PestClientThread.call(
                        client, Supplier { findTrapTarget(client, trapId) }, null
                    )
                    if (trapTarget == null) {
                        ClientUtils.sendDebugMessage("Could not find armor stand for trap #" + trapId)
                        continue
                    }

                    val trapEyePos = trapTarget.eyePosition()
                    val guiOpened = openTrapGui(client, trapId, trapEyePos)

                    if (shouldAbort()) {
                        return
                    }

                    if (guiOpened) {
                        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true))
                        var success = false
                        val baitSlots: IntArray = PestClientThread.call(
                            client,
                            Supplier {
                                val screen = client.gui.screen()
                                if (screen is AbstractContainerScreen<*>) {
                                    intArrayOf(findBaitInInventory(screen, baitMaterial), findBaitSlot(screen))
                                } else {
                                    intArrayOf(-1, -1)
                                }
                            },
                            intArrayOf(-1, -1)
                        )!!
                        val inventoryBaitSlot = baitSlots[0]
                        val trapBaitSlot = baitSlots[1]

                        if (inventoryBaitSlot != -1 && trapBaitSlot != -1) {
                            ClientUtils.sendDebugMessage(
                                "Refilling trap with " + baitMaterial + " from slot " + inventoryBaitSlot +
                                    " to bait slot " + trapBaitSlot
                            )
                            clickCurrentScreenSlot(client, inventoryBaitSlot)
                            MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false))
                            clickCurrentScreenSlot(client, trapBaitSlot)
                            MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false))

                            client.execute { client.player?.closeContainer() }
                            success = true
                        } else {
                            ClientUtils.sendDebugMessage(
                                "Could not find bait item or bait slot. Item=" + inventoryBaitSlot +
                                    ", BaitSlot=" + trapBaitSlot
                            )
                            client.execute { client.player?.closeContainer() }
                        }

                        if (success) {
                            clearedThisPass++
                        }
                        MacroWorkerThread.sleep(1000)
                    } else {
                        ClientUtils.sendDebugMessage(
                            "Failed to open trap GUI for #" + trapId +
                                " (dist=" + String.format("%.2f", trapTarget.distance()) + ")"
                        )
                        ensureGuiClosed(client)
                    }
                }

                if (clearedThisPass == 0) {
                    break
                }
                MacroWorkerThread.sleep(500)
            }
        }

        @JvmStatic
        fun getNoBaitTrapsFromTab(client: Minecraft?): List<Int> {
            if (client != null) {
                if (!client.isSameThread()) {
                    return PestClientThread.call(
                        client, Supplier { getNoBaitTrapsFromTab(client) }, java.util.Collections.emptyList<Int>()
                    ) ?: emptyList()
                }
                return PestTrapTabParser.parseTrapIds(TablistUtils.getRawTabLines(client), NO_BAIT_PATTERN)
            }
            return emptyList()
        }

        @Throws(InterruptedException::class)
        private fun walkToTrapsIfConfigured(client: Minecraft) {
            if (!AetherConfig.PEST_TRAPS_PATHFIND.get()) {
                return
            }

            val x = AetherConfig.PEST_TRAPS_X.get()
            val y = AetherConfig.PEST_TRAPS_Y.get()
            val z = AetherConfig.PEST_TRAPS_Z.get()
            if (y == 0) {
                ClientUtils.sendDebugMessage("PestTrapManager: trap position not set, skipping pathfind.")
                return
            }
            val alreadyNear = PestClientThread.call(
                client,
                Supplier {
                    val player = client.player
                    player != null &&
                        player.position().distanceToSqr(x + 0.5, y + 0.5, z + 0.5) <= 9.0
                },
                false
            )!!
            if (alreadyNear) {
                ClientUtils.sendDebugMessage("PestTrapManager: already near the trap position.")
                return
            }

            ClientUtils.sendDebugMessage("PestTrapManager: walking to traps at " + x + ", " + y + ", " + z)
            client.execute { PathfindingManager.startPathfind(client, x, y, z, false) }

            MacroWorkerThread.sleep(1000)
            val deadline = System.currentTimeMillis() + 30_000L
            while (PathfindingManager.isNavigating() && System.currentTimeMillis() < deadline &&
                isRunning && !shouldAbort()
            ) {
                MacroWorkerThread.sleep(200)
            }

            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop()
                ClientUtils.sendDebugMessage("PestTrapManager: pathfind to traps timed out, continuing anyway.")
            }
            MacroWorkerThread.sleep(300)
        }

        @Throws(InterruptedException::class)
        private fun teleportToTrapPlotIfNeeded(client: Minecraft, plot: String) {
            val currentPlot = ClientUtils.getCurrentPlot()
            val freshChatPlot = CommandUtils.getFreshKnownPlotChat()
            val scoreboardMatch = plot.equals(currentPlot, ignoreCase = true)
            val chatMatch = plot.equals(freshChatPlot, ignoreCase = true)

            if (scoreboardMatch || chatMatch) {
                val source = if (scoreboardMatch) "scoreboard" else "chat"
                ClientUtils.sendDebugMessage(
                    "Already on trap plot " + plot + " (via " + source + "), skipping plottp."
                )
                return
            }

            val posBefore = PestClientThread.call(
                client, Supplier { client.player?.position() }, null
            )
            CommandUtils.initiatePlotTp(plot)
            MacroWorkerThread.sleep(600)

            val tpDeadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < tpDeadline && isRunning && !shouldAbort()) {
                val teleported = posBefore == null || PestClientThread.call(
                    client,
                    Supplier {
                        val player = client.player
                        player != null && player.position().distanceTo(posBefore) > 5
                    },
                    false
                )!!
                if (teleported) {
                    break
                }
                MacroWorkerThread.sleep(200)
            }
            MacroWorkerThread.sleep(400)
        }

        @JvmStatic
        fun isBlockedByPestExchange(): Boolean {
            return PestExchangeManager.isExchanging() || AutoPestExchangeManager.isRunning()
        }

        private fun abortForPestExchange(client: Minecraft, operation: String): Boolean {
            if (!isBlockedByPestExchange()) {
                return false
            }

            ClientUtils.sendDebugMessage(
                "PestTrapManager: aborting trap " + operation + " because pest exchange is active."
            )
            isRunning = false
            ensureGuiClosed(client)
            PathfindingManager.stop()
            return true
        }

        private fun shouldAbort(): Boolean {
            return cancelRequested || MacroWorkerThread.getInstance().isCancelled()
        }

        @JvmStatic
        fun getFullTrapsFromTab(client: Minecraft?): List<Int> {
            if (client != null) {
                if (!client.isSameThread()) {
                    return PestClientThread.call(
                        client, Supplier { getFullTrapsFromTab(client) }, java.util.Collections.emptyList<Int>()
                    ) ?: emptyList()
                }
                return PestTrapTabParser.parseTrapIds(TablistUtils.getRawTabLines(client), FULL_TRAPS_PATTERN)
            }
            return emptyList()
        }

        private fun findVacuumHotbarSlot(client: Minecraft): Int {
            return PestLoadoutHelper.findVacuumHotbarSlot(client)
        }

        @Throws(InterruptedException::class)
        private fun openTrapGui(client: Minecraft, trapId: Int, trapEyePos: Vec3): Boolean {
            var attempt = 0
            while (attempt < 3 && isRunning && !shouldAbort()) {
                ensureGuiClosed(client)
                val target = getTrapInteractTarget(trapEyePos, attempt)
                if (attempt > 0) {
                    ClientUtils.sendDebugMessage(
                        "Retry interact attempt " + (attempt + 1) + " using y offset " +
                            String.format("%.1f", target.y - trapEyePos.y)
                    )
                }

                val rotationTime = if (attempt == 0) 200 else 150
                if (!aimAtTrap(client, target, rotationTime)) {
                    ClientUtils.sendDebugMessage("Could not aim at trap #" + trapId + ", retrying.")
                    attempt++
                    continue
                }
                PestClientThread.run(client, Runnable { ClientUtils.setKeyMappingState(client.options.keyUse, true) })
                MacroWorkerThread.sleep(100)
                PestClientThread.run(client, Runnable { ClientUtils.setKeyMappingState(client.options.keyUse, false) })

                val deadline = System.currentTimeMillis() + 1_500L
                while (System.currentTimeMillis() < deadline && isRunning && !shouldAbort()) {
                    val open = PestClientThread.call(
                        client,
                        Supplier {
                            val screen = client.gui.screen()
                            screen is AbstractContainerScreen<*> &&
                                screen.title.string.lowercase().contains("trap")
                        },
                        false
                    )
                    if (open == true) {
                        return true
                    }
                    MacroWorkerThread.sleep(100)
                }
                attempt++
            }
            ensureGuiClosed(client)
            return false
        }

        // initiateRotation silently no-ops when another rotation holds the lock or the failsafe suppresses
        // it, so aim is verified against the player's real angles rather than trusting the call landed
        @Throws(InterruptedException::class)
        private fun aimAtTrap(client: Minecraft, target: Vec3, rotationTime: Int): Boolean {
            val freeDeadline = System.currentTimeMillis() + 1_000L
            while (RotationManager.isRotating() && System.currentTimeMillis() < freeDeadline &&
                isRunning && !shouldAbort()
            ) {
                MacroWorkerThread.sleep(50)
            }
            if (RotationManager.isRotating()) {
                return false
            }

            PestClientThread.run(client, Runnable { RotationManager.initiateRotation(client, target, rotationTime.toLong()) })
            val settleDeadline = System.currentTimeMillis() + rotationTime + 800L
            while (System.currentTimeMillis() < settleDeadline && isRunning && !shouldAbort()) {
                if (!RotationManager.isRotating() && isAimedAtTrap(client, target)) {
                    MacroWorkerThread.sleep(150)
                    return isAimedAtTrap(client, target)
                }
                MacroWorkerThread.sleep(25)
            }

            RotationManager.cancelRotation()
            return false
        }

        private fun isAimedAtTrap(client: Minecraft, target: Vec3): Boolean {
            return PestClientThread.call(
                client,
                Supplier {
                    val player = client.player
                    player != null && RotationUtils.isLookingAt(
                        player.yRot,
                        player.xRot,
                        player.eyePosition,
                        target,
                        TRAP_AIM_TOLERANCE_DEGREES
                    )
                },
                false
            )!!
        }

        private fun clickCurrentScreenSlot(client: Minecraft, slot: Int) {
            PestClientThread.run(client, Runnable {
                val screen = client.gui.screen()
                if (screen is AbstractContainerScreen<*>) {
                    ClientUtils.performSlotClick(screen, slot, 0, ContainerInput.PICKUP)
                }
            })
        }

        private fun getTrapInteractTarget(baseTarget: Vec3, attempt: Int): Vec3 {
            val yOffset = when (attempt) {
                1 -> -0.3
                2 -> 0.3
                else -> 0.0
            }
            return baseTarget.add(0.0, yOffset, 0.0)
        }

        private fun findTrapTarget(client: Minecraft, trapId: Int): TrapTarget? {
            val level = client.level ?: return null
            val player = client.player ?: return null
            val target = "#" + trapId
            var closest: Entity? = null
            var closestDist = Double.MAX_VALUE

            for (entity in level.entitiesForRendering()) {
                if (entity !is ArmorStand) {
                    continue
                }

                val name = entity.name.string
                val cleanName = name.replace(Regex("(?i)\u00A7."), "").trim().lowercase()

                if (cleanName.endsWith(target) || cleanName.contains("trap " + target)) {
                    val dist = entity.distanceToSqr(player)
                    if (dist < closestDist) {
                        closestDist = dist
                        closest = entity
                    }
                }
            }
            val found = closest ?: return null
            val eyePosition = found.position().add(0.0, found.getEyeHeight() - 0.5, 0.0)
            return TrapTarget(eyePosition, Math.sqrt(closestDist))
        }

        private fun findReleasePestsSlot(screen: AbstractContainerScreen<*>): Int {
            for (i in screen.menu.slots.indices) {
                val slot = screen.menu.slots[i]
                if (!slot.hasItem()) {
                    continue
                }

                val stack = slot.item
                val name = stack.hoverName.string.lowercase()

                if (name.contains("release all pests")) {
                    val itemId = stack.item.toString().lowercase()
                    if (itemId.contains("lime") || itemId.contains("green") || itemId.contains("emerald") ||
                        itemId.contains("terracotta")
                    ) {
                        return i
                    }
                    return i
                }
            }
            return -1
        }

        private fun findBaitSlot(screen: AbstractContainerScreen<*>): Int {
            val containerSize = screen.menu.slots.size - 36
            for (i in 0 until containerSize) {
                val stack = screen.menu.slots[i].item
                if (!stack.isEmpty && stack.hoverName.string.lowercase().contains("trap bait")) {
                    return i
                }
            }
            return -1
        }

        private fun findBaitInInventory(screen: AbstractContainerScreen<*>, baitMaterial: String): Int {
            for (i in screen.menu.slots.indices) {
                val slot = screen.menu.slots[i]
                if (!slot.hasItem()) {
                    continue
                }

                val stack = slot.item
                val name = stack.hoverName.string.lowercase()
                if (name.contains(baitMaterial.lowercase())) {
                    return i
                }
            }
            return -1
        }

        @Throws(InterruptedException::class)
        private fun ensureMosquitoEquipped(client: Minecraft) {
            if (!AetherConfig.AUTO_MOSQUITO_FOR_PEST_TRAPS.get()) {
                return
            }
            BudgetAutopetManager.equipPetByName(client, "Mosquito", "pest traps")
        }

        @Throws(InterruptedException::class)
        private fun ensurePetEquippedAfterTrapOpen(client: Minecraft) {
            if (!AetherConfig.AUTO_PET_AFTER_TRAP_OPEN.get()) {
                return
            }
            BudgetAutopetManager.equipPetByName(
                client, AetherConfig.AUTO_PET_AFTER_TRAP_OPEN_PET.get(),
                "pest trap release"
            )
        }

        private fun waitForTrapGuiClosed(client: Minecraft) {
            val deadline = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < deadline && isRunning && !shouldAbort()) {
                val trapScreenOpen = PestClientThread.call(
                    client,
                    Supplier {
                        val screen = client.gui.screen()
                        screen is AbstractContainerScreen<*> &&
                            screen.title.string.lowercase().contains("trap")
                    },
                    false
                )
                if (trapScreenOpen != true) {
                    return
                }
                MacroWorkerThread.sleep(25)
            }
            ensureGuiClosed(client)
        }

        private fun ensureGuiClosed(client: Minecraft?) {
            if (client == null) {
                return
            }
            val closed = PestClientThread.call(
                client,
                Supplier {
                    if (client.player != null && client.gui.screen() != null) {
                        client.player?.closeContainer()
                        true
                    } else {
                        false
                    }
                },
                false
            )!!
            if (closed && !client.isSameThread()) {
                MacroWorkerThread.sleep(200)
            }
        }

        private fun snapshotLivePestIds(client: Minecraft): Set<Int> {
            val ids = HashSet<Int>()
            ids.addAll(
                PestClientThread.call(
                    client,
                    Supplier {
                        PestTargetTracker.getLoadedPests(client).stream()
                            .map { it.id }
                            .toList()
                    },
                    java.util.List.of<Int>()
                )!!
                .toSet()
            )
            return ids
        }

        // the interact right-click that opens a trap also vacuums the stack that trap releases, so one
        // confirmed kill means the release landed and the next trap's interact ray is clear again
        @Throws(InterruptedException::class)
        private fun awaitReleasedPestKill(client: Minecraft, preReleasePestIds: Set<Int>) {
            val deadline = System.currentTimeMillis() + RELEASED_PEST_KILL_TIMEOUT_MS
            var released: List<Entity> = java.util.List.of<Entity>()
            while (System.currentTimeMillis() < deadline && isRunning && !shouldAbort()) {
                released = PestClientThread.call(
                    client,
                    Supplier {
                        PestTargetTracker.getLoadedPests(client).stream()
                            .filter { pest -> !preReleasePestIds.contains(pest.id) }
                            .toList()
                    },
                    java.util.List.of<Entity>()
                )!!
                if (released.isNotEmpty()) {
                    break
                }
                MacroWorkerThread.sleep(50)
            }

            if (released.isEmpty()) {
                ClientUtils.sendDebugMessage("No released pests appeared after the trap release, continuing.")
                return
            }

            val tracked = released
            ClientUtils.sendDebugMessage("Waiting for one of " + tracked.size + " released pests to die.")
            while (System.currentTimeMillis() < deadline && isRunning && !shouldAbort()) {
                val killed = PestClientThread.call(
                    client,
                    Supplier { tracked.stream().anyMatch { isPestDead(it) } },
                    false
                )!!
                if (killed) {
                    ClientUtils.sendDebugMessage("Released pest confirmed dead, moving to the next trap.")
                    return
                }
                MacroWorkerThread.sleep(50)
            }
            ClientUtils.sendDebugMessage("Timed out waiting for a released pest to die, moving on anyway.")
        }

        private fun isPestDead(entity: Entity): Boolean {
            return entity.isRemoved || entity is LivingEntity && entity.isDeadOrDying
        }

        private class TrapTarget(private val eyePosition: Vec3, private val distance: Double) {
            fun eyePosition(): Vec3 = eyePosition

            fun distance(): Double = distance
        }
    }
}
