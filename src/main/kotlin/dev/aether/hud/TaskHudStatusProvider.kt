package dev.aether.hud

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.ComposterManager
import dev.aether.modules.CropFeverManager
import dev.aether.modules.GreenhouseManager
import dev.aether.modules.SupercraftManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.inventorymanager.BookCombineManager
import dev.aether.modules.inventorymanager.GeorgeManager
import dev.aether.modules.inventorymanager.JunkManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.modules.pest.helpers.AutoSprayonatorManager
import dev.aether.modules.pest.helpers.PestBonusManager
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.pest.helpers.PestExchangeManager
import dev.aether.modules.pest.helpers.PestTrapManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.visitor.VisitorManager
import dev.aether.modules.visitor.VisitorsMacro
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import java.util.ArrayList
import java.util.Collections
import java.util.EnumMap
import java.util.regex.Pattern

class TaskHudStatusProvider private constructor() {
    companion object {
        private val SPRAY_TIMER_PATTERN = Pattern.compile("(?i)Spray:\\s*.+?\\((?:(\\d+)m\\s*)?(?:(\\d+)s)?\\)")
        private val CACHED_ROWS = EnumMap<TaskGroupHudElement.Group, List<TaskStatusRow>>(TaskGroupHudElement.Group::class.java)
        private var cachedTick = Long.MIN_VALUE

        @JvmStatic
        fun getRows(group: TaskGroupHudElement.Group, client: Minecraft): List<TaskStatusRow> {
            refreshCache(client)
            return CACHED_ROWS.getOrDefault(group, emptyList())
        }

        private fun refreshCache(client: Minecraft) {
            val tick = resolveTick(client)
            if (tick == cachedTick) return
            CACHED_ROWS[TaskGroupHudElement.Group.INTERMEDIARIES] = Collections.unmodifiableList(getIntermediaries(client))
            CACHED_ROWS[TaskGroupHudElement.Group.MID_FARMING_TASKS] = Collections.unmodifiableList(getMidFarmingTasks(client))
            CACHED_ROWS[TaskGroupHudElement.Group.FAILSAFES] = Collections.unmodifiableList(getFailsafes(client))
            cachedTick = tick
        }

        private fun resolveTick(client: Minecraft): Long = if (client != null && client.level != null) client.level!!.gameTime else Long.MIN_VALUE + System.nanoTime()

        @JvmStatic fun getIntermediaries(client: Minecraft): List<TaskStatusRow> {
            val rows = ArrayList<TaskStatusRow>()
            rows.add(buildPestDestroyer(client)); rows.add(buildClearTraps(client)); rows.add(buildRefillTraps(client)); rows.add(buildGreenhouse(client)); rows.add(buildComposter(client)); rows.add(buildSupercraft(client)); rows.add(buildVisitors(client))
            return rows
        }

        @JvmStatic fun getMidFarmingTasks(client: Minecraft): List<TaskStatusRow> {
            val rows = ArrayList<TaskStatusRow>()
            rows.add(buildSprayonator(client)); rows.add(buildPestExchange(client)); rows.add(buildAutoSell(client)); rows.add(buildJunkDropper(client)); rows.add(buildAutoCombine(client)); rows.add(buildGeorgeSell(client))
            return rows
        }

        @JvmStatic fun getFailsafes(client: Minecraft): List<TaskStatusRow> {
            val rows = ArrayList<TaskStatusRow>()
            rows.add(buildInventorySlotChanged(client)); rows.add(buildGuiOpenedFailsafe(client)); rows.add(buildBpsFailsafe(client)); rows.add(buildGhostBlockFailsafe(client)); rows.add(buildDirtCheckFailsafe(client)); rows.add(buildRotationFailsafe(client))
            return rows
        }

        private fun buildPestDestroyer(client: Minecraft): TaskStatusRow {
            if (!PestManager.isPestDestroyerEnabled()) return TaskStatusRow.disabled("Pest Destroyer", "disabled")
            if (PestDestroyer.isActive()) { val alive = PestManager.getEffectiveAliveCountNow(client); return TaskStatusRow.running("Pest Destroyer", if (alive > 0) "$alive pests remaining" else "cleaning current cycle") }
            if (GreenhouseManager.isRunning()) return TaskStatusRow.waiting("Pest Destroyer", "handoff complete")
            val alive = PestManager.getEffectiveAliveCountNow(client)
            val threshold = AetherConfig.PEST_THRESHOLD.get()
            val cooldownMs = PestManager.getPestReentryCooldownRemainingMs()
            if (cooldownMs > 0 && alive >= threshold) return TaskStatusRow.waiting("Pest Destroyer", "cooldown " + fmtSeconds(cooldownMs))
            if (AutoPestExchangeManager.isExchangePending()) return TaskStatusRow.blocked("Pest Destroyer", "waiting for pest exchange")
            if (AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() && CropFeverManager.isCropFeverActive) return TaskStatusRow.blocked("Pest Destroyer", "crop fever active")
            if (VisitorsMacro.isRunning) return TaskStatusRow.blocked("Pest Destroyer", "visitors are running")
            if (alive >= threshold || alive >= 8) return TaskStatusRow.ready("Pest Destroyer", "$alive/$threshold pests")
            if (alive < 0) return TaskStatusRow.waiting("Pest Destroyer", "waiting for pest tab data")
            return TaskStatusRow.waiting("Pest Destroyer", "$alive/$threshold pests")
        }

        private fun buildVisitors(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_VISITOR.get()) return TaskStatusRow.disabled("Visitors", "auto visitor disabled")
            if (VisitorsMacro.isRunning) return TaskStatusRow.running("Visitors", "serving garden queue")
            val count = VisitorManager.getVisitorCount(client); val threshold = AetherConfig.VISITOR_THRESHOLD.get(); val cooldownMs = VisitorManager.getVisitorReentryCooldownRemainingMs()
            if (count >= threshold && cooldownMs > 0) return TaskStatusRow.waiting("Visitors", "$count/$threshold queued, cd " + fmtSeconds(cooldownMs))
            if (count >= threshold) return TaskStatusRow.ready("Visitors", "$count/$threshold queued")
            return TaskStatusRow.waiting("Visitors", "$count/$threshold queued")
        }

