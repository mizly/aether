package dev.aether.bootstrap

import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.visitor.VisitorManager
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot

class AetherScreenHooks private constructor() {
    companion object {
        private var lastScannedVisitorTitle: String? = null

        @JvmStatic
        fun register() {
            ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
                if (screen !is AbstractContainerScreen<*>) {
                    return@register
                }

                ScreenEvents.afterExtract(screen).register { renderedScreen, _, _, _, _ ->
                    if (renderedScreen !is AbstractContainerScreen<*>) {
                        return@register
                    }

                    val containerScreen = renderedScreen
                    val title = containerScreen.title.string.trim()
                    if (title == lastScannedVisitorTitle || !MacroStateManager.isMacroRunning()) {
                        return@register
                    }

                    if (containerScreen.menu.slots.size <= 29) {
                        return@register
                    }

                    val slot: Slot? = containerScreen.menu.getSlot(29)
                    if (slot == null || !slot.hasItem()) {
                        return@register
                    }

                    val itemName = slot.item.hoverName.string
                    if (!itemName.contains("Accept Offer")) {
                        return@register
                    }

                    lastScannedVisitorTitle = title
                    MacroWorkerThread.getInstance().submit("VisitorGui-Scan") {
                        VisitorManager.scanVisitorGui(Minecraft.getInstance(), containerScreen)
                    }
                }
            }
        }
    }
}
