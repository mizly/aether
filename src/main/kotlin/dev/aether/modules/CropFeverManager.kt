package dev.aether.modules

import dev.aether.config.AetherConfig
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

class CropFeverManager {
    companion object {
        @JvmField @Volatile var isCropFeverActive = false
        private var cropFeverStartTime = 0L

        @JvmStatic fun handleChatMessage(client: Minecraft, message: String) {
            val cleanMessage = message.replace(Regex("\u00A7[0-9a-fk-or]"), "").trim()
            if (cleanMessage.contains("WOAH! You caught a case of the CROP FEVER for 60 seconds!")) {
                isCropFeverActive = true
                cropFeverStartTime = System.currentTimeMillis()
                if (AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get()) ClientUtils.sendDebugMessage("\u00A7dCrop Fever detected! Pest sequence will be delayed.")
                else ClientUtils.sendDebugMessage("\u00A7dCrop Fever detected!")
            } else if (cleanMessage.contains("GONE! Your CROP FEVER has been cured!")) {
                isCropFeverActive = false
                cropFeverStartTime = 0
                if (AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get()) ClientUtils.sendDebugMessage("\u00A7dCrop Fever cured! Pest sequence can resume.")
                else ClientUtils.sendDebugMessage("\u00A7dCrop Fever cured!")
            }
        }

        @JvmStatic fun update() {
            val client = Minecraft.getInstance()
            // Failsafe: if it's been more than 65 seconds, consider it gone
            if (isCropFeverActive && System.currentTimeMillis() - cropFeverStartTime > 65000) {
                isCropFeverActive = false
                cropFeverStartTime = 0
                ClientUtils.sendDebugMessage("\u00A7eCrop Fever failsafe triggered (65s elapsed).")
            }
        }

        @JvmStatic fun reset() { isCropFeverActive = false; cropFeverStartTime = 0 }
    }
}