        private fun buildClearTraps(client: Minecraft): TaskStatusRow {
            if (!PestManager.arePestTrapsEnabled()) return TaskStatusRow.disabled("Clear Pest Traps", "pest traps disabled")
            if (!AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()) return TaskStatusRow.disabled("Clear Pest Traps", "clean pest traps disabled")
            val count = PestTrapManager.getFullTrapsFromTab(client).size
            if (PestTrapManager.isRunning() && PestTrapManager.getCurrentOperation() == PestTrapManager.Operation.CLEAR) return TaskStatusRow.running("Clear Pest Traps", if (count > 0) "$count full traps queued" else "clearing current pass")
            if (MacroStateManager.getCurrentState() != MacroState.State.FARMING && count > 0) return TaskStatusRow.blocked("Clear Pest Traps", "$count full traps waiting")
            if (count > 0) return TaskStatusRow.ready("Clear Pest Traps", "$count full traps")
            return TaskStatusRow.waiting("Clear Pest Traps", "0 full traps")
        }

        private fun buildRefillTraps(client: Minecraft): TaskStatusRow {
            if (!PestManager.arePestTrapsEnabled()) return TaskStatusRow.disabled("Refill Pest Traps", "pest traps disabled")
            if (!AetherConfig.AUTO_REFILL_PEST_TRAPS.get()) return TaskStatusRow.disabled("Refill Pest Traps", "refill pest traps disabled")
            val count = PestTrapManager.getNoBaitTrapsFromTab(client).size
            if (PestTrapManager.isRunning() && PestTrapManager.getCurrentOperation() == PestTrapManager.Operation.REFILL) return TaskStatusRow.running("Refill Traps", if (count > 0) "$count empty traps queued" else "refilling current pass")
            if (MacroStateManager.getCurrentState() != MacroState.State.FARMING && count > 0) return TaskStatusRow.blocked("Refill Traps", "$count empty traps waiting")
            if (count > 0) return TaskStatusRow.ready("Refill Traps", "$count empty traps")
            return TaskStatusRow.waiting("Refill Traps", "0 empty traps")
        }

