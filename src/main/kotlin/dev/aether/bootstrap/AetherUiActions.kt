package dev.aether.bootstrap

import dev.aether.Aether
import dev.aether.ui.MainGUI
import dev.aether.ui.MainGUIRegistry
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

class AetherUiActions private constructor() {
    companion object {
        @JvmStatic
        fun toggleMainGui() {
            val client = Minecraft.getInstance()
            if (client.gui.screen() is MainGUI) {
                client.gui.setScreen(null)
            } else {
                openMainGui()
            }
        }

        @JvmStatic
        fun openMainGui() {
            val client = Minecraft.getInstance()
            if (client == null) {
                return
            }

            try {
                MainGUIRegistry.refresh()
                client.execute {
                    try {
                        client.gui.setScreen(MainGUI())
                    } catch (e: RuntimeException) {
                        Aether.LOGGER.error("Failed to open Aether GUI from queued client task", e)
                        ClientUtils.sendMessage("\u00A7cFailed to open the Aether GUI. Check the client log.", false)
                    } catch (e: LinkageError) {
                        Aether.LOGGER.error("Failed to open Aether GUI from queued client task", e)
                        ClientUtils.sendMessage("\u00A7cFailed to open the Aether GUI. Check the client log.", false)
                    }
                }
            } catch (e: RuntimeException) {
                Aether.LOGGER.error("Failed to open Aether GUI", e)
                ClientUtils.sendMessage("\u00A7cFailed to open the Aether GUI. Check the client log.", false)
            } catch (e: LinkageError) {
                Aether.LOGGER.error("Failed to open Aether GUI", e)
                ClientUtils.sendMessage("\u00A7cFailed to open the Aether GUI. Check the client log.", false)
            }
        }
    }
}
