package dev.aether.macro.farming

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.AbstractMacro
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.farming.FastLaneSwitchManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.farming.UngrabMouse
import dev.aether.mixin.MixinMinecraft
import dev.aether.modules.rotation.RotationManager
import dev.aether.modules.visuals.FreecamManager
import dev.aether.util.ClientUtils
import dev.aether.util.ProgrammaticAttackTracker
import dev.aether.util.ProgrammaticMovementTracker
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.block.state.BlockState
import java.util.ArrayList
import java.util.Optional

// updateState picks the state each tick, invokeState presses its keys
// holdKeys releases every key it is not given
abstract class AbstractFarmingMacro : AbstractMacro() {
    enum class State { NONE, SWITCHING_LANE, LEFT, RIGHT, FORWARD, BACKWARD }

    @JvmField internal var currentState: State = State.NONE
    @JvmField protected var previousState: State = State.NONE
    protected var yaw: Optional<Float> = Optional.empty()
    protected var pitch: Optional<Float> = Optional.empty()
    protected var rotated = false
    private val stateCycles = ArrayList<StateCycle>()
    private var defaultStateCycle: StateCycle? = null
    private var continueAttack = true
    private var attackHeldByMacro = false

    override fun onEnable(mc: Minecraft) {
        val mousematRotation = SqueakyMousematManager.getMacroRotationOverride(mc)
        if (mousematRotation != null) {
            yaw = Optional.of(mousematRotation.yaw())
            pitch = Optional.of(mousematRotation.pitch())
        } else {
            if (AetherConfig.MACRO_USE_CUSTOM_PITCH.get()) {
                var pitchVal = AetherConfig.MACRO_CUSTOM_PITCH.get()
                val hum = AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION.get()
                if (hum > 0) pitchVal += ((Math.random() - 0.5) * 2.0 * hum).toFloat()
                pitch = Optional.of(pitchVal)
            }
            if (AetherConfig.MACRO_USE_CUSTOM_YAW.get()) {
                var yawVal = AetherConfig.MACRO_CUSTOM_YAW.get()
                val hum = AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION.get()
                if (hum > 0) yawVal += ((Math.random() - 0.5) * 2.0 * hum).toFloat()
                yaw = Optional.of(yawVal)
            }
        }
        if (AetherConfig.MACRO_UNGRAB_MOUSE.get()) UngrabMouse.requestMacroUngrab()
        FarmingMacroManager.loadCycleStep()
        rotated = false
        continueAttack = true
        attackHeldByMacro = false
        FastLaneSwitchManager.resetRuntime()
        changeState(State.NONE)
        stateCycles.forEach { it.reset() }
        applyDefaultOrientation(mc)
        rotateToConfiguredOrientation(mc)
    }

    override fun onDisable(mc: Minecraft) {
        releaseAll(mc)
        changeState(State.NONE)
        yaw = Optional.empty()
        pitch = Optional.empty()
        rotated = false
        continueAttack = false
        attackHeldByMacro = false
        FastLaneSwitchManager.resetRuntime()
    }

    final override fun onTick(mc: Minecraft) {
        val player = mc.player ?: return
        if (mc.level == null) return
        if (ClientUtils.isInventoryScreenOpen()) {
            interruptAttack()
            releaseAll(mc)
            return
        }
        if (mc.gui.screen() != null && isFarmingState()) interruptAttack()
        if (!rotated && (yaw.isPresent || pitch.isPresent)) {
            val targetYaw = yaw.orElseGet(player::getYRot)
            val targetPitch = pitch.orElseGet(player::getXRot)
            RotationManager.rotateToYawPitch(mc, targetYaw, targetPitch, AetherConfig.ROTATION_TIME.get().toLong())
            rotated = true
            stopMovementKeepAttack(mc)
            return
        }
        if (RotationManager.isRotating()) {
            stopMovementKeepAttack(mc)
            return
        }
        if (player.getAbilities().flying) {
            holdKeys(mc, false, false, false, false, true, false, true)
            return
        }
        FastLaneSwitchManager.tick(mc, currentState)
        updateState(mc)
        invokeState(mc)
    }

    open fun updateState(mc: Minecraft) {
        val cycle = defaultStateCycle
        if (cycle != null) updateHorizontalStateCycle(mc, cycle)
    }