        private fun buildGreenhouse(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_GREENHOUSE.get()) return TaskStatusRow.disabled("Greenhouse", "auto greenhouse disabled")
            if (GreenhouseManager.isRunning()) return TaskStatusRow.running("Greenhouse", "harvesting configured plots")
            val intervalMs = Math.max(1L, AetherConfig.AUTO_GREENHOUSE_INTERVAL_MINUTES.get().toLong()) * 60_000L; val elapsedMs = GreenhouseManager.getAutoGreenhouseElapsedMs()
            return if (elapsedMs >= intervalMs) TaskStatusRow.ready("Greenhouse", "${fmtMinutes(elapsedMs)}/${fmtMinutes(intervalMs)}") else TaskStatusRow.waiting("Greenhouse", "${fmtMinutes(elapsedMs)}/${fmtMinutes(intervalMs)}")
        }

        private fun buildComposter(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_COMPOSTER.get()) return TaskStatusRow.disabled("Composter", "auto composter disabled")
            if (ComposterManager.isRunning()) return TaskStatusRow.running("Composter", "refilling resources")
            val intervalMs = Math.max(1L, AetherConfig.AUTO_COMPOSTER_INTERVAL_MINUTES.get().toLong()) * 60_000L; val elapsedMs = ComposterManager.getAutoComposterElapsedMs()
            return if (elapsedMs >= intervalMs) TaskStatusRow.ready("Composter", "${fmtMinutes(elapsedMs)}/${fmtMinutes(intervalMs)}") else TaskStatusRow.waiting("Composter", "${fmtMinutes(elapsedMs)}/${fmtMinutes(intervalMs)}")
        }

        private fun buildSupercraft(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_SUPERCRAFT.get()) return TaskStatusRow.disabled("Supercraft", "auto supercraft disabled")
            if (SupercraftManager.isRunning()) return TaskStatusRow.running("Supercraft", "crafting configured items")
            if (AetherConfig.AUTO_SUPERCRAFT_ITEMS.get().stream().noneMatch { s -> s != null && s.trim().isNotEmpty() }) return TaskStatusRow.disabled("Supercraft", "no supercraft items configured")
            val intervalMs = Math.max(1L, AetherConfig.AUTO_SUPERCRAFT_INTERVAL_MINUTES.get().toLong()) * 60_000L; val elapsedMs = SupercraftManager.getAutoSupercraftElapsedMs()
            return if (elapsedMs >= intervalMs) TaskStatusRow.ready("Supercraft", "${fmtMinutes(elapsedMs)}/${fmtMinutes(intervalMs)}") else TaskStatusRow.waiting("Supercraft", "${fmtMinutes(elapsedMs)}/${fmtMinutes(intervalMs)}")
        }

        private fun buildSprayonator(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_SPRAYONATOR.get()) return TaskStatusRow.disabled("Sprayonator", "auto spray disabled")
            if (AutoSprayonatorManager.isRunning() || MacroStateManager.getCurrentState() == MacroState.State.SPRAYING) return TaskStatusRow.running("Sprayonator", "spraying unsprayed plot")
            val detectMs = AetherConfig.AUTO_SPRAYONATOR_DETECT_TIME.get() * 1000L; val elapsedMs = AutoSprayonatorManager.getSprayNeededElapsedMs(client); val sprayNeeded = AutoSprayonatorManager.isSprayNeededNow(client)
            if (!sprayNeeded) return TaskStatusRow.waiting("Sprayonator", "Current Plot: " + getCurrentPlotSprayTimer(client))
            if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) return TaskStatusRow.blocked("Sprayonator", "waiting for farming state")
            return if (elapsedMs >= detectMs) TaskStatusRow.ready("Sprayonator", "${fmtSeconds(elapsedMs)}/${fmtSeconds(detectMs)}") else TaskStatusRow.waiting("Sprayonator", "${fmtSeconds(elapsedMs)}/${fmtSeconds(detectMs)}")
        }

        private fun buildPestExchange(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_PEST_EXCHANGE.get()) return TaskStatusRow.disabled("Pest Exchange", "auto exchange disabled")
            if (AutoPestExchangeManager.isRunning() || PestExchangeManager.isExchanging()) return TaskStatusRow.running("Pest Exchange", "heading to Phillip")
            val memoryMs = AutoPestExchangeManager.getExchangeMemoryRemainingMs()
            if (memoryMs > 0) return TaskStatusRow.waiting("Pest Exchange", "exchanged, awaiting bonus " + fmtMinutes(memoryMs))
            val cooldownMs = AutoPestExchangeManager.getRunCooldownRemainingMs(); val inactiveMs = AutoPestExchangeManager.getBonusInactiveElapsedMs()
            if (!PestBonusManager.isBonusInactive()) return TaskStatusRow.waiting("Pest Exchange", "bonus is active")
            if (cooldownMs > 0 && inactiveMs > 0) return TaskStatusRow.waiting("Pest Exchange", "cooldown " + fmtSeconds(cooldownMs))
            if (PestManager.isCleaningInProgress()) return TaskStatusRow.blocked("Pest Exchange", "waiting for pest cycle")
            if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) return TaskStatusRow.blocked("Pest Exchange", "waiting for farming state")
            if (inactiveMs > 0) return TaskStatusRow.ready("Pest Exchange", "ready")
            return TaskStatusRow.waiting("Pest Exchange", "waiting for bonus trigger")
        }

        private fun buildAutoSell(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_SELL.get()) return TaskStatusRow.disabled("AutoSell", "inventory sell disabled")
            if (AutoSellManager.isPreparingToSell || AutoSellManager.isSelling) return TaskStatusRow.running("AutoSell", "selling inventory")
            val fullness = AutoSellManager.getInventoryFullnessRatio(client); val threshold = AetherConfig.AUTO_SELL_THRESHOLD.get(); val elapsedMs = AutoSellManager.getThresholdMetElapsedMs(); val requiredMs = AetherConfig.AUTO_SELL_TIME.get() * 1000L; val cooldownMs = AutoSellManager.getSellCooldownRemainingMs()
            if (fullness >= threshold && cooldownMs > 0) return TaskStatusRow.waiting("AutoSell", pct(fullness) + ", cd " + fmtSeconds(cooldownMs))
            if (fullness >= threshold && MacroStateManager.getCurrentState() != MacroState.State.FARMING) return TaskStatusRow.blocked("AutoSell", pct(fullness) + ", waiting for farming")
            if (fullness >= threshold && elapsedMs >= requiredMs) return TaskStatusRow.ready("AutoSell", pct(fullness) + " for " + fmtSeconds(elapsedMs))
            if (fullness >= threshold) return TaskStatusRow.waiting("AutoSell", pct(fullness) + " for " + fmtSeconds(elapsedMs) + "/" + fmtSeconds(requiredMs))
            return TaskStatusRow.waiting("AutoSell", pct(fullness) + " / " + threshold + "%")
        }

        private fun buildJunkDropper(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_DROP_JUNK.get()) return TaskStatusRow.disabled("Junk Dropper", "junk drop disabled")
            if (JunkManager.isPreparingToDrop || JunkManager.isDropping) return TaskStatusRow.running("Junk Dropper", "dropping junk items")
            val count = JunkManager.countJunkItems(client); val threshold = AetherConfig.JUNK_THRESHOLD.get()
            if (count >= threshold && MacroStateManager.getCurrentState() != MacroState.State.FARMING) return TaskStatusRow.blocked("Junk Dropper", "$count/$threshold junk items")
            if (count >= threshold) return TaskStatusRow.ready("Junk Dropper", "$count/$threshold junk items")
            return TaskStatusRow.waiting("Junk Dropper", "$count/$threshold junk items")
        }

        private fun buildAutoCombine(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_BOOK_COMBINE.get()) return TaskStatusRow.disabled("Auto Combine", "book combine disabled")
            if (BookCombineManager.isPreparingToCombine || BookCombineManager.isCombining) return TaskStatusRow.running("Auto Combine", "combining books")
            val count = BookCombineManager.getBookCountInInventory(client); val threshold = AetherConfig.BOOK_THRESHOLD.get()
            if (count >= threshold && MacroStateManager.getCurrentState() != MacroState.State.FARMING) return TaskStatusRow.blocked("Auto Combine", "$count/$threshold books")
            if (count >= threshold) return TaskStatusRow.ready("Auto Combine", "$count/$threshold books")
            return TaskStatusRow.waiting("Auto Combine", "$count/$threshold books")
        }

        private fun buildGeorgeSell(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.AUTO_GEORGE_SELL.get()) return TaskStatusRow.disabled("George Sell", "george sell disabled")
            if (GeorgeManager.isPreparingToSell || GeorgeManager.isSelling) return TaskStatusRow.running("George Sell", if (GeorgeManager.isPreparingToSell) "calling George" else "selling pests")
            val count = GeorgeManager.getSellablePetCount(client); val threshold = AetherConfig.GEORGE_SELL_THRESHOLD.get(); val cooldownMs = GeorgeManager.getGeorgeCooldownRemainingMs()
            if (count >= threshold && cooldownMs > 0) return TaskStatusRow.waiting("George Sell", "$count/$threshold pets, cd " + fmtSeconds(cooldownMs))
            if (count >= threshold && MacroStateManager.getCurrentState() != MacroState.State.FARMING) return TaskStatusRow.blocked("George Sell", "$count/$threshold pets")
            if (count >= threshold) return TaskStatusRow.ready("George Sell", "$count/$threshold pets")
            return TaskStatusRow.waiting("George Sell", "$count/$threshold pets")
        }

        private fun buildInventorySlotChanged(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.get()) return TaskStatusRow.disabled("Inventory Slot Changed", "failsafe disabled")
            val slotDetail = "Current: ${fmtSlot(FailsafeManager.getCurrentSelectedSlot(client))} / Expected: ${fmtSlot(FailsafeManager.getExpectedSelectedSlot())}"
            return when (FailsafeManager.getInventorySlotState(client)) {
                FailsafeManager.InventorySlotState.TRIGGERED -> TaskStatusRow.triggered("Inventory Slot Changed", slotDetail)
                FailsafeManager.InventorySlotState.WAIT -> TaskStatusRow.waiting("Inventory Slot Changed", slotDetail + "\ntriggering in " + fmtTenthsSeconds(FailsafeManager.getInventorySlotTriggerRemainingMs()))
                FailsafeManager.InventorySlotState.IDLE -> TaskStatusRow.ready("Inventory Slot Changed", slotDetail)
            }
        }

        private fun buildGuiOpenedFailsafe(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get()) return TaskStatusRow.disabled("GUI Opened", "failsafe disabled")
            val guiDetail = if (ClientUtils.isInventoryScreenOpen()) "Unexpected inventory GUI is currently open" else "No unexpected inventory GUI detected"
            return when (FailsafeManager.getInventoryGuiState(client)) {
                FailsafeManager.InventoryGuiState.TRIGGERED -> TaskStatusRow.triggered("GUI Opened", guiDetail)
                FailsafeManager.InventoryGuiState.WAIT -> TaskStatusRow.waiting("GUI Opened", guiDetail + "\ntriggering in " + fmtTenthsSeconds(FailsafeManager.getInventoryGuiTriggerRemainingMs()))
                FailsafeManager.InventoryGuiState.IDLE -> TaskStatusRow.ready("GUI Opened", guiDetail)
            }
        }

        private fun buildBpsFailsafe(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.FAILSAFE_BPS.get()) return TaskStatusRow.disabled("BPS", "failsafe disabled")
            val detail = String.format(java.util.Locale.US, "Current: %.2f (%d / %.1fs) / Expected: >= %.2f", FailsafeManager.getCurrentBps(), FailsafeManager.getBpsBreakCount(), FailsafeManager.getBpsWindowSeconds(), FailsafeManager.getExpectedBps())
            return when (FailsafeManager.getBpsState(client)) {
                FailsafeManager.BpsState.TRIGGERED -> TaskStatusRow.triggered("BPS", detail)
                FailsafeManager.BpsState.WAIT -> { val ms = FailsafeManager.getBpsTriggerRemainingMs(); TaskStatusRow.waiting("BPS", if (ms > 0L) detail + "\ntriggering in " + fmtTenthsSeconds(ms) else detail) }
                FailsafeManager.BpsState.IDLE -> TaskStatusRow.ready("BPS", detail)
            }
        }

        private fun buildGhostBlockFailsafe(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.FAILSAFE_GHOST_BLOCK.get()) return TaskStatusRow.disabled("Ghost Block", "failsafe disabled")
            val detail = String.format(java.util.Locale.US, "Farming Exp Text: %s / Missing For: %.1fs / Window: %.1fs", if (FailsafeManager.isGhostBlockFarmingTextVisible(client)) "visible" else "missing", FailsafeManager.getGhostBlockWindowSeconds(), FailsafeManager.getExpectedGhostBlockWindowSeconds())
            return when (FailsafeManager.getGhostBlockState(client)) {
                FailsafeManager.GhostBlockState.TRIGGERED -> TaskStatusRow.triggered("Ghost Block", detail)
                FailsafeManager.GhostBlockState.WAIT -> { val ms = FailsafeManager.getGhostBlockTriggerRemainingMs(); TaskStatusRow.waiting("Ghost Block", if (ms > 0L) detail + "\ntriggering in " + fmtTenthsSeconds(ms) else detail) }
                FailsafeManager.GhostBlockState.IDLE -> TaskStatusRow.ready("Ghost Block", detail)
            }
        }

        private fun buildDirtCheckFailsafe(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.FAILSAFE_DIRT_CHECK.get()) return TaskStatusRow.disabled("Dirt Check", "failsafe disabled")
            val detail = String.format(java.util.Locale.US, "Touching: %s / Tracked Blocks: %d", if (FailsafeManager.isTouchingDirtBlock(client)) "yes" else "no", FailsafeManager.getDirtCheckTrackedBlockCount(client))
            return when (FailsafeManager.getDirtCheckState(client)) {
                FailsafeManager.DirtCheckState.TRIGGERED -> TaskStatusRow.triggered("Dirt Check", detail)
                FailsafeManager.DirtCheckState.WAIT -> { val ms = FailsafeManager.getDirtCheckTriggerRemainingMs(); TaskStatusRow.waiting("Dirt Check", if (ms > 0L) detail + "\ntriggering in " + fmtTenthsSeconds(ms) else detail) }
                FailsafeManager.DirtCheckState.IDLE -> TaskStatusRow.ready("Dirt Check", detail)
            }
        }

        private fun buildRotationFailsafe(client: Minecraft): TaskStatusRow {
            if (!AetherConfig.FAILSAFE_ROTATION.get()) return TaskStatusRow.disabled("Rotation", "failsafe disabled")
            val detail = String.format(java.util.Locale.US, "Current Yaw/Pitch: %.1f / %.1f\nExpected Yaw/Pitch: %.1f / %.1f", if (client != null && client.player != null) Mth.wrapDegrees(client.player!!.yRot) else 0.0f, if (client != null && client.player != null) client.player!!.xRot else 0.0f, FailsafeManager.getExpectedYaw(), FailsafeManager.getExpectedPitch())
            return when (FailsafeManager.getRotationState(client)) {
                FailsafeManager.RotationState.TRIGGERED -> TaskStatusRow.triggered("Rotation", detail)
                FailsafeManager.RotationState.WAIT -> { val ms = FailsafeManager.getRotationTriggerRemainingMs(); TaskStatusRow.waiting("Rotation", if (ms > 0L) detail + "\ntriggering in " + fmtTenthsSeconds(ms) else detail) }
                FailsafeManager.RotationState.IDLE -> TaskStatusRow.ready("Rotation", detail)
            }
        }

        private fun getCurrentPlotSprayTimer(client: Minecraft): String {
            if (client == null || client.player == null || client.connection == null) return "-- / 30:00"
            for (line in TablistUtils.getRawTabLines(client)) {
                val matcher = SPRAY_TIMER_PATTERN.matcher(line)
                if (!matcher.find()) continue
                val minutes = if (matcher.group(1) != null) Integer.parseInt(matcher.group(1)) else 0
                val seconds = if (matcher.group(2) != null) Integer.parseInt(matcher.group(2)) else 0
                return String.format("%d:%02d / 30:00", minutes, seconds)
            }
            return "-- / 30:00"
        }

        private fun fmtSeconds(ms: Long): String = Math.max(0L, (ms + 999L) / 1000L).toString() + "s"
        private fun fmtMinutes(ms: Long): String = Math.max(0L, (ms + 59_999L) / 60_000L).toString() + "m"
        private fun pct(value: Double): String = String.format("%.0f%%", value)
        private fun fmtTenthsSeconds(ms: Long): String = String.format(java.util.Locale.US, "%.1f seconds", Math.max(0L, ms) / 1000.0)
        private fun fmtSlot(slot: Int): String = if (slot >= 0) (slot + 1).toString() else "-"
    }

    class TaskStatusRow private constructor(@JvmField val name: String, @JvmField val detail: String, @JvmField val badge: String) {
        @JvmField val detailLines: Array<String> = if (detail.isEmpty()) arrayOf("") else detail.split("\n").toTypedArray()

        fun color(): Int = when (badge) {
            "READY" -> dev.aether.ui.theme.Theme.HUD_SUCCESS
            "WAIT", "HOLD" -> dev.aether.ui.theme.Theme.HUD_WARNING
            "LIVE" -> dev.aether.ui.theme.Theme.HUD_ACCENT
            "TRIG" -> dev.aether.ui.theme.Theme.HUD_ERROR
            else -> dev.aether.ui.theme.Theme.HUD_LABEL
        }

        companion object {
            @JvmStatic fun disabled(name: String, detail: String) = TaskStatusRow(name, detail, "OFF")
            @JvmStatic fun waiting(name: String, detail: String) = TaskStatusRow(name, detail, "WAIT")
            @JvmStatic fun blocked(name: String, detail: String) = TaskStatusRow(name, detail, "HOLD")
            @JvmStatic fun ready(name: String, detail: String) = TaskStatusRow(name, detail, "READY")
            @JvmStatic fun running(name: String, detail: String) = TaskStatusRow(name, detail, "LIVE")
            @JvmStatic fun triggered(name: String, detail: String) = TaskStatusRow(name, detail, "TRIG")
        }
    }
}
