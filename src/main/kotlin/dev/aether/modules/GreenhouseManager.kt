package dev.aether.modules

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.config.AetherConfig
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.AetherLang
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.entity.SkullBlockEntity
import java.util.ArrayList
import java.util.Base64
import java.util.Collections
import java.util.Comparator
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadLocalRandom

class GreenhouseManager {
    companion object {
        private const val SKULL_SCAN_RADIUS = 25
        @JvmField val highlightedSkulls: MutableList<AABB> = ArrayList()
        @JvmField val currentPath: MutableList<Vec3> = ArrayList()
        @JvmField val flyToPoints: MutableList<Vec3> = ArrayList()
        private val configuredPlots = ArrayList<String>()
        private var currentSkullIndex = -1
        private var currentPlotIndex = -1
        private var sneakLocked = false
        private var currentPlotStartedAt = 0L
        @Volatile private var running = false
        @Volatile private var autoSequence = false
        @Volatile private var lastAutoRunCompletedMs = System.currentTimeMillis()
        @Volatile private var completionCallback: Runnable? = null
        private const val PLOT_HANDOFF_MIN_MS = 3000L
        private const val TARGET_SETTLE_DELAY_MS = 100
        private const val ROTATION_ACK_TIMEOUT_MS = 1000
        private const val ASHWREATH_SKIN_ID = "5890f50780fdecedaa85aa40bf3399e9439ee68594c6d022688165608171681d"
        private const val TURTELLINI_SKIN_ID = "1d1bd06a6738d0da5053eae49a1362b89489d1ac004c222504536f7bcd07679d"
        private const val GLASSCORN_SKIN_ID = "297de27338b9f876e570d1cc01fe1beccfc940467c5c97c467e93e79c81c25ee"
        private fun client(): Minecraft = Minecraft.getInstance()
        @JvmStatic fun isRunning(): Boolean = running
        @JvmStatic fun reset() {
            highlightedSkulls.clear(); currentPath.clear(); flyToPoints.clear(); configuredPlots.clear()
            currentSkullIndex = -1; currentPlotIndex = -1; currentPlotStartedAt = 0L; sneakLocked = false
            running = false; autoSequence = false; completionCallback = null
        }
        @JvmStatic fun getAutoGreenhouseElapsedMs(): Long = Math.max(0L, System.currentTimeMillis() - lastAutoRunCompletedMs)
        @JvmStatic fun shouldRunAutoGreenhouse(): Boolean {
            if (!AetherConfig.AUTO_GREENHOUSE.get() || running) return false
            if (AetherConfig.GREENHOUSE_PLOTS.get().isEmpty()) return false
            val intervalMs = Math.max(1L, AetherConfig.AUTO_GREENHOUSE_INTERVAL_MINUTES.get().toLong()) * 60_000L
            return getAutoGreenhouseElapsedMs() >= intervalMs
        }
        @JvmStatic fun runAutoGreenhouseIfDue(onComplete: Runnable) {
            if (!shouldRunAutoGreenhouse()) { runCompletion(onComplete); return }
            autoSequence = true; completionCallback = onComplete; harvest()
        }
        @JvmStatic fun harvest() {
            val mc = Minecraft.getInstance()
            val player = mc.player; val level = mc.level
            if (player == null || level == null) { runCompletion(completionCallback); completionCallback = null; autoSequence = false; return }
            if (running) { runCompletion(completionCallback); completionCallback = null; autoSequence = false; return }
            val plots = AetherConfig.GREENHOUSE_PLOTS.get().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (plots.isEmpty()) { ClientUtils.sendMessage("\u00A7cNo greenhouse plots configured!", false); return }
            if (plots.isEmpty()) { ClientUtils.sendMessage("\u00A7cNo greenhouse plots configured!", false); return }
            running = true
            if (!equipHarvestTool()) {
                running = false; configuredPlots.clear(); val callback = completionCallback; completionCallback = null; autoSequence = false; runCompletion(callback); return
            }
            configuredPlots.clear(); configuredPlots.addAll(plots); currentPlotIndex = -1; clearSneakState()
            val elapsedMs = if (currentPlotStartedAt <= 0L) PLOT_HANDOFF_MIN_MS else Math.max(0L, System.currentTimeMillis() - currentPlotStartedAt.toLong())
            val remainingDelayMs = Math.max(0L, PLOT_HANDOFF_MIN_MS - elapsedMs.toLong()); currentPlotStartedAt = 0L
            if (remainingDelayMs <= 0L) { startNextPlot(); return }
            MacroWorkerThread.getInstance().submit("GreenhouseNextPlotDelay") { MacroWorkerThread.sleep(remainingDelayMs); mc.execute { startNextPlot() } }
        }
        private fun startHarvesting() {
            if (currentPath.isEmpty()) {
                val plot = if (currentPlotIndex >= 0 && currentPlotIndex < configuredPlots.size) configuredPlots[currentPlotIndex] else "Unknown"
                ClientUtils.sendDebugMessage("No targets found on plot $plot, moving on."); onCurrentPlotFinished(); return
            }
            flyToPoints.clear(); sneakLocked = false
            for (p in currentPath) { // Walk directly to the skull target.
                flyToPoints.add(p)
            }
            currentSkullIndex = 0; ClientUtils.sendMessage("\u00A7eStarting automated greenhouse harvest...", false); flyToNextSkull(true)
        }
        private fun flyToNextSkull(isFirst: Boolean) {
            val mc = client()
            if (mc.player == null || currentSkullIndex < 0 || currentSkullIndex >= flyToPoints.size) {
                releaseMovementKeys(); ClientUtils.sendMessage("\u00A7aGreenhouse harvest complete!", false); currentSkullIndex = -1; onCurrentPlotFinished(); return
            }
            val target = flyToPoints[currentSkullIndex]
            PathfindingManager.startGreenhouseWalk(mc, target, {
                sneakLocked = sneakLocked || isFirst || PathfindingManager.isWalkSneakLatched(); PathfindingManager.setWalkSneakLatched(sneakLocked); currentSkullIndex++
                MacroWorkerThread.getInstance().submit("GreenhouseNextTargetDelay") {
                    mc.execute { releaseMovementKeys() }; MacroWorkerThread.sleep(TARGET_SETTLE_DELAY_MS.toLong())
                    val rotationQueued = CountDownLatch(1); val rotationMs = longArrayOf(0L)
                    mc.execute { try { releaseMovementKeys(); rotationMs[0] = rotateHumanizedToNextTarget(target) } finally { rotationQueued.countDown() } }
                    try { if (!rotationQueued.await(ROTATION_ACK_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)) ClientUtils.sendDebugMessage("Greenhouse rotation queue timed out") }
                    catch (e: InterruptedException) { Thread.currentThread().interrupt(); return@submit }
                    if (rotationMs[0] > 0L) MacroWorkerThread.sleep(rotationMs[0])
                    mc.execute { leftClick() }; MacroWorkerThread.sleep(ThreadLocalRandom.current().nextInt(100, 501).toLong()); mc.execute { flyToNextSkull(false) }
                }
            }, isFirst)
        }
        private fun startNextPlot() {
            val mc = client(); val player = mc.player; if (player == null) { finishSequence(); return }
            currentPlotIndex++; if (currentPlotIndex >= configuredPlots.size) { finishSequence(); return }
            val targetPlot = configuredPlots[currentPlotIndex]; currentPlotStartedAt = System.currentTimeMillis(); clearSneakState()
            val currentPlot = ClientUtils.getCurrentPlot()
            if (targetPlot.equals(currentPlot, ignoreCase = true)) { ClientUtils.sendDebugMessage("Already on plot $targetPlot. Scanning..."); detectSkulls(); startHarvesting(); return }
            ClientUtils.sendDebugMessage("Teleporting to plot $targetPlot..."); val yaw = player.yRot; val pitch = player.xRot
            MacroWorkerThread.getInstance().submit("GreenhouseHarvest-Plot-$targetPlot") {
                val success = CommandUtils.plotTp(targetPlot)
                if (success) { try { Thread.sleep(500L) } catch (_: InterruptedException) { }; mc.execute { clearSneakState(); detectSkulls(yaw, pitch); startHarvesting() } }
                else { ClientUtils.sendDebugMessage("Failed to confirm teleport to plot $targetPlot"); mc.execute { startNextPlot() } }
            }
        }
        private fun onCurrentPlotFinished() {
            val plot = if (currentPlotIndex >= 0 && currentPlotIndex < configuredPlots.size) configuredPlots[currentPlotIndex] else "Unknown"
            ClientUtils.sendMessage("\u00A7aFinished plot $plot.", false); startNextPlot()
        }
        private fun finishSequence() {
            clearSneakState(); releaseMovementKeys(); configuredPlots.clear(); currentPlotIndex = -1; currentSkullIndex = -1; currentPlotStartedAt = 0L; running = false
            if (autoSequence) lastAutoRunCompletedMs = System.currentTimeMillis()
            autoSequence = false; val callback = completionCallback; completionCallback = null; runCompletion(callback); ClientUtils.sendMessage("\u00A7aGreenhouse harvest complete!", false)
        }
        private fun runCompletion(callback: Runnable?) { callback?.run() }
        private fun equipHarvestTool(): Boolean {
            val mc = client()
            if (AetherConfig.EQUIP_GREENHOUSE_CUSTOM_ITEM.get()) {
                val customItem = AetherConfig.GREENHOUSE_CUSTOM_ITEM.get().trim()
                if (customItem.isEmpty()) { ClientUtils.sendDebugMessage("\u00A7cGreenhouse: custom item name is blank."); return false }
                val swapped = GearManager.swapToNamedHotbarItemSync(mc, customItem); if (!swapped) ClientUtils.sendDebugMessage("\u00A7cGreenhouse: custom item not found in hotbar: $customItem"); return swapped
            }
            val slot = GearManager.findFarmingToolSlot(mc); if (slot == -1) { ClientUtils.sendDebugMessage("\u00A7cGreenhouse: no farming tool found in hotbar."); return false }
            GearManager.swapToFarmingToolSync(mc); return true
        }
        private fun clearSneakState() { val mc = client(); sneakLocked = false; PathfindingManager.setWalkSneakLatched(false); if (mc != null && mc.options != null) ClientUtils.setKeyMappingState(mc.options.keyShift, false) }
        private fun rotateHumanizedToNextTarget(nextTarget: Vec3): Long {
            val mc = client(); val player = mc.player; if (player == null) return 0L
            val lookTarget = toAimTarget(nextTarget); val base = RotationUtils.calculateLookAt(player.eyePosition, lookTarget); val rotationMs = ThreadLocalRandom.current().nextLong(300L, 601L)
            ClientUtils.sendDebugMessage(String.format(Locale.US, "Greenhouse aim target: %.3f, %.3f, %.3f", lookTarget.x, lookTarget.y, lookTarget.z))
            ClientUtils.sendDebugMessage(String.format(Locale.US, "Greenhouse rotation needed: yaw %.2f, pitch %.2f, duration %dms", base.yaw, base.pitch, rotationMs))
            RotationManager.rotateToYawPitch(mc, base.yaw, base.pitch, rotationMs); return rotationMs
        }
        private fun toAimTarget(target: Vec3) = Vec3(target.x, Math.floor(target.y) + 0.5, target.z)
        private fun leftClick() { val mc = client(); if (mc == null || mc.options == null) return; ClientUtils.setKeyMappingState(mc.options.keyAttack, true); ClientUtils.clickKeyMapping(mc.options.keyAttack); ClientUtils.setKeyMappingState(mc.options.keyAttack, false) }
        private fun releaseMovementKeys() {
            val mc = client(); if (mc == null || mc.options == null) return
            ClientUtils.setKeyMappingState(mc.options.keyUp, false); ClientUtils.setKeyMappingState(mc.options.keyDown, false); ClientUtils.setKeyMappingState(mc.options.keyLeft, false); ClientUtils.setKeyMappingState(mc.options.keyRight, false)
            ClientUtils.setKeyMappingState(mc.options.keyJump, false); ClientUtils.setKeyMappingState(mc.options.keySprint, false); ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLocked)
        }
        @JvmStatic fun detectSkulls() { val mc = Minecraft.getInstance(); val player = mc.player; detectSkulls(if (player != null) player.yRot else 0f, if (player != null) player.xRot else 0f) }
        @JvmStatic fun detectSkulls(yaw: Float, pitch: Float) {
            val mc = client(); if (mc == null) return
            highlightedSkulls.clear(); if (mc.player == null) return
            scanNearbySkulls { entry -> processProfile(entry.profile(), entry.x(), entry.y(), entry.z()) }
            updatePath(yaw, pitch); ClientUtils.sendDebugMessage("Detected ${getHighlightedCount()} matching skulls. Path generated.")
        }
        @JvmStatic fun debugScanSkulls() {
            val mc = Minecraft.getInstance(); val player = mc.player ?: return
            val entries = ArrayList<SkullScanEntry>(); scanNearbySkulls { entries.add(it) }
            if (entries.isEmpty()) { ClientUtils.sendMessage(String.format(Locale.US, "\u00A7e" + AetherLang.localize("No skulls with owner profiles found within %d blocks."), SKULL_SCAN_RADIUS), false); return }
            entries.sortWith(Comparator.comparingDouble { entry -> player.position().distanceToSqr(entry.x(), entry.y(), entry.z()) })
            ClientUtils.sendMessage(String.format(Locale.US, "\u00A7e" + AetherLang.localize("Scanned %d skulls within %d blocks."), entries.size, SKULL_SCAN_RADIUS), false)
            for (entry in entries) ClientUtils.sendMessage("\u00A77" + String.format(Locale.US, AetherLang.localize("Skull %s (%s) ID: %s at %.1f, %.1f, %.1f"), entry.name(), entry.sourceLabel(), entry.skinId(), entry.x(), entry.y(), entry.z()), false)
        }
        private fun updatePath(yaw: Float, pitch: Float) {
            val mc = client(); val player = mc.player; currentPath.clear(); if (highlightedSkulls.isEmpty()) return
            val points = highlightedSkulls.map { it.center }
            val yawRad = Math.toRadians(yaw.toDouble()).toFloat(); val forward = Vec3(Math.sin(-yawRad.toDouble()), 0.0, Math.cos(yawRad.toDouble())); val right = Vec3(Math.cos(-yawRad.toDouble()), 0.0, -Math.sin(yawRad.toDouble()))
            val yAscending = pitch >= 0
            val layers: MutableMap<Int, MutableList<Vec3>> = if (yAscending) TreeMap() else TreeMap(Collections.reverseOrder())
            for (p in points) layers.computeIfAbsent(Math.round(p.y).toInt()) { ArrayList() }.add(p)
            val fullPath = ArrayList<Vec3>(); var reverseRow = false
            for (yKey in layers.keys) {
                val layerPoints = layers[yKey] ?: continue; val rows: MutableMap<Int, MutableList<Vec3>> = TreeMap()
                for (p in layerPoints) rows.computeIfAbsent(Math.floor(p.dot(forward)).toInt()) { ArrayList() }.add(p)
                for (rowKey in rows.keys) { val rowPoints = rows[rowKey] ?: continue; rowPoints.sortWith(Comparator.comparingDouble { p -> p.dot(right) }); if (reverseRow) Collections.reverse(rowPoints); fullPath.addAll(rowPoints); reverseRow = !reverseRow }
            }
            if (player != null && fullPath.isNotEmpty()) { val startDist = fullPath[0].distanceToSqr(player.position()); val endDist = fullPath[fullPath.size - 1].distanceToSqr(player.position()); if (endDist < startDist) Collections.reverse(fullPath) }
            currentPath.addAll(fullPath)
        }
        @JvmStatic fun getHighlightedCount(): Int = highlightedSkulls.size
        @JvmStatic fun getCurrentTarget(): Vec3? = if (currentSkullIndex >= 0 && currentSkullIndex < flyToPoints.size) flyToPoints[currentSkullIndex] else null
        @JvmStatic fun getCurrentAimTarget(): Vec3? { val target = getCurrentTarget(); return if (target == null) null else toAimTarget(target) }
        private fun scanNearbySkulls(consumer: (SkullScanEntry) -> Unit) {
            val mc = client(); val player = mc.player ?: return; val level = mc.level ?: return
            for (entity in level.entitiesForRendering()) {
                if (entity !is ArmorStand) continue
                val headItem = entity.getItemBySlot(EquipmentSlot.HEAD); if (headItem.isEmpty) continue
                val profile = headItem.get(DataComponents.PROFILE)
                if (profile != null) consumer(SkullScanEntry(profile, entity.x, entity.y + entity.eyeHeight, entity.z, AetherLang.localize("armor stand")))
            }
            val base = player.blockPosition()
            for (x in -SKULL_SCAN_RADIUS..SKULL_SCAN_RADIUS) for (y in -10..10) for (z in -SKULL_SCAN_RADIUS..SKULL_SCAN_RADIUS) {
                val pos = base.offset(x, y, z); val be = level.getBlockEntity(pos); if (be !is SkullBlockEntity) continue
                val profile = be.ownerProfile
                if (profile != null) consumer(SkullScanEntry(profile, pos.x + 0.5, pos.y + 0.25, pos.z + 0.5, AetherLang.localize("skull block")))
            }
        }
        private fun processProfile(profile: ResolvableProfile, x: Double, y: Double, z: Double) {
            val skinId = extractSkinId(profile); var shouldHighlight = false
            if (AetherConfig.HARVEST_ASHWREATH.get() && skinId == ASHWREATH_SKIN_ID) shouldHighlight = true
            if (AetherConfig.HARVEST_TURTELLINI.get() && skinId == TURTELLINI_SKIN_ID) shouldHighlight = true
            if (AetherConfig.HARVEST_GLASSCORN.get() && skinId == GLASSCORN_SKIN_ID) shouldHighlight = true
            if (shouldHighlight) { val name = profile.name().orElse("Skull"); highlightedSkulls.add(AABB(x - 0.25, y - 0.25, z - 0.25, x + 0.25, y + 0.25, z + 0.25)); ClientUtils.sendDebugMessage(String.format("Found highlighted: %s (ID: %s) at %.1f, %.1f, %.1f", name, skinId, x, y, z)) }
            else { // Still show debug in chat if debug mode is on, even if not highlighted
                val name = profile.name().orElse("Skull"); ClientUtils.sendDebugMessage(String.format("Filtered: %s (ID: %s) at %.1f, %.1f, %.1f", name, skinId, x, y, z))
            }
        }
        private fun extractSkinId(profile: ResolvableProfile): String {
            val properties = profile.partialProfile().properties(); if (properties == null) return "Unknown"
            val textures = properties.get("textures"); if (textures == null || textures.isEmpty()) return "Unknown"
            val value = textures.iterator().next().value()
            try {
                val decodedJson = String(Base64.getDecoder().decode(value)); val root = JsonParser.parseString(decodedJson)
                if (root.isJsonObject) {
                    val textureObject: JsonObject? = root.asJsonObject.getAsJsonObject("textures")
                    if (textureObject != null) { val skinObject = textureObject.getAsJsonObject("SKIN"); if (skinObject != null && skinObject.has("url")) { val extracted = extractSkinIdFromUrl(skinObject.get("url").asString); if ("Unknown" != extracted) return extracted } }
                }
                val textureUrlIndex = decodedJson.indexOf("textures.minecraft.net/texture/")
                if (textureUrlIndex != -1) { val idStart = textureUrlIndex + "textures.minecraft.net/texture/".length; val idEnd = decodedJson.indexOf("\"", idStart); if (idEnd != -1 && idStart < idEnd) return decodedJson.substring(idStart, idEnd) }
            } catch (_: Exception) { }
            return "Unknown"
        }
        private fun extractSkinIdFromUrl(url: String?): String {
            if (url == null || url.isBlank()) return "Unknown"; val lastSlash = url.lastIndexOf('/'); if (lastSlash == -1 || lastSlash >= url.length - 1) return "Unknown"; return url.substring(lastSlash + 1)
        }
    }
    private class SkullScanEntry(private val profileValue: ResolvableProfile, private val xValue: Double, private val yValue: Double, private val zValue: Double, private val sourceLabelValue: String) {
        fun profile() = profileValue
        fun x() = xValue
        fun y() = yValue
        fun z() = zValue
        fun sourceLabel() = sourceLabelValue
        fun name(): String = profileValue.name().orElse("Skull")
        fun skinId(): String = Companion.extractSkinId(profileValue)
    }
}
