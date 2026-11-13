package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.util.ClientUtils
import dev.aether.util.WindowFocusHelper
import net.minecraft.client.Minecraft

object FailsafeWindowFocusManager {
    @JvmStatic
    fun bringWindowToFront() {
        if (!AetherConfig.FAILSAFE_AUTO_ALT_TAB.get()) {
            ClientUtils.sendDebugMessage("FailsafeWindowFocus: auto alt-tab disabled")
            return
        }
        focusWindowNow()
    }

    @JvmStatic
    fun focusWindowNow() {
        if (Minecraft.getInstance() == null) {
            ClientUtils.sendDebugMessage("FailsafeWindowFocus: client is null")
            return
        }
        WindowFocusHelper.focusMinecraftWindow("FailsafeWindowFocus")
    }
}
