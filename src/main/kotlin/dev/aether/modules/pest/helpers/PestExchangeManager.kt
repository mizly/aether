package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.EntityUtils
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

// plottp barn, walk to phillip's desk, interact, then click empty vacuum bag in the pesthunter gui
object PestExchangeManager {
    private const val MAX_ABIPHONE_CALL_ATTEMPTS = 3
    private const val ABIPHONE_GUI_WAIT_MS = 10000L

    @Volatile private var isExchanging = false
    @Volatile private var interactionStage = 0
    @Volatile private var interactionTime = 0L

    @JvmStatic fun isExchanging(): Boolean = isExchanging

    @JvmStatic
    fun start(client: Minecraft) {
        if (client.player == null) return
        MacroWorkerThread.getInstance().submit("PestExchange") { runExchangeBlocking(client) }
    }

    @JvmStatic
    fun runExchangeBlocking(client: Minecraft): Boolean {
        if (isExchanging) {
            ClientUtils.sendMessage("§cPest exchange is already running.", false)
            return false
        }
        if (!hasPlayer(client)) return false
        isExchanging = true
        interactionStage = 0
        interactionTime = 0
        ClientUtils.sendMessage("§eStarting pest exchange...", false)
        try {
            return runExchange(client)
        } catch (e: Exception) {
            client.execute {
                if (client.player != null) ClientUtils.sendMessage("§cPest exchange error: ${e.message}", false)
            }
            e.printStackTrace()
            return false
        } finally {
            isExchanging = false
            interactionStage = 0
            PathfindingManager.stop()
        }
    }

    @JvmStatic
    fun stop() {
        isExchanging = false
        interactionStage = 0
        PathfindingManager.stop()
    }

    @JvmStatic
    fun reset() {
        isExchanging = false
        interactionStage = 0
        interactionTime = 0
    }

