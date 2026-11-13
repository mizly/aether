package dev.aether.modules.pest.helpers

import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

class VinylManager private constructor() {
    companion object {
        @JvmStatic fun setVinyl(client: Minecraft, targetVinyl: String?): Boolean {
            if (client.player == null || targetVinyl == null) return false
            val guiDelay = ClientUtils.getGuiClickDelayMs(true)
            if (isTargetVinylPlaying(client, targetVinyl)) { ClientUtils.sendDebugMessage("VinylManager: '" + targetVinyl + "' already playing, skipping."); return true }
            if (!holdVacuum(client)) { ClientUtils.sendMessage("§cVacuum not found in hotbar. Cannot set vinyl."); return false }
            ClientUtils.sendDebugMessage("VinylManager: holding shift")
            PestClientThread.run(client, Runnable { ClientUtils.performShiftLeftClick() })
            ClientUtils.sendDebugMessage("VinylManager: left click sent")
            val deadline = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < deadline) {
                if (isStereoGuiOpen(client)) break
                MacroWorkerThread.sleep(100)
            }
            ClientUtils.releaseShiftKey()
            MacroWorkerThread.sleep(guiDelay)
            ClientUtils.sendDebugMessage("VinylManager: shift released")
            if (!isStereoGuiOpen(client)) { ClientUtils.sendMessage("§cStereo Harmony GUI did not open."); return false }
            ClientUtils.sendDebugMessage("VinylManager: GUI open confirmed")
            MacroWorkerThread.sleep(guiDelay)
            val result = handleStereoGui(client, targetVinyl, guiDelay)
            client.execute {
                if (client.gui.screen() != null && client.player != null) client.player!!.closeContainer()
            }
            MacroWorkerThread.sleep(200)
            return result
        }

        @JvmStatic fun isTargetVinylPlaying(client: Minecraft?, targetVinyl: String?): Boolean {
            if (client == null || client.player == null || targetVinyl == null) return false
            if (!client.isSameThread) return PestClientThread.call(client, java.util.function.Supplier { isTargetVinylPlaying(client, targetVinyl) }, false)!!
            return isVinylPlayingFromHotbarLore(client, targetVinyl)
        }

        private fun isVinylPlayingFromHotbarLore(client: Minecraft, targetVinyl: String): Boolean {
            val vacuum = findVacuumStack(client) ?: return false
            val targetLower = targetVinyl.lowercase()
            return try {
                vacuum.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)
                    .map { TablistUtils.stripColors(it.string) }.map { it.replace('\u00A0', ' ').trim() }
                    .filter { it.lowercase().startsWith("now playing:") }.any { it.lowercase().contains(targetLower) }
            } catch (_: Exception) { false }
        }

        private fun handleStereoGui(client: Minecraft, targetVinyl: String, guiDelay: Long): Boolean {
            val selection: StereoSelection = PestClientThread.call(client, java.util.function.Supplier { inspectStereoGui(client, targetVinyl) }, StereoSelection.NOT_FOUND)!!
            if (selection.alreadyPlaying()) { ClientUtils.sendDebugMessage("VinylManager: '" + targetVinyl + "' already playing, skipping."); return true }
            if (selection.slot() < 0) { ClientUtils.sendMessage("§cVinyl '" + targetVinyl + "' not found in Stereo Harmony."); return false }
            PestClientThread.run(client, Runnable {
                val screen = client.gui.screen()
                if (screen is AbstractContainerScreen<*>) ClientUtils.performSlotClick(screen, selection.slot(), 0, ContainerInput.PICKUP)
            })
            MacroWorkerThread.sleep(guiDelay)
            return true
        }

        private fun isStereoGuiOpen(client: Minecraft): Boolean {
            if (!client.isSameThread) return PestClientThread.call(client, java.util.function.Supplier { isStereoGuiOpen(client) }, false)!!
            val screen = client.gui.screen()
            if (screen !is AbstractContainerScreen<*>) return false
            return TablistUtils.stripColors(screen.title.string).lowercase().contains("stereo")
        }

        private fun holdVacuum(client: Minecraft): Boolean {
            if (client.player == null) return false
            val slot = PestClientThread.call(client, java.util.function.Supplier { findVacuumSlot(client) }, -1)!!
            if (slot < 0) return false
            PestClientThread.run(client, Runnable { if (client.player != null) FailsafeManager.selectHotbarSlot(client, slot) })
            MacroWorkerThread.sleep(150)
            return true
        }

        private fun findVacuumStack(client: Minecraft): ItemStack? {
            if (client.player == null) return null
            for (i in 0 until 9) {
                val stack = client.player!!.inventory.getItem(i)
                if (stack != null && !stack.isEmpty && isVacuumStack(stack)) return stack
            }
            return null
        }

        private fun findVacuumSlot(client: Minecraft): Int {
            if (client.player == null) return -1
            for (i in 0 until 9) {
                val stack = client.player!!.inventory.getItem(i)
                if (stack != null && !stack.isEmpty && isVacuumStack(stack)) return i
            }
            return -1
        }

        private fun inspectStereoGui(client: Minecraft, targetVinyl: String): StereoSelection {
            val screen = client.gui.screen()
            if (screen !is AbstractContainerScreen<*>) return StereoSelection.NOT_FOUND
            val targetLower = targetVinyl.lowercase()
            for (i in screen.menu.slots.indices) {
                val slot: Slot = screen.menu.slots[i]
                if (!slot.hasItem()) continue
                val stack = slot.item
                val name = TablistUtils.stripColors(stack.hoverName.string).lowercase()
                if (!name.contains(targetLower)) continue
                val playing = stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)
                    .map(Component::getString).map(TablistUtils::stripColors).any { it.contains("PLAYING") }
                return StereoSelection(i, playing)
            }
            return StereoSelection.NOT_FOUND
        }

        private fun isVacuumStack(stack: ItemStack): Boolean = TablistUtils.stripColors(stack.hoverName.string).lowercase().contains("vacuum")

        private class StereoSelection(private val slot: Int, private val alreadyPlaying: Boolean) {
            fun slot(): Int = slot
            fun alreadyPlaying(): Boolean = alreadyPlaying
            companion object { val NOT_FOUND = StereoSelection(-1, false) }
        }
    }
}
