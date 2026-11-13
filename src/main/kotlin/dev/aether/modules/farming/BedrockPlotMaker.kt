package dev.aether.modules.farming

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.config.AetherConfig
import dev.aether.macro.MacroInput
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.MacroState
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.notification.NotificationManager
import dev.aether.util.ClientUtils
import dev.aether.util.GardenPlots
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Comparator
import kotlin.collections.MutableList
import java.util.OptionalInt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class BedrockPlotMaker private constructor() {
    companion object {
        private const val PLOT_SIZE = 96
        private const val PLOT_OFFSET = 48
        private const val TARGET_Y = 71
        private const val MIN_Y = 66
        private const val MAX_Y = 120
        private const val PATH_TIMEOUT_MS = 90_000L
        private const val REMOVE_TIMEOUT_MS = 25_000L
        private const val ROTATION_MS = 450L
        private const val COLUMN_CLEAR_TIMEOUT_MS = 8_000L
        private const val STEP_RIGHT_TIMEOUT_MS = 4_000L
        private const val SHOVEL_CORNER_TIMEOUT_MS = 90_000L
        private const val ARCH_BREAK_TIMEOUT_MS = 4_000L
        private const val ARCH_ROTATION_MS = 180L
        private const val HELD_USE_QUIET_MS = 1_200L
        private const val CORNER_FINISH_ROWS = 3
        private const val CORNER_FINISH_Y = 68
        private const val UPPER_BREAK_Y_OFFSET = 2
        private const val ARCH_SCAN_HALF_WIDTH = 8
        private const val ARCH_REACH = 5.0
        private const val ARCH_WALL_INSET = 0.3
        private const val EAST_YAW = -90.0f
        private const val DOWN_PITCH = 90.0f
        private const val NORTH_YAW = -180.0f
        private const val TRENCH_PITCH = -60.0f
        private const val RETURN_YAW = 180.0f
        private const val RETURN_PITCH = 40.0f
        private const val FINISH_SOUTH_YAW = 0.0f
        private const val FINISH_WEST_YAW = 90.0f
        private const val SHOVEL_CORNER_PITCH = -40.0f
        private const val PICKAXE_UP_PITCH = -90.0f
        private const val YAW_TOLERANCE = 4.0f
        private const val PITCH_TOLERANCE = 2.0f
        // one column east of the inclusive west edge
        private const val STAND_EAST_OFFSET = 1.5
        // centered on the south-interior block so the ruler drops cleanly
        private const val STAND_Z_OFFSET = -0.5
        private const val TARGET_TOLERANCE = 0.08
        private const val BEDROCK_RAY_DISTANCE = 8.0
        private val REMOVED_BLOCKS = Pattern.compile("(?i)you removed\\s+\\d+\\s+blocks!?")

        @Volatile private var running = false
        @Volatile private var countingRemovals = false
        @Volatile private var rotationLockActive = false
        @Volatile private var lockedYaw = 0.0f
        @Volatile private var lockedPitch = 0.0f
        private val removedMessages = AtomicInteger(0)
        private const val BEDROCK_STANDING_Y = MIN_Y + 1

        @JvmStatic fun isRunning(): Boolean = running

        @JvmStatic fun hasVisibleHighlights(): Boolean {
            val mc = Minecraft.getInstance()
            return running && mc.level != null && mc.player != null && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN && targetBounds(mc) != null
        }

        @JvmStatic fun renderWorld() {
            if (!hasVisibleHighlights()) {
                return
            }
            val mc = Minecraft.getInstance()
            val bounds = targetBounds(mc) ?: return

            // AABB max edges are exclusive, so the raw maxX/maxZ are correct here.
            val box = AABB(bounds.minX().toDouble(), MIN_Y.toDouble(), bounds.minZ().toDouble(), bounds.maxX().toDouble(), MAX_Y.toDouble(), bounds.maxZ().toDouble())
            val style = GizmoStyle.strokeAndFill(ARGB.color(220, 80, 210, 255), 3.0f, ARGB.color(34, 80, 210, 255))
            val props = Gizmos.cuboid(box, style)
            props.setAlwaysOnTop()
        }

        @JvmStatic fun setConfiguredPlotToCurrent(client: Minecraft) {
            val currentPlot = ClientUtils.getCurrentPlot()
            if (parsePlot(currentPlot).isPresent) {
                AetherConfig.BEDROCK_PLOT_MAKER_PLOT.set(currentPlot)
                AetherConfig.save()
            }
        }

        @JvmStatic fun start(client: Minecraft) {
            if (running) {
                return
            }

            val validationError = validateStart(client)
            if (validationError != null) {
                ClientUtils.sendMessage("\u00A7c" + validationError, false)
                return
            }

            running = true
            clearRotationLock()
            removedMessages.set(0)
            prepareRun(client)
            MacroWorkerThread.getInstance().submit("BedrockPlotMaker", Runnable { runSequence(client) })
        }

        private fun validateStart(client: Minecraft): String? {
            if (client.player == null || client.level == null) {
                return "World not ready."
            }

            val errors = ArrayList<String>()
            val missingItems = ArrayList<String>()
            if (!hasHotbarItem(client, Items.GOLDEN_PICKAXE)) {
                missingItems.add("Golden pickaxe")
            }
            if (!hasHotbarItem(client, Items.GOLDEN_SHOVEL)) {
                missingItems.add("Golden shovel")
            }
            if (GearManager.findHotbarItemSlot(client, "Builder's Ruler") < 0) {
                missingItems.add("Builder's ruler")
            }
            if (!missingItems.isEmpty()) {
                errors.add(java.lang.String.join(", ", missingItems) + " not found.")
            }

            val bounds = targetBounds(client)
            if (bounds == null) {
                errors.add("Plot not found.")
            } else {
                val plotError = validateSuperflatInterior(client, bounds)
                if (plotError != null) {
                    errors.add(plotError + ".")
                }
            }
            return if (errors.isEmpty()) null else java.lang.String.join(" ", errors)
        }

        private fun hasHotbarItem(client: Minecraft, item: net.minecraft.world.item.Item): Boolean {
            for (slot in 0 until 9) {
                if (client.player!!.getInventory().getItem(slot).`is`(item)) {
                    return true
                }
            }
            return false
        }

        private fun validateSuperflatInterior(client: Minecraft, bounds: PlotBounds): String? {
            val cursor = BlockPos.MutableBlockPos()
            val surfaceY = TARGET_Y - 1
            for (x in bounds.westBlockX() + 1 until bounds.eastBlockX()) {
                for (z in bounds.northBlockZ() + 1 until bounds.southBlockZ()) {
                    cursor.set(x, surfaceY, z)
                    if (!client.level!!.hasChunkAt(cursor)) {
                        return "Plot is not fully loaded"
                    }

                    val surface = client.level!!.getBlockState(cursor)
                    if (surface.isAir || !surface.isCollisionShapeFullBlock(client.level!!, cursor)) {
                        return "Plot is not superflat"
                    }
                    for (y in TARGET_Y..MAX_Y) {
                        if (!client.level!!.getBlockState(cursor.set(x, y, z)).isAir) {
                            return "Plot is not superflat"
                        }
                    }
                }
            }
            return null
        }

        @JvmStatic fun startTrenchStep(client: Minecraft) {
            if (running) {
                return
            }

            running = true
            clearRotationLock()
            removedMessages.set(0)
            prepareRun(client)
            MacroWorkerThread.getInstance().submit("BedrockPlotMaker-Trench", Runnable { runTrenchSequence(client) })
        }

        @JvmStatic fun startEndLaneStep(client: Minecraft) {
            if (running) {
                return
            }

            running = true
            clearRotationLock()
            removedMessages.set(0)
            prepareRun(client)
            MacroWorkerThread.getInstance().submit("BedrockPlotMaker-EndLane", Runnable { runEndLaneSequence(client) })
        }

        @JvmStatic fun startShovelCornerStep(client: Minecraft) {
            if (running) {
                return
            }

            running = true
            clearRotationLock()
            prepareRun(client)
            MacroWorkerThread.getInstance().submit("BedrockPlotMaker-ShovelCorner", Runnable { runShovelCornerSequence(client) })
        }

        @JvmStatic fun startPickaxePerimeterStep(client: Minecraft) {
            if (running) {
                return
            }

            running = true
            clearRotationLock()
            prepareRun(client)
            MacroWorkerThread.getInstance().submit("BedrockPlotMaker-PickaxePerimeter", Runnable { runPickaxePerimeterSequence(client) })
        }

        @JvmStatic fun startArchEndingStep(client: Minecraft) {
            if (running) {
                return
            }

            running = true
            clearRotationLock()
            prepareRun(client)
            MacroWorkerThread.getInstance().submit("BedrockPlotMaker-FinalArches", Runnable { runArchEndingSequence(client) })
        }

        @JvmStatic fun stop(client: Minecraft?) {
            val wasRunning = running
            running = false
            countingRemovals = false
            clearRotationLock()
            removedMessages.set(0)
            clearMouseUngrab()
            if (wasRunning && client != null) {
                client.execute(Runnable {
                    releaseHeldKeys(client)
                    RotationManager.cancelRotation()
                    PathfindingManager.stop(false)
                })
            }
        }

        private fun prepareRun(client: Minecraft?) {
            if (client != null) {
                val closeConfigScreen = Runnable {
                    if (AetherBootstrapHooks.isBootstrapConfigScreen(client.gui.screen()!!)) {
                        client.gui.setScreen(null)
                    }
                }
                if (client.isSameThread) {
                    closeConfigScreen.run()
                } else {
                    client.execute(closeConfigScreen)
                }
            }
            if (AetherConfig.MACRO_UNGRAB_MOUSE.get()) {
                UngrabMouse.requestMacroUngrab()
            }
        }

        private fun clearMouseUngrab() {
            UngrabMouse.clearMacroUngrab()
        }

        @JvmStatic fun onChatMessage(plainText: String?) {
            if (running && countingRemovals && plainText != null && REMOVED_BLOCKS.matcher(plainText).find()) {
                removedMessages.incrementAndGet()
            }
        }

        @JvmStatic fun update(client: Minecraft?) {
            if (rotationLockActive && client != null && client.player != null) {
                applyRotationLock(client)
            }
        }

        private fun runSequence(client: Minecraft) {
            try {
                if (shouldStop(client)) {
                    return
                }

                val bounds = resolveBoundsForRun(client) ?: return

                if (!clearFinalArches(client, bounds)) {
                    ClientUtils.sendDebugMessage("Bedrock Plot Maker: initial arch pass failed.")
                    return
                }

                val dropCenter = bounds.standPoint()
                if (!walkToExactPoint(client, dropCenter)) {
                    ClientUtils.sendDebugMessage("Bedrock Plot Maker: failed to reach plot corner.")
                    return
                }

                if (shouldStop(client)) {
                    return
                }

                if (!GearManager.swapToNamedHotbarItemSync(client, "Builder")) {
                    return
                }

                if (!rotateEastStraightDown(client)) {
                    ClientUtils.sendDebugMessage("Bedrock Plot Maker: not facing east/down, refusing to right click.")
                    return
                }
                if (!holdRulerUntilBedrock(client, dropCenter)) {
                    if (shouldStop(client)) {
                        return
                    }
                    clearRotationLock()
                    releaseHeldKeysSync(client)
                    if (!descendToBedrock(client, dropCenter)) {
                        val stoppedY = if (client.player != null) client.player!!.blockPosition().y else BEDROCK_STANDING_Y
                        ClientUtils.sendDebugMessage("Bedrock Plot Maker: failed to reach bedrock level.")
                        NotificationManager.error("Bedrock Plot Maker", "Couldn't reach bedrock (stopped at Y=" + stoppedY + "). Macro halted.")
                        return
                    }
                }
                clearAcrossPlot(client, bounds)
            } finally {
                running = false
                countingRemovals = false
                clearRotationLock()
                clearMouseUngrab()
                client.execute(Runnable { releaseHeldKeys(client) })
            }
        }

        private fun runTrenchSequence(client: Minecraft) {
            try {
                if (shouldStop(client)) {
                    return
                }
                val bounds = resolveBoundsForRun(client) ?: return
                if (!GearManager.swapToNamedHotbarItemSync(client, "Builder")) {
                    return
                }
                clearAcrossPlot(client, bounds)
            } finally {
                running = false
                countingRemovals = false
                clearRotationLock()
                clearMouseUngrab()
                client.execute(Runnable { releaseHeldKeys(client) })
            }
        }

        private fun runEndLaneSequence(client: Minecraft) {
            try {
                val bounds = if (shouldStop(client)) null else resolveBoundsForRun(client)
                if (bounds != null) {
                    finishFromOppositeCorner(client, bounds)
                }
            } finally {
                running = false
                countingRemovals = false
                clearRotationLock()
                clearMouseUngrab()
                client.execute(Runnable { releaseHeldKeys(client) })
            }
        }

        private fun runShovelCornerSequence(client: Minecraft) {
            try {
                val bounds = if (shouldStop(client)) null else resolveBoundsForRun(client)
                if (bounds != null) {
                    clearPerimeterWithShovel(client, bounds)
                }
            } finally {
                running = false
                countingRemovals = false
                clearRotationLock()
                clearMouseUngrab()
                client.execute(Runnable { releaseHeldKeys(client) })
            }
        }

        private fun runPickaxePerimeterSequence(client: Minecraft) {
            try {
                val bounds = if (shouldStop(client)) null else resolveBoundsForRun(client)
                if (bounds != null) {
                    clearPerimeterWithPickaxe(client, bounds)
                }
            } finally {
                running = false
                countingRemovals = false
                clearRotationLock()
                clearMouseUngrab()
                client.execute(Runnable { releaseHeldKeys(client) })
            }
        }

        private fun runArchEndingSequence(client: Minecraft) {
            try {
                val bounds = if (shouldStop(client)) null else resolveBoundsForRun(client)
                if (bounds != null) {
                    clearFinalArches(client, bounds)
                }
            } finally {
                running = false
                countingRemovals = false
                clearRotationLock()
                clearMouseUngrab()
                client.execute(Runnable { releaseHeldKeys(client) })
            }
        }

        private fun walkToExactPoint(client: Minecraft, target: Vec3): Boolean {
            val pathFinished = AtomicBoolean(false)
            val pathFailed = AtomicBoolean(false)

            client.execute(Runnable {
                PathfindingManager.startConfiguredWalk(client, target, { pathFinished.set(true) }, { pathFailed.set(true) }, true, TARGET_TOLERANCE, true, true)
            })

            val deadline = System.currentTimeMillis() + PATH_TIMEOUT_MS
            while (!pathFinished.get() && !pathFailed.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(20)
            }

            if (!pathFinished.get()) {
                client.execute(Runnable { PathfindingManager.stop(false) })
                return false
            }

            val settleDeadline = System.currentTimeMillis() + 1500L
            while (System.currentTimeMillis() < settleDeadline && !shouldStop(client)) {
                if (client.player != null && horizontalDistanceSqr(client.player!!.position(), target) <= TARGET_TOLERANCE * TARGET_TOLERANCE) {
                    return true
                }
                MacroWorkerThread.sleep(20)
            }
            return client.player != null && horizontalDistanceSqr(client.player!!.position(), target) <= TARGET_TOLERANCE * TARGET_TOLERANCE
        }

        private fun walkToCommandPoint(client: Minecraft, target: Vec3): Boolean {
            val pathFinished = AtomicBoolean(false)
            val pathFailed = AtomicBoolean(false)

            client.execute(Runnable {
                PathfindingManager.startConfiguredWalk(client, Mth.floor(target.x), Mth.floor(target.y), Mth.floor(target.z), { pathFinished.set(true) }, { pathFailed.set(true) }, true, 0.5, false, false)
            })

            val deadline = System.currentTimeMillis() + PATH_TIMEOUT_MS
            while (!pathFinished.get() && !pathFailed.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(20)
            }

            if (!pathFinished.get()) {
                client.execute(Runnable { PathfindingManager.stop(false) })
                return false
            }
            return true
        }

        private fun descendToBedrock(client: Minecraft, dropCenter: Vec3): Boolean {
            if (client.player == null || shouldStop(client)) {
                return false
            }

            val playerY = client.player!!.blockPosition().y
            if (playerY <= BEDROCK_STANDING_Y) {
                return true
            }

            val playerPos = client.player!!.position()
            val bedrockTarget = Vec3(playerPos.x, BEDROCK_STANDING_Y.toDouble(), playerPos.z)
            if (!walkToCommandPoint(client, bedrockTarget)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: pathfinding to bedrock level (Y=" + BEDROCK_STANDING_Y + ") failed.")
                return false
            }

            ClientUtils.sendDebugMessage("Bedrock Plot Maker: descended to bedrock at Y=" + BEDROCK_STANDING_Y + ".")
            return true
        }

        private fun rotateEastStraightDown(client: Minecraft): Boolean = rotateToLocked(client, EAST_YAW, DOWN_PITCH)

        private fun rotateNorthForTrench(client: Minecraft): Boolean = rotateToLocked(client, NORTH_YAW, TRENCH_PITCH)

        private fun rotateForReturnTrench(client: Minecraft): Boolean = rotateToLocked(client, RETURN_YAW, RETURN_PITCH)

        private fun rotateToLocked(client: Minecraft, yaw: Float, pitch: Float): Boolean {
            clearRotationLock()
            val rotationScheduled = AtomicBoolean(false)
            client.execute(Runnable {
                RotationManager.cancelRotation()
                RotationManager.rotateToYawPitch(client, yaw, pitch, ROTATION_MS, true)
                rotationScheduled.set(true)
            })

            val deadline = System.currentTimeMillis() + ROTATION_MS + 1500L
            while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
                if (rotationScheduled.get() && !RotationManager.isRotating() && isFacing(client, yaw, pitch)) {
                    lockRotation(client, yaw, pitch)
                    return true
                }
                MacroWorkerThread.sleep(20)
            }
            val locked = isFacing(client, yaw, pitch)
            if (locked) {
                lockRotation(client, yaw, pitch)
            }
            return locked
        }

        private fun lockRotation(client: Minecraft, yaw: Float, pitch: Float) {
            lockedYaw = yaw
            lockedPitch = pitch
            rotationLockActive = true
            client.execute(Runnable { applyRotationLock(client) })
        }

        private fun clearRotationLock() {
            rotationLockActive = false
        }

        private fun applyRotationLock(client: Minecraft?) {
            if (!rotationLockActive || client == null || client.player == null) {
                return
            }
            client.player!!.setYRot(lockedYaw)
            client.player!!.setXRot(lockedPitch)
            client.player!!.yRotO = lockedYaw
            client.player!!.xRotO = lockedPitch
            FailsafeManager.expectRotation(lockedYaw, lockedPitch)
        }

        private fun isFacingEastDown(client: Minecraft): Boolean = isFacing(client, EAST_YAW, DOWN_PITCH)

        private fun isFacingNorthTrench(client: Minecraft): Boolean = isFacing(client, NORTH_YAW, TRENCH_PITCH)

        private fun isFacingReturnTrench(client: Minecraft): Boolean = isFacing(client, RETURN_YAW, RETURN_PITCH)

        private fun isFacing(client: Minecraft?, yaw: Float, pitch: Float): Boolean {
            if (client == null || client.player == null) {
                return false
            }
            val yawDiff = Math.abs(Mth.wrapDegrees(client.player!!.getYRot() - yaw))
            val pitchDiff = Math.abs(client.player!!.getXRot() - pitch)
            return yawDiff <= YAW_TOLERANCE && pitchDiff <= PITCH_TOLERANCE
        }

        private fun isCrosshairOnBlock(client: Minecraft): Boolean = crosshairBlockHit(client) != null

        private fun isCrosshairOnBedrock(client: Minecraft): Boolean {
            val hit = crosshairBlockHit(client)
            return hit != null && client.level!!.getBlockState(hit.getBlockPos()).`is`(Blocks.BEDROCK)
        }

        private fun crosshairBlockHit(client: Minecraft?): BlockHitResult? {
            if (client == null || client.player == null || client.level == null) {
                return null
            }

            val eye = client.player!!.getEyePosition()
            val end = eye.add(client.player!!.getViewVector(1.0f).scale(BEDROCK_RAY_DISTANCE))
            val hit = client.level!!.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player!!))
            return if (hit.getType() == HitResult.Type.BLOCK) hit else null
        }

        private fun holdRulerUntilBedrock(client: Minecraft, dropCenter: Vec3): Boolean {
            if (!isFacingEastDown(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: use blocked because rotation is not locked.")
                return false
            }
            removedMessages.set(0)
            countingRemovals = true
            val useStarted = AtomicBoolean(false)
            client.execute(Runnable {
                if (client.options == null || !isFacingEastDown(client)) {
                    return@Runnable
                }
                if (client.player != null) {
                    client.player!!.setShiftKeyDown(true)
                }
                MacroInput.set(client.options.keyShift, true)
                ClientUtils.setKeyMappingState(client.options.keyUse, true)
                useStarted.set(true)
            })

            val useDeadline = System.currentTimeMillis() + 1000L
            while (!useStarted.get() && System.currentTimeMillis() < useDeadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(10)
            }
            if (!useStarted.get()) {
                countingRemovals = false
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: right click never started because rotation was not locked.")
                return false
            }

            var deadline = System.currentTimeMillis() + REMOVE_TIMEOUT_MS
            var reachedBedrock = false
            var lastY = client.player!!.blockPosition().y
            while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
                var currentY = client.player!!.blockPosition().y
                if (currentY < lastY && !isCenteredOnDropColumn(client, dropCenter)) {
                    releaseHeldKeysSync(client)
                    clearRotationLock()
                    currentY = waitForLandingY(client, currentY)

                    val lowerCenter = Vec3(dropCenter.x, currentY.toDouble(), dropCenter.z)
                    if (!walkToExactPoint(client, lowerCenter)) {
                        countingRemovals = false
                        ClientUtils.sendDebugMessage("Bedrock Plot Maker: failed to recenter after dropping to Y=" + currentY + ".")
                        return false
                    }
                    if (!rotateEastStraightDown(client)) {
                        countingRemovals = false
                        ClientUtils.sendDebugMessage("Bedrock Plot Maker: failed to restore the downward angle after recentering.")
                        return false
                    }
                    deadline = System.currentTimeMillis() + REMOVE_TIMEOUT_MS
                }
                lastY = currentY

                client.execute(Runnable {
                    if (client.options != null && isFacingEastDown(client)) {
                        MacroInput.set(client.options.keyShift, true)
                        ClientUtils.setKeyMappingState(client.options.keyUse, true)
                    }
                })
                reachedBedrock = isCrosshairOnBedrock(client) && currentY <= BEDROCK_STANDING_Y
                if (reachedBedrock && removedMessages.get() > 0) {
                    break
                }
                MacroWorkerThread.sleep(50)
            }
            countingRemovals = false
            clearRotationLock()
            releaseHeldKeysSync(client)
            if ((!reachedBedrock || removedMessages.get() == 0) && !shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: stopped with bedrock=" + reachedBedrock + ", removal messages=" + removedMessages.get() + ".")
            }
            return reachedBedrock && removedMessages.get() > 0
        }

        private fun waitForLandingY(client: Minecraft, fallbackY: Int): Int {
            var landedY = fallbackY
            val deadline = System.currentTimeMillis() + 1500L
            while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
                landedY = client.player!!.blockPosition().y
                if (client.player!!.onGround()) {
                    break
                }
                MacroWorkerThread.sleep(10)
            }
            return landedY
        }

        private fun isCenteredOnDropColumn(client: Minecraft, dropCenter: Vec3): Boolean {
            return client.player != null && horizontalDistanceSqr(client.player!!.position(), dropCenter) <= TARGET_TOLERANCE * TARGET_TOLERANCE
        }

        private fun clearAcrossPlot(client: Minecraft, bounds: PlotBounds) {
            if (shouldStop(client)) {
                return
            }
            if (!rotateNorthForTrench(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: not facing north trench angle.")
                return
            }

            holdShiftAndForward(client)
            val stopX = bounds.trenchRightStopX()
            while (!shouldStop(client) && client.player != null && client.player!!.blockPosition().x <= stopX) {
                if (!isFacingNorthTrench(client)) {
                    if (!rotateNorthForTrench(client)) {
                        break
                    }
                }

                clearCurrentTrenchBlock(client)
                if (client.player == null || client.player!!.blockPosition().x >= stopX) {
                    break
                }
                stepRightOneBlock(client, Math.min(client.player!!.blockPosition().x + 1, stopX))
            }

            if (shouldStop(client) || client.player == null) {
                return
            }
            if (!rotateForReturnTrench(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: not facing return trench angle.")
                return
            }

            holdShiftOnly(client)
            val leftStopX = bounds.returnLeftStopX()
            while (!shouldStop(client) && client.player != null && client.player!!.blockPosition().x >= leftStopX) {
                if (!isFacingReturnTrench(client)) {
                    if (!rotateForReturnTrench(client)) {
                        break
                    }
                }

                clearCurrentReturnBlock(client)
                if (client.player == null || client.player!!.blockPosition().x <= leftStopX) {
                    break
                }
                stepLeftOneBlock(client, Math.max(client.player!!.blockPosition().x - 1, leftStopX))
            }

            finishFromOppositeCorner(client, bounds)
        }

        private fun holdShiftAndForward(client: Minecraft) {
            client.execute(Runnable {
                if (client.options == null) {
                    return@Runnable
                }
                if (client.player != null) {
                    client.player!!.setShiftKeyDown(true)
                }
                MacroInput.set(client.options.keyShift, true)
                MacroInput.set(client.options.keyUp, true)
            })
        }

        private fun holdShiftOnly(client: Minecraft) {
            client.execute(Runnable {
                if (client.options == null) {
                    return@Runnable
                }
                if (client.player != null) {
                    client.player!!.setShiftKeyDown(true)
                }
                MacroInput.set(client.options.keyShift, true)
                MacroInput.set(client.options.keyUp, false)
            })
        }

        // returns removal chat messages seen while clearing, i.e. rows broken
        private fun clearCurrentTrenchBlock(client: Minecraft): Int {
            if (!isCrosshairOnBlock(client)) {
                return 0
            }

            removedMessages.set(0)
            countingRemovals = true
            client.execute(Runnable {
                if (client.options != null && isFacingNorthTrench(client)) {
                    ClientUtils.setKeyMappingState(client.options.keyUse, true)
                }
            })

            val deadline = System.currentTimeMillis() + COLUMN_CLEAR_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
                if (!isFacingNorthTrench(client)) {
                    client.execute(Runnable {
                        if (client.options != null) {
                            ClientUtils.setKeyMappingState(client.options.keyUse, false)
                        }
                    })
                    rotateNorthForTrench(client)
                    client.execute(Runnable {
                        if (client.options != null) {
                            ClientUtils.setKeyMappingState(client.options.keyUse, true)
                        }
                    })
                }

                if (!isCrosshairOnBlock(client) && removedMessages.get() > 0) {
                    break
                }
                MacroWorkerThread.sleep(50)
            }

            countingRemovals = false
            client.execute(Runnable {
                if (client.options != null) {
                    ClientUtils.setKeyMappingState(client.options.keyUse, false)
                }
            })
            if (removedMessages.get() == 0 && !shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: no removal chat while clearing trench block.")
            }
            return removedMessages.get()
        }

        private fun clearCurrentReturnBlock(client: Minecraft) {
            clearHeldRowFacing(client, RETURN_YAW, RETURN_PITCH, 1, Integer.MAX_VALUE, null)
        }

        // holds right-click like vanilla until the requested row removals land
        private fun clearHeldRowFacing(client: Minecraft, yaw: Float, pitch: Float, maxRemovals: Int, maxBreakY: Int, advanceTarget: BlockPos?): Boolean {
            if (hasReachedAdvanceTarget(client, advanceTarget, yaw)) {
                return true
            }
            if (advanceTarget == null && (isCrosshairAboveBreakLimit(client, maxBreakY) || isCrosshairOnBedrock(client) || !isCrosshairOnBlock(client))) {
                return true
            }

            removedMessages.set(0)
            countingRemovals = true
            val useStarted = AtomicBoolean(false)
            client.execute(Runnable {
                if (client.options != null && isFacing(client, yaw, pitch)) {
                    ClientUtils.setKeyMappingState(client.options.keyUse, true)
                    useStarted.set(true)
                }
            })

            val startDeadline = System.currentTimeMillis() + 1000L
            while (!useStarted.get() && System.currentTimeMillis() < startDeadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(10)
            }

            var rotationFailed = !useStarted.get()
            val deadline = System.currentTimeMillis() + COLUMN_CLEAR_TIMEOUT_MS
            var lastRemovalCount = 0
            var lastRemovalAt = System.currentTimeMillis()
            while (!rotationFailed && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                if (!isFacing(client, yaw, pitch)) {
                    client.execute(Runnable {
                        if (client.options != null) {
                            ClientUtils.setKeyMappingState(client.options.keyUse, false)
                        }
                    })
                    if (!rotateToLocked(client, yaw, pitch)) {
                        rotationFailed = true
                        break
                    }
                    client.execute(Runnable {
                        if (client.options != null) {
                            ClientUtils.setKeyMappingState(client.options.keyUse, true)
                        }
                    })
                }

                val removalCount = removedMessages.get()
                if (removalCount > lastRemovalCount) {
                    lastRemovalCount = removalCount
                    lastRemovalAt = System.currentTimeMillis()
                }

                val advanced = hasReachedAdvanceTarget(client, advanceTarget, yaw)
                if (advanced || (advanceTarget == null && (removalCount >= maxRemovals || isCrosshairAboveBreakLimit(client, maxBreakY) || isCrosshairOnBedrock(client) || (!isCrosshairOnBlock(client) && removalCount > 0) || (removalCount > 0 && System.currentTimeMillis() - lastRemovalAt >= HELD_USE_QUIET_MS)))) {
                    break
                }
                MacroWorkerThread.sleep(50)
            }

            countingRemovals = false
            val released = AtomicBoolean(false)
            client.execute(Runnable {
                if (client.options != null) {
                    ClientUtils.setKeyMappingState(client.options.keyUse, false)
                }
                released.set(true)
            })
            val releaseDeadline = System.currentTimeMillis() + 1000L
            while (!released.get() && System.currentTimeMillis() < releaseDeadline) {
                MacroWorkerThread.sleep(10)
            }

            val cleared = hasReachedAdvanceTarget(client, advanceTarget, yaw) || (advanceTarget == null && (removedMessages.get() > 0 || isCrosshairAboveBreakLimit(client, maxBreakY) || isCrosshairOnBedrock(client) || !isCrosshairOnBlock(client)))
            if (!cleared && !rotationFailed && !shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: held row removal was not confirmed.")
            }
            return released.get() && !rotationFailed && cleared && !shouldStop(client)
        }

        private fun finishFromOppositeCorner(client: Minecraft, bounds: PlotBounds) {
            if (shouldStop(client) || client.player == null) {
                return
            }

            clearRotationLock()
            client.execute(Runnable { releaseHeldKeys(client) })
            MacroWorkerThread.sleep(50)

            if (!GearManager.swapToNamedHotbarItemSync(client, "Builder")) {
                return
            }

            val pocket = bounds.cornerPocket()
            if (!walkToCommandPoint(client, pocket)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: failed to reach opposite corner pocket.")
                return
            }

            if (!rotateNorthForTrench(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: not facing north for corner finish.")
                return
            }

            // One clear can remove several rows at once (the ruler keeps firing while the
            // crosshair still reaches farther blocks), so count removal messages rather
            // than loop iterations and stop as soon as the last row is gone.
            var rowsBroken = 0
            var attempt = 0
            while (attempt < CORNER_FINISH_ROWS && !shouldStop(client) && client.player != null) {
                if (!isFacingNorthTrench(client) && !rotateNorthForTrench(client)) {
                    break
                }

                holdShiftAndForward(client)
                rowsBroken += clearCurrentTrenchBlock(client)
                if (rowsBroken >= CORNER_FINISH_ROWS || attempt >= CORNER_FINISH_ROWS - 1 || client.player == null) {
                    break
                }
                stepLeftOneBlock(client, client.player!!.blockPosition().x - 1)
                attempt++
            }

            // Re-runs on a partially cleared corner can find fewer than 3 rows left, so
            // don't gate the finish phase on the count; each step guards itself. The
            // shovel wall-follow only runs once the finish legs all completed, since it
            // relies on ending up at the south-west corner.
            if (!shouldStop(client) && clearFinalReturnRows(client, bounds)) {
                clearPerimeterWithShovel(client, bounds)
            }

            clearRotationLock()
            releaseHeldKeysSync(client)
        }

        // breaks the return row, then opens the south wall the same way the corner start does, pathfinds onto the broken spot, and repeats west and north
        // true only if every leg completed
        private fun clearFinalReturnRows(client: Minecraft, bounds: PlotBounds): Boolean {
            if (!rotateAndClearRow(client, RETURN_YAW, RETURN_PITCH, 1, false)) {
                return false
            }
            if (!breakWallFacing(client, FINISH_SOUTH_YAW)) {
                return false
            }
            clearRotationLock()
            releaseHeldKeysSync(client)
            if (!breakWallFacing(client, FINISH_WEST_YAW)) {
                return false
            }
            if (!walkToFinishPoint(client, bounds.southWestCorner())) {
                return false
            }
            return breakWallFacing(client, NORTH_YAW)
        }

        // same motion as the corner start: the up angle takes the top three rows, the down angle the bottom one
        // capped by removal count so the ruler never chews into the rows behind it
        private fun breakWallFacing(client: Minecraft, yaw: Float): Boolean {
            if (!rotateAndClearRow(client, yaw, TRENCH_PITCH, CORNER_FINISH_ROWS, false)) {
                return false
            }
            return rotateAndClearRow(client, yaw, RETURN_PITCH, 1, true)
        }

        private fun rotateAndClearRow(client: Minecraft, yaw: Float, pitch: Float, maxRemovals: Int, advanceIntoClearedBlock: Boolean): Boolean {
            if (shouldStop(client) || client.player == null) {
                return false
            }

            holdShiftOnly(client)
            if (!rotateToLocked(client, yaw, pitch)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: could not face " + yaw + " / " + pitch + " for the finish row.")
                return false
            }

            // Push into the wall while breaking, same as the corner-start rows.
            holdShiftAndForward(client)
            val maxBreakY = if (Math.abs(pitch - TRENCH_PITCH) < 0.01f) client.player!!.blockPosition().y + UPPER_BREAK_Y_OFFSET else Integer.MAX_VALUE
            val advanceTarget = if (advanceIntoClearedBlock) oneBlockForward(client.player!!.blockPosition(), yaw) else null
            val cleared = clearHeldRowFacing(client, yaw, pitch, maxRemovals, maxBreakY, advanceTarget)
            holdShiftOnly(client)
            return cleared
        }

        private fun isCrosshairAboveBreakLimit(client: Minecraft, maxBreakY: Int): Boolean {
            if (maxBreakY == Integer.MAX_VALUE) {
                return false
            }
            val hit = crosshairBlockHit(client)
            return hit != null && hit.getBlockPos().y > maxBreakY
        }

        private fun oneBlockForward(start: BlockPos, yaw: Float): BlockPos {
            val radians = Math.toRadians(yaw.toDouble())
            val stepX = Math.round(-Math.sin(radians)).toInt()
            val stepZ = Math.round(Math.cos(radians)).toInt()
            return BlockPos(start.x + stepX, start.y, start.z + stepZ)
        }

        private fun hasReachedAdvanceTarget(client: Minecraft, target: BlockPos?, yaw: Float): Boolean {
            if (target == null || client.player == null) {
                return false
            }
            val current = client.player!!.blockPosition()
            val radians = Math.toRadians(yaw.toDouble())
            val stepX = Math.round(-Math.sin(radians)).toInt()
            val stepZ = Math.round(Math.cos(radians)).toInt()
            val reachedX = stepX == 0 || (if (stepX > 0) current.x >= target.x else current.x <= target.x)
            val reachedZ = stepZ == 0 || (if (stepZ > 0) current.z >= target.z else current.z <= target.z)
            return reachedX && reachedZ
        }

        private fun walkToFinishPoint(client: Minecraft, point: Vec3): Boolean {
            clearRotationLock()
            releaseHeldKeysSync(client)
            if (walkToCommandPoint(client, point)) {
                return true
            }
            if (!shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: failed to reach the finish point " + Mth.floor(point.x) + " " + Mth.floor(point.y) + " " + Mth.floor(point.z) + ".")
            }
            return false
        }

        // mines all four perimeter legs and returns to the exact starting x/z
        private fun clearPerimeterWithShovel(client: Minecraft, bounds: PlotBounds) {
            releaseHeldKeysSync(client)
            if (!GearManager.swapToNamedHotbarItemSync(client, "Golden Shovel")) {
                return
            }
            if (client.player == null) {
                return
            }

            val start = client.player!!.blockPosition()
            val startX = start.x
            val startZ = start.z
            val y = start.y
            if (!runShovelLeg(client, NORTH_YAW, BlockPos(bounds.westBlockX(), y, bounds.northBlockZ()))) return
            if (!runShovelLeg(client, EAST_YAW, BlockPos(bounds.eastBlockX(), y, bounds.northBlockZ()))) return
            if (!runShovelLeg(client, FINISH_SOUTH_YAW, BlockPos(bounds.eastBlockX(), y, bounds.southBlockZ()))) return
            if (!runShovelLeg(client, FINISH_WEST_YAW, BlockPos(startX, y, startZ))) return
            if (!breakForwardAfterShovel(client, bounds, NORTH_YAW, 4)) return
            ClientUtils.sendDebugMessage("Bedrock Plot Maker: shovel complete; starting Golden Pickaxe perimeter.")
            clearPerimeterWithPickaxe(client, bounds)
        }

        private fun breakForwardAfterShovel(client: Minecraft, bounds: PlotBounds, yaw: Float, blocks: Int): Boolean {
            releaseHeldKeysSync(client)
            if (client.player == null || !rotateToLocked(client, yaw, SHOVEL_CORNER_PITCH)) {
                return false
            }
            if (!startHeldAttack(client, yaw, SHOVEL_CORNER_PITCH)) {
                return false
            }

            val start = client.player!!.blockPosition()
            val radians = Math.toRadians(yaw.toDouble())
            val stepX = Math.round(-Math.sin(radians)).toInt()
            val stepZ = Math.round(Math.cos(radians)).toInt()
            val target = start.offset(stepX * blocks, 0, stepZ * blocks)
            val deadline = System.currentTimeMillis() + STEP_RIGHT_TIMEOUT_MS
            while (!hasReachedMiningTarget(client, target, yaw) && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                client.execute(Runnable {
                    if (client.options != null && isFacing(client, yaw, SHOVEL_CORNER_PITCH)) {
                        MacroInput.set(client.options.keyUp, true)
                        MacroInput.setAttack(client.options.keyAttack, !isCrosshairOutsidePlot(client, bounds))
                    }
                })
                MacroWorkerThread.sleep(20)
            }

            val reached = hasReachedMiningTarget(client, target, yaw)
            releaseAttackSync(client)
            releaseHeldKeysSync(client)
            if (!reached && !shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: failed to break forward " + blocks + " blocks after shovel perimeter.")
            }
            return reached
        }

        private fun runShovelLeg(client: Minecraft, yaw: Float, target: BlockPos): Boolean {
            releaseHeldKeysSync(client)
            if (!rotateToLocked(client, yaw, SHOVEL_CORNER_PITCH)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: could not rotate to " + yaw + " / " + SHOVEL_CORNER_PITCH + " for shovel perimeter.")
                return false
            }
            if (!startHeldAttack(client, yaw, SHOVEL_CORNER_PITCH)) {
                return false
            }

            val deadline = System.currentTimeMillis() + SHOVEL_CORNER_TIMEOUT_MS
            while (!hasReachedMiningTarget(client, target, yaw) && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                client.execute(Runnable {
                    if (client.options != null && isFacing(client, yaw, SHOVEL_CORNER_PITCH)) {
                        MacroInput.set(client.options.keyUp, true)
                        MacroInput.set(client.options.keyLeft, true)
                        MacroInput.setAttack(client.options.keyAttack, true)
                    }
                })
                MacroWorkerThread.sleep(20)
            }

            val reached = hasReachedMiningTarget(client, target, yaw)
            releaseAttackSync(client)
            releaseHeldKeysSync(client)
            if (!reached && !shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: shovel perimeter failed to reach " + target.x + " " + target.z + ".")
            }
            return reached
        }

        private fun hasReachedMiningTarget(client: Minecraft, target: BlockPos, yaw: Float): Boolean {
            if (client.player == null) {
                return false
            }
            val current = client.player!!.position()
            val targetCenterX = target.x + 0.5
            val targetCenterZ = target.z + 0.5
            val radians = Math.toRadians(yaw.toDouble())
            val stepX = Math.round(-Math.sin(radians)).toInt()
            val stepZ = Math.round(Math.cos(radians)).toInt()
            // Wall-follow deliberately strafes into the border, so only the travel
            // axis determines whether the corner or final advance is complete.
            return if (stepX > 0) current.x >= targetCenterX
            else if (stepX < 0) current.x <= targetCenterX
            else if (stepZ > 0) current.z >= targetCenterZ
            else current.z <= targetCenterZ
        }

        private fun isCrosshairOutsidePlot(client: Minecraft, bounds: PlotBounds): Boolean {
            val hit = crosshairBlockHit(client)
            return hit != null && !isInsidePlot(bounds, hit.getBlockPos())
        }

        private fun clearPerimeterWithPickaxe(client: Minecraft, bounds: PlotBounds): Boolean {
            releaseHeldKeysSync(client)
            if (!swapToGoldenPickaxeSync(client) || client.player == null) {
                return false
            }
            if (!holdPickaxeSneakSync(client)) {
                return false
            }

            val start = client.player!!.blockPosition()
            val y = start.y
            try {
                if (!runPickaxeLeg(client, NORTH_YAW, BlockPos(bounds.westBlockX(), y, bounds.northBlockZ()), true)) return false
                if (!runPickaxeLeg(client, EAST_YAW, BlockPos(bounds.eastBlockX(), y, bounds.northBlockZ()), false)) return false
                if (!runPickaxeLeg(client, FINISH_SOUTH_YAW, BlockPos(bounds.eastBlockX(), y, bounds.southBlockZ()), false)) return false
                if (!runPickaxeLeg(client, FINISH_WEST_YAW, BlockPos(bounds.westBlockX(), y, bounds.southBlockZ()), false)) return false
                return runPickaxeLeg(client, NORTH_YAW, start, false)
            } finally {
                releaseAttackSync(client)
                releaseHeldKeysSync(client)
            }
        }

        private fun holdPickaxeSneakSync(client: Minecraft): Boolean {
            val held = AtomicBoolean(false)
            client.execute(Runnable {
                if (client.options == null || client.player == null) {
                    return@Runnable
                }
                MacroInput.set(client.options.keyShift, true)
                client.player!!.setShiftKeyDown(true)
                held.set(true)
            })
            val deadline = System.currentTimeMillis() + 1000L
            while (!held.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(10)
            }
            return held.get()
        }

        private fun swapToGoldenPickaxeSync(client: Minecraft): Boolean {
            if (client.player == null) {
                return false
            }

            var slot = -1
            for (i in 0 until 9) {
                if (client.player!!.getInventory().getItem(i).`is`(Items.GOLDEN_PICKAXE)) {
                    slot = i
                    break
                }
            }
            if (slot < 0) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: no Golden Pickaxe item found in the hotbar.")
                return false
            }

            val targetSlot = slot
            client.execute(Runnable { FailsafeManager.selectHotbarSlot(client, targetSlot) })
            val deadline = System.currentTimeMillis() + 1500L
            while (!shouldStop(client) && System.currentTimeMillis() < deadline && !client.player!!.getMainHandItem().`is`(Items.GOLDEN_PICKAXE)) {
                MacroWorkerThread.sleep(10)
            }
            val equipped = client.player != null && client.player!!.getMainHandItem().`is`(Items.GOLDEN_PICKAXE)
            if (!equipped && !shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: Golden Pickaxe equip timed out.")
            }
            return equipped
        }

        private fun runPickaxeLeg(client: Minecraft, yaw: Float, target: BlockPos, startAttack: Boolean): Boolean {
            if (!rotateToLocked(client, yaw, PICKAXE_UP_PITCH)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: could not rotate to " + yaw + " / " + PICKAXE_UP_PITCH + " for pickaxe perimeter.")
                return false
            }
            if (startAttack && !startHeldAttack(client, yaw, PICKAXE_UP_PITCH)) {
                return false
            }

            val deadline = System.currentTimeMillis() + SHOVEL_CORNER_TIMEOUT_MS
            while (!hasReachedMiningTarget(client, target, yaw) && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                client.execute(Runnable {
                    if (client.options == null || !isFacing(client, yaw, PICKAXE_UP_PITCH)) {
                        return@Runnable
                    }
                    if (client.player != null) {
                        client.player!!.setShiftKeyDown(true)
                    }
                    MacroInput.set(client.options.keyShift, true)
                    MacroInput.set(client.options.keyUp, true)
                    MacroInput.set(client.options.keyLeft, true)
                    MacroInput.setAttack(client.options.keyAttack, true)
                })
                MacroWorkerThread.sleep(20)
            }

            val reached = hasReachedMiningTarget(client, target, yaw)
            if (!reached && !shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: pickaxe perimeter failed to reach " + target.x + " " + target.z + ".")
            }
            return reached
        }

        private fun clearFinalArches(client: Minecraft, bounds: PlotBounds): Boolean {
            clearRotationLock()
            releaseHeldKeysSync(client)
            if (!swapToGoldenPickaxeSync(client)) {
                return false
            }

            for (wall in archWalls(bounds)) {
                if (shouldStop(client) || !clearArchWall(client, bounds, wall)) {
                    return false
                }
            }
            ClientUtils.sendDebugMessage("Bedrock Plot Maker: final arch pass complete.")
            return true
        }

        private fun archWalls(bounds: PlotBounds): MutableList<ArchWall> {
            val centerX = bounds.minX() + PLOT_SIZE / 2
            val centerZ = bounds.minZ() + PLOT_SIZE / 2
            val walls: MutableList<ArchWall> = ArrayList<ArchWall>()
            walls.add(ArchWall("north", false, bounds.northBlockZ(), bounds.northBlockZ() - 1, centerX))
            walls.add(ArchWall("east", true, bounds.eastBlockX(), bounds.eastBlockX() + 1, centerZ))
            walls.add(ArchWall("south", false, bounds.southBlockZ(), bounds.southBlockZ() + 1, centerX))
            walls.add(ArchWall("west", true, bounds.westBlockX(), bounds.westBlockX() - 1, centerZ))
            return walls
        }

        private fun clearArchWall(client: Minecraft, bounds: PlotBounds, wall: ArchWall): Boolean {
            val targets = findArchBlocks(client, bounds, wall)
            if (targets.isEmpty()) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: no inside arch blocks remain on the " + wall.name() + " wall.")
                return true
            }

            if (!walkToExactPoint(client, wall.insideWalkPoint(TARGET_Y.toDouble()))) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: failed to walk to the " + wall.name() + " arch.")
                return false
            }
            clearReachableArchBlocks(client, bounds, wall)
            releaseHeldKeysSync(client)

            val cleared = findArchBlocks(client, bounds, wall).isEmpty()
            if (!cleared && !shouldStop(client)) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: stone-brick or polished-andesite blocks remain on the " + wall.name() + " arch.")
            }
            return cleared
        }

        private fun clearReachableArchBlocks(client: Minecraft, bounds: PlotBounds, wall: ArchWall) {
            val remaining = findArchBlocks(client, bounds, wall)
            var lastBroken: BlockPos? = null
            var attackHeld = false
            try {
                while (!remaining.isEmpty() && !shouldStop(client)) {
                    remaining.removeAll { !isArchBlockAt(client, it) }
                    if (remaining.isEmpty()) {
                        return
                    }

                    val reachable: List<BlockPos> = remaining.filter { distanceToArchBlockSqr(client, it) <= ARCH_REACH * ARCH_REACH }
                    val movedCloser = reachable.isEmpty()
                    val target = nextClosestArchTarget(client, if (movedCloser) remaining else reachable, lastBroken)
                    if (movedCloser) {
                        releaseAttackSync(client)
                        attackHeld = false
                        releaseHeldKeysSync(client)
                        if (!walkToExactPoint(client, wall.insideWalkPoint(target, TARGET_Y.toDouble()))) {
                            ClientUtils.sendDebugMessage("Bedrock Plot Maker: could not move within reach of " + target.toShortString() + ".")
                            return
                        }
                    }

                    var broken = breakArchBlockHeld(client, target, !attackHeld)
                    if (!broken && !movedCloser) {
                        releaseAttackSync(client)
                        attackHeld = false
                        releaseHeldKeysSync(client)
                        if (walkToExactPoint(client, wall.insideWalkPoint(target, TARGET_Y.toDouble()))) {
                            broken = breakArchBlockHeld(client, target, true)
                        }
                    }
                    if (!broken) {
                        ClientUtils.sendDebugMessage("Bedrock Plot Maker: smooth arch sweep stalled at " + target.toShortString() + ".")
                        return
                    }

                    attackHeld = true
                    lastBroken = target
                    remaining.remove(target)
                }
            } finally {
                releaseAttackSync(client)
            }
        }

        private fun nextClosestArchTarget(client: Minecraft, remaining: List<BlockPos>, lastBroken: BlockPos?): BlockPos {
            val nearest: Comparator<BlockPos> = if (lastBroken == null) Comparator.comparingDouble { distanceToArchBlockSqr(client, it) } else Comparator.comparingDouble { distanceBetweenBlocksSqr(lastBroken, it) }
            return remaining.stream().min(nearest).orElseThrow()
        }

        private fun distanceBetweenBlocksSqr(a: BlockPos, b: BlockPos): Double {
            val dx = (a.x - b.x).toDouble()
            val dy = (a.y - b.y).toDouble()
            val dz = (a.z - b.z).toDouble()
            return dx * dx + dy * dy + dz * dz
        }

        private fun findArchBlocks(client: Minecraft, bounds: PlotBounds, wall: ArchWall): MutableList<BlockPos> {
            val targets = ArrayList<BlockPos>()
            if (client.level == null) {
                return targets
            }

            for (parallel in wall.parallelCenter() - ARCH_SCAN_HALF_WIDTH..wall.parallelCenter() + ARCH_SCAN_HALF_WIDTH) {
                for (y in MIN_Y..MAX_Y) {
                    val pos = wall.blockAt(parallel, y)
                    if (isInsidePlot(bounds, pos) && isArchBlock(client.level!!.getBlockState(pos))) {
                        targets.add(pos)
                    }
                }
            }
            return targets
        }

        private fun isInsidePlot(bounds: PlotBounds, pos: BlockPos): Boolean {
            return pos.x >= bounds.minX() && pos.x < bounds.maxX() && pos.z >= bounds.minZ() && pos.z < bounds.maxZ()
        }

        private fun isArchBlock(state: BlockState): Boolean {
            val id = BuiltInRegistries.BLOCK.getKey(state.block) ?: return false
            val path = id.path
            return path.contains("stone_brick") || path.contains("polished_andesite")
        }

        private fun isArchBlockAt(client: Minecraft?, pos: BlockPos): Boolean {
            return client != null && client.level != null && isArchBlock(client.level!!.getBlockState(pos))
        }

        private fun distanceToArchBlockSqr(client: Minecraft?, pos: BlockPos): Double {
            if (client == null || client.player == null) {
                return Double.MAX_VALUE
            }
            val eye = client.player!!.getEyePosition()
            val target = Vec3.atCenterOf(pos)
            val dx = eye.x - target.x
            val dy = eye.y - target.y
            val dz = eye.z - target.z
            return dx * dx + dy * dy + dz * dz
        }

        private fun breakArchBlockHeld(client: Minecraft, target: BlockPos, startAttack: Boolean): Boolean {
            if (!isArchBlockAt(client, target)) {
                return true
            }
            if (distanceToArchBlockSqr(client, target) > ARCH_REACH * ARCH_REACH || !rotateToArchBlock(client, target)) {
                return !isArchBlockAt(client, target)
            }
            if (!isArchBlockAt(client, target)) {
                return true
            }

            if (startAttack && !startArchHeldAttack(client, target)) {
                return false
            }

            val breakDeadline = System.currentTimeMillis() + ARCH_BREAK_TIMEOUT_MS
            while (isArchBlockAt(client, target) && System.currentTimeMillis() < breakDeadline && !shouldStop(client)) {
                client.execute(Runnable {
                    if (client.options != null) {
                        MacroInput.setAttack(client.options.keyAttack, true)
                    }
                })
                MacroWorkerThread.sleep(20)
            }
            return !isArchBlockAt(client, target)
        }

        private fun startArchHeldAttack(client: Minecraft, target: BlockPos): Boolean {
            val started = AtomicBoolean(false)
            client.execute(Runnable {
                val hit = crosshairBlockHit(client)
                if (client.options != null && hit != null && hit.getBlockPos() == target) {
                    MacroInput.setAttack(client.options.keyAttack, true)
                    ClientUtils.clickKeyMapping(client.options.keyAttack)
                    started.set(true)
                }
            })
            val deadline = System.currentTimeMillis() + 1000L
            while (!started.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(10)
            }
            return started.get()
        }

        private fun rotateToArchBlock(client: Minecraft, target: BlockPos): Boolean {
            val queued = AtomicBoolean(false)
            client.execute(Runnable {
                RotationManager.initiateRotation(client, Vec3.atCenterOf(target), ARCH_ROTATION_MS)
                queued.set(true)
            })
            val queueDeadline = System.currentTimeMillis() + 1000L
            while (!queued.get() && System.currentTimeMillis() < queueDeadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(10)
            }
            val rotationDeadline = System.currentTimeMillis() + ARCH_ROTATION_MS + 1000L
            while (RotationManager.isRotating() && System.currentTimeMillis() < rotationDeadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(20)
            }
            val hit = crosshairBlockHit(client)
            return !isArchBlockAt(client, target) || (hit != null && hit.getBlockPos() == target)
        }

        private fun startHeldAttack(client: Minecraft, yaw: Float, pitch: Float): Boolean {
            val started = AtomicBoolean(false)
            client.execute(Runnable {
                if (client.options != null && isFacing(client, yaw, pitch)) {
                    MacroInput.setAttack(client.options.keyAttack, true)
                    ClientUtils.clickKeyMapping(client.options.keyAttack)
                    started.set(true)
                }
            })
            val deadline = System.currentTimeMillis() + 1000L
            while (!started.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(10)
            }
            return started.get()
        }

        private fun releaseAttackSync(client: Minecraft) {
            val released = AtomicBoolean(false)
            client.execute(Runnable {
                if (client.options != null) {
                    MacroInput.setAttack(client.options.keyAttack, false)
                }
                released.set(true)
            })
            val deadline = System.currentTimeMillis() + 1000L
            while (!released.get() && System.currentTimeMillis() < deadline) {
                MacroWorkerThread.sleep(10)
            }
        }

        private fun stepRightOneBlock(client: Minecraft, targetBlockX: Int) {
            holdShiftAndForward(client)
            client.execute(Runnable {
                if (client.options != null) {
                    MacroInput.set(client.options.keyRight, true)
                }
            })

            val deadline = System.currentTimeMillis() + STEP_RIGHT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
                if (client.player != null && client.player!!.blockPosition().x >= targetBlockX) {
                    break
                }
                MacroWorkerThread.sleep(20)
            }

            client.execute(Runnable {
                if (client.options != null) {
                    MacroInput.set(client.options.keyRight, false)
                }
            })
        }

        private fun stepLeftOneBlock(client: Minecraft, targetBlockX: Int) {
            holdShiftOnly(client)
            client.execute(Runnable {
                if (client.options != null) {
                    MacroInput.set(client.options.keyLeft, true)
                }
            })

            val deadline = System.currentTimeMillis() + STEP_RIGHT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && !shouldStop(client)) {
                if (client.player != null && client.player!!.blockPosition().x <= targetBlockX) {
                    break
                }
                MacroWorkerThread.sleep(20)
            }

            client.execute(Runnable {
                if (client.options != null) {
                    MacroInput.set(client.options.keyLeft, false)
                }
            })
        }

        private fun releaseHeldKeys(client: Minecraft?) {
            if (client == null || client.options == null) {
                return
            }
            ClientUtils.setKeyMappingState(client.options.keyUse, false)
            MacroInput.setAttack(client.options.keyAttack, false)
            MacroInput.set(client.options.keyShift, false)
            MacroInput.set(client.options.keyUp, false)
            MacroInput.set(client.options.keyRight, false)
            MacroInput.set(client.options.keyLeft, false)
            MacroInput.set(client.options.keyJump, false)
            if (client.player != null) {
                client.player!!.setShiftKeyDown(false)
            }
        }

        private fun releaseHeldKeysSync(client: Minecraft) {
            val released = AtomicBoolean(false)
            client.execute(Runnable {
                releaseHeldKeys(client)
                released.set(true)
            })

            val deadline = System.currentTimeMillis() + 500L
            while (!released.get() && System.currentTimeMillis() < deadline && !shouldStop(client)) {
                MacroWorkerThread.sleep(10)
            }
        }

        private fun shouldStop(client: Minecraft?): Boolean {
            return !running || MacroWorkerThread.getInstance().isCancelled() || client == null || client.player == null || client.level == null
        }

        private fun horizontalDistanceSqr(a: Vec3, b: Vec3): Double {
            val dx = a.x - b.x
            val dz = a.z - b.z
            return dx * dx + dz * dz
        }

        private fun targetBounds(mc: Minecraft): PlotBounds? {
            val configuredPlot = parsePlot(AetherConfig.BEDROCK_PLOT_MAKER_PLOT.get())
            val plot = if (configuredPlot.isPresent) configuredPlot.getAsInt() else parsePlot(ClientUtils.getCurrentPlot()).orElse(-1)
            return boundsForPlot(plot)
        }

        // the configured plot wins over the detected one and a mismatch warns; render paths keep using the quiet targetBounds
        private fun resolveBoundsForRun(client: Minecraft): PlotBounds? {
            val configured = AetherConfig.BEDROCK_PLOT_MAKER_PLOT.get()
            val scoreboard = ClientUtils.getCurrentPlot()
            val configPlot = parsePlot(configured)
            val currentPlot = parsePlot(scoreboard)
            val plot = configPlot.orElse(currentPlot.orElse(-1))
            val bounds = boundsForPlot(plot)
            if (bounds == null) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: could not resolve plot" + " (config='" + configured + "', scoreboard='" + scoreboard + "').")
                return null
            }

            if (configPlot.isPresent && currentPlot.isPresent && configPlot.getAsInt() != currentPlot.getAsInt()) {
                ClientUtils.sendDebugMessage("Bedrock Plot Maker: WARNING configured plot " + configPlot.getAsInt() + " does not match the plot you are on (" + currentPlot.getAsInt() + "); using the configured plot.")
            }
            ClientUtils.sendDebugMessage("Bedrock Plot Maker: plot " + plot + (if (configPlot.isPresent) " (config), " else " (scoreboard), ") + bounds.describe())
            return bounds
        }

        private fun parsePlot(value: String?): OptionalInt {
            if (value == null || value.isBlank()) {
                return OptionalInt.empty()
            }

            val digits = value.trim().replace(Regex("\\D"), "")
            if (digits.isBlank()) {
                return OptionalInt.empty()
            }

            try {
                return OptionalInt.of(Integer.parseInt(digits))
            } catch (ignored: NumberFormatException) {
                return OptionalInt.empty()
            }
        }

        private fun boundsForPlot(plot: Int): PlotBounds? {
            if (plot <= 0) {
                return null
            }
            val grid = GardenPlots.gridForPlot(plot)
            return if (grid == null) null else bounds(grid[0], grid[1])
        }

        private fun bounds(gridX: Int, gridZ: Int): PlotBounds {
            val minX = gridX * PLOT_SIZE - PLOT_OFFSET
            val minZ = gridZ * PLOT_SIZE - PLOT_OFFSET
            return PlotBounds(minX, minZ, minX + PLOT_SIZE, minZ + PLOT_SIZE)
        }
    }

    private class ArchWall(private val name: String, private val constantX: Boolean, private val insidePlane: Int, private val outsidePlane: Int, private val parallelCenter: Int) {
        fun blockAt(parallel: Int, y: Int): BlockPos {
            return if (constantX) BlockPos(insidePlane, y, parallel) else BlockPos(parallel, y, insidePlane)
        }

        fun insideWalkPoint(y: Double): Vec3 {
            return insideWalkPoint(parallelCenter.toDouble(), y)
        }

        fun insideWalkPoint(target: BlockPos, y: Double): Vec3 {
            val parallel = if (constantX) target.z + 0.5 else target.x + 0.5
            return insideWalkPoint(parallel, y)
        }

        private fun insideWalkPoint(parallel: Double, y: Double): Vec3 {
            val perpendicular = if (outsidePlane < insidePlane) insidePlane + 1.0 + ARCH_WALL_INSET else insidePlane - ARCH_WALL_INSET
            return if (constantX) Vec3(perpendicular, y, parallel) else Vec3(parallel, y, perpendicular)
        }

        fun name(): String = name
        fun parallelCenter(): Int = parallelCenter
        override fun toString(): String = "ArchWall[name=" + name + ", constantX=" + constantX + ", insidePlane=" + insidePlane + ", outsidePlane=" + outsidePlane + ", parallelCenter=" + parallelCenter + "]"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as ArchWall
            return constantX == that.constantX && insidePlane == that.insidePlane && outsidePlane == that.outsidePlane && parallelCenter == that.parallelCenter && name == that.name
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + (if (constantX) 1231 else 1237)
            result = 31 * result + insidePlane
            result = 31 * result + outsidePlane
            result = 31 * result + parallelCenter
            return result
        }
    }

    // maxX/maxZ are EXCLUSIVE, which matches aabb rendering but not block math - use the *BlockX/*BlockZ accessors for anything naming a block
    // every garden plot shares the orientation: north = -Z, west = -X
    private class PlotBounds(private val minX: Int, private val minZ: Int, private val maxX: Int, private val maxZ: Int) {
        fun minX(): Int = minX
        fun minZ(): Int = minZ
        fun maxX(): Int = maxX
        fun maxZ(): Int = maxZ

        fun westBlockX(): Int = minX

        // maxX is exclusive
        fun eastBlockX(): Int = maxX - 1

        fun northBlockZ(): Int = minZ

        // maxZ is exclusive
        fun southBlockZ(): Int = maxZ - 1

        // where the builder ruler drills down, offset by the field-tuned stand offsets
        fun standPoint(): Vec3 = Vec3(westBlockX() + STAND_EAST_OFFSET, TARGET_Y.toDouble(), southBlockZ() + STAND_Z_OFFSET)

        fun trenchRightStopX(): Int = eastBlockX() - 1

        fun returnLeftStopX(): Int = westBlockX() + 1

        // intentionally one block PAST the inclusive east edge (the pathfinder floors this); field-tuned on plot 1
        fun cornerPocket(): Vec3 = Vec3(eastBlockX() + 1.0, CORNER_FINISH_Y.toDouble(), southBlockZ().toDouble())

        // intentionally one block PAST the inclusive south edge; field-tuned on plot 1
        fun southWestCorner(): Vec3 = Vec3(westBlockX().toDouble(), (CORNER_FINISH_Y - 1).toDouble(), southBlockZ() + 1.0)

        fun describe(): String = "x " + westBlockX() + ".." + eastBlockX() + ", z " + northBlockZ() + ".." + southBlockZ()

        override fun toString(): String = "PlotBounds[minX=" + minX + ", minZ=" + minZ + ", maxX=" + maxX + ", maxZ=" + maxZ + "]"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as PlotBounds
            return minX == that.minX && minZ == that.minZ && maxX == that.maxX && maxZ == that.maxZ
        }

        override fun hashCode(): Int {
            var result = minX
            result = 31 * result + minZ
            result = 31 * result + maxX
            result = 31 * result + maxZ
            return result
        }
    }
}
