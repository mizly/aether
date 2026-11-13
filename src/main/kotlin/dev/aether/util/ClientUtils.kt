package dev.aether.util

import com.mojang.blaze3d.platform.InputConstants
import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.mixin.AccessorAbstractContainerScreen
import dev.aether.mixin.AccessorKeyMapping
import dev.aether.macro.MacroState
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.farming.UngrabMouse
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.visuals.StreamerModeManager
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Collection
import java.util.Collections
import java.util.List
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Matcher
import java.util.regex.Pattern

class ClientUtils private constructor() {
    companion object {
        private const val GUI_CLOSE_STABILIZATION_MS = 250L
        private const val GUI_CLOSE_POLL_MS = 25L
        private const val JACOBS_CONTEST_START_MINUTE_UTC = 15
        private const val JACOBS_CONTEST_END_MINUTE_UTC = 35
        private val SIDEBAR_CACHE_LOCK = Any()
        private val STRIP_SCOREBOARD_FORMATTING = Pattern.compile("\u00A7.")
        private val STRIP_MINECRAFT_FORMATTING = Pattern.compile("(?i)\u00A7[0-9A-FK-ORZ]")
        private val DEBUG_LOG_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm'.txt'")
        private val DEBUG_LOG_LINE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val DEBUG_LOG_LOCK = Any()
        private val DEBUG_LOG_FILE = createDebugLogFile()
        private var sidebarCacheTick = Int.MIN_VALUE
        private var sidebarCacheConnectionId = 0
        private var sidebarCacheLines: java.util.List<String> = java.util.Collections.emptyList<String>() as java.util.List<String>
        private val COMMAND_QUEUE_LOCK = Any()
        private val COMMAND_EXECUTOR = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aether-command-dispatch").apply { isDaemon = true } }
        private val INPUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aether-input-dispatch").apply { isDaemon = true } }
        private val GUI_CLOSE_EXECUTOR = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aether-gui-close").apply { isDaemon = true } }
        private var nextCommandTime = 0L
        private const val COMMAND_COOLDOWN_MS = 250L
        private const val INPUT_CLICK_HOLD_MIN_MS = 80L
        private const val INPUT_CLICK_HOLD_JITTER_MS = 60L
        private const val USER_MESSAGE_PREFIX = "\u00A7c\u00A7lAether >> \u00A77"
        private val USE_CLICK_SEQUENCE = AtomicLong()
        private val ATTACK_CLICK_SEQUENCE = AtomicLong()
        private fun randomizedClickHoldMs() = INPUT_CLICK_HOLD_MIN_MS + ThreadLocalRandom.current().nextLong(INPUT_CLICK_HOLD_JITTER_MS + 1)

        @JvmStatic fun sendMessage(message: String) = sendMessage(message, false)
        @JvmStatic fun sendMessage(message: String, overlay: Boolean) {
            val client = Minecraft.getInstance() ?: return
            if (StreamerModeManager.isEnabled()) return
            val normalized = normalizeUserMessage(AetherLang.localize(message))
            client.execute { if (client.player != null) { if (overlay) client.player!!.sendOverlayMessage(Component.literal(USER_MESSAGE_PREFIX + normalized)) else client.player!!.sendSystemMessage(Component.literal(USER_MESSAGE_PREFIX + normalized)) } }
        }
        @JvmStatic fun sendDebugMessage(message: String) { writeDebugLine(normalizeDebugMessage(AetherLang.localize(message))) }
        @JvmStatic fun formatElapsedMs(startTimeMs: Long, nowMs: Long): String = if (startTimeMs <= 0L) "0ms" else Math.max(0L, nowMs - startTimeMs).toString() + "ms"
        private fun normalizeUserMessage(message: String?): String {
            if (message == null) return ""
            return message.replaceFirst(Regex("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[Aether\\]\\s*"), "").replaceFirst(Regex("^(?i)(?:\u00A7[0-9A-FK-OR])*Aether\\s*>>\\s*(?:\u00A7[0-9A-FK-OR])*"), "").trim()
        }
        private fun normalizeDebugMessage(message: String?): String {
            if (message == null) return ""
            val withoutPrefixes = message.replaceFirst(Regex("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[Debug\\]\\s*"), "").replaceFirst(Regex("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[Aether\\]\\s*"), "").replaceFirst(Regex("^(?i)(?:\u00A7[0-9A-FK-OR])*Aether\\s*>>\\s*(?:\u00A7[0-9A-FK-OR])*"), "").trim()
            return STRIP_MINECRAFT_FORMATTING.matcher(withoutPrefixes).replaceAll("").trim()
        }
        private fun createDebugLogFile(): Path = FabricLoader.getInstance().configDir.resolve("aether").resolve("logs").resolve(LocalDateTime.now().format(DEBUG_LOG_FILE_FORMAT))
        private fun writeDebugLine(message: String) { synchronized(DEBUG_LOG_LOCK) { try { val parent = DEBUG_LOG_FILE.parent; if (parent != null) Files.createDirectories(parent); Files.writeString(DEBUG_LOG_FILE, "[" + LocalTime.now().format(DEBUG_LOG_LINE_TIME_FORMAT) + "] " + message + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND) } catch (e: IOException) { System.err.println("[Aether] Failed to write debug log: " + e.message) } } }
        @JvmStatic fun sendCommand(cmd: String?) {
            val client = Minecraft.getInstance(); if (client == null || cmd == null || cmd.isBlank()) return
            val delayMs: Long
            synchronized(COMMAND_QUEUE_LOCK) { val now = System.currentTimeMillis(); val scheduledTime = Math.max(now, nextCommandTime); nextCommandTime = scheduledTime + COMMAND_COOLDOWN_MS; delayMs = Math.max(0L, scheduledTime - now) }
            COMMAND_EXECUTOR.schedule({ client.execute { if (client.player == null || client.connection == null) return@execute; if (cmd.startsWith("/")) client.connection!!.sendCommand(cmd.substring(1)) else client.connection!!.sendChat(cmd) } }, delayMs, TimeUnit.MILLISECONDS)
        }
        @JvmStatic fun disconnectWithScreen(screen: Screen, reason: Component) { val client = Minecraft.getInstance() ?: return; client.execute { val connection: ClientPacketListener? = client.connection; if (connection != null) connection.connection.disconnect(reason); client.disconnect(screen, false) } }
        @JvmStatic fun forceReleaseKeys() { val client = Minecraft.getInstance() ?: return; if (client.options != null) { releaseKeyMapping(client.options.keyUp); releaseKeyMapping(client.options.keyDown); releaseKeyMapping(client.options.keyLeft); releaseKeyMapping(client.options.keyRight); releaseKeyMapping(client.options.keyJump); releaseKeyMapping(client.options.keyShift); releaseKeyMapping(client.options.keySprint); releaseKeyMapping(client.options.keyAttack); releaseKeyMapping(client.options.keyUse); ProgrammaticAttackTracker.setHeld(client.options.keyAttack, false); restorePhysicalKeyStates(client) } }
        @JvmStatic fun isInventoryScreenOpen() = Minecraft.getInstance()?.let { it.gui.screen() is AbstractContainerScreen<*> } ?: false
        @JvmStatic fun closeGui(client: Minecraft?) {
            if (client == null) return
            val deadline = System.currentTimeMillis() + GUI_CLOSE_STABILIZATION_MS
            var interrupted = false
            var screenBeingClosed: Screen? = null
            while (true) {
                val remaining = deadline - System.currentTimeMillis(); val currentScreen = client.gui.screen()
                if (currentScreen == null) screenBeingClosed = null else if (currentScreen !== screenBeingClosed) {
                    screenBeingClosed = currentScreen
                    val closeAction = Runnable { if (client.player != null && client.gui.screen() != null) client.player!!.closeContainer() else if (client.gui.screen() != null) client.gui.setScreen(null) }
                    if (client.isSameThread) closeAction.run() else client.execute(closeAction)
                }
                if (remaining <= 0) break
                try { Thread.sleep(Math.min(GUI_CLOSE_POLL_MS, remaining)) } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        @JvmStatic fun closeGuiAsync(client: Minecraft?): CompletableFuture<Void> = CompletableFuture.runAsync({ closeGui(client) }, GUI_CLOSE_EXECUTOR)
        @JvmStatic fun forceReleaseMovementKeys() { val client = Minecraft.getInstance() ?: return; if (client.options == null) return; releaseKeyMapping(client.options.keyUp); releaseKeyMapping(client.options.keyDown); releaseKeyMapping(client.options.keyLeft); releaseKeyMapping(client.options.keyRight); releaseKeyMapping(client.options.keyJump); releaseKeyMapping(client.options.keyShift); releaseKeyMapping(client.options.keySprint) }
        @JvmStatic fun getCurrentLocation(): MacroState.Location = getCurrentLocation(Minecraft.getInstance())
        private fun getCurrentLocation(client: Minecraft?): MacroState.Location {
            if (client == null || client.level == null || client.player == null || client.connection == null) return MacroState.Location.UNKNOWN
            if (!client.isSameThread) { val future = CompletableFuture<MacroState.Location>(); client.execute { future.complete(getCurrentLocation(client)) }; try { return future.get(1L, TimeUnit.SECONDS) } catch (_: Exception) { return MacroState.Location.UNKNOWN } }
            val scoreboard = client.level!!.scoreboard
            val sidebar = scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return MacroState.Location.LIMBO
            var hasLobbyItems = false
            for (i in 0 until 9) { val stack = client.player!!.inventory.getItem(i); if (stack != null && !stack.isEmpty) { val itemName = TablistUtils.stripColors(stack.hoverName.string).trim(); if (itemName.contains("Game Menu") || itemName.contains("My Profile")) { hasLobbyItems = true; break } } }
            return SkyblockLocation.resolve(sidebar.displayName.string, hasLobbyItems, TablistUtils.findLine(client, "Area:"))
        }
        @JvmStatic fun isSupportedHudArea() = isSupportedHudArea(getCurrentLocation())
        @JvmStatic fun isSupportedHudArea(location: MacroState.Location) = location == MacroState.Location.GARDEN || location == MacroState.Location.CRYSTAL_HOLLOWS
        @JvmStatic fun getJacobsContestRemainingMs(): Long { val utcTime = LocalTime.now(ZoneOffset.UTC); val minute = utcTime.minute; if (minute < JACOBS_CONTEST_START_MINUTE_UTC || minute >= JACOBS_CONTEST_END_MINUTE_UTC) return 0L; return Duration.between(utcTime, utcTime.withMinute(JACOBS_CONTEST_END_MINUTE_UTC).withSecond(0).withNano(0)).toMillis() }
        @JvmStatic fun getPurse(): Long {
            val client = Minecraft.getInstance(); if (client.level == null || client.player == null) return -1L
            val scoreboard = client.level!!.scoreboard ?: return -1L; val sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return -1L
            for (entry in scoreboard.listPlayerScores(sidebar)) { val entryName = entry.owner(); val team = scoreboard.getPlayersTeam(entryName); val fullText = if (team != null) team.playerPrefix.string + entryName + team.playerSuffix.string else entryName; val line = fullText.replace(Regex("(?i)\\u00A7[0-9A-FK-ORZ]"), "").trim(); val balance = dev.aether.modules.profit.ProfitTrackingRules.parsePurseLine(line); if (balance >= 0) return balance }
            return -1L
        }
        @JvmStatic fun getCurrentPlot(): String {
            val client = Minecraft.getInstance(); if (client.level == null || client.player == null) return "Unknown"
            val scoreboard = client.level!!.scoreboard ?: return "Unknown"; val sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return "Unknown"
            val scores = scoreboard.listPlayerScores(sidebar)
            for (entry in scores) { val entryName = entry.owner(); val team = scoreboard.getPlayersTeam(entryName); val fullText = if (team != null) team.playerPrefix.string + entryName + team.playerSuffix.string else entryName; val line = fullText.replace(Regex("(?i)\u00A7."), "").trim(); if (line.lowercase().contains("plot")) { val matcher = Pattern.compile("plot\\s*[:\\-#]\\s*([a-z0-9]+)", Pattern.CASE_INSENSITIVE).matcher(line); if (matcher.find()) return matcher.group(1).trim() } }
            sendDebugMessage("Failed to find Plot in Scoreboard. Lines found:"); for (entry in scores) { val entryName = entry.owner(); val team = scoreboard.getPlayersTeam(entryName); val fullText = if (team != null) team.playerPrefix.string + entryName + team.playerSuffix.string else entryName; sendDebugMessage(" - " + fullText.replace(Regex("(?i)\u00A7[0-9A-FK-ORZ]"), "")) }; return "Unknown"
        }
        @JvmStatic fun getSidebarLines(): List<String> {
            val client = Minecraft.getInstance(); if (client == null || client.level == null || client.player == null) return java.util.Collections.emptyList<String>() as java.util.List<String>
            if (!client.isSameThread) { val future = CompletableFuture<List<String>>(); client.execute { future.complete(getSidebarLines()) }; try { return future.get(1, TimeUnit.SECONDS) } catch (_: Exception) { return java.util.Collections.emptyList<String>() as java.util.List<String> } }
            val scoreboard = client.level!!.scoreboard; val sidebar = scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR); if (sidebar == null || client.connection == null) return java.util.Collections.emptyList<String>() as java.util.List<String>
            val tick = client.player!!.tickCount; val connectionId = System.identityHashCode(client.connection)
            synchronized(SIDEBAR_CACHE_LOCK) { if (tick == sidebarCacheTick && connectionId == sidebarCacheConnectionId) return sidebarCacheLines }
            val lines = ArrayList<String>(); for (entry in scoreboard!!.listPlayerScores(sidebar)) { val entryName = entry.owner(); val team = scoreboard.getPlayersTeam(entryName); val fullText = if (team != null) team.playerPrefix.string + entryName + team.playerSuffix.string else entryName; lines.add(STRIP_SCOREBOARD_FORMATTING.matcher(fullText).replaceAll("").trim()) }
            val cachedLines: List<String> = lines as List<String>; synchronized(SIDEBAR_CACHE_LOCK) { sidebarCacheTick = tick; sidebarCacheConnectionId = connectionId; sidebarCacheLines = cachedLines; return sidebarCacheLines }
        }
        @JvmStatic fun hasLineOfSight(player: Player, target: Vec3): Boolean { if (player.level() == null) return false; val context = ClipContext(player.eyePosition, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player); val result: BlockHitResult = player.level().clip(context); return result.type == HitResult.Type.MISS }
        @JvmStatic fun lookAt(player: Player, target: Vec3) { val rot = RotationUtils.calculateLookAt(player.eyePosition, target); val adjustedRot = RotationUtils.getAdjustedEnd(RotationUtils.Rotation(player.yRot, player.xRot), rot); player.yRot = adjustedRot.yaw; player.xRot = adjustedRot.pitch; FailsafeManager.expectRotation(adjustedRot.yaw, adjustedRot.pitch) }
        @JvmStatic @Throws(InterruptedException::class) fun sleepRandom(min: Int, max: Int) { val sleepTime = min + (Math.random() * (max - min + 1)).toLong(); Thread.sleep(sleepTime) }
        @JvmStatic fun waitForGearAndGui() { val client = Minecraft.getInstance(); try { val loadoutStart = System.currentTimeMillis(); while (LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - loadoutStart < 6000) Thread.sleep(50); if (LoadoutManager.isSwappingLoadout) { sendDebugMessage("\u00A7eWARNING: Loadout swap detection timeout. Force-completing and resuming sequence..."); LoadoutManager.forceLoadoutCompletionFailsafe(client) }; val guiStart = System.currentTimeMillis(); while (client.gui.screen() != null && System.currentTimeMillis() - guiStart < 5000) Thread.sleep(50); Thread.sleep(50) } catch (_: InterruptedException) { } }
        @JvmStatic fun waitForWardrobeGui() { val client = Minecraft.getInstance(); try { val start = System.currentTimeMillis(); var lastRetry = start; var retryCount = 0; while (!LoadoutManager.loadoutGuiDetected && System.currentTimeMillis() - start < 5000) { if (!LoadoutManager.isSwappingLoadout) return; val now = System.currentTimeMillis(); if (now - lastRetry >= 500) { retryCount++; sendDebugMessage("Loadout GUI not detected after " + (now - start) + "ms. Retrying /loadout (" + retryCount + ")"); client.execute { sendCommand("/loadout") }; lastRetry = now }; Thread.sleep(50) } } catch (_: InterruptedException) { } }
        @JvmStatic fun waitForYChange(startY: Double, timeoutMs: Long): Boolean { val client = Minecraft.getInstance(); if (client.player == null) return false; val startTime = System.currentTimeMillis(); while (System.currentTimeMillis() - startTime < timeoutMs) { val currentY = client.player!!.y; if (Math.abs(currentY - startY) > 0.1) { sendDebugMessage("AOTV Y-change detected: " + String.format("%.2f", startY) + " -> " + String.format("%.2f", currentY) + " (+" + (System.currentTimeMillis() - startTime) + "ms)"); return true }; try { Thread.sleep(10) } catch (_: InterruptedException) { break } }; sendDebugMessage("AOTV Y-change timeout after " + timeoutMs + "ms. startY=" + String.format("%.2f", startY) + " currentY=" + String.format("%.2f", client.player!!.y)); return false }
        @JvmStatic fun findAspectOfTheVoidSlot(): Int { val client = Minecraft.getInstance(); if (client.player == null) return -1; for (i in 0 until 36) { val stack = client.player!!.inventory.getItem(i); if (stack != null && !stack.isEmpty) { val itemName = stack.hoverName.string.replace(Regex("\u00A7[0-9a-fk-or]"), "").trim(); val lowercaseName = itemName.lowercase(); if (lowercaseName.contains("aspect of the void") || lowercaseName.contains("aspect of the end")) return i } }; return -1 }
        @JvmStatic fun performShiftRightClick() { val client = Minecraft.getInstance(); if (client.player == null || client.options == null) return; val clickId = USE_CLICK_SEQUENCE.incrementAndGet(); val holdMs = randomizedClickHoldMs(); client.execute { setKeyMappingState(client.options.keyShift, true); scheduleClientAction(client, 60) { if (USE_CLICK_SEQUENCE.get() != clickId) return@scheduleClientAction; setKeyMappingState(client.options.keyUse, true); scheduleClientAction(client, holdMs) { if (USE_CLICK_SEQUENCE.get() == clickId) { setKeyMappingState(client.options.keyUse, false); setKeyMappingState(client.options.keyShift, false) } } } } }
        @JvmStatic fun performShiftLeftClick() { val client = Minecraft.getInstance(); if (client == null || client.options == null) return; client.execute { if (client.player != null) client.player!!.setShiftKeyDown(true); setKeyMappingState(client.options.keyShift, true); scheduleClientAction(client, 60) { if (client.player == null) return@scheduleClientAction; clickKeyMapping(client.options.keyAttack) } } }
        @JvmStatic fun releaseShiftKey() { val client = Minecraft.getInstance(); if (client == null || client.options == null) return; client.execute { setKeyMappingState(client.options.keyShift, false); if (client.player != null) client.player!!.setShiftKeyDown(false) } }
        @JvmStatic fun performUseClick() = performUseClick(null)
        @JvmStatic fun performUseClick(beforePress: Runnable?) { val client = Minecraft.getInstance(); if (client == null || client.options == null) return; pressAndReleaseInput(client, USE_CLICK_SEQUENCE, { beforePress?.run(); setKeyMappingState(client.options.keyUse, true) }, { setKeyMappingState(client.options.keyUse, false) }, randomizedClickHoldMs()) }
        @JvmStatic fun performUseClickNow() { val client = Minecraft.getInstance(); if (client == null || client.options == null) return; pressAndReleaseInput(client, USE_CLICK_SEQUENCE, { setKeyMappingState(client.options.keyUse, true); clickKeyMapping(client.options.keyUse) }, { setKeyMappingState(client.options.keyUse, false) }, randomizedClickHoldMs()) }
        @JvmStatic fun performUseClickInstant() { val client = Minecraft.getInstance(); if (client == null || client.options == null) return; USE_CLICK_SEQUENCE.incrementAndGet(); client.execute { setKeyMappingState(client.options.keyUse, true); clickKeyMapping(client.options.keyUse); setKeyMappingState(client.options.keyUse, false) } }
        @JvmStatic fun beginUseHoldNow() { val client = Minecraft.getInstance(); if (client == null || client.options == null) return; USE_CLICK_SEQUENCE.incrementAndGet(); client.execute { setKeyMappingState(client.options.keyUse, true); clickKeyMapping(client.options.keyUse) } }
        @JvmStatic fun endUseHold() { val client = Minecraft.getInstance(); if (client == null || client.options == null) return; USE_CLICK_SEQUENCE.incrementAndGet(); client.execute { setKeyMappingState(client.options.keyUse, false); discardQueuedClicks(client.options.keyUse) } }
        @JvmStatic fun performAttackClickDirect() { val client = Minecraft.getInstance() ?: return; client.execute { if (client.player == null) return@execute; clickKeyMapping(client.options.keyAttack) } }
        @JvmStatic fun performAttackClick() { val client = Minecraft.getInstance(); if (client == null || client.options == null) return; if (UngrabMouse.isMouseUngrabbed() && client.gui.screen() == null) { client.execute { if (client.player == null) return@execute; clickKeyMapping(client.options.keyAttack) }; return }; pressAndReleaseInput(client, ATTACK_CLICK_SEQUENCE, { setKeyMappingState(client.options.keyAttack, true) }, { setKeyMappingState(client.options.keyAttack, false) }, randomizedClickHoldMs()) }
        @JvmStatic fun performSlotClick(screen: AbstractContainerScreen<*>, slotIndex: Int, mouseButton: Int, type: ContainerInput) { val client = Minecraft.getInstance(); if (client.player == null || screen.menu == null) return; val slots = screen.menu.slots; if (slotIndex < 0 || slotIndex >= slots.size) return; val slot = slots[slotIndex]; (screen as AccessorAbstractContainerScreen).invokeSlotClicked(slot, slot.index, mouseButton, type) }
        @JvmStatic fun getGuiClickDelayMs(firstClick: Boolean): Long = if (firstClick) ConfigHelpers.getRandomizedDelay(AetherConfig.GUI_FIRST_CLICK_DELAY_MIN.get(), AetherConfig.GUI_FIRST_CLICK_DELAY_MAX.get()).toLong() else ConfigHelpers.getRandomizedDelay(AetherConfig.GUI_CLICK_DELAY_MIN.get(), AetherConfig.GUI_CLICK_DELAY_MAX.get()).toLong()
        @JvmStatic fun performHotbarSlotClick(slot: Int) { val client = Minecraft.getInstance(); if (client == null || client.options == null || slot < 0 || slot >= client.options.keyHotbarSlots.size) return; if (client.isSameThread) clickKeyMapping(client.options.keyHotbarSlots[slot]) else client.execute { clickKeyMapping(client.options.keyHotbarSlots[slot]) } }
        @JvmStatic fun discardQueuedClicks(mapping: KeyMapping?) { if (mapping == null) return; while (mapping.consumeClick()) { } }
        @JvmStatic fun setKeyMappingState(mapping: KeyMapping?, down: Boolean) { if (mapping == null) return; mapping.setDown(down) }
        @JvmStatic fun reapplyProgrammaticKeyStates(client: Minecraft?) { if (client == null || client.options == null) return; reapplyProgrammaticKey(client.options.keyLeft); reapplyProgrammaticKey(client.options.keyRight); reapplyProgrammaticKey(client.options.keyUp); reapplyProgrammaticKey(client.options.keyDown); reapplyProgrammaticKey(client.options.keyShift); reapplyProgrammaticKey(client.options.keySprint); reapplyProgrammaticKey(client.options.keyJump); if (ProgrammaticAttackTracker.isHeld()) setKeyMappingState(client.options.keyAttack, true) }
        private fun reapplyProgrammaticKey(mapping: KeyMapping) { if (ProgrammaticMovementTracker.isDown(mapping)) setKeyMappingState(mapping, true) }
        private fun releaseKeyMapping(mapping: KeyMapping?) { setKeyMappingState(mapping, false); ProgrammaticMovementTracker.clear(mapping) }
        private fun restorePhysicalKeyStates(client: Minecraft?) { if (client == null) return; if (client.isSameThread) KeyMapping.setAll() else client.execute { KeyMapping.setAll() } }
        @JvmStatic fun clickKeyMapping(mapping: KeyMapping?) { if (mapping == null) return; val key = getBoundKey(mapping); if (key != null && key != InputConstants.UNKNOWN) KeyMapping.click(key) }
        private fun pressAndReleaseInput(client: Minecraft, sequence: AtomicLong, pressAction: Runnable, releaseAction: Runnable, holdMs: Long) { val clickId = sequence.incrementAndGet(); if (client.isSameThread) { pressAction.run(); scheduleClientAction(client, holdMs) { if (sequence.get() == clickId) releaseAction.run() }; return }; client.execute(pressAction); try { Thread.sleep(holdMs) } catch (_: InterruptedException) { }; client.execute { if (sequence.get() == clickId) releaseAction.run() } }
        private fun scheduleClientAction(client: Minecraft, delayMs: Long, action: Runnable) { INPUT_EXECUTOR.schedule({ client.execute(action) }, delayMs, TimeUnit.MILLISECONDS) }
        @JvmStatic fun scheduleClientTask(client: Minecraft?, delayMs: Long, action: Runnable?) { if (client == null || action == null) return; scheduleClientAction(client, delayMs, action) }
        private fun getBoundKey(mapping: KeyMapping): InputConstants.Key = (mapping as AccessorKeyMapping).getKey()
        @JvmStatic fun waitForRotationToComplete(targetPitch: Float, rotationTime: Int) { val client = Minecraft.getInstance(); if (client.player == null) return; val startTime = System.currentTimeMillis(); val timeout = 5000L; while (System.currentTimeMillis() - startTime < timeout) { val currentPitch = client.player!!.xRot; val pitchDiff = Math.abs(currentPitch - targetPitch); if (pitchDiff < 1.0f) break; try { Thread.sleep(50) } catch (_: InterruptedException) { } } }
    }
}