    private fun runExchange(client: Minecraft): Boolean {
        if (PestClientThread.call(client, { client.gui.screen() != null }, false)!!) {
            PestClientThread.run(client) { client.player?.closeContainer() }
            MacroWorkerThread.sleep(500)
        }
        if (!isExchanging) return false
        if (AetherConfig.AUTO_PEST_USE_ABIPHONE.get()) return runAbiphoneExchange(client)
        GearManager.swapToFarmingToolSync(client)

        ClientUtils.sendDebugMessage("[PestExchange] Teleporting to barn...")
        client.execute { if (client.player != null) ClientUtils.sendMessage("§eTeleporting to barn...", false) }
        val posBefore: Vec3 = PestClientThread.call(client, { client.player?.position() }, Vec3.ZERO)!!
        dev.aether.modules.failsafe.FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get().toLong())
        ClientUtils.sendCommand("/plottp barn")
        MacroWorkerThread.sleep(600)
        val tpDeadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < tpDeadline && isExchanging) {
            if (PestClientThread.call(client, { val p = client.player; p != null && p.position().distanceTo(posBefore) > 5 }, false) == true) break
            MacroWorkerThread.sleep(200)
        }
        if (!isExchanging) return false
        MacroWorkerThread.sleep(500)

        if (AetherConfig.PEST_EXCHANGE_PATHFIND.get()) {
            val deskX = AetherConfig.PEST_EXCHANGE_DESK_X.get()
            val deskY = AetherConfig.PEST_EXCHANGE_DESK_Y.get()
            val deskZ = AetherConfig.PEST_EXCHANGE_DESK_Z.get()
            ClientUtils.sendDebugMessage("[PestExchange] Walking to desk at $deskX, $deskY, $deskZ")
            client.execute { if (client.player != null) ClientUtils.sendMessage("§eWalking to Phillip's desk...", false) }
            client.execute { PathfindingManager.startPathfind(client, deskX, deskY, deskZ, false) }
            MacroWorkerThread.sleep(1000)
            val pathDeadline = System.currentTimeMillis() + 30000
            while (PathfindingManager.isNavigating() && System.currentTimeMillis() < pathDeadline && isExchanging) MacroWorkerThread.sleep(200)
            if (!isExchanging) return false
            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop()
                client.execute { if (client.player != null) ClientUtils.sendMessage("§cPathfinding timed out. Stopping pest exchange.", false) }
                isExchanging = false
                return false
            }
            MacroWorkerThread.sleep(300)
        }

        ClientUtils.sendDebugMessage("[PestExchange] Looking for Phillip...")
        var phillipTarget = findPhillipTarget(client)
        if (phillipTarget == null) {
            client.execute { if (client.player != null) ClientUtils.sendMessage("§ePhillip not found, retrying in 3s...", false) }
            MacroWorkerThread.sleep(3000)
            if (!isExchanging) return false
            phillipTarget = findPhillipTarget(client)
        }
        if (phillipTarget == null) {
            client.execute { if (client.player != null) ClientUtils.sendMessage("§cCould not find Phillip NPC after retry. Stopping.", false) }
            isExchanging = false
            return false
        }
        ClientUtils.sendDebugMessage("[PestExchange] Found Phillip, rotating...")
        GearManager.swapToFarmingToolSync(client)
        facePhillipForInteraction(client, phillipTarget)
        ClientUtils.sendDebugMessage("[PestExchange] Interacting with Phillip...")
        PestClientThread.run(client) { ClientUtils.performAttackClick() }

        interactionStage = 1
        var guiDeadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < guiDeadline && isExchanging) {
            if (isPesthunterGuiOpen(client)) break
            MacroWorkerThread.sleep(200)
        }
        if (!isExchanging) return false
        if (!isPesthunterGuiOpen(client)) {
            ClientUtils.sendDebugMessage("[PestExchange] GUI didn't open, retrying click...")
            facePhillipForInteraction(client, phillipTarget)
            PestClientThread.run(client) { ClientUtils.performUseClick() }
            guiDeadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < guiDeadline && isExchanging) {
                if (isPesthunterGuiOpen(client)) break
                MacroWorkerThread.sleep(200)
            }
        }
        if (!isPesthunterGuiOpen(client)) {
            client.execute { if (client.player != null) ClientUtils.sendMessage("§cFailed to open Phillip's GUI. Stopping.", false) }
            isExchanging = false
            return false
        }
        val exchanged = handlePesthunterGuiActions(client)
        isExchanging = false
        return exchanged
    }

    private fun runAbiphoneExchange(client: Minecraft): Boolean {
        for (attempt in 1..MAX_ABIPHONE_CALL_ATTEMPTS) {
            if (!isExchanging) break
            ClientUtils.sendDebugMessage("PestExchange: calling Phillip via Abiphone, attempt $attempt/$MAX_ABIPHONE_CALL_ATTEMPTS")
            client.execute { ClientUtils.sendMessage("§eCalling Phillip via Abiphone ($attempt/$MAX_ABIPHONE_CALL_ATTEMPTS)...", false) }
            ClientUtils.sendCommand("/call phillip")
            if (waitForPesthunterGui(client, ABIPHONE_GUI_WAIT_MS)) {
                ClientUtils.sendDebugMessage("PestExchange: Pesthunter GUI opened via Abiphone")
                val exchanged = handlePesthunterGuiActions(client)
                isExchanging = false
                return exchanged
            }
        }
        if (isExchanging) {
            client.execute {
                if (client.player != null) ClientUtils.sendMessage("§cCould not open Phillip's GUI after 3 Abiphone calls. Stopping pest exchange.", false)
            }
        }
        isExchanging = false
        return false
    }

    private fun waitForPesthunterGui(client: Minecraft, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && isExchanging) {
            if (isPesthunterGuiOpen(client)) return true
            MacroWorkerThread.sleep(200)
        }
        return false
    }

    private fun isPesthunterGuiOpen(client: Minecraft): Boolean {
        if (!client.isSameThread) return PestClientThread.call(client, { isPesthunterGuiOpen(client) }, false)!!
        val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
        val title = screen.title.string.lowercase()
        return title.contains("pesthunter") || title.contains("phillip")
    }

    // assumes the pesthunter gui is open, reports whether the bag actually reached phillip
    private fun handlePesthunterGuiActions(client: Minecraft): Boolean {
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true))
        val exchanged = PestClientThread.call(client, { emptyVacuumBag(client) }, false)!!
        MacroWorkerThread.sleep(1500)
        client.execute { if (client.gui.screen() != null) client.player?.closeContainer() }
        MacroWorkerThread.sleep(300)
        client.execute {
            if (client.player != null) ClientUtils.sendMessage(if (exchanged) "§aPest exchange complete!" else "§cPest exchange did not go through.", false)
        }
        return exchanged
    }

    private fun emptyVacuumBag(client: Minecraft): Boolean {
        val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
        val player = client.player ?: return false
        val vacuumSlot = findVacuumSlot(screen)
        if (vacuumSlot == -1) {
            ClientUtils.sendMessage("§cCould not find 'Empty Vacuum Bag' slot. Closing.", false)
            player.closeContainer()
            return false
        }
        val vacuumStack: ItemStack = screen.menu.slots[vacuumSlot].item
        val tooltipLines = vacuumStack.getTooltipLines(Item.TooltipContext.EMPTY, player, net.minecraft.world.item.TooltipFlag.NORMAL)
        val lore = tooltipLinesToString(tooltipLines)
        if (lore.contains("Click to empty")) {
            ClientUtils.sendMessage("§aEmptying vacuum bag!", false)
            ClientUtils.performSlotClick(screen, vacuumSlot, 0, ContainerInput.PICKUP)
            return true
        }
        if (lore.contains("exchanged enough Pests")) {
            ClientUtils.sendMessage("§eAlready emptied the vacuum recently!", false)
            player.closeContainer()
            return true
        }
        ClientUtils.sendMessage("§cVacuum bag state unknown. Closing.", false)
        player.closeContainer()
        return false
    }

    private fun findVacuumSlot(screen: AbstractContainerScreen<*>): Int {
        for (i in screen.menu.slots.indices) {
            val slot: Slot = screen.menu.slots[i]
            if (!slot.hasItem()) continue
            val name = slot.item.hoverName.string.replace(Regex("(?i)§."), "").lowercase()
            if (name.contains("empty vacuum") || name.contains("vacuum bag")) return i
        }
        return -1
    }

    private fun findPhillipTarget(client: Minecraft): Vec3? = PestClientThread.call(client, {
        val phillip: Entity? = EntityUtils.findEntity(client, "Phillip")
        phillip?.eyePosition
    }, null)

    private fun hasPlayer(client: Minecraft?): Boolean = client != null && PestClientThread.call(client, { client.player != null }, false)!!

    private fun facePhillipForInteraction(client: Minecraft, phillipTarget: Vec3) {
        rotateToPhillip(client, phillipTarget)
        waitForRotationToFinish()
        if (!isLookingAt(client, phillipTarget, AetherConfig.PEST_EXCHANGE_FOV_RANGE.get())) MacroWorkerThread.sleep(100)
    }

    private fun rotateToPhillip(client: Minecraft, phillipTarget: Vec3) {
        client.execute { RotationManager.initiateRotation(client, phillipTarget, AetherConfig.ROTATION_TIME.get().toLong(), AetherConfig.PEST_EXCHANGE_FOV_RANGE.get()) }
        MacroWorkerThread.sleep(AetherConfig.ROTATION_TIME.get() + 50L)
    }

    private fun waitForRotationToFinish() {
        val deadline = System.currentTimeMillis() + 1500L
        while (RotationManager.isRotating() && System.currentTimeMillis() < deadline) MacroWorkerThread.sleep(25)
    }

    private fun isLookingAt(client: Minecraft, target: Vec3, tolerance: Float): Boolean = PestClientThread.call(client, {
        val p = client.player; p != null && RotationUtils.isLookingAt(p.yRot, p.xRot, p.eyePosition, target, tolerance)
    }, false)!!

    private fun tooltipLinesToString(lines: List<Component>): String = buildString {
        for (component in lines) append(component.string).append(' ')
    }
}
