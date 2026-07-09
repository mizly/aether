package dev.aether.modules.misc;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.mixin.AccessorScreen;
import dev.aether.mixin.MixinMinecraft;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.EntityUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AutoCarnivalManager {
    private static final long CLICK_COOLDOWN_MS = 200L;
    private static final long AIM_TIMEOUT_MS = 3000L;
    private static final long DEBUG_THROTTLE_MS = 1000L;
    private static final long SCAN_DEBUG_THROTTLE_MS = 2000L;
    private static final long SHOOTOUT_EXIT_CONFIRM_MS = 1000L;
    private static final long REPLAY_RETRY_COOLDOWN_MS = 2000L;
    private static final long REPLAY_START_GRACE_MS = 6000L;
    private static final long REPLAY_CHAT_CLICK_WINDOW_MS = 15000L;
    private static final long REPLAY_CLICK_DELAY_MS = 1000L;
    private static final long NPC_INTERACTION_TIMEOUT_MS = 10000L;
    private static final double MAX_TARGET_DISTANCE_SQR = 1600.0;
    private static final float AIM_TOLERANCE_DEGREES = 4.0f;
    private static final float NPC_INTERACTION_RANGE = 3.25f;
    private static final float NPC_RETRY_RANGE = 4.25f;
    private static final int MAX_NPC_INTERACTION_RETRIES = 2;
    private static final int MAX_REPLAY_CLICK_ATTEMPTS = 3;
    private static final String CARNIVAL_COWBOY_NAME = "Carnival Cowboy";
    private static final String COWBOY_CONFIRM_TEXT = "Sure thing";
    private static final String COWBOY_OPTION_PROMPT_TEXT = "Select an option:";
    private static final Pattern TOKEN_EARNED_PATTERN = Pattern.compile(
            "You earned ([\\d,]+) Carnival Tokens?!",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TICKET_EXHAUSTED_PATTERN = Pattern.compile(
            ".*Carnival Cowboy.*Sorry pal, but ya need .*Carnival Tickets to play!.*",
            Pattern.CASE_INSENSITIVE);
    private static final List<BlockPos> LAMP_COORDS = List.of(
            new BlockPos(-119, 75, 45),
            new BlockPos(-119, 77, 42),
            new BlockPos(-118, 76, 39),
            new BlockPos(-118, 76, 49),
            new BlockPos(-117, 76, 52),
            new BlockPos(-115, 77, 55),
            new BlockPos(-112, 76, 58),
            new BlockPos(-109, 75, 60),
            new BlockPos(-106, 77, 61),
            new BlockPos(-102, 75, 62),
            new BlockPos(-99, 77, 62),
            new BlockPos(-96, 76, 61));

    private static long lastClickMs;
    private static long aimStartedMs;
    private static long lastDebugMs;
    private static long lastScanDebugMs;
    private static long lastDartSeenMs;
    private static long lastReplayAttemptMs;
    private static long waitingForNextShootoutUntilMs;
    private static Vec3 pendingTarget;
    private static String lastDebugMessage = "";
    private static long sessionTokensEarned;
    private static boolean enabled;
    private static boolean suppressAutoEnterUntilNoDart;
    private static volatile boolean shootoutSeen;
    private static volatile boolean replayTaskQueued;
    private static volatile boolean replayTaskRunning;
    private static volatile boolean replayPromptClicked;
    private static volatile boolean ticketExhausted;
    private static volatile boolean retrySneakLatched;
    private static volatile long replayTaskToken;
    private static volatile long pendingReplayClickEventAt;
    private static volatile long pendingReplayClickReadyAt;
    private static volatile int pendingReplayClickAttempts;
    private static volatile ClickEvent pendingReplayClickEvent;

    private AutoCarnivalManager() {
    }

    public static void syncFromConfig(Minecraft client) {
        enabled = AetherConfig.AUTO_CARNIVAL_SHOOTOUT.get();
        if (!enabled) {
            exitCarnivalState(client, true);
        }
    }

    public static void setEnabled(Minecraft client, boolean enabled) {
        AetherConfig.AUTO_CARNIVAL_SHOOTOUT.set(enabled);
        AetherConfig.save();
        AutoCarnivalManager.enabled = enabled;
        if (!enabled) {
            exitCarnivalState(client, true);
            return;
        }

        resetSession();
    }

    public static void stopForMacro(Minecraft client) {
        if (MacroStateManager.getCurrentState() == MacroState.State.AUTO_CARNIVAL) {
            suppressAutoEnterUntilNoDart = true;
        }
        stopActiveInteraction(client);
        resetSession();
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled) {
            return;
        }

        if (client.player == null || client.level == null) {
            resetSession();
            return;
        }

        MacroState.State currentState = MacroStateManager.getCurrentState();
        if (currentState != MacroState.State.OFF && currentState != MacroState.State.AUTO_CARNIVAL) {
            resetSession();
            return;
        }

        if (client.screen != null) {
            resetAimState();
            return;
        }

        LocalPlayer player = client.player;
        long now = System.currentTimeMillis();
        boolean holdingDart = isHoldingDart(player.getMainHandItem());
        if (!holdingDart) {
            suppressAutoEnterUntilNoDart = false;
        }
        if (currentState == MacroState.State.OFF) {
            if (!holdingDart || suppressAutoEnterUntilNoDart) {
                return;
            }

            MacroStateManager.setCurrentState(MacroState.State.AUTO_CARNIVAL);
            currentState = MacroStateManager.getCurrentState();
            if (currentState != MacroState.State.AUTO_CARNIVAL) {
                return;
            }
        }

        if (holdingDart) {
            shootoutSeen = true;
            replayPromptClicked = false;
            clearPendingReplayPrompt();
            waitingForNextShootoutUntilMs = 0L;
            lastDartSeenMs = now;

            if (pendingTarget != null) {
                continuePendingShot(client, player, now);
                return;
            }

            if (RotationManager.isRotating() || now - lastClickMs < CLICK_COOLDOWN_MS) {
                return;
            }

            List<Vec3> targets = gatherTargets(client, client.level, player);
            if (targets.isEmpty()) {
                return;
            }

            pendingTarget = targets.get(0);
            aimStartedMs = now;
            RotationManager.initiateRotation(client, pendingTarget, 0L);
            return;
        }

        resetAimState();

        if (isReplayConfirmationPending()) {
            tryConsumePendingReplayPrompt(client);
        }

        if (waitingForNextShootoutUntilMs > now) {
            return;
        }

        if (!shootoutSeen || ticketExhausted || replayTaskQueued || replayTaskRunning || isReplayConfirmationPending()) {
            return;
        }

        if (now - lastDartSeenMs < SHOOTOUT_EXIT_CONFIRM_MS || now - lastReplayAttemptMs < REPLAY_RETRY_COOLDOWN_MS) {
            return;
        }

        queueReplayTask(client);
    }

    public static void handleChatMessage(Minecraft client, Component message, String plainText) {
        if (!enabled
                || MacroStateManager.getCurrentState() != MacroState.State.AUTO_CARNIVAL
                || client == null
                || message == null
                || plainText == null) {
            return;
        }

        trackTokenEarned(plainText);

        if (TICKET_EXHAUSTED_PATTERN.matcher(plainText).matches()) {
            ticketExhausted = true;
            shootoutSeen = false;
            replayPromptClicked = false;
            clearPendingReplayPrompt();
            waitingForNextShootoutUntilMs = 0L;
            ClientUtils.sendMessage("\u00A7eAuto Carnival ran out of Carnival Tickets. Stopping replay loop.", true);
            ClientUtils.sendDebugMessage("AutoCarnival: ticket gate detected, replay loop aborted.");
            exitCarnivalState(client, true);
            return;
        }

        if (!shouldHandleReplayPrompt()) {
            return;
        }

        boolean isOptionPrompt = plainText.contains(COWBOY_OPTION_PROMPT_TEXT);
        boolean isDirectConfirmLine = plainText.contains(COWBOY_CONFIRM_TEXT);
        if (!isOptionPrompt && !isDirectConfirmLine) {
            return;
        }

        ClickEvent clickEvent = extractReplayClickEvent(message, isOptionPrompt);
        if (clickEvent == null) {
            ClientUtils.sendDebugMessage("AutoCarnival: confirmation message had no clickable event.");
            return;
        }

        long now = System.currentTimeMillis();
        pendingReplayClickEvent = clickEvent;
        pendingReplayClickEventAt = now;
        pendingReplayClickReadyAt = now + REPLAY_CLICK_DELAY_MS;
        pendingReplayClickAttempts = 0;
        ClientUtils.sendDebugMessage("AutoCarnival: cached Carnival Cowboy prompt click and waiting 1s before retrying.");
    }

    private static void queueReplayTask(Minecraft client) {
        replayTaskQueued = true;
        long token = ++replayTaskToken;
        MacroWorkerThread.getInstance().submit("AutoCarnival-Replay", () -> runReplayTask(client, token));
    }

    private static void runReplayTask(Minecraft client, long token) {
        replayTaskQueued = false;
        replayTaskRunning = true;
        lastReplayAttemptMs = System.currentTimeMillis();
        replayPromptClicked = false;
        if (pendingReplayClickEventAt < lastReplayAttemptMs) {
            clearPendingReplayPrompt();
        }

        try {
            if (!isReplayTaskCurrent(token) || client.player == null || client.level == null) {
                return;
            }

            Entity cowboy = findCowboyEntity(client);
            if (cowboy == null) {
                ClientUtils.sendDebugMessage("AutoCarnival: Carnival Cowboy not found after shootout.");
                return;
            }

            cowboy = prepareCowboyForInteractionWithRetries(client, cowboy, token);
            if (cowboy == null || !isReplayTaskCurrent(token)) {
                ClientUtils.sendDebugMessage("AutoCarnival: failed to prep Carnival Cowboy interaction.");
                return;
            }

            if (!interactUntilReplayQueued(client, cowboy, token, NPC_INTERACTION_TIMEOUT_MS)) {
                ClientUtils.sendDebugMessage("AutoCarnival: no replay confirmation received from Carnival Cowboy.");
            }
        } finally {
            replayTaskRunning = false;
            replayTaskQueued = false;
            retrySneakLatched = false;
        }
    }

    private static boolean interactUntilReplayQueued(Minecraft client, Entity entity, long token, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int prepRetries = 0;

        while (System.currentTimeMillis() < deadline && isReplayTaskCurrent(token)) {
            if (isBackInShootout(client)) {
                onReplayRoundResumed(client);
                return true;
            }

            if (tryConsumePendingReplayPrompt(client)) {
                return true;
            }

            if (hasPendingReplayPrompt()) {
                MacroWorkerThread.sleep(100L);
                continue;
            }

            Entity refreshedEntity = findCowboyEntity(client);
            if (refreshedEntity != null) {
                entity = refreshedEntity;
            }

            float retryRange = getRetryRangeForAttempt(prepRetries);
            if (!isEntityInRetryRange(client, entity, retryRange)
                    || !isLookingAt(client, entity, AetherConfig.VISITOR_FOV_RANGE.get())) {
                if (prepRetries >= MAX_NPC_INTERACTION_RETRIES) {
                    ClientUtils.sendDebugMessage("AutoCarnival: interaction retries exceeded for Carnival Cowboy.");
                    return false;
                }

                prepRetries++;
                ClientUtils.sendDebugMessage("AutoCarnival: re-prepping Carnival Cowboy interaction (" + prepRetries + "/"
                                + MAX_NPC_INTERACTION_RETRIES + ").");
                entity = prepareCowboyForInteraction(client, entity, getRetryRangeForAttempt(prepRetries), token);
                if (entity == null) {
                    continue;
                }
            }

            interactWithEntity(client, entity);
            for (int i = 0; i < 5; i++) {
                MacroWorkerThread.sleep(100L);
                if (isBackInShootout(client)) {
                    onReplayRoundResumed(client);
                    return true;
                }
                if (tryConsumePendingReplayPrompt(client)) {
                    return true;
                }
                if (!isReplayTaskCurrent(token)) {
                    return false;
                }
                if (ticketExhausted) {
                    return true;
                }
            }
        }

        return ticketExhausted || isBackInShootout(client);
    }

    private static Entity prepareCowboyForInteractionWithRetries(Minecraft client, Entity cowboy, long token) {
        Entity preparedCowboy = cowboy;
        retrySneakLatched = false;

        for (int attempt = 0; attempt <= MAX_NPC_INTERACTION_RETRIES && isReplayTaskCurrent(token); attempt++) {
            if (attempt > 0) {
                retrySneakLatched = true;
            }

            float retryRange = getRetryRangeForAttempt(attempt);
            Entity refreshedCowboy = findCowboyEntity(client);
            if (refreshedCowboy != null) {
                preparedCowboy = refreshedCowboy;
            }

            preparedCowboy = prepareCowboyForInteraction(client, preparedCowboy, retryRange, token);
            if (preparedCowboy != null) {
                return preparedCowboy;
            }

            if (attempt < MAX_NPC_INTERACTION_RETRIES) {
                ClientUtils.sendDebugMessage("AutoCarnival: initial prep retry (" + (attempt + 1) + "/"
                                + MAX_NPC_INTERACTION_RETRIES + ") for Carnival Cowboy.");
                MacroWorkerThread.sleep(250L);
            }
        }

        return null;
    }

    private static Entity prepareCowboyForInteraction(
            Minecraft client,
            Entity cowboy,
            float retryRange,
            long token
    ) {
        if (!isReplayTaskCurrent(token) || client.player == null) {
            return null;
        }

        Entity refreshedCowboy = findCowboyEntity(client);
        if (refreshedCowboy != null) {
            cowboy = refreshedCowboy;
        }

        if (!isEntityInRetryRange(client, cowboy, retryRange)) {
            walkToEntity(client, cowboy, retryRange, token);
            if (!isReplayTaskCurrent(token)) {
                return null;
            }

            refreshedCowboy = findCowboyEntity(client);
            if (refreshedCowboy != null) {
                cowboy = refreshedCowboy;
            }
        }

        if (!isEntityInInteractionRange(client, cowboy)) {
            return null;
        }

        faceEntityForInteraction(client, cowboy);
        return cowboy;
    }

    private static void walkToEntity(Minecraft client, Entity entity, float retryRange, long token) {
        if (client.player == null) {
            return;
        }

        BlockPos target = findBestWalkingTarget(client, entity, retryRange);
        if (target == null) {
            target = entity.blockPosition();
        }

        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();

        ClientUtils.sendDebugMessage("AutoCarnival: walking to Carnival Cowboy at " + x + ", " + y + ", " + z);

        try {
            final boolean latchSneak = retrySneakLatched;
            client.execute(() -> {
                PathfindingManager.startPathfind(client, x, y, z, false, entity);
                PathfindingManager.setWalkSneakLatched(latchSneak);
            });
        } catch (Exception ignored) {
        }

        MacroWorkerThread.sleep(500L);

        long deadline = System.currentTimeMillis() + 15000L;
        while (PathfindingManager.isNavigating()
                && System.currentTimeMillis() < deadline
                && isReplayTaskCurrent(token)) {
            MacroWorkerThread.sleep(200L);
        }

        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop(false);
        }

        try {
            client.execute(() -> PathfindingManager.setWalkSneakLatched(false));
        } catch (Exception ignored) {
        }

        MacroWorkerThread.sleep(200L);
    }

    private static BlockPos findBestWalkingTarget(Minecraft client, Entity entity, float retryRange) {
        if (client.level == null || client.player == null) {
            return null;
        }

        BlockPos base = entity.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        double retryRangeSqr = retryRange * retryRange;

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos pos = base.offset(x, y, z);
                    double entityDistance = pos.distToCenterSqr(entity.position());
                    if (entityDistance > retryRangeSqr) {
                        continue;
                    }

                    if (isWalkable(client, pos)) {
                        double playerDistance = pos.distToCenterSqr(client.player.position());
                        if (playerDistance < bestDistSq) {
                            bestDistSq = playerDistance;
                            best = pos;
                        }
                    }
                }
            }
        }

        return best;
    }

    private static boolean isWalkable(Minecraft client, BlockPos pos) {
        if (client.level == null) {
            return false;
        }

        return !client.level.getBlockState(pos.below()).getCollisionShape(client.level, pos.below()).isEmpty()
                && client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty()
                && client.level.getBlockState(pos.above()).getCollisionShape(client.level, pos.above()).isEmpty();
    }

    private static boolean isLookingAt(Minecraft client, Entity entity, float tolerance) {
        if (client.player == null) {
            return false;
        }

        return RotationUtils.isLookingAt(client.player.getYRot(), client.player.getXRot(),
                client.player.getEyePosition(), entity.getEyePosition(), tolerance);
    }

    private static void faceEntityForInteraction(Minecraft client, Entity entity) {
        rotateToEntity(client, entity);
        waitForRotationToFinish();
        if (!isLookingAt(client, entity, AetherConfig.VISITOR_FOV_RANGE.get())) {
            rotateToEntity(client, entity);
            waitForRotationToFinish();
        }
    }

    private static void rotateToEntity(Minecraft client, Entity entity) {
        client.execute(() -> RotationManager.initiateRotation(client,
                new Vec3(entity.getX(), entity.getEyeY(), entity.getZ()),
                AetherConfig.ROTATION_TIME.get(),
                AetherConfig.VISITOR_FOV_RANGE.get()));
        MacroWorkerThread.sleep(AetherConfig.ROTATION_TIME.get() + 50L);
    }

    private static void waitForRotationToFinish() {
        long deadline = System.currentTimeMillis() + 1500L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < deadline) {
            MacroWorkerThread.sleep(25L);
        }
    }

    private static boolean interactWithEntity(Minecraft client, Entity entity) {
        if (client.player == null || client.options == null || entity == null) {
            return false;
        }

        client.execute(() -> {
            if (client.player == null) {
                return;
            }
            client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            ((MixinMinecraft) client).aether$startAttack();
        });
        MacroWorkerThread.sleep(100L);
        return true;
    }

    private static Entity findCowboyEntity(Minecraft client) {
        return EntityUtils.findEntity(client, CARNIVAL_COWBOY_NAME);
    }

    private static boolean isEntityInInteractionRange(Minecraft client, Entity entity) {
        return client.player != null && entity != null && client.player.distanceTo(entity) <= NPC_INTERACTION_RANGE;
    }

    private static boolean isEntityInRetryRange(Minecraft client, Entity entity, float retryRange) {
        return client.player != null && entity != null && client.player.distanceTo(entity) <= retryRange;
    }

    private static float getRetryRangeForAttempt(int attempt) {
        return Math.max(0.0f, NPC_RETRY_RANGE - attempt);
    }

    private static boolean isReplayTaskCurrent(long token) {
        return enabled
                && MacroStateManager.getCurrentState() == MacroState.State.AUTO_CARNIVAL
                && !ticketExhausted
                && replayTaskToken == token;
    }

    private static boolean shouldHandleReplayPrompt() {
        if (ticketExhausted) {
            return false;
        }

        long now = System.currentTimeMillis();
        return replayTaskQueued
                || replayTaskRunning
                || (shootoutSeen && now - lastReplayAttemptMs <= REPLAY_CHAT_CLICK_WINDOW_MS);
    }

    private static boolean tryConsumePendingReplayPrompt(Minecraft client) {
        ClickEvent clickEvent = pendingReplayClickEvent;
        if (clickEvent == null || !shouldHandleReplayPrompt()) {
            return false;
        }

        if (isBackInShootout(client)) {
            onReplayRoundResumed(client);
            return true;
        }

        long ageMs = System.currentTimeMillis() - pendingReplayClickEventAt;
        if (ageMs > REPLAY_CHAT_CLICK_WINDOW_MS) {
            clearPendingReplayPrompt();
            return false;
        }

        long now = System.currentTimeMillis();
        if (now < pendingReplayClickReadyAt) {
            return false;
        }

        if (pendingReplayClickAttempts >= MAX_REPLAY_CLICK_ATTEMPTS) {
            ClientUtils.sendDebugMessage("AutoCarnival: replay prompt click retries exhausted.");
            clearPendingReplayPrompt();
            return false;
        }

        if (client.player == null || client.getConnection() == null) {
            return false;
        }

        int attempt = pendingReplayClickAttempts + 1;
        pendingReplayClickAttempts = attempt;
        pendingReplayClickReadyAt = now + REPLAY_CLICK_DELAY_MS;
        client.execute(() -> {
            if (client.player == null || client.getConnection() == null) {
                return;
            }
            AccessorScreen.aether$defaultHandleGameClickEvent(clickEvent, client, client.screen);
        });

        ClientUtils.sendDebugMessage("AutoCarnival: clicked cached Carnival Cowboy confirmation ("
                        + attempt + "/" + MAX_REPLAY_CLICK_ATTEMPTS + ").");
        return false;
    }

    private static void continuePendingShot(Minecraft client, LocalPlayer player, long now) {
        if (pendingTarget == null) {
            return;
        }

        if (now - aimStartedMs > AIM_TIMEOUT_MS) {
            resetAimState();
            return;
        }

        if (RotationUtils.isLookingAt(
                player.getYRot(),
                player.getXRot(),
                player.getEyePosition(),
                pendingTarget,
                AIM_TOLERANCE_DEGREES)) {
            ClientUtils.performUseClick(client);
            lastClickMs = now;
            resetAimState();
            return;
        }

        aimStartedMs = now;
        RotationManager.initiateRotation(client, pendingTarget, 0L);
    }

    private static List<Vec3> gatherTargets(Minecraft client, ClientLevel world, LocalPlayer player) {
        List<Vec3> diamondTargets = new ArrayList<>();
        List<Vec3> goldTargets = new ArrayList<>();
        List<Vec3> ironTargets = new ArrayList<>();
        List<Vec3> leatherTargets = new ArrayList<>();
        List<String> sampledNames = new ArrayList<>();
        int zombieCount = 0;
        int nearbyZombieCount = 0;
        int namedChestCount = 0;

        for (var entity : world.entitiesForRendering()) {
            if (!(entity instanceof Zombie zombie)) {
                continue;
            }

            zombieCount++;
            if (zombie.distanceToSqr(player) > MAX_TARGET_DISTANCE_SQR) {
                continue;
            }

            nearbyZombieCount++;
            ItemStack chestItem = zombie.getItemBySlot(EquipmentSlot.CHEST);
            if (chestItem.isEmpty()) {
                continue;
            }

            namedChestCount++;
            Vec3 aimPoint = projectAhead(zombie, player);
            String name = chestItem.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (sampledNames.size() < 6) {
                sampledNames.add(name);
            }

            if (name.contains("diamond")) {
                diamondTargets.add(aimPoint);
            } else if (name.contains("gold")) {
                goldTargets.add(aimPoint);
            } else if (name.contains("iron")) {
                ironTargets.add(aimPoint);
            } else if (name.contains("leather")) {
                leatherTargets.add(aimPoint);
            }
        }

        List<Vec3> lampTargets = new ArrayList<>();
        int litLampCount = 0;
        for (BlockPos pos : LAMP_COORDS) {
            BlockState state = world.getBlockState(pos);
            if (isActiveLamp(world, pos, state)) {
                litLampCount++;
                lampTargets.add(new Vec3(pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5));
            }
        }

        debugScan(client, zombieCount, nearbyZombieCount, namedChestCount,
                diamondTargets.size(), goldTargets.size(), ironTargets.size(), leatherTargets.size(),
                litLampCount, sampledNames);

        List<Vec3> targets = new ArrayList<>(
                diamondTargets.size() + lampTargets.size() + goldTargets.size() + ironTargets.size() + leatherTargets.size());
        targets.addAll(diamondTargets);
        targets.addAll(lampTargets);
        targets.addAll(goldTargets);
        targets.addAll(ironTargets);
        targets.addAll(leatherTargets);
        return targets;
    }

    private static boolean isActiveLamp(ClientLevel world, BlockPos pos, BlockState state) {
        if (!state.is(Blocks.REDSTONE_LAMP)) {
            return false;
        }

        if (state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT)) {
            return true;
        }

        return state.getLightEmission() > 0;
    }

    private static Vec3 projectAhead(Zombie target, LocalPlayer player) {
        double distance = Math.sqrt(target.distanceToSqr(player));
        double baseLeadTicks = Mth.clamp(distance / 6.0, 1.0, 12.0);
        double pingLeadTicks = Math.max(AetherConfig.AUTO_CARNIVAL_PING.get(), 0) / 50.0;
        double leadTicks = baseLeadTicks + pingLeadTicks;
        Vec3 velocity = target.getDeltaMovement();

        return new Vec3(
                target.getX() + velocity.x * leadTicks,
                target.getEyeY(),
                target.getZ() + velocity.z * leadTicks);
    }

    private static boolean isHoldingDart(ItemStack heldItem) {
        return heldItem != null
                && !heldItem.isEmpty()
                && heldItem.getHoverName().getString().toLowerCase(Locale.ROOT).contains("dart");
    }

    private static void resetAimState() {
        aimStartedMs = 0L;
        pendingTarget = null;
    }

    private static void resetSession() {
        lastClickMs = 0L;
        lastDartSeenMs = 0L;
        lastReplayAttemptMs = 0L;
        resetAimState();
        shootoutSeen = false;
        replayTaskQueued = false;
        replayTaskRunning = false;
        replayPromptClicked = false;
        ticketExhausted = false;
        retrySneakLatched = false;
        waitingForNextShootoutUntilMs = 0L;
        clearPendingReplayPrompt();
        replayTaskToken++;
    }

    private static boolean isReplayConfirmationPending() {
        return pendingReplayClickEvent != null || pendingReplayClickAttempts > 0;
    }

    private static boolean hasPendingReplayPrompt() {
        return pendingReplayClickEvent != null;
    }

    private static boolean isBackInShootout(Minecraft client) {
        return client != null && client.player != null && isHoldingDart(client.player.getMainHandItem());
    }

    private static void onReplayRoundResumed(Minecraft client) {
        replayPromptClicked = true;
        clearPendingReplayPrompt();
        waitingForNextShootoutUntilMs = System.currentTimeMillis() + REPLAY_START_GRACE_MS;
        ClientUtils.sendDebugMessage("AutoCarnival: replay round resumed.");
    }

    private static void clearPendingReplayPrompt() {
        pendingReplayClickEvent = null;
        pendingReplayClickEventAt = 0L;
        pendingReplayClickReadyAt = 0L;
        pendingReplayClickAttempts = 0;
    }

    public static void resetTokenSession() {
        sessionTokensEarned = 0L;
    }

    public static long getSessionTokensEarned() {
        return sessionTokensEarned;
    }

    public static long getSessionTokensPerHour() {
        long sessionMs = MacroStateManager.getSessionRunningTime();
        if (sessionMs <= 0L) {
            return 0L;
        }
        return Math.round(sessionTokensEarned * (3_600_000.0 / sessionMs));
    }

    private static void exitCarnivalState(Minecraft client, boolean updateMacroState) {
        suppressAutoEnterUntilNoDart = true;
        stopActiveInteraction(client);
        resetSession();
        if (updateMacroState && MacroStateManager.getCurrentState() == MacroState.State.AUTO_CARNIVAL) {
            MacroStateManager.setCurrentState(MacroState.State.OFF);
        }
    }

    private static void stopActiveInteraction(Minecraft client) {
        RotationManager.cancelRotation();
        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop(false);
        }

        Minecraft activeClient = client != null ? client : Minecraft.getInstance();
        if (activeClient == null) {
            return;
        }

        try {
            activeClient.execute(() -> PathfindingManager.setWalkSneakLatched(false));
        } catch (Exception ignored) {
        }
    }

    private static void debug(Minecraft client, String message) {
        if (client == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (message.equals(lastDebugMessage) && now - lastDebugMs < DEBUG_THROTTLE_MS) {
            return;
        }

        lastDebugMessage = message;
        lastDebugMs = now;
        ClientUtils.sendDebugMessage(message);
    }

    private static void trackTokenEarned(String plainText) {
        var matcher = TOKEN_EARNED_PATTERN.matcher(plainText);
        if (!matcher.find()) {
            return;
        }

        try {
            sessionTokensEarned += Long.parseLong(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException ignored) {
        }
    }

    private static void debugScan(
            Minecraft client,
            int armorStandCount,
            int nearbyArmorStandCount,
            int namedChestCount,
            int diamondCount,
            int goldCount,
            int ironCount,
            int leatherCount,
            int litLampCount,
            List<String> sampledNames) {
        if (client == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScanDebugMs < SCAN_DEBUG_THROTTLE_MS) {
            return;
        }
        lastScanDebugMs = now;

        StringBuilder message = new StringBuilder("Auto Carnival scan: zombies=")
                .append(armorStandCount)
                .append(", nearby=").append(nearbyArmorStandCount)
                .append(", chest=").append(namedChestCount)
                .append(", d=").append(diamondCount)
                .append(", g=").append(goldCount)
                .append(", i=").append(ironCount)
                .append(", l=").append(leatherCount)
                .append(", lamps=").append(litLampCount);
        if (!sampledNames.isEmpty()) {
            message.append(", names=").append(sampledNames);
        }

        debug(client, message.toString());
    }

    private static ClickEvent extractReplayClickEvent(Component component, boolean preferFirstAllowedEvent) {
        if (preferFirstAllowedEvent) {
            ClickEvent firstAllowedEvent = findFirstAllowedClickEvent(component);
            if (isAllowedServerClickEvent(firstAllowedEvent)) {
                return firstAllowedEvent;
            }
        }

        ClickEvent targetedEvent = findClickEvent(component, COWBOY_CONFIRM_TEXT);
        if (isAllowedServerClickEvent(targetedEvent)) {
            return targetedEvent;
        }

        ClickEvent firstAllowedEvent = findFirstAllowedClickEvent(component);
        if (isAllowedServerClickEvent(firstAllowedEvent)) {
            return firstAllowedEvent;
        }

        return null;
    }

    private static ClickEvent findClickEvent(Component component, String targetText) {
        if (component == null) {
            return null;
        }

        for (Component flatComponent : component.toFlatList()) {
            ClickEvent clickEvent = flatComponent.getStyle().getClickEvent();
            if (clickEvent != null && flatComponent.getString().contains(targetText)) {
                return clickEvent;
            }
        }

        if (component.getString().contains(targetText)) {
            ClickEvent clickEvent = findAnyClickEvent(component);
            if (clickEvent != null) {
                return clickEvent;
            }
        }

        return null;
    }

    private static ClickEvent findFirstAllowedClickEvent(Component component) {
        if (component == null) {
            return null;
        }

        for (Component flatComponent : component.toFlatList()) {
            ClickEvent clickEvent = flatComponent.getStyle().getClickEvent();
            if (isAllowedServerClickEvent(clickEvent)) {
                return clickEvent;
            }
        }

        return findAnyClickEvent(component);
    }

    private static ClickEvent findAnyClickEvent(Component component) {
        if (component == null) {
            return null;
        }

        ClickEvent clickEvent = component.getStyle().getClickEvent();
        if (clickEvent != null) {
            return clickEvent;
        }

        for (Component sibling : component.getSiblings()) {
            ClickEvent nested = findAnyClickEvent(sibling);
            if (nested != null) {
                return nested;
            }
        }

        return null;
    }

    private static boolean isAllowedServerClickEvent(ClickEvent clickEvent) {
        return clickEvent != null && clickEvent.action().isAllowedFromServer();
    }
}