    open fun invokeState(mc: Minecraft) {
        val cycle = defaultStateCycle
        if (cycle == null || cycle.isWaitingAtStateEnd()) {
            stopMovementKeepAttack(mc)
            return
        }
        val keys = stateKeys()[currentState] ?: defaultStateKeys(currentState)
        if (keys != null) {
            holdKeys(mc, keys.left(), keys.right(), keys.forward(), keys.back(), keys.attack(), keys.sprint(), keys.sneak())
            return
        }
        stopMovementKeepAttack(mc)
    }

    open fun isFarmingState(): Boolean = defaultStateKeys(currentState) != null || stateKeys().containsKey(currentState)
    private fun interruptAttack() { continueAttack = false }
    protected fun changeState(newState: State) {
        val oldState = currentState
        previousState = currentState
        currentState = newState
        if (oldState != newState) FastLaneSwitchManager.onStateChanged(oldState, newState)
    }
    fun getOppositeCycleStep(): Int? {
        val cycle = defaultStateCycle ?: return null
        val opposite = when (currentState) {
            State.LEFT -> State.RIGHT
            State.RIGHT -> State.LEFT
            State.FORWARD -> State.BACKWARD
            State.BACKWARD -> State.FORWARD
            else -> State.NONE
        }
        if (opposite == State.NONE) return null
        for (i in cycle.states.indices) if (cycle.states[i] == opposite) return i
        return null
    }
    fun reverseDirection(mc: Minecraft) {
        val step = getOppositeCycleStep() ?: return
        val cycle = defaultStateCycle ?: return
        cycle.currentIndex = step
        changeAndSaveCycleState(cycle, cycle.states[step])
        cycle.resetHorizontal(mc)
        invokeState(mc)
    }
    protected open fun isYawSet() = yaw.isPresent
    protected open fun isPitchSet() = pitch.isPresent

    protected fun holdKeys(mc: Minecraft, left: Boolean, right: Boolean, forward: Boolean, back: Boolean, attack: Boolean, sprint: Boolean, sneak: Boolean) {
        if (ClientUtils.isInventoryScreenOpen()) {
            ProgrammaticMovementTracker.set(mc.options.keyLeft, false)
            ProgrammaticMovementTracker.set(mc.options.keyRight, false)
            ProgrammaticMovementTracker.set(mc.options.keyUp, false)
            ProgrammaticMovementTracker.set(mc.options.keyDown, false)
            ProgrammaticMovementTracker.set(mc.options.keyShift, false)
            ProgrammaticMovementTracker.set(mc.options.keySprint, false)
            ProgrammaticMovementTracker.set(mc.options.keyJump, false)
            ProgrammaticAttackTracker.setHeld(mc.options.keyAttack, false)
            attackHeldByMacro = false
            ClientUtils.setKeyMappingState(mc.options.keyAttack, false)
            ClientUtils.forceReleaseMovementKeys()
            return
        }
        val opts = mc.options
        val shouldAttack = attack && continueAttack
        val wasAttackHeldByMacro = attackHeldByMacro
        attackHeldByMacro = shouldAttack
        ProgrammaticAttackTracker.setHeld(opts.keyAttack, shouldAttack)
        ProgrammaticMovementTracker.set(opts.keyLeft, left)
        ProgrammaticMovementTracker.set(opts.keyRight, right)
        ProgrammaticMovementTracker.set(opts.keyUp, forward)
        ProgrammaticMovementTracker.set(opts.keyDown, back)
        ProgrammaticMovementTracker.set(opts.keyShift, sneak)
        ProgrammaticMovementTracker.set(opts.keySprint, sprint)
        ProgrammaticMovementTracker.set(opts.keyJump, false)
        ClientUtils.setKeyMappingState(opts.keyLeft, left)
        ClientUtils.setKeyMappingState(opts.keyRight, right)
        ClientUtils.setKeyMappingState(opts.keyUp, forward)
        ClientUtils.setKeyMappingState(opts.keyDown, back)
        ClientUtils.setKeyMappingState(opts.keyShift, sneak)
        ClientUtils.setKeyMappingState(opts.keySprint, sprint)
        ClientUtils.setKeyMappingState(opts.keyAttack, shouldAttack)
        if (shouldAttack) {
            val minecraft = mc as MixinMinecraft
            minecraft.`aether$setMissTime`(0)
            if (!wasAttackHeldByMacro) minecraft.`aether$startAttack`()
        }
    }
    fun releaseAll(mc: Minecraft) = holdKeys(mc, false, false, false, false, false, false, false)
    protected fun stopMovementKeepAttack(mc: Minecraft) = holdKeys(mc, false, false, false, false, true, false, false)

