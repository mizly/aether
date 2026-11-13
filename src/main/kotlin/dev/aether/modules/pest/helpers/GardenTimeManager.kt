package dev.aether.modules.pest.helpers

import dev.aether.macro.MacroWorkerThread
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GardenTimeManager private constructor() {
    companion object {
        private const val DAYTIME_SLOT = 11
        private const val NIGHTTIME_SLOT = 13
        private const val DAYTIME_MARKER = '\u2600'
        private const val NIGHTTIME_MARKER = '\u263D'
        @Volatile private var switchingGardenTime = false

        @JvmStatic fun switchToDaytime(client: Minecraft): Boolean {
            if (isDaytime(client)) {
                ClientUtils.sendDebugMessage("GardenTimeManager: daytime already active, skipping.")
                return true
            }
            return switchGardenTime(client, DAYTIME_SLOT, "daytime")
        }

        @JvmStatic fun switchToNightTime(client: Minecraft): Boolean {
            if (isNightTime(client)) {
                ClientUtils.sendDebugMessage("GardenTimeManager: night time already active, skipping.")
                return true
            }
            return switchGardenTime(client, NIGHTTIME_SLOT, "night time")
        }

        @JvmStatic fun isDaytime(client: Minecraft): Boolean = sidebarContains(client, DAYTIME_MARKER)
        @JvmStatic fun isNightTime(client: Minecraft): Boolean = sidebarContains(client, NIGHTTIME_MARKER)
        @JvmStatic fun isSwitchingGardenTime(): Boolean = switchingGardenTime

        private fun sidebarContains(client: Minecraft?, marker: Char): Boolean {
            if (client == null || client.player == null || client.connection == null) return false
            if (!client.isSameThread) return PestClientThread.call(client, java.util.function.Supplier { sidebarContains(client, marker) }, false)!!
            for (line in ClientUtils.getSidebarLines()) if (line.indexOf(marker) >= 0) return true
            return false
        }

        private fun switchGardenTime(client: Minecraft?, timeSlot: Int, label: String): Boolean {
            if (client == null || client.player == null || client.connection == null) return false
            ClientUtils.sendDebugMessage("GardenTimeManager: switching garden time to " + label)
            switchingGardenTime = true
            try {
                ClientUtils.sendCommand("/islandtime")
                if (!waitForScreenTitle(client, "garden time", 5000L)) {
                    ClientUtils.sendDebugMessage("GardenTimeManager: garden time GUI did not open in time.")
                    return false
                }
                if (!MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true))) return false
                if (!clickSlot(client, timeSlot)) {
                    ClientUtils.sendDebugMessage("GardenTimeManager: failed to click time slot " + timeSlot)
                    return false
                }
                if (!MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false))) return false
                client.execute(Runnable { client.player?.closeContainer() })
                if (!waitForContainerClosed(client, 2000L)) ClientUtils.sendDebugMessage("GardenTimeManager: garden time GUI did not close in time.")
                return true
            } finally {
                switchingGardenTime = false
            }
        }

        private fun waitForContainerClosed(client: Minecraft, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (PestClientThread.call(client, java.util.function.Supplier { client.gui.screen() !is AbstractContainerScreen<*> }, true)!!) return true
                if (!MacroWorkerThread.sleep(50)) return false
            }
            return false
        }

        private fun waitForScreenTitle(client: Minecraft, expectedFragment: String, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            val expected = expectedFragment.lowercase()
            while (System.currentTimeMillis() < deadline) {
                if (PestClientThread.call(client, java.util.function.Supplier {
                    val screen = client.gui.screen()
                    screen is AbstractContainerScreen<*> && screen.title.string.lowercase().contains(expected)
                }, false)!!) return true
                if (!MacroWorkerThread.sleep(50)) return false
            }
            return false
        }

        private fun clickSlot(client: Minecraft, slotId: Int): Boolean {
            if (client.isSameThread) return clickSlotOnClientThread(client, slotId)
            val latch = CountDownLatch(1)
            val clicked = booleanArrayOf(false)
            client.execute(Runnable {
                try { clicked[0] = clickSlotOnClientThread(client, slotId) }
                finally { latch.countDown() }
            })
            try { return latch.await(1000L, TimeUnit.MILLISECONDS) && clicked[0] }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }

        private fun clickSlotOnClientThread(client: Minecraft, slotId: Int): Boolean {
            val screen = client.gui.screen()
            if (screen is AbstractContainerScreen<*> && slotId >= 0 && slotId < screen.menu.slots.size) {
                ClientUtils.performSlotClick(screen, slotId, 0, ContainerInput.PICKUP)
                return true
            }
            return false
        }
    }
}
