package dev.aether.modules

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import java.util.HashSet
import java.util.regex.Pattern

class TablistSetupManager private constructor() {
    companion object {
        private val ENABLED_WIDGETS: Set<String> = setOf(
            "General Info Widget", "Profile Widget", "Stats Widget", "Skills Widget", "Jacob's Contest Widget",
            "Pests Widget", "Visitors Widget", "Composter Widget", "Pest Traps Widget", "Pet Widget"
        )
        private val PAGE_PATTERN = Pattern.compile("\\((\\d+)/(\\d+)\\)")
        private const val AUTO_START_DELAY_MS = 3_000L
        private const val TIMEOUT_MS = 60_000L
        private val completedPages: MutableSet<Int> = HashSet()
        private var active = false
        private var visitorsConfigured = false
        private var pestsConfigured = false
        private var visitorAmountClicks = 0
        private var automaticAttempted = false
        private var automaticStartAt = 0L
        private var deadline = 0L
        private var nextActionAt = 0L

        @JvmStatic fun isActive() = active
        @JvmStatic fun stop() {
            if (active) { active = false; automaticAttempted = true }
        }
        @JvmStatic fun start(client: Minecraft?): Int {
            if (active) { ClientUtils.sendMessage("\u00A7eTablist setup is already running.", false); return 0 }
            if (client == null || client.player == null || client.connection == null) return 0
            if (MacroStateManager.isMacroRunning()) {
                ClientUtils.sendMessage("\u00A7cStop the active macro before changing tablist widgets.", false); return 0
            }
            active = true; visitorsConfigured = false; pestsConfigured = false; visitorAmountClicks = 0; completedPages.clear()
            deadline = System.currentTimeMillis() + TIMEOUT_MS; nextActionAt = 0L
            ClientUtils.sendMessage("\u00A7eApplying Aether's Garden tablist setup...", false)
            ClientUtils.sendCommand("/widgets"); return 1
        }
        @JvmStatic fun update(client: Minecraft) {
            val now = System.currentTimeMillis()
            if (!active) { updateAutomaticStart(client, now); return }
            if (client.player == null || client.connection == null || now >= deadline) {
                finish(client, false, "Timed out waiting for the Tablist Widgets menu."); return
            }
            if (MacroStateManager.isMacroRunning()) { finish(client, false, "Tablist setup stopped because a macro started."); return }
            if (now < nextActionAt) return
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
            val title = clean(screen.title.string)
            if (title.equals("Tablist Widgets", ignoreCase = true)) clickNamed(screen, "Widgets In Garden", 0)
            else if (title.contains("Widgets in Garden")) configureWidgetPage(screen, title)
            else if (title == "Visitors Widget Settings") configureSettings(screen, false)
            else if (title == "Pests Widget Settings") configureSettings(screen, true)
        }
        private fun updateAutomaticStart(client: Minecraft, now: Long) {
            val location = ClientUtils.getCurrentLocation()
            if (!needsAutomaticSetup(AetherConfig.TABLIST_SETUP_COMPLETE.get(), location)) {
                automaticAttempted = false; automaticStartAt = 0L; return
            }
            if (automaticAttempted || MacroStateManager.isMacroRunning()) { automaticStartAt = 0L; return }
            if (automaticStartAt == 0L) {
                automaticStartAt = now + AUTO_START_DELAY_MS
                client.gui.hud.setTimes(10, 60, 10)
                client.gui.hud.setTitle(Component.literal("running necessary setup").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                client.gui.hud.setSubtitle(Component.literal("please wait").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                return
            }
            if (now < automaticStartAt) return
            automaticAttempted = true; automaticStartAt = 0L; start(client)
        }
        @JvmStatic fun needsAutomaticSetup(completed: Boolean, location: MacroState.Location): Boolean = !completed && location == MacroState.Location.GARDEN
        private fun configureWidgetPage(screen: AbstractContainerScreen<*>, title: String) {
            for (slot in menuSlots(screen)) {
                if (!slot.hasItem()) continue
                val name = clean(slot.item.hoverName.string)
                val lore = lore(slot.item)
                if (name.contains("Third Column") && java.lang.Boolean.FALSE == toggleState(lore)) { click(screen, slot.index, 0); return }
                if (!name.endsWith(" Widget") || !lore.contains("Currently:")) continue
                val enabled = lore.contains("Currently: ENABLED") || lore.contains("Currently: ALWAYS ENABLED")
                if (enabled != shouldEnableWidget(name)) { click(screen, slot.index, 0); return }
            }
            val page = PAGE_PATTERN.matcher(title)
            val hasPage = page.find()
            val currentPage = if (hasPage) Integer.parseInt(page.group(1)) else 1
            val pageCount = if (hasPage) Integer.parseInt(page.group(2)) else 1
            completedPages.add(currentPage)
            if (currentPage == 1 && !visitorsConfigured && rightClickWidget(screen, "Visitors Widget")) return
            if (currentPage == 1 && !pestsConfigured && rightClickWidget(screen, "Pests Widget")) return
            for (targetPage in 1..pageCount) {
                if (!completedPages.contains(targetPage)) {
                    clickNamed(screen, if (targetPage > currentPage) "Next Page" else "Previous Page", 0); return
                }
            }
            finish(Minecraft.getInstance(), true, "Garden tablist widgets configured.")
        }
        private fun configureSettings(screen: AbstractContainerScreen<*>, pests: Boolean) {
            for (slot in menuSlots(screen)) {
                if (!slot.hasItem()) continue
                val name = clean(slot.item.hoverName.string); val lore = lore(slot.item)
                if (!pests && name == "Visitor Amount" && !isFiveVisitorsSelected(lore)) {
                    if (++visitorAmountClicks > 6) { finish(Minecraft.getInstance(), false, "Could not select 5 visitors."); return }
                    click(screen, slot.index, 0); return
                }
                val enabled = toggleState(lore)
                if (enabled != null && enabled != shouldEnableSetting(name, pests)) { click(screen, slot.index, 0); return }
            }
            if (pests) pestsConfigured = true else visitorsConfigured = true
            clickNamed(screen, "Go Back", 0)
        }
        @JvmStatic fun shouldEnableWidget(name: String): Boolean = ENABLED_WIDGETS.contains(withoutStatus(name))
        @JvmStatic fun isFiveVisitorsSelected(lore: String): Boolean = lore.lines().any { it.endsWith("5 Visitors") && !it.startsWith("5 Visitors") }
        @JvmStatic fun shouldEnableSetting(name: String, pests: Boolean): Boolean {
            val normalized = withoutStatus(name)
            return pests || normalized == "Spacing" || normalized == "Wrapping"
        }
        private fun withoutStatus(name: String): String = name.replaceFirst(Regex("^[✔✖]\\s*"), "")
        private fun toggleState(lore: String): Boolean? {
            if (lore.contains("Click to disable!")) return true
            if (lore.contains("Click to enable!")) return false
            return null
        }
        private fun rightClickWidget(screen: AbstractContainerScreen<*>, widgetName: String): Boolean = clickNamed(screen, widgetName, 1)
        private fun clickNamed(screen: AbstractContainerScreen<*>, target: String, mouseButton: Int): Boolean {
            for (slot in menuSlots(screen)) {
                if (slot.hasItem() && clean(slot.item.hoverName.string).contains(target)) { click(screen, slot.index, mouseButton); return true }
            }
            return false
        }
        private fun click(screen: AbstractContainerScreen<*>, slot: Int, mouseButton: Int) {
            ClientUtils.performSlotClick(screen, slot, mouseButton, ContainerInput.PICKUP)
            nextActionAt = System.currentTimeMillis() + Math.max(150L, ClientUtils.getGuiClickDelayMs(false))
        }
        private fun menuSlots(screen: AbstractContainerScreen<*>): List<Slot> {
            val slots = screen.menu.slots
            return slots.subList(0, Math.max(0, slots.size - 36))
        }
        private fun lore(stack: ItemStack): String {
            val result = StringBuilder(); val client = Minecraft.getInstance()
            val player = client.player ?: return ""
            val lines = stack.getTooltipLines(Item.TooltipContext.EMPTY, player, TooltipFlag.NORMAL)
            for (line in lines) result.append(clean(line.string)).append('\n')
            return result.toString()
        }
        private fun clean(text: String): String = TablistUtils.stripColors(text).trim()
        private fun finish(client: Minecraft?, success: Boolean, message: String) {
            active = false
            if (success) { AetherConfig.TABLIST_SETUP_COMPLETE.set(true); AetherConfig.save() }
            val player = client?.player
            if (player != null) player.closeContainer()
            ClientUtils.sendMessage((if (success) "\u00A7a" else "\u00A7c") + message, false)
        }
    }
}