    protected fun shouldIgnoreMovementStall(mc: Minecraft): Boolean = mc.player != null && FreecamManager.shouldSuppressMacroMovementDetection(mc.player)
    protected fun shouldSuppressLaneChangeForDirt(mc: Minecraft): Boolean = FailsafeManager.isTouchingDirtBlock(mc)

    class DefaultAngle(private val pitchValue: Float, private val yawOffsetValue: Float) {
        fun pitch(): Float = pitchValue
        fun yawOffset(): Float = yawOffsetValue
        override fun equals(other: Any?): Boolean = other is DefaultAngle && java.lang.Float.compare(pitchValue, other.pitchValue) == 0 && java.lang.Float.compare(yawOffsetValue, other.yawOffsetValue) == 0
        override fun hashCode(): Int = java.util.Objects.hash(pitchValue, yawOffsetValue)
        override fun toString(): String = "DefaultAngle[pitch=$pitchValue, yawOffset=$yawOffsetValue]"
        companion object { @JvmField val NONE = DefaultAngle(Float.NaN, Float.NaN) }
    }
    protected open fun defaultAngle(): DefaultAngle = DefaultAngle.NONE

    class StateKeys(private val leftValue: Boolean, private val rightValue: Boolean, private val forwardValue: Boolean, private val backValue: Boolean, private val attackValue: Boolean, private val sprintValue: Boolean, private val sneakValue: Boolean) {
        fun left() = leftValue
        fun right() = rightValue
        fun forward() = forwardValue
        fun back() = backValue
        fun attack() = attackValue
        fun sprint() = sprintValue
        fun sneak() = sneakValue
        override fun equals(other: Any?): Boolean = other is StateKeys && leftValue == other.leftValue && rightValue == other.rightValue && forwardValue == other.forwardValue && backValue == other.backValue && attackValue == other.attackValue && sprintValue == other.sprintValue && sneakValue == other.sneakValue
        override fun hashCode(): Int = java.util.Objects.hash(leftValue, rightValue, forwardValue, backValue, attackValue, sprintValue, sneakValue)
        override fun toString(): String = "StateKeys[left=$leftValue, right=$rightValue, forward=$forwardValue, back=$backValue, attack=$attackValue, sprint=$sprintValue, sneak=$sneakValue]"
    }
    protected open fun stateKeys(): Map<State, StateKeys> = emptyMap()

    companion object {
        private val LEFT_KEYS = StateKeys(true, false, false, false, true, false, false)
        private val RIGHT_KEYS = StateKeys(false, true, false, false, true, false, false)
        private val FORWARD_KEYS = StateKeys(false, false, true, false, true, false, false)
        private val BACKWARD_KEYS = StateKeys(false, false, false, true, true, false, false)
        private val SWITCHING_LANE_KEYS = StateKeys(false, false, true, false, true, true, false)
        private fun defaultStateKeys(state: State): StateKeys? = when (state) {
            State.LEFT -> LEFT_KEYS
            State.RIGHT -> RIGHT_KEYS
            State.FORWARD -> FORWARD_KEYS
            State.BACKWARD -> BACKWARD_KEYS
            State.SWITCHING_LANE -> SWITCHING_LANE_KEYS
            else -> null
        }
    }

    protected inner class MovementStallTracker(private val progressEpsilonSq: Double, private val threshold: Int) {
        private var initialized = false
        private var previousX = 0.0
        private var previousZ = 0.0
        private var stalledTicks = 0
        fun resetHorizontal(mc: Minecraft) {
            val player = mc.player
            if (player != null) { previousX = player.getX(); previousZ = player.getZ(); initialized = true }
            stalledTicks = 0
        }
        fun clear() { initialized = false; stalledTicks = 0 }
        fun hasStalledHorizontally(mc: Minecraft, forceStall: Boolean): Boolean {
            val player = mc.player ?: return false
            if (!initialized) { resetHorizontal(mc); return false }
            val dx = player.getX() - previousX
            val dz = player.getZ() - previousZ
            previousX = player.getX(); previousZ = player.getZ()
            return recordSample(mc, dx * dx + dz * dz >= progressEpsilonSq, forceStall)
        }
        private fun recordSample(mc: Minecraft, madeProgress: Boolean, forceStall: Boolean): Boolean {
            if (shouldIgnoreMovementStall(mc) || (madeProgress && !forceStall)) { stalledTicks = 0; return false }
            if (forceStall || ++stalledTicks >= threshold) { stalledTicks = 0; return true }
            return false
        }
    }

