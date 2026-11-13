package dev.aether.modules.rewarp

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.config.RewarpMode
import dev.aether.config.RewarpPointPair
import dev.aether.config.RewarpPointPairs
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.PestReturnManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class RewarpManager private constructor() {
    companion object {
        private const val REWARP_COOLDOWN_MS = 5000L
        private const val CLIENT_THREAD_TIMEOUT_MS = 1000L
        private const val REWARP_LANDING_ATTEMPTS = 3
        private const val REWARP_POSITION_ADJUST_TIMEOUT_MS = 6000L
        private const val REWARP_POSITION_TAP_MS = 50L
        private const val REWARP_POSITION_SETTLE_MS = 75L
        private const val REWARP_DESCENT_TIMEOUT_MS = 12000L
        private const val REWARP_RECOVERY_TIMEOUT_MS = 4000L
        private const val REWARP_MOVEMENT_TICK_MS = 50L
        private const val REWARP_RECOVERY_HEIGHT_ABOVE_TARGET = 4.0
        private const val REWARP_RECOVERY_HEIGHT_ABOVE_OBSTACLE = 2.0
        private const val REWARP_CENTER_TOLERANCE = 0.2
        private const val REWARP_AOTV_ALIGN_CENTER_TOLERANCE = 1.0
        private const val REWARP_DESCENT_DRIFT_LIMIT = 0.4
        private const val REWARP_SETTLED_SPEED = 0.025
        private const val REWARP_CORRECTION_AXIS_THRESHOLD = 0.05
        private const val REWARP_CORRECTION_PULSE_MS = 50L
        private const val REWARP_SETTLED_SAMPLES = 3

        private var lastRewarpTime = 0L
        @Volatile private var pendingPostResumePair: RewarpPointPair? = null

        @JvmStatic
        fun handle(client: Minecraft) {
            if (!AetherConfig.ENABLE_REWARP.get()
                || MacroStateManager.getCurrentState() != MacroState.State.FARMING) return

            val now = System.currentTimeMillis()
            if (now - lastRewarpTime < REWARP_COOLDOWN_MS) return
            val pair = findReachedEndPair(client) ?: return

            val rewarpMode = pair.rewarpMode ?: return
            if (rewarpMode.usesCommand() && isZeroRewarpDelay()
                && !AetherConfig.SQUEAKY_MOUSEMAT.get()) {
                handleInstantCommandRewarp(client, now, pair)
                return
            }

            lastRewarpTime = now
            ClientUtils.sendMessage("\u00A76Rewarp End Position reached!", true)
            MacroStateManager.setCurrentState(MacroState.State.REWARPING)
            val activeMacro = FarmingMacroManager.getActiveMacro()
            val resumeStep: Int? = if (pair.reverseDirection && activeMacro != null)
                activeMacro.getOppositeCycleStep() else null
            FarmingMacroManager.disable(client)
            MacroWorkerThread.getInstance().submit("PlotTpRewarp") { performRewarp(client, pair, resumeStep) }
        }

        private fun isZeroRewarpDelay(): Boolean =
            AetherConfig.REWARP_DELAY_MIN.get() <= 0 && AetherConfig.REWARP_DELAY_MAX.get() <= 0

        private fun handleInstantCommandRewarp(client: Minecraft, now: Long, pair: RewarpPointPair) {
            if (client == null || client.player == null) return
            lastRewarpTime = now
            client.execute {
                ConfigHelpers.executeRewarpCommand(pair.rewarpMode, pair.plotTpNumber)
                if (pair.reverseDirection) {
                    val activeMacro = FarmingMacroManager.getActiveMacro()
                    if (activeMacro != null) activeMacro.reverseDirection(client)
                }
                PestManager.markRewarpCompleted()
            }
        }

        private fun withinRadius(client: Minecraft, x: Double, y: Double, z: Double): Boolean {
            val player = client.player ?: return false
            val dx = player.getX() - x
            val dy = player.getY() - y
            val dz = player.getZ() - z
            return dx * dx + dy * dy + dz * dz <= 1.5 * 1.5
        }

        private fun findReachedEndPair(client: Minecraft): RewarpPointPair? {
            if (client == null || client.player == null) return null
            for (pair in RewarpPointPairs.get()) {
                if (pair.hasEnd() && withinRadius(client, pair.endX, pair.endY, pair.endZ)) return pair
            }
            return null
        }

        private fun performRewarp(client: Minecraft, pair: RewarpPointPair, resumeStep: Int?) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return
            // Save the current view angles so we can restore them after any server-side
            // teleport that may have changed the player's rotation (avoids snapping).
            val savedRotation = getPlayerRotation(client)
            MacroWorkerThread.sleepRandom(255, 90)
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return

            var rewarpCompleted = false
            val rewarpMode = pair.rewarpMode
            if (rewarpMode != null && rewarpMode.usesCommand()) {
                performCommandRewarp(client, pair)
                rewarpCompleted = MacroStateManager.getCurrentState() == MacroState.State.REWARPING
            } else if (rewarpMode == RewarpMode.FLY && pair.hasStart()) {
                rewarpCompleted = performCoordinateRewarp(client, pair)
            }

            if (rewarpCompleted && MacroStateManager.getCurrentState() == MacroState.State.REWARPING) {
                restorePreRewarpRotation(client, savedRotation.yaw(), savedRotation.pitch())
                if (MacroStateManager.getCurrentState() != MacroState.State.REWARPING) return
                queuePostResumeActions(pair)
                MacroStateManager.setCurrentState(MacroState.State.FARMING)
                SqueakyMousematManager.armReapplyAttempt()
                client.execute {
                    if (resumeStep != null) FarmingMacroManager.saveCycleStep(resumeStep)
                    FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig())
                }
                PestManager.markRewarpCompleted()
            }
        }

        private fun restorePreRewarpRotation(client: Minecraft, restoreYaw: Float, restorePitch: Float) {
            if (client == null || getPlayerPosition(client) == null) return
            client.execute {
                RotationManager.rotateToYawPitch(client, restoreYaw, restorePitch, AetherConfig.ROTATION_TIME.get().toLong())
            }
            while (RotationManager.isRotating()) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    RotationManager.cancelRotation()
                    return
                }
                MacroWorkerThread.sleep(25)
            }
            MacroWorkerThread.sleepRandom(50, 20)
        }

        private fun performCommandRewarp(client: Minecraft, pair: RewarpPointPair) {
            client.execute { ConfigHelpers.executeRewarpCommand(pair.rewarpMode, pair.plotTpNumber) }
            MacroWorkerThread.sleepRandom(
                AetherConfig.REWARP_DELAY_MIN.get().toLong(),
                Math.max(0, AetherConfig.REWARP_DELAY_MAX.get() - AetherConfig.REWARP_DELAY_MIN.get()).toLong()
            )
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return
        }

        private fun performCoordinateRewarp(client: Minecraft, pair: RewarpPointPair): Boolean {
            ensureFlight(client)
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return false
            rotateToRewarpStart(client, pair)
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return false
            client.execute { ClientUtils.setKeyMappingState(client.options.keyJump, true) }
            MacroWorkerThread.sleepRandom(1300, 300)
            client.execute { ClientUtils.setKeyMappingState(client.options.keyJump, false) }
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return false
            client.execute {
                PathfindingManager.startFlyPathfind(
                    client, Math.floor(pair.startX).toInt(), 74, Math.floor(pair.startZ).toInt()
                )
            }
            if (!waitForNavigationToFinish(client)) return false
            if (pair.aotvAlign) {
                if (!performAotvAlign(client, pair)) return false
            } else if (!landOnRewarpBlock(client, pair)) return false
            return true
        }

        @JvmStatic
        fun runPendingPostResumeActions(client: Minecraft) {
            val pair = consumePendingPostResumePair() ?: return
            holdWUntilWall(client, pair)
        }

        private fun queuePostResumeActions(pair: RewarpPointPair?) {
            pendingPostResumePair = if (pair != null && pair.holdWUntilWall) pair else null
        }

        private fun consumePendingPostResumePair(): RewarpPointPair? {
            val pair = pendingPostResumePair
            pendingPostResumePair = null
            return pair
        }

        private fun performAotvAlign(client: Minecraft, pair: RewarpPointPair): Boolean {
            if (getPlayerPosition(client) == null) return false
            val aotvSlot = GearManager.findAspectOfTheVoidSlot(client)
            if (aotvSlot < 0 || aotvSlot > 8) {
                ClientUtils.sendDebugMessage("Rewarp align failed: no AOTV/AOTE in hotbar")
                return false
            }
            if (!stabilizeFlyPositionForAotv(client, pair)) {
                ClientUtils.sendDebugMessage("Rewarp align failed: could not stabilize above target")
                return false
            }
            rotateToRewarpStartBlock(client, pair)
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return false
            val playerPos = getPlayerPosition(client) ?: return false
            GearManager.swapToAOTVSync(client)
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return false
            ClientUtils.performUseClick()
            ClientUtils.waitForYChange(playerPos.y, 900)
            MacroWorkerThread.sleepRandom(170, 60)
            return !MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)
        }

        private fun stabilizeFlyPositionForAotv(client: Minecraft, pair: RewarpPointPair): Boolean {
            releaseHorizontalMovementKeys(client)
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return false
            rotateToRewarpStartBlock(client, pair)
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return false
            val target = getRewarpWalkGoalPosition(pair)
            val deadline = System.currentTimeMillis() + REWARP_POSITION_ADJUST_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    releaseHorizontalMovementKeys(client)
                    return false
                }
                val playerPos = getPlayerPosition(client)
                if (playerPos == null) {
                    releaseHorizontalMovementKeys(client)
                    return false
                }
                val deltaX = target.x - playerPos.x
                val deltaZ = target.z - playerPos.z
                if (Math.abs(deltaX) <= REWARP_AOTV_ALIGN_CENTER_TOLERANCE
                    && Math.abs(deltaZ) <= REWARP_AOTV_ALIGN_CENTER_TOLERANCE) {
                    releaseHorizontalMovementKeys(client)
                    return true
                }
                val yawRad = Math.toRadians(getPlayerYaw(client).toDouble())
                val forwardError = deltaX * -Math.sin(yawRad) + deltaZ * Math.cos(yawRad)
                val strafeError = deltaX * Math.cos(yawRad) + deltaZ * Math.sin(yawRad)
                val keyToTap = if (Math.abs(forwardError) >= Math.abs(strafeError))
                    if (forwardError >= 0) client.options.keyUp else client.options.keyDown
                else if (strafeError >= 0) client.options.keyRight else client.options.keyLeft
                tapMovementKey(client, keyToTap)
            }
            releaseHorizontalMovementKeys(client)
            return false
        }

        private fun holdWUntilWall(client: Minecraft, pair: RewarpPointPair) {
            if (!pair.holdWUntilWall) return
            client.execute { ClientUtils.setKeyMappingState(client.options.keyUp, true) }
            MacroWorkerThread.sleepRandom(170, 60)
            val wallTimeout = System.currentTimeMillis() + 5000
            val lastPos = getPlayerPosition(client)
            if (lastPos == null) {
                client.execute { ClientUtils.setKeyMappingState(client.options.keyUp, false) }
                return
            }
            var lastX = lastPos.x
            var lastZ = lastPos.z
            while (System.currentTimeMillis() < wallTimeout) {
                if (MacroWorkerThread.shouldAbortTask(client)) {
                    client.execute { ClientUtils.setKeyMappingState(client.options.keyUp, false) }
                    return
                }
                MacroWorkerThread.sleep(100)
                val currPos = getPlayerPosition(client)
                if (currPos == null) {
                    client.execute { ClientUtils.setKeyMappingState(client.options.keyUp, false) }
                    return
                }
                val currX = currPos.x
                val currZ = currPos.z
                val moved = Math.sqrt((currX - lastX) * (currX - lastX) + (currZ - lastZ) * (currZ - lastZ))
                if (moved < 0.03) break
                lastX = currX
                lastZ = currZ
            }
            client.execute { ClientUtils.setKeyMappingState(client.options.keyUp, false) }
            MacroWorkerThread.sleepRandom(85, 30)
        }

        private fun landOnRewarpBlock(client: Minecraft, pair: RewarpPointPair): Boolean {
            val target = getRewarpWalkGoalPosition(pair)
            for (attempt in 1..REWARP_LANDING_ATTEMPTS) {
                if (!prepareLandingAttempt(client, target, attempt)) {
                    ClientUtils.sendDebugMessage("Rewarp landing attempt $attempt could not recover flight above the landing area")
                    continue
                }
                ClientUtils.sendDebugMessage("Rewarp landing attempt $attempt/$REWARP_LANDING_ATTEMPTS target=${formatVec(target)} current=${formatVec(getPlayerPosition(client))} flying=${isPlayerFlying(client)}")
                if (!stabilizeAboveTarget(client, target)) {
                    ClientUtils.sendDebugMessage("Rewarp landing attempt $attempt could not center above the target")
                    continue
                }
                if (descendOntoTarget(client, pair, target)) {
                    try {
                        PestReturnManager.performUnfly(client)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                    MacroWorkerThread.sleepRandom(225, 75)
                    return isStandingOnTarget(client, pair)
                }
                ClientUtils.sendDebugMessage("Rewarp landing attempt $attempt drifted away from the target; retrying")
            }
            releaseMovementKeys(client)
            ClientUtils.sendMessage("\u00A7cCould not land on the rewarp start block.", true)
            return false
        }

        private fun stabilizeAboveTarget(client: Minecraft, target: Vec3): Boolean {
            releaseHorizontalMovementKeys(client)
            MacroWorkerThread.sleep(150)
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return false
            val deadline = System.currentTimeMillis() + REWARP_POSITION_ADJUST_TIMEOUT_MS
            var nextDebugAt = 0L
            var settledSamples = 0
            while (System.currentTimeMillis() < deadline) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    releaseHorizontalMovementKeys(client)
                    return false
                }
                val snapshot = getFlightSnapshot(client) ?: return false
                if (snapshot.onGround()) {
                    ClientUtils.sendDebugMessage("Rewarp centering touched a solid block: target=${formatVec(target)} current=${formatVec(snapshot.position())} flying=${isPlayerFlying(client)}; retrying from above")
                    releaseMovementKeys(client)
                    return false
                }
                val deltaX = target.x - snapshot.position().x
                val deltaZ = target.z - snapshot.position().z
                val centered = Math.abs(deltaX) <= REWARP_CENTER_TOLERANCE && Math.abs(deltaZ) <= REWARP_CENTER_TOLERANCE
                val correction = calculateHorizontalCorrection(deltaX, deltaZ, snapshot.velocity(), getPlayerYaw(client))
                val horizontalSpeed = Math.hypot(snapshot.velocity().x, snapshot.velocity().z)
                val now = System.currentTimeMillis()
                if (now >= nextDebugAt) {
                    ClientUtils.sendDebugMessage("Rewarp centering: target=${formatVec(target)} current=${formatVec(snapshot.position())} delta=${formatPair(deltaX, deltaZ)} velocity=${formatVec(snapshot.velocity())} correction=${formatCorrection(correction)} phase=${if (centered) "settling" else if (horizontalSpeed <= REWARP_SETTLED_SPEED) "tap" else "coast"} centered=$centered settledSamples=$settledSamples")
                    nextDebugAt = now + 500L
                }
                if (centered) {
                    releaseHorizontalMovementKeys(client)
                    if (horizontalSpeed <= REWARP_SETTLED_SPEED) {
                        settledSamples++
                        if (settledSamples >= REWARP_SETTLED_SAMPLES) return true
                    } else settledSamples = 0
                } else {
                    settledSamples = 0
                    releaseHorizontalMovementKeys(client)
                    if (horizontalSpeed <= REWARP_SETTLED_SPEED) {
                        applyHorizontalCorrection(client, correction)
                        MacroWorkerThread.sleep(REWARP_CORRECTION_PULSE_MS)
                        releaseHorizontalMovementKeys(client)
                    }
                }
                MacroWorkerThread.sleep(REWARP_MOVEMENT_TICK_MS)
            }
            releaseHorizontalMovementKeys(client)
            ClientUtils.sendDebugMessage("Rewarp centering timed out after ${REWARP_POSITION_ADJUST_TIMEOUT_MS}ms; target=${formatVec(target)} current=${formatVec(getPlayerPosition(client))}")
            return false
        }

        private fun descendOntoTarget(client: Minecraft, pair: RewarpPointPair, target: Vec3): Boolean {
            releaseHorizontalMovementKeys(client)
            val deadline = System.currentTimeMillis() + REWARP_DESCENT_TIMEOUT_MS
            var nextDebugAt = 0L
            while (System.currentTimeMillis() < deadline) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    releaseMovementKeys(client)
                    return false
                }
                val snapshot = getFlightSnapshot(client)
                if (snapshot == null) {
                    releaseMovementKeys(client)
                    return false
                }
                if (isStandingOnTarget(snapshot, pair)) {
                    releaseMovementKeys(client)
                    return true
                }
                if (snapshot.onGround()) {
                    ClientUtils.sendDebugMessage("Rewarp descent landed on an unexpected solid block: target=${formatVec(target)} current=${formatVec(snapshot.position())} targetFeetY=${format(pair.startY)}; retrying from above")
                    releaseMovementKeys(client)
                    return false
                }
                val deltaX = target.x - snapshot.position().x
                val deltaZ = target.z - snapshot.position().z
                if (Math.abs(deltaX) > REWARP_DESCENT_DRIFT_LIMIT || Math.abs(deltaZ) > REWARP_DESCENT_DRIFT_LIMIT) {
                    ClientUtils.sendDebugMessage("Rewarp descent drifted outside limit: target=${formatVec(target)} current=${formatVec(snapshot.position())} delta=${formatPair(deltaX, deltaZ)} velocity=${formatVec(snapshot.velocity())} limit=$REWARP_DESCENT_DRIFT_LIMIT")
                    releaseMovementKeys(client)
                    return false
                }
                val now = System.currentTimeMillis()
                if (now >= nextDebugAt) {
                    ClientUtils.sendDebugMessage("Rewarp descending: current=${formatVec(snapshot.position())} delta=${formatPair(deltaX, deltaZ)} velocity=${formatVec(snapshot.velocity())} horizontalKeys=released onGround=${snapshot.onGround()}")
                    nextDebugAt = now + 500L
                }
                client.execute { ClientUtils.setKeyMappingState(client.options.keyShift, true) }
                MacroWorkerThread.sleep(REWARP_MOVEMENT_TICK_MS)
            }
            releaseMovementKeys(client)
            ClientUtils.sendDebugMessage("Rewarp descent timed out after ${REWARP_DESCENT_TIMEOUT_MS}ms; target=${formatVec(target)} current=${formatVec(getPlayerPosition(client))}")
            return false
        }

        private fun prepareLandingAttempt(client: Minecraft, target: Vec3, attempt: Int): Boolean {
            val initial = getFlightSnapshot(client) ?: return false
            if (!initial.onGround() && isPlayerFlying(client)) return true
            releaseMovementKeys(client)
            val recoveryY = Math.max(target.y + REWARP_RECOVERY_HEIGHT_ABOVE_TARGET, initial.position().y + REWARP_RECOVERY_HEIGHT_ABOVE_OBSTACLE)
            ClientUtils.sendDebugMessage("Rewarp landing attempt $attempt recovering from solid block: current=${formatVec(initial.position())} flying=${isPlayerFlying(client)} recoveryY=${format(recoveryY)}")
            ensureFlight(client)
            if (!isPlayerFlying(client)) {
                ClientUtils.sendDebugMessage("Rewarp recovery could not enable flight: current=${formatVec(getPlayerPosition(client))} canFly=${canPlayerFly(client)}")
                return false
            }
            val deadline = System.currentTimeMillis() + REWARP_RECOVERY_TIMEOUT_MS
            client.execute { ClientUtils.setKeyMappingState(client.options.keyJump, true) }
            while (System.currentTimeMillis() < deadline) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    releaseMovementKeys(client)
                    return false
                }
                val snapshot = getFlightSnapshot(client)
                if (snapshot == null) {
                    releaseMovementKeys(client)
                    return false
                }
                if (!snapshot.onGround() && snapshot.position().y >= recoveryY) {
                    client.execute { ClientUtils.setKeyMappingState(client.options.keyJump, false) }
                    MacroWorkerThread.sleep(100)
                    ClientUtils.sendDebugMessage("Rewarp recovery reached safe height: current=${formatVec(snapshot.position())} recoveryY=${format(recoveryY)}")
                    return true
                }
                MacroWorkerThread.sleep(REWARP_MOVEMENT_TICK_MS)
            }
            releaseMovementKeys(client)
            ClientUtils.sendDebugMessage("Rewarp recovery timed out after ${REWARP_RECOVERY_TIMEOUT_MS}ms: current=${formatVec(getPlayerPosition(client))} recoveryY=${format(recoveryY)} flying=${isPlayerFlying(client)}")
            return false
        }

        private fun calculateHorizontalCorrection(deltaX: Double, deltaZ: Double, velocity: Vec3, yaw: Float): HorizontalCorrection {
            val yawRad = Math.toRadians(yaw.toDouble())
            val forwardError = deltaX * -Math.sin(yawRad) + deltaZ * Math.cos(yawRad)
            val strafeError = deltaX * -Math.cos(yawRad) + deltaZ * -Math.sin(yawRad)
            val forwardVelocity = velocity.x * -Math.sin(yawRad) + velocity.z * Math.cos(yawRad)
            val strafeVelocity = velocity.x * -Math.cos(yawRad) + velocity.z * -Math.sin(yawRad)
            val forward = forwardError > REWARP_CORRECTION_AXIS_THRESHOLD
            val backward = forwardError < -REWARP_CORRECTION_AXIS_THRESHOLD
            val right = strafeError > REWARP_CORRECTION_AXIS_THRESHOLD
            val left = strafeError < -REWARP_CORRECTION_AXIS_THRESHOLD
            return HorizontalCorrection(forwardError, strafeError, forwardVelocity, strafeVelocity, forward, backward, right, left, yaw)
        }

        private fun applyHorizontalCorrection(client: Minecraft, correction: HorizontalCorrection) {
            client.execute {
                ClientUtils.setKeyMappingState(client.options.keyUp, correction.forward())
                ClientUtils.setKeyMappingState(client.options.keyDown, correction.backward())
                ClientUtils.setKeyMappingState(client.options.keyRight, correction.right())
                ClientUtils.setKeyMappingState(client.options.keyLeft, correction.left())
            }
        }

        private fun waitForNavigationToFinish(client: Minecraft): Boolean {
            MacroWorkerThread.sleepRandom(170, 60)
            while (PathfindingManager.isNavigating()) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    PathfindingManager.stop()
                    return false
                }
                MacroWorkerThread.sleep(100)
            }
            return MacroStateManager.getCurrentState() == MacroState.State.REWARPING
        }

        private fun ensureFlight(client: Minecraft) {
            if (isPlayerFlying(client) || !canPlayerFly(client)) return
            val flyStart = System.currentTimeMillis()
            while (!isPlayerFlying(client) && System.currentTimeMillis() - flyStart < 3000) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) {
                    client.execute { ClientUtils.setKeyMappingState(client.options.keyJump, false) }
                    return
                }
                val elapsed = System.currentTimeMillis() - flyStart
                val cycle = elapsed % 250
                client.execute {
                    if (cycle < 50) ClientUtils.setKeyMappingState(client.options.keyJump, true)
                    else if (cycle < 100) ClientUtils.setKeyMappingState(client.options.keyJump, false)
                    else if (cycle < 150) ClientUtils.setKeyMappingState(client.options.keyJump, true)
                    else ClientUtils.setKeyMappingState(client.options.keyJump, false)
                }
                MacroWorkerThread.sleep(20)
            }
            client.execute { ClientUtils.setKeyMappingState(client.options.keyJump, false) }
        }

        private fun rotateToRewarpStart(client: Minecraft, pair: RewarpPointPair) {
            client.execute {
                RotationManager.initiateRotation(client, Vec3(pair.startX, pair.startY, pair.startZ), 0)
            }
            while (RotationManager.isRotating()) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return
                MacroWorkerThread.sleep(50)
            }
        }

        private fun rotateToRewarpStartBlock(client: Minecraft, pair: RewarpPointPair) {
            val targetBlock = BlockPos.containing(Math.floor(pair.startX), Math.floor(pair.startY), Math.floor(pair.startZ))
            client.execute { RotationManager.initiateRotation(client, Vec3.atCenterOf(targetBlock), 0) }
            while (RotationManager.isRotating()) {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.REWARPING)) return
                MacroWorkerThread.sleep(50)
            }
        }

        private fun tapMovementKey(client: Minecraft, key: KeyMapping?) {
            if (client == null || client.options == null || key == null) return
            client.execute { ClientUtils.setKeyMappingState(key, true) }
            MacroWorkerThread.sleep(REWARP_POSITION_TAP_MS)
            client.execute { ClientUtils.setKeyMappingState(key, false) }
            MacroWorkerThread.sleep(REWARP_POSITION_SETTLE_MS)
        }

        private fun releaseHorizontalMovementKeys(client: Minecraft) {
            if (client == null || client.options == null) return
            client.execute {
                ClientUtils.setKeyMappingState(client.options.keyUp, false)
                ClientUtils.setKeyMappingState(client.options.keyDown, false)
                ClientUtils.setKeyMappingState(client.options.keyLeft, false)
                ClientUtils.setKeyMappingState(client.options.keyRight, false)
            }
        }

        private fun releaseMovementKeys(client: Minecraft) {
            releaseHorizontalMovementKeys(client)
            if (client == null || client.options == null) return
            client.execute {
                ClientUtils.setKeyMappingState(client.options.keyShift, false)
                ClientUtils.setKeyMappingState(client.options.keyJump, false)
            }
        }

        private fun getPlayerRotation(client: Minecraft): RotationSnapshot = queryClientThread(client, Supplier {
            val player = client.player
            if (player == null) RotationSnapshot(0f, 0f)
            else RotationSnapshot(player.getYRot(), player.getXRot())
        }, RotationSnapshot(0f, 0f))

        private fun getPlayerPosition(client: Minecraft): Vec3? = queryClientThread(client, Supplier {
            client.player?.position()
        }, null)

        private fun isPlayerFlying(client: Minecraft): Boolean = queryClientThread(client, Supplier {
            client.player?.getAbilities()?.flying == true
        }, false)

        private fun canPlayerFly(client: Minecraft): Boolean = queryClientThread(client, Supplier {
            client.player?.getAbilities()?.mayfly == true
        }, false)

        private fun getPlayerYaw(client: Minecraft): Float = queryClientThread(client, Supplier {
            client.player?.getYRot() ?: 0f
        }, 0f)

        private fun formatVec(value: Vec3?): String {
            if (value == null) return "null"
            return String.format("(%.3f, %.3f, %.3f)", value.x, value.y, value.z)
        }

        private fun formatPair(first: Double, second: Double): String = String.format("(%.3f, %.3f)", first, second)

        private fun formatCorrection(correction: HorizontalCorrection): String =
            "(yaw=${format(correction.yaw())}, error=${formatPair(correction.forwardError(), correction.strafeError())}, speed=${formatPair(correction.forwardVelocity(), correction.strafeVelocity())}, keys=${if (correction.forward()) "W" else if (correction.backward()) "S" else "-"}${if (correction.right()) "D" else if (correction.left()) "A" else "-"})"

        private fun format(value: Float): String = String.format("%.1f", value)
        private fun format(value: Double): String = String.format("%.1f", value)

        private fun getFlightSnapshot(client: Minecraft): FlightSnapshot? = queryClientThread(client, Supplier {
            val player = client.player
            if (player == null) null
            else FlightSnapshot(player.position(), player.getDeltaMovement(), player.onGround())
        }, null)

        private fun isStandingOnTarget(client: Minecraft, pair: RewarpPointPair): Boolean {
            val snapshot = getFlightSnapshot(client)
            return snapshot != null && isStandingOnTarget(snapshot, pair)
        }

        private fun isStandingOnTarget(snapshot: FlightSnapshot, pair: RewarpPointPair): Boolean =
            snapshot.onGround()
                && Math.floor(snapshot.position().x).toInt() == Math.floor(pair.startX).toInt()
                && Math.floor(snapshot.position().y).toInt() == Math.floor(pair.startY).toInt()
                && Math.floor(snapshot.position().z).toInt() == Math.floor(pair.startZ).toInt()

        private fun getRewarpWalkGoalPosition(pair: RewarpPointPair): Vec3 {
            val targetX = Math.floor(pair.startX).toInt()
            val targetY = Math.floor(pair.startY).toInt()
            val targetZ = Math.floor(pair.startZ).toInt()
            return Vec3(targetX + 0.5, targetY.toDouble(), targetZ + 0.5)
        }

        private fun <T> queryClientThread(client: Minecraft, supplier: Supplier<T>, fallback: T): T {
            if (client == null) return fallback
            if (client.isSameThread) return supplier.get()
            val future = CompletableFuture<T>()
            client.execute {
                try {
                    future.complete(supplier.get())
                } catch (throwable: Throwable) {
                    future.complete(fallback)
                }
            }
            return try {
                future.get(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (ignored: Exception) {
                fallback
            }
        }
    }

    private class RotationSnapshot(private val yawValue: Float, private val pitchValue: Float) {
        fun yaw(): Float = yawValue
        fun pitch(): Float = pitchValue
    }

    private class FlightSnapshot(private val positionValue: Vec3, private val velocityValue: Vec3, private val onGroundValue: Boolean) {
        fun position(): Vec3 = positionValue
        fun velocity(): Vec3 = velocityValue
        fun onGround(): Boolean = onGroundValue
    }

    private class HorizontalCorrection(
        private val forwardErrorValue: Double,
        private val strafeErrorValue: Double,
        private val forwardVelocityValue: Double,
        private val strafeVelocityValue: Double,
        private val forwardValue: Boolean,
        private val backwardValue: Boolean,
        private val rightValue: Boolean,
        private val leftValue: Boolean,
        private val yawValue: Float
    ) {
        fun forwardError(): Double = forwardErrorValue
        fun strafeError(): Double = strafeErrorValue
        fun forwardVelocity(): Double = forwardVelocityValue
        fun strafeVelocity(): Double = strafeVelocityValue
        fun forward(): Boolean = forwardValue
        fun backward(): Boolean = backwardValue
        fun right(): Boolean = rightValue
        fun left(): Boolean = leftValue
        fun yaw(): Float = yawValue
    }
}
