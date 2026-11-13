package dev.aether.modules.profit

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.profit.helpers.FarmingXpTracker
import dev.aether.modules.profit.helpers.PetXpTracker
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.util.LinkedHashMap
import java.util.function.BiConsumer

internal class ProfitLiveTracker(private val refreshPrices: Runnable) {
    companion object {
        private val BASE_CROPS = setOf("Wheat", "Potato", "Carrot", "Melon Slice", "Pumpkin", "Sugar Cane", "Cactus", "Nether Wart", "Cocoa Beans", "Red Mushroom", "Brown Mushroom", "Sunflower", "Moonflower", "Wild Rose", "Seeds")
        private const val PURSE_SAMPLE_INTERVAL_TICKS = 5
        private const val PET_XP_SAMPLE_INTERVAL_TICKS = 5
        private const val MAX_CULTIVATING_DELTA = 50000L
        private const val MAX_PURSE_DELTA = 50000L
        private const val PURSE_SETTLE_TICKS = 100
        private val MUSHROOMS = setOf("Red Mushroom", "Brown Mushroom")
    }

    private val prevInventoryCounts = LinkedHashMap<String, Long>()
    private var lastCultivatingValue = -1L
    private var currentFarmedCrop = "Wheat"
    private var lastCultivatingToolUuid: String? = null
    private var lastPurseBalance = -1L
    private var ticksSinceNonFarming = 0
    private var trackingLiveMetrics = false

    fun resetSessionState() {
        prevInventoryCounts.clear()
        lastCultivatingValue = -1
        lastCultivatingToolUuid = null
        lastPurseBalance = -1
        ticksSinceNonFarming = 0
        currentFarmedCrop = "Wheat"
        PetXpTracker.reset()
        if (AetherConfig.PERSIST_SESSION_TIMER.get()) FarmingXpTracker.resetLiveState() else FarmingXpTracker.reset()
        trackingLiveMetrics = false
    }

    fun update(client: Minecraft, dropRecorder: BiConsumer<String, Long>) {
        val player = client.player
        if (player == null) {
            if (trackingLiveMetrics) resetSessionState()
            return
        }
        if (!ProfitManager.isProfitTrackingActive()) {
            if (trackingLiveMetrics) resetSessionState()
            return
        }
        if (!trackingLiveMetrics) { resetSessionState(); trackingLiveMetrics = true }

        val held: ItemStack = player.mainHandItem
        var heldTag: net.minecraft.nbt.CompoundTag? = null
        if (!held.isEmpty) {
            val custom: CustomData? = held.get(DataComponents.CUSTOM_DATA)
            if (custom != null) heldTag = custom.copyTag()
        }
        val heldId = heldTag?.let { tag ->
            tag.getString("id").orElseGet {
                tag.getCompound("ExtraAttributes").flatMap { it.getString("id") }.orElse("")
            }
        } ?: ""
        val inventoryCrop = detectCropFromInventory(client, ProfitTrackingRules.isMushroomTool(heldId))
        val toolCrop = ProfitTrackingRules.cropForToolId(heldId)
        if (toolCrop != null) currentFarmedCrop = toolCrop else if (inventoryCrop != null) currentFarmedCrop = inventoryCrop

        if (heldTag != null && heldTag.contains("farmed_cultivating")) {
            val newValue = heldTag.getLong("farmed_cultivating").orElse(-1L)
            val toolUuid = heldTag.getString("uuid").orElse(heldId)
            val sameTool = toolUuid == lastCultivatingToolUuid
            if (sameTool && lastCultivatingValue != -1L && newValue > lastCultivatingValue) {
                val delta = newValue - lastCultivatingValue
                if (delta <= MAX_CULTIVATING_DELTA) recordCrops(delta, dropRecorder)
                else ClientUtils.sendDebugMessage("Dismissed large cultivating change: +$delta")
            }
            lastCultivatingValue = newValue
            lastCultivatingToolUuid = toolUuid
        } else { lastCultivatingValue = -1; lastCultivatingToolUuid = null }

        if (MacroStateManager.getCurrentState() == MacroState.State.FARMING) {
            if (ticksSinceNonFarming < PURSE_SETTLE_TICKS) ticksSinceNonFarming++
        } else ticksSinceNonFarming = 0

        if (player.tickCount % PURSE_SAMPLE_INTERVAL_TICKS == 0) {
            val purse = ClientUtils.getPurse()
            if (purse != -1L) {
                val countable = ticksSinceNonFarming >= PURSE_SETTLE_TICKS
                if (countable && lastPurseBalance != -1L && purse > lastPurseBalance) {
                    val delta = purse - lastPurseBalance
                    if (delta <= MAX_PURSE_DELTA) dropRecorder.accept("Purse", delta)
                    else ClientUtils.sendDebugMessage("Dismissed large purse change: +$delta")
                }
                lastPurseBalance = purse
            }
        }
        if (player.tickCount % PET_XP_SAMPLE_INTERVAL_TICKS == 0 && PetXpTracker.hasTrackedPetsConfigured()) PetXpTracker.update()
        if (player.tickCount % PET_XP_SAMPLE_INTERVAL_TICKS == 0) FarmingXpTracker.updateFromTablist(client)
        refreshPrices.run()
    }

    private fun recordCrops(delta: Long, recorder: BiConsumer<String, Long>) {
        if (currentFarmedCrop.equals("Wheat", true) || currentFarmedCrop.equals("Seeds", true)) {
            val wheatDelta = kotlin.math.round(delta / 2.5).toLong()
            val seedsDelta = delta - wheatDelta
            if (wheatDelta > 0) recorder.accept("Wheat", wheatDelta)
            if (seedsDelta > 0) recorder.accept("Seeds", seedsDelta)
        } else recorder.accept(currentFarmedCrop, delta)
    }

    private fun detectCropFromInventory(client: Minecraft, mushroomsOnly: Boolean): String? {
        val currentCounts = LinkedHashMap<String, Long>()
        for (slot in 0 until 36) {
            val stack = client.player!!.inventory.getItem(slot)
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.replace(Regex("\u00A7[0-9a-fk-or]"), "").trim()
            if (name in BASE_CROPS && (!mushroomsOnly || name in MUSHROOMS)) currentCounts[name] = currentCounts.getOrDefault(name, 0L) + stack.count
        }
        var detected: String? = null
        var maxIncrease = 0L
        for ((name, count) in currentCounts) {
            val diff = count - prevInventoryCounts.getOrDefault(name, 0L)
            if (diff > maxIncrease) { maxIncrease = diff; detected = name }
        }
        prevInventoryCounts.clear(); prevInventoryCounts.putAll(currentCounts)
        return detected
    }
}