    protected inner class StateCycle(progressEpsilon: Double, stallThreshold: Int, vararg states: State) {
        @JvmField internal val states: Array<out State>
        @JvmField internal val movement: MovementStallTracker
        @JvmField internal var currentIndex = 0
        @JvmField internal var graceTicks = 0
        @JvmField internal var waitingAtStateEnd = false
        init {
            if (states.isEmpty()) throw IllegalArgumentException("A state cycle requires at least one state")
            this.states = states.clone()
            movement = MovementStallTracker(progressEpsilon * progressEpsilon, stallThreshold)
        }
        fun resetHorizontal(mc: Minecraft) { movement.resetHorizontal(mc); graceTicks = 20; waitingAtStateEnd = false }
        fun reset() { movement.clear(); graceTicks = 20; waitingAtStateEnd = false; currentIndex = 0 }
        fun isWaitingAtStateEnd() = waitingAtStateEnd
        fun contains(state: State) = states.any { it == state }
        fun firstOrCached(): State {
            val cachedStep = FarmingMacroManager.getCachedCycleStep()
            currentIndex = if (cachedStep == null) 0 else Math.floorMod(cachedStep, states.size)
            return states[currentIndex]
        }
        fun next(currentState: State): State {
            if (states[currentIndex] != currentState) select(currentState)
            currentIndex = (currentIndex + 1) % states.size
            return states[currentIndex]
        }
        private fun select(state: State) { currentIndex = states.indexOf(state).let { if (it < 0) 0 else it } }
    }

    protected fun stateCycle(progressEpsilon: Double, stallThreshold: Int, vararg states: State): StateCycle {
        val cycle = StateCycle(progressEpsilon, stallThreshold, *states)
        stateCycles.add(cycle)
        if (defaultStateCycle == null) defaultStateCycle = cycle
        return cycle
    }
    protected fun updateHorizontalStateCycle(mc: Minecraft, cycle: StateCycle) {
        updateStateCycle(mc, cycle, cycle.movement.hasStalledHorizontally(mc, FastLaneSwitchManager.shouldFastSwitch(mc, currentState))) { cycle.movement.resetHorizontal(mc) }
    }
    private fun updateStateCycle(mc: Minecraft, cycle: StateCycle, stalled: Boolean, resetMovement: Runnable) {
        if (mc.player == null) return
        if (currentState == State.NONE) {
            changeAndSaveCycleState(cycle, cycle.firstOrCached())
            cycle.graceTicks = 10
            resetMovement.run()
            return
        }
        if (!cycle.contains(currentState)) return
        if (shouldSuppressLaneChangeForDirt(mc)) {
            cycle.waitingAtStateEnd = false
            cycle.graceTicks = 0
            resetMovement.run()
            return
        }
        if (cycle.graceTicks > 0) {
            cycle.graceTicks--
            resetMovement.run()
            if (cycle.waitingAtStateEnd && cycle.graceTicks == 0) {
                cycle.waitingAtStateEnd = false
                changeAndSaveCycleState(cycle, cycle.next(currentState))
                cycle.graceTicks = 10
                resetMovement.run()
            }
            return
        }
        if (!stalled) return
        val fastLaneSwitch = FastLaneSwitchManager.shouldFastSwitch(mc, currentState)
        val delayMs = if (fastLaneSwitch) 0 else ConfigHelpers.getRandomizedDelay(AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(), AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get())
        val delayTicks = (delayMs + 25) / 50
        if (delayTicks > 0) { cycle.waitingAtStateEnd = true; cycle.graceTicks = delayTicks; return }
        changeAndSaveCycleState(cycle, cycle.next(currentState))
        cycle.graceTicks = if (fastLaneSwitch) 0 else 10
        resetMovement.run()
    }
    private fun changeAndSaveCycleState(cycle: StateCycle, state: State) { changeState(state); FarmingMacroManager.saveCycleStep(cycle.currentIndex) }

