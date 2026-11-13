package dev.aether.util

import dev.aether.modules.failsafe.FailsafeManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.HashMap
import java.util.Iterator

class DelayedBlockBreakTracker private constructor() {
    companion object {
        private const val CLICK_EXPIRY_MS = 1000L
        private val recentClicks: MutableMap<BlockPos, Long> = HashMap()
        @JvmStatic fun onBlockBreakClick(client: Minecraft?, pos: BlockPos?) {
            if (client == null || client.level == null || pos == null) return
            val state = client.level!!.getBlockState(pos)
            if (state == null || state.isAir) return
            val now = System.currentTimeMillis()
            synchronized(recentClicks) { recentClicks[pos.immutable()] = now; cleanupExpired(now) }
        }
        @JvmStatic fun onImmediateBlockBreak(pos: BlockPos?) { if (pos != null) synchronized(recentClicks) { recentClicks.remove(pos) } }
        @JvmStatic fun onBlockChanged(client: Minecraft?, pos: BlockPos?, newState: BlockState?) { if (newState == null || !newState.isAir) return; consumeIfRecent(client, pos, System.currentTimeMillis()) }
        @JvmStatic fun tick(client: Minecraft?) {
            val now = System.currentTimeMillis()
            synchronized(recentClicks) {
                cleanupExpired(now)
                if (recentClicks.isEmpty() || client == null || client.level == null) return
                val iterator = recentClicks.entries.iterator()
                while (iterator.hasNext()) { val entry = iterator.next(); if (client.level!!.getBlockState(entry.key).isAir) { iterator.remove(); recordConfirmedBreak(entry.key) } }
            }
        }
        @JvmStatic fun reset() { synchronized(recentClicks) { recentClicks.clear() } }
        private fun consumeIfRecent(client: Minecraft?, pos: BlockPos?, now: Long) {
            if (client == null || client.level == null || pos == null) return
            val consumed: Boolean
            synchronized(recentClicks) { cleanupExpired(now); consumed = recentClicks.remove(pos) != null }
            if (consumed) recordConfirmedBreak(pos)
        }
        private fun recordConfirmedBreak(pos: BlockPos) { BpsTracker.onBlockBreak(); FailsafeManager.onBlockBreak(pos) }
        private fun cleanupExpired(now: Long) {
            val iterator = recentClicks.entries.iterator()
            while (iterator.hasNext()) if (now - iterator.next().value > CLICK_EXPIRY_MS) iterator.remove()
        }
    }
}
