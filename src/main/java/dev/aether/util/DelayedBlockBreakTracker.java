package dev.aether.util;

import dev.aether.modules.failsafe.FailsafeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tracks recent mining clicks that may only be confirmed by a later server block update.
 */
public final class DelayedBlockBreakTracker {
    private static final long CLICK_EXPIRY_MS = 1000L;
    private static final Map<BlockPos, Long> recentClicks = new HashMap<>();

    private DelayedBlockBreakTracker() {
    }

    public static void onBlockBreakClick(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null || pos == null) {
            return;
        }

        BlockState state = client.level.getBlockState(pos);
        if (state == null || state.isAir()) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (recentClicks) {
            recentClicks.put(pos.immutable(), now);
            cleanupExpired(now);
        }
    }

    public static void onImmediateBlockBreak(BlockPos pos) {
        if (pos == null) {
            return;
        }

        synchronized (recentClicks) {
            recentClicks.remove(pos);
        }
    }

    public static void onBlockChanged(Minecraft client, BlockPos pos, BlockState newState) {
        if (newState == null || !newState.isAir()) {
            return;
        }
        consumeIfRecent(client, pos, System.currentTimeMillis());
    }

    public static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        synchronized (recentClicks) {
            cleanupExpired(now);
            if (recentClicks.isEmpty() || client == null || client.level == null) {
                return;
            }

            Iterator<Map.Entry<BlockPos, Long>> iterator = recentClicks.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<BlockPos, Long> entry = iterator.next();
                if (client.level.getBlockState(entry.getKey()).isAir()) {
                    iterator.remove();
                    recordConfirmedBreak(entry.getKey());
                }
            }
        }
    }

    public static void reset() {
        synchronized (recentClicks) {
            recentClicks.clear();
        }
    }

    private static void consumeIfRecent(Minecraft client, BlockPos pos, long now) {
        if (client == null || client.level == null || pos == null) {
            return;
        }

        boolean consumed;
        synchronized (recentClicks) {
            cleanupExpired(now);
            consumed = recentClicks.remove(pos) != null;
        }

        if (consumed) {
            recordConfirmedBreak(pos);
        }
    }

    private static void recordConfirmedBreak(BlockPos pos) {
        BpsTracker.onBlockBreak();
        FailsafeManager.onBlockBreak(pos);
    }

    private static void cleanupExpired(long now) {
        Iterator<Map.Entry<BlockPos, Long>> iterator = recentClicks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() > CLICK_EXPIRY_MS) {
                iterator.remove();
            }
        }
    }
}