    protected fun isWalkable(mc: Minecraft, base: BlockPos): Boolean {
        if (mc.level == null) return false
        return isPassable(mc, base) && isPassable(mc, base.above())
    }
    private fun isPassable(mc: Minecraft, pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        val state: BlockState = level.getBlockState(pos)
        return state.getCollisionShape(level, pos).isEmpty
    }
    protected fun getStrafeLeftPos(origin: BlockPos, yaw: Float, strafeSteps: Int): BlockPos {
        val rad = Math.toRadians(yaw.toDouble())
        val dx = Math.round(Math.cos(rad) * strafeSteps).toInt()
        val dz = Math.round(Math.sin(rad) * strafeSteps).toInt()
        return origin.offset(dx, 0, dz)
    }
    protected fun getStrafeRightPos(origin: BlockPos, yaw: Float, strafeSteps: Int): BlockPos = getStrafeLeftPos(origin, yaw, -strafeSteps)
    protected fun getForwardPos(origin: BlockPos, yaw: Float, forwardSteps: Int): BlockPos {
        val rad = Math.toRadians(yaw.toDouble())
        val dx = Math.round(-Math.sin(rad) * forwardSteps).toInt()
        val dz = Math.round(Math.cos(rad) * forwardSteps).toInt()
        return origin.offset(dx, 0, dz)
    }
    protected fun isLeftWalkable(mc: Minecraft): Boolean { val player = mc.player ?: return false; return isWalkable(mc, getStrafeLeftPos(player.blockPosition(), player.getYRot(), 1)) }
    protected fun isRightWalkable(mc: Minecraft): Boolean { val player = mc.player ?: return false; return isWalkable(mc, getStrafeRightPos(player.blockPosition(), player.getYRot(), 1)) }
    protected fun isFrontWalkable(mc: Minecraft): Boolean { val player = mc.player ?: return false; return isWalkable(mc, getForwardPos(player.blockPosition(), player.getYRot(), 1)) }
    protected fun isBackWalkable(mc: Minecraft): Boolean { val player = mc.player ?: return false; return isWalkable(mc, getForwardPos(player.blockPosition(), player.getYRot(), -1)) }
    fun calculateDirection(mc: Minecraft): State {
        val player = mc.player ?: return State.NONE
        val playerYaw = player.getYRot()
        val origin = player.blockPosition()
        for (i in 1 until 64) {
            val rightCandidate = getStrafeRightPos(origin, playerYaw, i)
            val leftCandidate = getStrafeLeftPos(origin, playerYaw, i)
            val rightBlocked = !isWalkable(mc, rightCandidate)
            val leftBlocked = !isWalkable(mc, leftCandidate)
            if (rightBlocked && !leftBlocked) return State.LEFT
            if (leftBlocked && !rightBlocked) return State.RIGHT
        }
        if (currentState == State.LEFT || currentState == State.RIGHT) return currentState
        if (previousState == State.LEFT || previousState == State.RIGHT) return previousState
        return State.LEFT
    }
    private fun applyDefaultOrientation(mc: Minecraft) {
        val angle = defaultAngle()
        val configuredDefaultPitch = angle.pitch()
        if (!isPitchSet() && java.lang.Float.isFinite(configuredDefaultPitch)) pitch = Optional.of(configuredDefaultPitch)
        val configuredDefaultYawOffset = angle.yawOffset()
        val player = mc.player
        if (!isYawSet() && player != null && java.lang.Float.isFinite(configuredDefaultYawOffset)) {
            val nearest = Math.round(Mth.wrapDegrees(player.getYRot()) / 90f) * 90f
            yaw = Optional.of(Mth.wrapDegrees(nearest + configuredDefaultYawOffset))
        }
    }
    protected fun rotateToConfiguredOrientation(mc: Minecraft) {
        val player = mc.player ?: return
        if (yaw.isEmpty && pitch.isEmpty) return
        RotationManager.rotateToYawPitch(mc, yaw.orElseGet(player::getYRot), pitch.orElseGet(player::getXRot), AetherConfig.ROTATION_TIME.get().toLong())
        rotated = true
    }
    fun restoreConfiguredOrientation(mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        if (yaw.isEmpty && pitch.isEmpty) return false
        RotationManager.rotateToYawPitch(mc, yaw.orElseGet(player::getYRot), pitch.orElseGet(player::getXRot), AetherConfig.ROTATION_TIME.get().toLong(), true)
        return true
    }
}
