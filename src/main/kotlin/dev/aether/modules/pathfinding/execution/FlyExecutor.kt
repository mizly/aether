package dev.aether.modules.pathfinding.execution

import dev.aether.config.AetherConfig
import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.rotation.EasingType
import dev.aether.modules.pathfinding.rotation.Rotation
import dev.aether.modules.pathfinding.rotation.RotationExecutor
import dev.aether.modules.pathfinding.rotation.strategy.TimedEaseStrategy
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayList

class FlyExecutor {
    enum class State { IDLE, FLYING, DECELERATING, FINISHED }
    private val guidance = FlightGuidance()
    private var state = State.IDLE
    private var onFinished: Runnable? = null

    fun start(path: List<Node>, goalX: Int, goalY: Int, goalZ: Int) = start(path, goalX, goalY, goalZ, null)
    fun start(path: List<Node>, goalX: Int, goalY: Int, goalZ: Int, onFinished: Runnable?) {
        guidance.start(path.toList(), goalX, goalY, goalZ)
        guidance.setBrakingLookaheadTicks(AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.get().toDouble())
        this.onFinished = onFinished; state = State.FLYING
    }
    fun setPitchControl(pitch: Float) = guidance.setPitchControl(pitch)
    fun setLookTargetRotation(lookTarget: Vec3) = guidance.setLookTargetRotation(lookTarget)
    fun setPreciseGoalTolerance(tolerance: Double) = guidance.setPreciseGoalTolerance(tolerance)
    fun getState() = state

    fun tick(mc: Minecraft) {
        if (mc.player == null || mc.level == null) return
        if (ClientUtils.isInventoryScreenOpen()) { releaseAll(mc); return }
        if (state != State.FLYING && state != State.DECELERATING) return
        guidance.setBrakingLookaheadTicks(AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.get().toDouble())
        val command = guidance.tick(MinecraftFlightView(mc))
        val note = command.note()
        if (note != null) ClientUtils.sendDebugMessage(note)
        when (command.status()) {
            FlightGuidance.Status.REPATH -> { stop(mc); return }
            FlightGuidance.Status.ARRIVED -> { finish(mc); return }
            FlightGuidance.Status.HOLD -> return
            FlightGuidance.Status.STEER -> Unit
        }
        val aim = command.aim()
        if (aim != null) rotateSmoothly(mc, aim)
        FlightMotion.apply(mc, command.horizontal())
        ClientUtils.setKeyMappingState(mc.options.keySprint, command.sprint())
        ClientUtils.setKeyMappingState(mc.options.keyJump, command.vertical() > 0)
        val abilities = mc.player?.abilities
        ClientUtils.setKeyMappingState(mc.options.keyShift, command.vertical() < 0 && abilities != null && abilities.flying)
        state = when (guidance.state()) {
            FlightGuidance.State.DECELERATING -> State.DECELERATING
            FlightGuidance.State.FINISHED -> State.FINISHED
            FlightGuidance.State.IDLE -> State.IDLE
            FlightGuidance.State.FLYING -> State.FLYING
        }
        val guidancePath = guidance.path()
        val pathSize = guidancePath?.size ?: 0
        ClientUtils.sendDebugMessage(String.format("fly wp=%d/%d state=%s mode=%s", Math.min(guidance.waypointIndex() + 1, pathSize), pathSize, state, command.mode()))
    }
    fun stop(mc: Minecraft) { state = State.IDLE; guidance.stop(); RotationExecutor.stopRotating(); releaseAll(mc) }
    fun releaseAll(mc: Minecraft?) {
        if (mc == null || mc.options == null) return
        ClientUtils.setKeyMappingState(mc.options.keyUp, false); ClientUtils.setKeyMappingState(mc.options.keyDown, false)
        ClientUtils.setKeyMappingState(mc.options.keyLeft, false); ClientUtils.setKeyMappingState(mc.options.keyRight, false)
        ClientUtils.setKeyMappingState(mc.options.keyJump, false); ClientUtils.setKeyMappingState(mc.options.keySprint, false)
        ClientUtils.setKeyMappingState(mc.options.keyShift, false)
    }
    private fun finish(mc: Minecraft) {
        RotationExecutor.stopRotating(); releaseAll(mc); state = State.FINISHED
        onFinished?.run()
    }
    private fun rotateSmoothly(mc: Minecraft, desired: Rotation) {
        val player = mc.player ?: return
        val plan = FlightAim.plan(player.yRot, player.xRot, desired, RotationExecutor.isRotating(), RotationExecutor.getTargetYaw(), RotationExecutor.getTargetPitch())
        if (plan != null) RotationExecutor.rotateTo(plan.rotation(), TimedEaseStrategy(EasingType.EASE_IN_OUT_CUBIC, plan.durationMs()))
    }
    private class MinecraftFlightView(private val mc: Minecraft) : FlightView {
        override fun position(): Vec3 = mc.player?.position() ?: Vec3.ZERO
        override fun eyePosition(): Vec3 = mc.player?.eyePosition ?: Vec3.ZERO
        override fun velocity(): Vec3 = mc.player?.deltaMovement ?: Vec3.ZERO
        override fun yaw(): Float = mc.player?.yRot ?: 0f
        override fun pitch(): Float = mc.player?.xRot ?: 0f
        override fun flyingSpeed(): Double = mc.player?.abilities?.flyingSpeed?.toDouble() ?: 0.0
        override fun sprinting(): Boolean = (mc.player?.isSprinting ?: false) || mc.options.keySprint.isDown
        override fun nowMillis(): Long = System.currentTimeMillis()
        override fun bodyAt(feet: Vec3): AABB {
            val player = mc.player ?: return AABB(feet, feet)
            return player.boundingBox.move(feet.subtract(player.position()))
        }
        override fun collisions(search: AABB): Iterable<AABB> {
            val obstacles = ArrayList<AABB>()
            val level = mc.level
            val player = mc.player
            if (level != null && player != null) for (shape in level.getBlockCollisions(player, search)) obstacles.addAll(shape.toAabbs())
            return obstacles
        }
    }
}
