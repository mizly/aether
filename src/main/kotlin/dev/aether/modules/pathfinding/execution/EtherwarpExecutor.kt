package dev.aether.modules.pathfinding.execution

import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper
import dev.aether.modules.pathfinding.movement.WalkabilityChecker
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.function.Function

class EtherwarpExecutor {
    enum class FailureReason { PLAYER_CONTEXT_MISSING, LOST_LINE_OF_SIGHT, MISSING_ETHERWARP_ITEM, WARP_TIMEOUT }
    enum class State { IDLE, ROTATING, WAITING_FOR_WARP, FINISHED, FAILED }
    private var state = State.IDLE
    private var path: List<Node> = emptyList()
    private var waypointIndex = 1
    private var stateSince = 0L
    private var warpAttempts = 0
    private var warpStartPos = Vec3.ZERO
    private var onFinished: Runnable? = null
    private var onFailed: Function<FailureReason, Boolean>? = null

    fun start(path: List<Node>?, onFinished: Runnable?, onFailed: Function<FailureReason, Boolean>?) {
        this.path = if (path == null) emptyList() else path.toList()
        waypointIndex = if (this.path.size > 1) 1 else this.path.size
        state = if (this.path.size <= 1) State.FINISHED else State.IDLE
        stateSince = System.currentTimeMillis(); warpAttempts = 0; warpStartPos = Vec3.ZERO
        this.onFinished = onFinished; this.onFailed = onFailed
    }
    fun getState() = state
    fun getWaypointIndex() = waypointIndex

    fun tick(mc: Minecraft) {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) { fail(mc, FailureReason.PLAYER_CONTEXT_MISSING, "Etherwarp stopped: player context missing."); return }
        if (mc.options != null) ClientUtils.setKeyMappingState(mc.options.keyShift, true)
        player.setShiftKeyDown(true)
        if (state == State.IDLE || state == State.ROTATING || state == State.WAITING_FOR_WARP) advanceReachedWaypoints(player)
        if (state == State.FINISHED || waypointIndex >= path.size) { finish(mc); return }
        if (state == State.FAILED || ClientUtils.isInventoryScreenOpen()) return
        val next = path[waypointIndex]
        val checker = WalkabilityChecker(level)
        val eyePos = EtherwarpHelper.getEyePosition(mc, player.position())
        val targetPoint = EtherwarpHelper.findVisibleTargetPoint(mc, checker, eyePos, next.position)
        if (targetPoint == null) { fail(mc, FailureReason.LOST_LINE_OF_SIGHT, "Etherwarp lost line of sight to waypoint " + waypointIndex + "."); return }
        val etherwarpSlot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(mc)
        if (etherwarpSlot < 0) { fail(mc, FailureReason.MISSING_ETHERWARP_ITEM, "No hotbar AOTV with Ether Transmission found."); return }
        val now = System.currentTimeMillis()
        if (state == State.WAITING_FOR_WARP) {
            if (now - stateSince > WARP_SETTLE_TIMEOUT_MS) {
                if (warpAttempts >= MAX_WARP_ATTEMPTS) { fail(mc, FailureReason.WARP_TIMEOUT, "Etherwarp timed out at waypoint " + waypointIndex + "."); return }
                state = State.IDLE
                return
            }
            return
        }
        val eye = eyePos ?: run { fail(mc, FailureReason.PLAYER_CONTEXT_MISSING, "Etherwarp stopped: player context missing."); return }
        if (!EtherwarpHelper.isLookingAtTarget(mc, eye, next.position)) {
            if (!RotationManager.isRotating() || now - stateSince > ROTATION_RETRY_MS) {
                RotationManager.cancelRotation()
                val lookRotation = RotationUtils.calculateLookAt(eye, targetPoint)
                RotationManager.rotateToExactYawPitch(mc, lookRotation.yaw, lookRotation.pitch, 80L)
                state = State.ROTATING; stateSince = now
            }
            return
        }
        if (FailsafeManager.getCurrentSelectedSlot(mc) != etherwarpSlot) { FailsafeManager.selectHotbarSlot(mc, etherwarpSlot); return }
        RotationManager.cancelRotation()
        ClientUtils.performUseClickInstant()
        warpAttempts++; warpStartPos = player.position(); state = State.WAITING_FOR_WARP; stateSince = now
    }

    fun stop(mc: Minecraft?) {
        RotationManager.cancelRotation(); state = State.IDLE; path = emptyList(); waypointIndex = 0; warpAttempts = 0
        onFinished = null; onFailed = null
        if (mc != null && mc.options != null) { ClientUtils.setKeyMappingState(mc.options.keyUse, false); ClientUtils.setKeyMappingState(mc.options.keyShift, false) }
        val p = mc?.player
        if (p != null) p.setShiftKeyDown(false)
    }
    private fun advanceReachedWaypoints(player: net.minecraft.client.player.LocalPlayer) {
        while (waypointIndex < path.size) {
            val waypointPos = EtherwarpHelper.getCenteredFeet(path[waypointIndex].position)
            if (player.position().distanceTo(waypointPos) > WAYPOINT_REACHED_DIST) return
            waypointIndex++; warpAttempts = 0; state = State.IDLE; stateSince = System.currentTimeMillis()
        }
    }
    private fun finish(mc: Minecraft?) {
        if (state == State.FINISHED) return
        RotationManager.cancelRotation()
        if (mc != null && mc.options != null) { ClientUtils.setKeyMappingState(mc.options.keyUse, false); ClientUtils.setKeyMappingState(mc.options.keyShift, false) }
        val p = mc?.player
        if (p != null) p.setShiftKeyDown(false)
        state = State.FINISHED
        onFinished?.run()
    }
    private fun fail(mc: Minecraft?, reason: FailureReason, message: String) {
        RotationManager.cancelRotation(); state = State.FAILED
        val handled = onFailed != null && java.lang.Boolean.TRUE == onFailed!!.apply(reason)
        if (mc != null) {
            if (!handled) ClientUtils.sendMessage("\u00A7c$message", false)
            if (mc.options != null) { ClientUtils.setKeyMappingState(mc.options.keyUse, false); ClientUtils.setKeyMappingState(mc.options.keyShift, false) }
            val p = mc.player
            if (p != null) p.setShiftKeyDown(false)
        }
    }
    companion object {
        private const val ROTATION_RETRY_MS = 1200L
        private const val WARP_SETTLE_TIMEOUT_MS = 900L
        private const val MAX_WARP_ATTEMPTS = 3
        private const val WAYPOINT_REACHED_DIST = 1.35
    }
}
