package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.notification.NotificationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class DirtFailsafe {
    private static final long TRACKED_BLOCK_TTL_MS = 120_000L;
    private static final long DESTROYED_BLOCK_TTL_MS = 10_000L;
    private static final double TOUCH_DISTANCE = 1.5;

    enum State {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private static final List<TrackedBlock> dirtBlocks = new ArrayList<>();
    private static final List<TrackedBlock> blocksDestroyedByPlayer = new ArrayList<>();
    private static long touchingSinceMs = 0L;
    private static long touchingRandomDelayMs = 0L;
    private static boolean triggered = false;

    private DirtFailsafe() {
    }

    static void reset() {
        synchronized (dirtBlocks) {
            dirtBlocks.clear();
        }
        synchronized (blocksDestroyedByPlayer) {
            blocksDestroyedByPlayer.clear();
        }
        resetTouchWait();
        triggered = false;
    }

    static void onBlockBreak(BlockPos pos) {
        if (pos == null || !isTrackingActive()) {
            return;
        }

        synchronized (blocksDestroyedByPlayer) {
            blocksDestroyedByPlayer.add(new TrackedBlock(pos.immutable(), System.currentTimeMillis()));
            cleanupDestroyedByPlayer();
        }
    }

    static void onBlockChanged(Minecraft client, BlockPos pos, BlockState oldState, BlockState newState) {
        if (client == null || client.level == null || pos == null || oldState == null || newState == null) {
            return;
        }

        if (!AetherConfig.FAILSAFE_DIRT_CHECK.get() || !isTrackingActive()) {
            return;
        }

        if (!isReplaceableFarmState(oldState) || !isSuspiciousPlacedBlock(client, pos, newState)) {
            return;
        }

        synchronized (blocksDestroyedByPlayer) {
            cleanupDestroyedByPlayer();
            if (blocksDestroyedByPlayer.stream().anyMatch(block -> block.pos.equals(pos))) {
                return;
            }
        }

        synchronized (dirtBlocks) {
            cleanupTrackedBlocks(client);
            boolean alreadyTracked = dirtBlocks.stream().anyMatch(block -> block.pos.equals(pos));
            if (!alreadyTracked) {
                dirtBlocks.add(new TrackedBlock(pos.immutable(), System.currentTimeMillis()));
            }
        }
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            reset();
            return;
        }

        cleanupTrackedBlocks(client);

        if (!AetherConfig.FAILSAFE_DIRT_CHECK.get()) {
            resetTouchWait();
            return;
        }

        if (!isTrackingActive()) {
            resetTouchWait();
            return;
        }

        if (!isTouchingDirtBlock(client)) {
            resetTouchWait();
            return;
        }

        if (touchingSinceMs == 0L) {
            touchingSinceMs = System.currentTimeMillis();
            touchingRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
            return;
        }

        long triggerDelayMs = Math.round(AetherConfig.FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS.get() * 1000.0f)
                + touchingRandomDelayMs;
        if (System.currentTimeMillis() - touchingSinceMs < triggerDelayMs) {
            return;
        }

        trigger(client);
    }

    static State getState(Minecraft client) {
        if (triggered) {
            return State.TRIGGERED;
        }
        if (client == null || client.player == null || client.level == null
                || !AetherConfig.FAILSAFE_DIRT_CHECK.get()
                || !isTrackingActive()) {
            return State.IDLE;
        }
        return isTouchingDirtBlock(client) ? State.WAIT : State.IDLE;
    }

    static boolean isTouchingDirtBlock(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return false;
        }

        cleanupTrackedBlocks(client);
        Vec3 eyePos = client.player.getEyePosition(1.0f);
        synchronized (dirtBlocks) {
            for (TrackedBlock block : dirtBlocks) {
                Vec3 center = Vec3.atCenterOf(block.pos);
                if (eyePos.distanceTo(center) <= TOUCH_DISTANCE) {
                    return true;
                }
            }
        }
        return false;
    }

    static int getTrackedBlockCount(Minecraft client) {
        cleanupTrackedBlocks(client);
        synchronized (dirtBlocks) {
            return dirtBlocks.size();
        }
    }

    static long getTriggerRemainingMs() {
        if (touchingSinceMs == 0L) {
            return 0L;
        }
        long elapsedMs = System.currentTimeMillis() - touchingSinceMs;
        long triggerDelayMs = Math.round(AetherConfig.FAILSAFE_DIRT_CHECK_TRIGGER_DELAY_SECONDS.get() * 1000.0f)
                + touchingRandomDelayMs;
        return Math.max(0L, triggerDelayMs - elapsedMs);
    }

    private static boolean isReplaceableFarmState(BlockState state) {
        return state.isAir() || isCrop(state) || isWater(state);
    }

    private static boolean isSuspiciousPlacedBlock(Minecraft client, BlockPos pos, BlockState state) {
        if (state.isAir() || isCrop(state) || isWater(state)) {
            return false;
        }
        if (state.is(Blocks.LADDER) || state.getBlock() instanceof TrapDoorBlock) {
            return false;
        }

        return !state.getCollisionShape(client.level, pos).isEmpty()
                && Block.isShapeFullBlock(state.getCollisionShape(client.level, pos));
    }

    private static boolean isRemovedOrPassable(Minecraft client, BlockPos pos, BlockState state) {
        return state.isAir()
                || isCrop(state)
                || isWater(state)
                || state.getCollisionShape(client.level, pos).isEmpty();
    }

    private static boolean isCrop(BlockState state) {
        Block block = state.getBlock();
        return block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof AttachedStemBlock
                || block instanceof NetherWartBlock
                || block instanceof CocoaBlock;
    }

    private static boolean isWater(BlockState state) {
        return state.getFluidState().is(Fluids.WATER);
    }

    private static void cleanupTrackedBlocks(Minecraft client) {
        if (client == null || client.level == null) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (dirtBlocks) {
            Iterator<TrackedBlock> iterator = dirtBlocks.iterator();
            while (iterator.hasNext()) {
                TrackedBlock block = iterator.next();
                BlockState state = client.level.getBlockState(block.pos);
                if (now - block.createdAtMs > TRACKED_BLOCK_TTL_MS || isRemovedOrPassable(client, block.pos, state)) {
                    iterator.remove();
                }
            }
        }
    }

    private static void cleanupDestroyedByPlayer() {
        long now = System.currentTimeMillis();
        blocksDestroyedByPlayer.removeIf(block -> now - block.createdAtMs > DESTROYED_BLOCK_TTL_MS);
    }

    private static boolean isTrackingActive() {
        if (!MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return false;
        }

        if (!FarmingMacroManager.isActive()) {
            return false;
        }

        var activeMacro = FarmingMacroManager.getActiveMacro();
        return activeMacro != null && activeMacro.isFarmingState();
    }

    private static void resetTouchWait() {
        touchingSinceMs = 0L;
        touchingRandomDelayMs = 0L;
    }

    private static void trigger(Minecraft client) {
        if (triggered) {
            return;
        }

        FailsafeAction action = FailsafeManager.getDirtCheckAction();
        triggered = true;
        NotificationManager.error(
                FailsafeManager.getNotificationTitle(action),
                "A suspicious solid block stayed close to the player.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                FailsafeCustomReplayManager.FailsafeReplayType.DIRT_CHECK,
                "suspicious solid block stayed close to the player.",
                "DirtFailsafe: suspicious solid block stayed close to the player");
        reset();
    }

    private record TrackedBlock(BlockPos pos, long createdAtMs) {
    }
}
