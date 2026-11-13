package dev.aether.modules

import dev.aether.config.AetherConfig
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import java.util.Locale

class SupercraftManager private constructor() {
    companion object {
        private const val SLOT_OPEN_SUPERCRAFT = 10
        private const val SLOT_CRAFT_AMOUNT = 32
        private const val ACTION_DELAY_MS = 500L
        private const val SHIFT_TO_CRAFT_CLICK_DELAY_MS = 500L
        private const val GUI_OPEN_DELAY_MS = 550L
        private const val NEXT_ITEM_DELAY_MS = 500L
        private const val STALL_TIMEOUT_MS = 5_000L
        @Volatile private var running = false
        @Volatile private var autoSequence = false
        @Volatile private var lastAutoRunCompletedMs = System.currentTimeMillis()
        @Volatile private var completionCallback: Runnable? = null
        @Volatile private var resumeFarmingOnFinish = false
        @Volatile private var currentItemIndex = 0
        @Volatile private var craftingStage = 0
        @Volatile private var nextActionAtMs = 0L
        @Volatile private var guiOpenedAtMs = 0L
        @Volatile private var lastProgressAtMs = 0L

        @JvmStatic fun isRunning() = running
        @JvmStatic fun reset() {
            running = false; autoSequence = false; completionCallback = null; resumeFarmingOnFinish = false
            currentItemIndex = 0; craftingStage = 0; nextActionAtMs = 0L; guiOpenedAtMs = 0L; lastProgressAtMs = 0L
        }
        @JvmStatic fun getAutoSupercraftElapsedMs(): Long = Math.max(0L, System.currentTimeMillis() - lastAutoRunCompletedMs)
        @JvmStatic fun shouldRunAutoSupercraft(): Boolean {
            if (!AetherConfig.AUTO_SUPERCRAFT.get() || running) return false
            if (getConfiguredItems().isEmpty()) return false
            val intervalMs = Math.max(1L, AetherConfig.AUTO_SUPERCRAFT_INTERVAL_MINUTES.get().toLong()) * 60_000L
            return getAutoSupercraftElapsedMs() >= intervalMs
        }
        @JvmStatic fun runAutoSupercraftIfDue(onComplete: Runnable) {
            if (!shouldRunAutoSupercraft()) { runCompletion(onComplete); return }
            autoSequence = true; completionCallback = onComplete; start(false)
        }
        @JvmStatic fun update() {
            if (!running) return
            val lastProgress = lastProgressAtMs
            if (lastProgress <= 0L) { markProgress(); return }
            val stalledMs = System.currentTimeMillis() - lastProgress
            if (stalledMs < STALL_TIMEOUT_MS) return
            failAndContinue("stalled for ${stalledMs}ms")
        }
        @JvmStatic fun manualTrigger() { start(true) }
        @Synchronized private fun start(manual: Boolean) {
            val client = Minecraft.getInstance()
            if (client.player == null || client.level == null) { runCompletionIfAuto(); return }
            if (running) { ClientUtils.sendMessage("\u00A7cAuto Supercraft is already running.", false); runCompletionIfAuto(); return }
            val items = getConfiguredItems()
            if (items.isEmpty()) { ClientUtils.sendMessage("\u00A7cNo supercraft items configured.", false); runCompletionIfAuto(); return }
            resumeFarmingOnFinish = manual && MacroStateManager.getCurrentState() == MacroState.State.FARMING
            if (resumeFarmingOnFinish) client.execute { FarmingMacroManager.disable(client) }
            running = true; currentItemIndex = 0; craftingStage = 0; nextActionAtMs = 0L; guiOpenedAtMs = 0L; markProgress()
            ClientUtils.sendMessage("\u00A7eStarting Auto Supercraft...", false); sendRecipeCommand()
        }
        @JvmStatic fun handleRecipeGui(screen: AbstractContainerScreen<*>) {
            val client = Minecraft.getInstance()
            if (!running || client.player == null) return
            val items = getConfiguredItems()
            if (currentItemIndex < 0 || currentItemIndex >= items.size) { finish(); return }
            val now = System.currentTimeMillis()
            if (now < nextActionAtMs) return
            val currentItem = items[currentItemIndex]
            val normalizedCurrentItem = currentItem.lowercase(Locale.ROOT)
            val title = TablistUtils.stripColors(screen.title.string).lowercase(Locale.ROOT)
            if (!title.contains(normalizedCurrentItem)) { guiOpenedAtMs = 0L; return }
            if (guiOpenedAtMs == 0L) { guiOpenedAtMs = now; markProgress(now) }
            if (now - guiOpenedAtMs < GUI_OPEN_DELAY_MS) return
            if (screen.menu.slots.size <= SLOT_CRAFT_AMOUNT) return
            when (craftingStage) {
                0 -> {
                    val supercraftSlot = findExactRecipeSlot(screen, currentItem)
                    ClientUtils.sendDebugMessage("Supercraft: opening supercraft for $currentItem using slot $supercraftSlot")
                    ClientUtils.performSlotClick(screen, supercraftSlot, 0, ContainerInput.PICKUP)
                    markProgress(now); nextActionAtMs = now + ACTION_DELAY_MS; craftingStage = 1
                }
                1 -> {
                    ClientUtils.sendDebugMessage("Supercraft: maximizing craft amount for $currentItem")
                    ClientUtils.performSlotClick(screen, SLOT_CRAFT_AMOUNT, 0, ContainerInput.QUICK_MOVE)
                    markProgress(now); nextActionAtMs = now + SHIFT_TO_CRAFT_CLICK_DELAY_MS; craftingStage = 2
                }
                2 -> {
                    ClientUtils.sendDebugMessage("Supercraft: crafting $currentItem")
                    ClientUtils.performSlotClick(screen, SLOT_CRAFT_AMOUNT, 0, ContainerInput.PICKUP)
                    markProgress(now); nextActionAtMs = now + ACTION_DELAY_MS; craftingStage = 3
                }
                3 -> advanceToNextItem()
            }
        }
        private fun advanceToNextItem() {
            val client = Minecraft.getInstance() ?: return
            client.execute { val player = client.player; if (player != null) player.closeContainer() }
            val items = getConfiguredItems(); currentItemIndex++; craftingStage = 0; guiOpenedAtMs = 0L
            nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS; markProgress()
            if (currentItemIndex >= items.size) { finish(); return }
            MacroWorkerThread.getInstance().submit("AutoSupercraft-Next") {
                MacroWorkerThread.sleep(NEXT_ITEM_DELAY_MS)
                if (shouldAbort()) return@submit
                sendRecipeCommand()
            }
        }
        private fun sendRecipeCommand() {
            val items = getConfiguredItems()
            if (currentItemIndex < 0 || currentItemIndex >= items.size) { finish(); return }
            val item = items[currentItemIndex]
            ClientUtils.sendDebugMessage("Supercraft: sending /recipe $item"); markProgress(); ClientUtils.sendCommand("/recipe $item")
        }
        private fun finish() {
            val client = Minecraft.getInstance(); val shouldResumeFarming = resumeFarmingOnFinish
            if (autoSequence) lastAutoRunCompletedMs = System.currentTimeMillis()
            val callback = completionCallback; reset(); runCompletion(callback)
            if (shouldResumeFarming && MacroStateManager.isMacroRunning()) client.execute { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) }
            if (client.player != null) ClientUtils.sendMessage("\u00A7aAuto Supercraft finished.", false)
        }
        @Synchronized private fun failAndContinue(reason: String) {
            if (!running) return
            val client = Minecraft.getInstance(); ClientUtils.sendDebugMessage("Supercraft: $reason. Continuing handoff.")
            val shouldResumeFarming = resumeFarmingOnFinish
            if (autoSequence) lastAutoRunCompletedMs = System.currentTimeMillis()
            val callback = completionCallback; reset()
            if (client != null) client.execute { val player = client.player; if (player != null) player.closeContainer() }
            runCompletion(callback)
            if (shouldResumeFarming && client != null && MacroStateManager.isMacroRunning()) client.execute { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) }
        }
        private fun markProgress() { markProgress(System.currentTimeMillis()) }
        private fun markProgress(now: Long) { lastProgressAtMs = now }
        private fun runCompletionIfAuto() { val callback = completionCallback; completionCallback = null; autoSequence = false; runCompletion(callback) }
        private fun runCompletion(callback: Runnable?) { callback?.run() }
        private fun getConfiguredItems(): List<String> = ArrayList(AetherConfig.AUTO_SUPERCRAFT_ITEMS.get().map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        private fun findExactRecipeSlot(screen: AbstractContainerScreen<*>, itemName: String): Int {
            val targetName = normalizeItemName(itemName)
            val containerSize = if (screen.menu.slots.size > 36) screen.menu.slots.size - 36 else screen.menu.slots.size
            for (i in 0 until containerSize) {
                val slot = screen.menu.slots[i]
                if (!slot.hasItem()) continue
                val slotName = normalizeItemName(slot.item.hoverName.string)
                if (slotName == targetName) return i
            }
            return SLOT_OPEN_SUPERCRAFT
        }
        private fun normalizeItemName(itemName: String): String = TablistUtils.stripColors(itemName).replace('\u00A0', ' ').trim().lowercase(Locale.ROOT)
        private fun shouldAbort(): Boolean {
            val client = Minecraft.getInstance()
            return !running || client == null || client.player == null || client.level == null
        }
    }
}
