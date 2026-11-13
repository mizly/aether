package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.notification.NotificationManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.AttachedStemBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.StemBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.Vec3

internal object DirtFailsafe {
    private const val TRACKED_BLOCK_TTL_MS = 120_000L
    private const val DESTROYED_BLOCK_TTL_MS = 10_000L
    private const val TOUCH_DISTANCE = 1.5
    enum class State { IDLE, WAIT, TRIGGERED }
    private val dirtBlocks = mutableListOf<TrackedBlock>()
    private val blocksDestroyedByPlayer = mutableListOf<TrackedBlock>()
    private var touchingSinceMs = 0L
    private var touchingRandomDelayMs = 0L
    private var triggered = false

    fun reset() {
        synchronized(dirtBlocks) { dirtBlocks.clear() }
        synchronized(blocksDestroyedByPlayer) { blocksDestroyedByPlayer.clear() }
        resetTouchWait(); triggered = false
    }
    fun onBlockBreak(pos: BlockPos?) {
        if (pos == null || !isTrackingActive()) return
        synchronized(blocksDestroyedByPlayer) { blocksDestroyedByPlayer.add(TrackedBlock(pos.immutable(), System.currentTimeMillis())); cleanupDestroyedByPlayer() }
    }
    fun onBlockChanged(client: Minecraft?, pos: BlockPos?, oldState: BlockState?, newState: BlockState?) {
        val level = client?.level ?: return
        if (pos == null || oldState == null || newState == null || !AetherConfig.FAILSAFE_DIRT_CHECK.get() || !isTrackingActive()) return
        if (!isReplaceableFarmState(oldState) || !isSuspiciousPlacedBlock(client, pos, newState)) return
        synchronized(blocksDestroyedByPlayer) { cleanupDestroyedByPlayer(); if (blocksDestroyedByPlayer.any { it.pos == pos }) return }
        synchronized(dirtBlocks) {
            cleanupTrackedBlocks(client)
            if (dirtBlocks.none { it.pos == pos }) dirtBlocks.add(TrackedBlock(pos.immutable(), System.currentTimeMillis()))
        }
    }
    fun tick(client: Minecraft?) {
        if (client?.player == null || client.level == null) { reset(); return }
        cleanupTrackedBlocks(client)
        if (!AetherConfig.FAILSAFE_DIRT_CHECK.get() || !isTrackingActive() || !isTouchingDirtBlock(client)) { resetTouchWait(); return }
        if (touchingSinceMs == 0L) { touchingSinceMs = System.currentTimeMillis(); touchingRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs(); return }
        val delay = Math.round(AetherConfig.FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS.get() * 1000.0f).toLong() + touchingRandomDelayMs
        if (System.currentTimeMillis() - touchingSinceMs >= delay) trigger(client)
    }
    fun getState(client: Minecraft?): State {
        if (triggered) return State.TRIGGERED
        if (client?.player == null || client.level == null || !AetherConfig.FAILSAFE_DIRT_CHECK.get() || !isTrackingActive()) return State.IDLE
        return if (isTouchingDirtBlock(client)) State.WAIT else State.IDLE
    }
    fun isTouchingDirtBlock(client: Minecraft?): Boolean {
        val player = client?.player ?: return false
        if (client.level == null) return false
        cleanupTrackedBlocks(client)
        val eyePos = player.getEyePosition(1.0f)
        synchronized(dirtBlocks) { for (block in dirtBlocks) if (eyePos.distanceTo(Vec3.atCenterOf(block.pos)) <= TOUCH_DISTANCE) return true }
        return false
    }
    fun getTrackedBlockCount(client: Minecraft?): Int { cleanupTrackedBlocks(client); return synchronized(dirtBlocks) { dirtBlocks.size } }
    fun getTriggerRemainingMs(): Long {
        if (touchingSinceMs == 0L) return 0L
        val delay = Math.round(AetherConfig.FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS.get() * 1000.0f).toLong() + touchingRandomDelayMs
        return maxOf(0L, delay - (System.currentTimeMillis() - touchingSinceMs))
    }
    private fun isReplaceableFarmState(state: BlockState) = state.isAir || isCrop(state) || isWater(state)
    private fun isSuspiciousPlacedBlock(client: Minecraft, pos: BlockPos, state: BlockState): Boolean {
        if (state.isAir || isCrop(state) || isWater(state) || state.`is`(Blocks.LADDER) || state.block is TrapDoorBlock) return false
        val shape = state.getCollisionShape(client.level!!, pos)
        return !shape.isEmpty && Block.isShapeFullBlock(shape)
    }
    private fun isRemovedOrPassable(client: Minecraft, pos: BlockPos, state: BlockState) = state.isAir || isCrop(state) || isWater(state) || state.getCollisionShape(client.level!!, pos).isEmpty
    private fun isCrop(state: BlockState): Boolean {
        val block = state.block
        return block is CropBlock || block is StemBlock || block is AttachedStemBlock || block is NetherWartBlock || block is CocoaBlock
    }
    private fun isWater(state: BlockState) = state.fluidState.`is`(Fluids.WATER)
    private fun cleanupTrackedBlocks(client: Minecraft?) {
        if (client?.level == null) return
        val now = System.currentTimeMillis()
        synchronized(dirtBlocks) { val iterator = dirtBlocks.iterator(); while (iterator.hasNext()) {
            val block = iterator.next(); val state = client.level!!.getBlockState(block.pos)
            if (now - block.createdAtMs > TRACKED_BLOCK_TTL_MS || isRemovedOrPassable(client, block.pos, state)) iterator.remove()
        } }
    }
    private fun cleanupDestroyedByPlayer() { val now = System.currentTimeMillis(); blocksDestroyedByPlayer.removeIf { now - it.createdAtMs > DESTROYED_BLOCK_TTL_MS } }
    private fun isTrackingActive(): Boolean {
        if (!MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() != MacroState.State.FARMING || !FarmingMacroManager.isActive()) return false
        val activeMacro = FarmingMacroManager.getActiveMacro()
        return activeMacro != null && activeMacro.isFarmingState()
    }
    private fun resetTouchWait() { touchingSinceMs = 0L; touchingRandomDelayMs = 0L }
    private fun trigger(client: Minecraft) {
        if (triggered) return
        val action = FailsafeManager.getDirtCheckAction(); triggered = true
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), "A suspicious solid block stayed close to the player.")
        FailsafeManager.handleConfiguredAction(client, action, FailsafeCustomReplayManager.FailsafeReplayType.DIRT_CHECK,
            "suspicious solid block stayed close to the player.", "DirtFailsafe: suspicious solid block stayed close to the player")
        reset()
    }
    private data class TrackedBlock(val pos: BlockPos, val createdAtMs: Long)
}
