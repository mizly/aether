package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.mixin.AccessorInventory
import dev.aether.notification.NotificationManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

internal object InventorySlotFailsafe {
    enum class State { IDLE, WAIT, TRIGGERED }

    private const val EXPECTED_SLOT_GRACE_MS = 250L
    @Volatile private var expectedSelectedSlot = -1
    @Volatile private var expectedSelectedSlotAt = 0L
    @Volatile private var slotMismatchSince = 0L
    @Volatile private var slotMismatchRandomDelayMs = 0L
    @Volatile private var triggered = false

    fun reset() {
        expectedSelectedSlot = -1
        expectedSelectedSlotAt = 0L
        slotMismatchSince = 0L
        slotMismatchRandomDelayMs = 0L
        triggered = false
    }

    fun syncSelectedSlotFromClient(client: Minecraft?) {
        if (client?.player == null) return
        expectedSelectedSlot = (client.player!!.getInventory() as AccessorInventory).getSelected()
        expectedSelectedSlotAt = System.currentTimeMillis()
        slotMismatchSince = 0L
        slotMismatchRandomDelayMs = 0L
        triggered = false
    }

    fun expectSelectedHotbarSlot(slot: Int) {
        if (slot !in 0..8) return
        expectedSelectedSlot = slot
        expectedSelectedSlotAt = System.currentTimeMillis()
        slotMismatchSince = 0L
        slotMismatchRandomDelayMs = 0L
    }

    fun selectHotbarSlot(client: Minecraft?, slot: Int) {
        if (client?.player == null || slot !in 0..8) return
        expectSelectedHotbarSlot(slot)
        val inventory = client.player!!.getInventory() as AccessorInventory
        if (inventory.getSelected() == slot) return
        if (client.isSameThread) inventory.setSelected(slot)
        ClientUtils.performHotbarSlotClick(slot)
    }

    fun tick(client: Minecraft?) {
        if (client?.player == null) { reset(); return }
        if (!MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() == MacroState.State.RECOVERING) {
            syncSelectedSlotFromClient(client); return
        }
        if (!AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.get()) {
            slotMismatchSince = 0L
            if (expectedSelectedSlot !in 0..8) syncSelectedSlotFromClient(client)
            return
        }
        val currentSelectedSlot = (client.player!!.getInventory() as AccessorInventory).getSelected()
        if (expectedSelectedSlot !in 0..8) {
            expectedSelectedSlot = currentSelectedSlot
            expectedSelectedSlotAt = System.currentTimeMillis()
            slotMismatchSince = 0L
            slotMismatchRandomDelayMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (currentSelectedSlot == expectedSelectedSlot) {
            slotMismatchSince = 0L; slotMismatchRandomDelayMs = 0L; return
        }
        if (now - expectedSelectedSlotAt < EXPECTED_SLOT_GRACE_MS) {
            slotMismatchSince = 0L; slotMismatchRandomDelayMs = 0L; return
        }
        if (slotMismatchSince == 0L) {
            slotMismatchSince = now
            slotMismatchRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs()
            return
        }
        val triggerDelayMs = Math.round(AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.get() * 1000.0f).toLong() + slotMismatchRandomDelayMs
        if (now - slotMismatchSince >= triggerDelayMs) trigger(client, expectedSelectedSlot, currentSelectedSlot)
    }

    fun getExpectedSelectedSlot() = expectedSelectedSlot
    fun getCurrentSelectedSlot(client: Minecraft?) = if (client?.player == null) -1 else (client.player!!.getInventory() as AccessorInventory).getSelected()
    fun getState(client: Minecraft?): State {
        if (triggered) return State.TRIGGERED
        if (client?.player == null || !MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() == MacroState.State.RECOVERING || !AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.get()) return State.IDLE
        val currentSelectedSlot = getCurrentSelectedSlot(client)
        return if (expectedSelectedSlot !in 0..8 || currentSelectedSlot == expectedSelectedSlot) State.IDLE else State.WAIT
    }
    fun getTriggerRemainingMs(): Long {
        if (slotMismatchSince == 0L) return 0L
        val delay = Math.round(AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.get() * 1000.0f).toLong() + slotMismatchRandomDelayMs
        return maxOf(0L, delay - (System.currentTimeMillis() - slotMismatchSince))
    }

    private fun trigger(client: Minecraft, expectedSlot: Int, currentSlot: Int) {
        if (triggered) return
        val action = FailsafeManager.getInventorySlotChangedAction()
        triggered = true
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), "Selected hotbar slot changed unexpectedly.")
        FailsafeManager.handleConfiguredAction(client, action, FailsafeCustomReplayManager.FailsafeReplayType.INVENTORY_SLOT,
            "selected hotbar slot changed unexpectedly. Expected slot ${expectedSlot + 1}, got slot ${currentSlot + 1}.",
            "InventorySlotFailsafe: selected hotbar slot changed unexpectedly")
        reset()
    }
}
