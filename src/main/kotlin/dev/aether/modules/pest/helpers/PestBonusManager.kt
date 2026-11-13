package dev.aether.modules.pest.helpers

import dev.aether.config.ConfigHelpers
import dev.aether.macro.MacroWorkerThread
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import java.util.regex.Pattern

class PestBonusManager private constructor() {
    companion object {
        private val PHILLIP_REACTIVATION_PATTERN = Pattern.compile("^\\[NPC\\] Pesthunter Phillip: Thanks for the \\S+ Pests, [A-Za-z0-9_]{1,16}!$")
        private val BONUS_INACTIVE_PATTERN = Pattern.compile("(?i)\\bbonus\\b.*\\binactive\\b")
        private val BONUS_ACTIVE_PATTERN = Pattern.compile("(?i)\\bbonus\\b.*\\bactive\\b")
        @Volatile private var isBonusInactive = false
        @Volatile private var isReactivatingBonus = false
        @Volatile private var bonusActiveSeenAtMs = 0L

        @JvmStatic fun isBonusInactive(): Boolean = isBonusInactive
        @JvmStatic fun applyTabBonusState(inactive: Boolean) {
            isBonusInactive = inactive
            if (!inactive) bonusActiveSeenAtMs = System.currentTimeMillis()
        }
        @JvmStatic fun hasSeenBonusActiveSince(timestampMs: Long): Boolean = bonusActiveSeenAtMs != 0L && bonusActiveSeenAtMs >= timestampMs
        @JvmStatic fun beginReactivation() { isReactivatingBonus = true }
        @JvmStatic fun resetState() { isBonusInactive = false; isReactivatingBonus = false; bonusActiveSeenAtMs = 0L }

        @JvmStatic fun updateFromTab() {
            val client = Minecraft.getInstance()
            if (client == null || client.player == null || client.connection == null) return
            val bonusInactive = readBonusState(client)
            if (bonusInactive != null) applyTabBonusState(bonusInactive)
        }

        @JvmStatic fun parseBonusState(text: String?): Boolean? {
            if (text == null || text.isBlank()) return null
            val normalized = normalizeLine(text)
            if (BONUS_INACTIVE_PATTERN.matcher(normalized).find()) return true
            if (BONUS_ACTIVE_PATTERN.matcher(normalized).find()) return false
            return null
        }

        @JvmStatic fun handlePhillipMessage(client: Minecraft, text: String, currentInfestedPlot: String) {
            if (client.player == null) return
            val plain = text.replace(Regex("(?i)\u00A7[0-9a-fk-or]"), "").replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
            if (!PHILLIP_REACTIVATION_PATTERN.matcher(plain).matches()) return
            AutoPestExchangeManager.rememberExchange()
            if (!isReactivatingBonus) return
            ClientUtils.sendMessage("\u00A7aPhillip reactivation detected. Returning to plot \u00A7e" + currentInfestedPlot + "...", true)
            MacroWorkerThread.getInstance().submit("PhillipReactivation", Runnable {
                try {
                    if (MacroWorkerThread.shouldAbortTask(client)) return@Runnable
                    ClientUtils.sendDebugMessage("Disabling farming macro: Phillip reactivation detected")
                    client.execute(Runnable { dev.aether.macro.farming.FarmingMacroManager.disable(client) })
                    MacroWorkerThread.sleep(ConfigHelpers.getRandomizedDelay(250).toLong())
                    if (MacroWorkerThread.shouldAbortTask(client)) return@Runnable
                    ClientUtils.sendDebugMessage("PestDestroyer: starting after Phillip reactivation")
                    client.execute(Runnable { PestDestroyer.start(client) })
                } catch (e: Exception) { e.printStackTrace() }
                finally { isReactivatingBonus = false }
            })
        }

        private fun readBonusState(client: Minecraft): Boolean? {
            for (line in TablistUtils.getRawTabLines(client)) {
                val parsed = parseBonusState(line)
                if (parsed != null) return parsed
            }
            for (line in TablistUtils.getTabLines(client)) {
                val parsed = parseBonusState(line)
                if (parsed != null) return parsed
            }
            return null
        }

        private fun normalizeLine(text: String): String = TablistUtils.stripColors(text).replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }
}
