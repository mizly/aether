package dev.aether.modules.visuals

import dev.aether.modules.farming.UngrabMouse
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

class UngrabMouseManager private constructor() {
    companion object {
        @JvmStatic fun isEnabled(): Boolean = UngrabMouse.isVisualUngrabEnabled()
        @JvmStatic fun toggle() { setEnabled(!isEnabled()) }
        @JvmStatic fun setEnabled(shouldEnable: Boolean) {
            val client = Minecraft.getInstance()
            if (shouldEnable && StreamerModeManager.isEnabled()) return
            if (client == null) {
                if (shouldEnable) UngrabMouse.requestVisualUngrab() else UngrabMouse.clearVisualUngrab()
                return
            }
            if (!client.isSameThread()) {
                client.execute { setEnabled(shouldEnable) }
                return
            }
            if (isEnabled() == shouldEnable) return
            if (shouldEnable) UngrabMouse.requestVisualUngrab() else UngrabMouse.clearVisualUngrab()
            ClientUtils.sendMessage(if (shouldEnable) "§aUngrab mouse enabled." else "§cUngrab mouse disabled.")
        }
    }
}
