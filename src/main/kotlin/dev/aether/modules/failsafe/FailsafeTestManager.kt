package dev.aether.modules.failsafe

import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import java.util.Locale

object FailsafeTestManager {
    private const val COUNTDOWN_TICKS = 100
    private const val TICKS_PER_SECOND = 20
    private var pendingTest: PendingTest? = null
    private var activeGuiFlashScreen: InvisibleContainerScreen? = null
    private var activeGuiFlashTicksRemaining = 0

    @JvmStatic fun scheduleRotation(pitchDelta: Float, yawDelta: Float) {
        val client = Minecraft.getInstance(); if (client.player == null) return
        pendingTest = RotationTest(pitchDelta, yawDelta); ClientUtils.sendMessage("§eRotation failsafe test in 5 seconds.", false)
    }
    @JvmStatic fun scheduleGuiFlash(durationTicks: Int) {
        val client = Minecraft.getInstance(); if (client.player == null) return
        pendingTest = GuiFlashTest(maxOf(1, durationTicks)); ClientUtils.sendMessage("§eGUI failsafe test in 5 seconds.", false)
    }
    @JvmStatic fun scheduleInventorySlot(slot: Int) {
        val client = Minecraft.getInstance(); if (client.player == null) return
        val zeroBasedSlot = slot - 1; if (zeroBasedSlot !in 0..8) return
        pendingTest = InventorySlotTest(zeroBasedSlot)
        ClientUtils.sendMessage("§eInventory slot failsafe test in 5 seconds for slot $slot.", false)
    }
    internal fun reset() {
        val client = Minecraft.getInstance()
        if (client.gui.screen() == activeGuiFlashScreen) client.gui.setScreen(null)
        pendingTest = null; activeGuiFlashTicksRemaining = 0; activeGuiFlashScreen = null
    }
    internal fun tick(client: Minecraft?) { tickGuiFlash(client); tickPendingTest(client) }
    private fun tickPendingTest(client: Minecraft?) {
        val test = pendingTest ?: return
        if (client?.player == null) { pendingTest = null; return }
        test.ticksRemaining--
        val secondsRemaining = Mth.ceil(test.ticksRemaining / TICKS_PER_SECOND.toFloat())
        if (secondsRemaining > 0 && secondsRemaining != test.lastAnnouncedSecond) {
            test.lastAnnouncedSecond = secondsRemaining
            ClientUtils.sendMessage("§eFailsafe test triggering in $secondsRemaining...", false)
        }
        if (test.ticksRemaining > 0) return
        pendingTest = null; test.trigger(client)
    }
    private fun tickGuiFlash(client: Minecraft?) {
        val screen = activeGuiFlashScreen ?: return
        if (client?.player == null || client.gui.screen() != screen) { activeGuiFlashScreen = null; activeGuiFlashTicksRemaining = 0; return }
        activeGuiFlashTicksRemaining--
        if (activeGuiFlashTicksRemaining <= 0) { client.gui.setScreen(null); activeGuiFlashScreen = null }
    }
    private fun rotatePlayer(client: Minecraft, pitchDelta: Float, yawDelta: Float) {
        val player = client.player ?: return
        val yaw = player.getYRot() + yawDelta
        val pitch = Mth.clamp(player.getXRot() + pitchDelta, -90.0f, 90.0f)
        player.setYRot(yaw); player.setXRot(pitch)
        ClientUtils.sendMessage(String.format(Locale.US, "§eApplied rotation test delta pitch %.1f yaw %.1f.", pitchDelta, yawDelta), false)
    }
    private fun openGuiFlash(client: Minecraft, durationTicks: Int) {
        val player = client.player ?: return
        val inventory = player.getInventory()
        val menu = ChestMenu.threeRows(0, inventory, SimpleContainer(27))
        val screen = InvisibleContainerScreen(menu, inventory)
        activeGuiFlashScreen = screen; activeGuiFlashTicksRemaining = maxOf(1, durationTicks)
        client.gui.setScreen(screen)
        ClientUtils.sendMessage("§eOpened invisible GUI test for $durationTicks ticks.", false)
    }
    private fun switchHotbarSlot(client: Minecraft, zeroBasedSlot: Int) {
        if (client.player == null) return
        ClientUtils.performHotbarSlotClick(zeroBasedSlot)
        ClientUtils.sendMessage("§eSwitched hotbar to slot ${zeroBasedSlot + 1}.", false)
    }
    private abstract class PendingTest {
        var ticksRemaining = COUNTDOWN_TICKS
        var lastAnnouncedSecond = 0
        abstract fun trigger(client: Minecraft)
    }
    private class RotationTest(private val pitchDelta: Float, private val yawDelta: Float) : PendingTest() {
        override fun trigger(client: Minecraft) = rotatePlayer(client, pitchDelta, yawDelta)
    }
    private class GuiFlashTest(private val durationTicks: Int) : PendingTest() {
        override fun trigger(client: Minecraft) = openGuiFlash(client, durationTicks)
    }
    private class InventorySlotTest(private val zeroBasedSlot: Int) : PendingTest() {
        override fun trigger(client: Minecraft) = switchHotbarSlot(client, zeroBasedSlot)
    }
    private class InvisibleContainerScreen(menu: ChestMenu, inventory: Inventory) : ContainerScreen(menu, inventory, Component.empty()) {
        override fun extractRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {}
        override fun extractContents(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {}
        override fun extractLabels(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {}
        override fun onClose() { Minecraft.getInstance().gui.setScreen(null) }
        override fun removed() {}
    }
}
