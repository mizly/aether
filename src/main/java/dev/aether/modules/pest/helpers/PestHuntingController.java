package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.ProgrammaticAttackTracker;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Stuns, lassos and reels in a pest for its shard, in place of vacuuming it. */
final class PestHuntingController {
    private static final String REEL_PROMPT = "REEL";
    private static final double MARKER_SEARCH_SIZE = 6.0;
    private static final double MARKER_SEARCH_HEIGHT_OFFSET = 1.5;
    private static final double MARKER_MAX_HORIZONTAL = 2.0;
    // Hopping pests can separate from their server-driven marker by more than
    // one block. The exact REEL name remains authoritative, so a wider
    // association radius is safe and prevents waiting until the hunt timeout.
    private static final double REEL_MARKER_MAX_HORIZONTAL = 3.0;
    private static final int REEL_PROMPT_CONFIRM_TICKS = 2;
    private static final double MARKER_MAX_HEIGHT_ABOVE = 3.5;
    private static final double MARKER_MAX_HEIGHT_BELOW = 1.0;
    private static final int VACUUM_SETTLE_TICKS = 1;
    // A single tap is consumed before the suction registers, so the pest is
    // routinely unstunned by the time the lasso goes out. Hold the vacuum.
    private static final long STUN_VACUUM_HOLD_MS = 500L;
    // A stun that will not land must not hold the catch hostage: the lasso works
    // without it, and standing aimed for the whole catch timeout reads as a macro.
    private static final long STUN_STAGE_TIMEOUT_MS = 3_000L;
    // Chasing a pest that never lets us inside stun range still has to end in a
    // throw, and a throw we can never close on has to end in giving the pest up.
    private static final long STUN_APPROACH_TIMEOUT_MS = 6_000L;
    private static final long THROW_APPROACH_TIMEOUT_MS = 6_000L;
    private static final int MIN_VACUUM_SWAP_TICKS = 1;
    private static final int MAX_VACUUM_SWAP_TICKS = 5;
    // The re-stun no longer races the pest from where it stood: it has to be
    // chased down again first, so take a visible beat before swapping back.
    private static final int MIN_RETRY_SWAP_TICKS = 4;
    private static final int MAX_RETRY_SWAP_TICKS = 8;
    private static final int LASSO_SETTLE_TICKS = 0;
    // Has to cover the web's flight plus the server's attach round trip. Too
    // short and a landed lasso is retried, which knocks the pest back off.
    private static final long THROW_CONFIRM_MS = 1_200L;
    private static final long REEL_CLICK_COOLDOWN_MS = 250L;
    private static final long DETACH_CONFIRM_MS = 2_500L;
    private static final float THROW_AIM_TOLERANCE_DEGREES = 10.0f;
    private static final float REEL_AIM_TOLERANCE_DEGREES = 25.0f;
    // Stays under REEL_AIM_TOLERANCE_DEGREES so the crosshair is corrected before
    // the reel click gate starts refusing, but not so tight that a landed lasso
    // buys a visible correction the reel never needed.
    private static final float ATTACHED_AIM_TOLERANCE_DEGREES = 18.0f;
    // The focus flips between a pest and its marker stand, which sit blocks
    // apart; decay that step instead of handing the tracker an instant error.
    private static final long AIM_BLEND_MS = 420L;
    private static final long FOCUS_SWITCH_DEBOUNCE_MS = 200L;
    // The stun and throw windows are a tick or two wide, so the hunt tracks with
    // a shorter time constant and a higher ceiling than the cleaner's aim: enough
    // smoothing to read as a hand, not so much that the pest walks out of it.
    private static final float HUNT_AIM_SMOOTHING_MS = 90.0f;
    private static final float HUNT_AIM_MAX_TURN_SPEED = 700.0f;
    private static final long REEL_RESPONSE_WAIT_MS = 500L;
    private static final long REEL_OVERLAY_SIGNAL_GRACE_MS = 300L;
    private static final long LANDING_WAIT_TIMEOUT_MS = 250L;
    private static final double LANDING_Y_EPSILON = 0.05;
    // Doubles as the closest the follow band will sit (see followDirection), so
    // it keeps the ride wide enough to absorb an overshoot without braking.
    private static final double MIN_AIM_DISTANCE = 1.0;
    // Right-clicking the pest itself is an entity interaction and the server
    // spends the click on that instead of the lasso, so clear the top of its
    // hitbox. Measured off the hitbox rather than the ground: a flat offset that
    // clears a bat has the crosshair way above a silverfish, which is what had
    // the hunter craning upwards over the pest it was reeling in.
    // Deterministic per pest: the steer and the click gate have to agree tick to
    // tick, and one fixed clearance for every pest would be its own tell.
    private static final double MIN_AIM_CLEARANCE = 0.15;
    private static final double MAX_AIM_CLEARANCE = 0.35;
    private static final int AIM_CLEARANCE_BUCKETS = 5;
    // Keep the approach inside the server's dependable lasso hit range, but do
    // not gate the stun/throw on reaching it: a moving pest can escape during
    // that wait. The hunter closes distance while the immediate sequence runs.
    private static final double LASSO_INTERACTION_RANGE = 4.75;
    // The vacuum has to be delivered from properly close. Stunning from the edge
    // of the leash range gave the pest the whole 500ms hold to walk out of it,
    // and the lasso then went out at an unstunned pest.
    private static final double STUN_RANGE = 3.5;
    private static final double STUN_FOLLOW_DISTANCE = 2.5;
    // A leashed pest keeps walking and the line snaps within a few blocks, so
    // ride along instead of holding position. Riding closer than this had the
    // hunter pumping forward and back: flight momentum overshoots any band
    // narrower than its stopping distance.
    private static final double LEASHED_FOLLOW_DISTANCE = 2.0;
    // Ordinary flight tops out under a fleeing pest. Sprint only once the gap is
    // wide enough that the catch-up cannot turn into overshoot and orbiting.
    private static final double SPRINT_GAP = 2.0;
    private static final double FOLLOW_BAND = 0.75;
    // Flight goes where the camera points, so translating at an angle to a moving
    // pest traces a curve around it. Turn first and fly straight instead: yaw
    // only, since altitude is the jump/shift keys' job and the aim sits above the
    // pest, which would eat most of a 3D cone at this range.
    private static final double FOLLOW_YAW_CONE_DEGREES = 25.0;
    // The follow band stops closing at LASSO_INTERACTION_RANGE horizontally, so
    // the throw gate carries the height slack that band still leaves.
    private static final double THROW_RANGE = LASSO_INTERACTION_RANGE + 0.25;
    private static final double HANDOFF_SLACK = 2.0;
    private static final double REPATH_DISTANCE = 16.0;
    private static final double VERTICAL_ALIGN_TOLERANCE = 1.0;
    private static final int MAX_STAGES_PER_TICK = 3;
    private static final long CATCH_PROBE_WINDOW_MS = 2_500L;
    private static final int CATCH_PROBE_INTERVAL_TICKS = 10;
    private static final double CATCH_PROBE_SIZE = 5.0;

    enum Stage {
        STUN,
        SWAP_TO_LASSO,
        THROW,
        REEL
    }

    interface Context {
        PestDestroyerRuntime runtime();

        void setState(PestDestroyer.State state);

        void markKilled(Entity entity);

        void deferTarget(Entity entity);

        boolean recordTrackedPestKill(Minecraft client, Entity entity);

        boolean switchToNextQueuedTarget(Minecraft client);
    }

    private PestHuntingController() {
    }

    static boolean isEnabled() {
        return AetherConfig.PEST_HUNTING.get();
    }

    static boolean shouldLassoTarget(Minecraft client, Entity target) {
        return PestHuntingPolicy.shouldLasso(client, target);
    }

    static double handoffRange(Minecraft client, Entity target, double vacuumRange) {
        return shouldLassoTarget(client, target)
                ? AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE.get() + HANDOFF_SLACK
                : vacuumRange;
    }

    static void beginHunt(PestDestroyerRuntime runtime, Minecraft client) {
        runtime.huntStage = Stage.STUN;
        runtime.huntStageEnteredAt = System.currentTimeMillis();
        runtime.huntStartedAt = System.currentTimeMillis();
        runtime.huntThrowCount = 0;
        runtime.huntReelCount = 0;
        runtime.huntThrownAt = 0L;
        runtime.huntLastReelClickAt = 0L;
        runtime.huntLastAttachedAt = 0L;
        runtime.huntAttachedSince = 0L;
        runtime.huntReelSignalAt = 0L;
        runtime.huntEverAttached = false;
        runtime.huntReelPromptLatched = false;
        runtime.huntReelPromptTicks = 0;
        runtime.huntReelPromptClearTicks = 0;
        runtime.huntReelPromptArmed = true;
        runtime.huntReelAwaitingResponseUntil = 0L;
        runtime.huntSwapReadyTick = 0;
        runtime.huntDelayBeforeStunSwap = false;
        runtime.huntStunDelivered = false;
        runtime.huntStunHoldStartedAt = 0L;
        runtime.huntStunRangeSince = 0L;
        runtime.huntStageEnteredTick = 0;
        runtime.huntLandingWaitStartedAt = 0L;
        runtime.huntTargetY = Double.NaN;
        runtime.huntCaughtSignal = false;
        runtime.resetHuntAimState();
        runtime.lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(client);
    }

    static void clearHunt(Minecraft client, PestDestroyerRuntime runtime) {
        // A moving-target rotation must not survive the catch and own the camera afterwards.
        RotationManager.cancelRotation();
        releaseStunVacuum(runtime);
        runtime.huntStage = Stage.STUN;
        runtime.huntStartedAt = 0L;
        runtime.huntStageEnteredAt = 0L;
        runtime.huntThrowCount = 0;
        runtime.huntReelCount = 0;
        runtime.huntThrownAt = 0L;
        runtime.huntLastReelClickAt = 0L;
        runtime.huntLastAttachedAt = 0L;
        runtime.huntAttachedSince = 0L;
        runtime.huntReelSignalAt = 0L;
        runtime.huntEverAttached = false;
        runtime.huntReelPromptLatched = false;
        runtime.huntReelPromptTicks = 0;
        runtime.huntReelPromptClearTicks = 0;
        runtime.huntReelPromptArmed = true;
        runtime.huntReelAwaitingResponseUntil = 0L;
        runtime.huntSwapReadyTick = 0;
        runtime.huntDelayBeforeStunSwap = false;
        runtime.huntStunDelivered = false;
        runtime.huntStunHoldStartedAt = 0L;
        runtime.huntStunRangeSince = 0L;
        runtime.huntStageEnteredTick = 0;
        runtime.huntLandingWaitStartedAt = 0L;
        runtime.huntTargetY = Double.NaN;
        runtime.huntCaughtSignal = false;
        runtime.resetHuntAimState();
        runtime.lassoSlot = -1;
        if (client != null && client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            ClientUtils.setKeyMappingState(client.options.keyAttack, false);
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            ClientUtils.setKeyMappingState(client.options.keySprint, false);
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
        }
    }

    static void onPestCaught(PestDestroyerRuntime runtime) {
        runtime.huntCaughtSignal = true;
    }

    static void onOverlayMessage(PestDestroyerRuntime runtime, String message) {
        if (message != null && isReelPrompt(stripFormatting(message))) {
            runtime.huntReelSignalAt = System.currentTimeMillis();
        }
    }

    static void handleHuntPest(Minecraft client, Context context) {
        PestDestroyerRuntime runtime = context.runtime();
        Entity target = runtime.currentTarget;

        if (runtime.huntCaughtSignal) {
            finishCatch(client, context, runtime, target, true, "caught");
            return;
        }
        // The destroyer's target is often the pest's marker armor stand, which
        // cannot hold a leash; re-throwing onto a live lasso reels it in early.
        Entity lassoed = findLassoedPest(client, target);
        if (isGone(target)) {
            // Hypixel cycles the marker stands, and losing one is not losing the
            // pest: as long as it is still on our lasso, stay on it.
            if (lassoed == null) {
                finishCatch(
                        client, context, runtime, target, runtime.huntEverAttached, "target gone");
                return;
            }
            runtime.currentTarget = lassoed;
            target = lassoed;
        }

        if (runtime.lassoSlot == -1) {
            runtime.lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(client);
            if (runtime.lassoSlot == -1) {
                ClientUtils.sendMessage(
                        "§cNo lasso found for this lasso-routed pest. Leaving it untouched and stopping the hunt.", false);
                clearHunt(client, runtime);
                // Never fall through to KILL_PEST here. The route was selected
                // as LASSO before entering this state; vacuuming now would kill
                // exactly the pests the user asked us to preserve/catch.
                context.setState(PestDestroyer.State.FINISH);
                return;
            }
        }

        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop();
        }

        // Descending to the pest's level can put us on the ground and drop
        // flight, at which point holding jump to climb is just bunny hopping.
        if (!client.player.getAbilities().flying && client.player.getAbilities().mayfly) {
            clearHunt(client, runtime);
            context.setState(PestDestroyer.State.FLY_UP);
            return;
        }

        long now = System.currentTimeMillis();
        // Being attached is not a completion signal. Measure the configured
        // timeout from the start so a stuck leash cannot keep this state alive
        // forever merely by remaining attached.
        if (now - runtime.huntStartedAt > AetherConfig.PEST_HUNTING_TIMEOUT_MS.get()) {
            ClientUtils.sendDebugMessage("[PestHunting] Catch timed out. Moving on.");
            abandonCatch(client, context, runtime, target);
            return;
        }

        // The thrown lasso flies on our leash too, so it counts as attached
        // while it is still in the air. That is fine for the stage machine, but
        // aiming at it made the camera follow the web out of the player's hand.
        Entity focus = resolveFocus(
                runtime, isPestMob(target) ? target : lassoed != null ? lassoed : target, now);

        // An overlay received before the previous click belongs to the previous
        // reel stage. Letting its grace window spill into the next stage can
        // manufacture a prompt that is no longer on screen.
        boolean overlayReelSignal = runtime.huntReelSignalAt > runtime.huntLastReelClickAt
                && now - runtime.huntReelSignalAt <= REEL_OVERLAY_SIGNAL_GRACE_MS;
        boolean reelPromptUp = hasReelPrompt(client, focus) || overlayReelSignal;
        // The prompt is only up while the lasso is on the pest, so it proves the
        // catch by itself. Requiring the leash instead had a landed lasso read as
        // a miss, and the retry stun then knocked the pest straight back off.
        boolean attached = lassoed != null
                || (reelPromptUp && runtime.huntThrownAt != 0L);

        if (attached) {
            if (!runtime.huntEverAttached) {
                ClientUtils.sendDebugMessage("[PestHunting] Lasso on ("
                        + (lassoed != null ? "leash" : "reel prompt") + ").");
            }
            runtime.huntEverAttached = true;
            runtime.huntLastAttachedAt = now;
            if (runtime.huntAttachedSince == 0L) {
                runtime.huntAttachedSince = now;
            }
        } else {
            runtime.huntAttachedSince = 0L;
        }

        if (reelPromptUp) {
            runtime.huntReelPromptTicks++;
            runtime.huntReelPromptClearTicks = 0;
        } else {
            runtime.huntReelPromptTicks = 0;
            runtime.huntReelPromptClearTicks++;
            if (now >= runtime.huntReelAwaitingResponseUntil
                    && runtime.huntReelPromptClearTicks >= 3) {
                runtime.huntReelPromptArmed = true;
                runtime.huntReelPromptLatched = false;
            }
        }
        boolean clickPending = attached
                && runtime.huntReelPromptTicks >= REEL_PROMPT_CONFIRM_TICKS
                && runtime.huntReelPromptArmed
                && !runtime.huntReelPromptLatched;
        // Only smoothed after the throw: before it, the stun and throw gates want
        // the crosshair on the real point now, and a blend there costs the stun.
        updateAimBlend(client, runtime, focus, attached || runtime.huntStage == Stage.REEL, now);
        probeCatchState(client, runtime, focus, lassoed, now);
        // The reel prompt lasts only a moment, so stay aimed for the whole leash
        // instead of starting to turn once it is already up.
        maintainAim(client, runtime, focus, huntAimTolerance(attached, runtime.huntStage), now);
        // Attack is never ours here, and a swing knocks the pest off the lasso.
        // The farming latch has to go first: MixinMinecraftAttackInput reports the
        // key as held while it is set, so releasing the key alone does nothing.
        ProgrammaticAttackTracker.setHeld(client.options.keyAttack, false);
        ClientUtils.setKeyMappingState(client.options.keyAttack, false);
        ClientUtils.discardQueuedClicks(client.options.keyAttack);
        // Stop translating once the crosshair is ready AND the pest is inside the
        // range this stage acts from. Holding still merely because the aim landed
        // parked the hunter out at the leash range, where the stun cannot reach.
        double horizontal = horizontalDistanceTo(client, focus);
        // Outside this the stage machine is skipped below, so holding still on
        // aim leaves the hunter frozen and staring until the catch times out.
        boolean withinLeashRange = horizontal <= AetherConfig.PEST_HUNTING_MAX_DISTANCE.get();
        boolean stunPhase = !attached && usesVacuumAim(runtime);
        boolean clickWindow = clickPending
                || (client.player.distanceTo(focus) <= (stunPhase ? STUN_RANGE : THROW_RANGE)
                        && !attached
                        && runtime.huntStage != Stage.REEL
                        && isAimedAtTarget(
                                client,
                                runtime,
                                focus,
                                THROW_AIM_TOLERANCE_DEGREES));
        if (attached) {
            // Only the reel click itself needs a still camera. Holding position
            // for the whole leash let the pest walk to the end of the line and
            // break it, which put the hunt back to chasing an unstunned pest.
            if (clickPending) {
                holdLassoPosition(client);
                runtime.huntFollowMove = 0;
            } else {
                maintainFollowDistance(
                        client, runtime, focus, LEASHED_FOLLOW_DISTANCE, true, true);
            }
        } else {
            maintainFollowDistance(
                    client,
                    runtime,
                    focus,
                    stunPhase ? STUN_FOLLOW_DISTANCE : AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE.get(),
                    !clickWindow,
                    !clickWindow);
        }

        if (runtime.huntStage != Stage.REEL && !attached) {
            if (horizontal > REPATH_DISTANCE) {
                ClientUtils.sendDebugMessage(
                        "[PestHunting] Target drifted out of reach. Re-approaching.");
                clearHunt(client, runtime);
                PestTargetController.startPathToPest(client, focus);
                context.setState(PestDestroyer.State.FLY_TO_PEST);
                return;
            }
            if (!withinLeashRange) {
                // No stage runs this tick, so a hold started in range would stay
                // pressed for the whole approach back.
                releaseStunVacuum(runtime);
                return;
            }
        }

        // Stages chain within one tick so the lasso goes out on the stun instead of
        // one tick per stage later, by which point the pest has recovered.
        int tick = client.player.tickCount;
        for (int i = 0; i < MAX_STAGES_PER_TICK; i++) {
            Stage before = runtime.huntStage;
            switch (runtime.huntStage) {
                case STUN -> handleStun(client, runtime, focus, attached, now, tick);
                case SWAP_TO_LASSO -> handleSwapToLasso(client, runtime, now, tick);
                case THROW -> handleThrow(client, context, runtime, focus, attached, now, tick);
                case REEL -> handleReel(
                        client, context, runtime, focus, attached, reelPromptUp, now, tick);
            }
            // Only forward progress chains: a stage that reset back to STUN did so
            // because the catch ended or the lasso fell off, and its focus is stale.
            if (runtime.huntStage.ordinal() <= before.ordinal()
                    || runtime.state != PestDestroyer.State.HUNT_PEST) {
                return;
            }
        }
    }

    // -- Stages ---------------------------------------------------------------

    private static void handleStun(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity target,
            boolean attached,
            long now,
            int tick) {
        // A leash that landed late must not eat a stun tap; the tap knocks it back off.
        if (attached) {
            releaseStunVacuum(runtime);
            advance(runtime, Stage.SWAP_TO_LASSO, now, tick);
            return;
        }
        if (!AetherConfig.PEST_HUNTING_VACUUM_STUN.get() || runtime.stunVacuumSlot < 0) {
            advance(runtime, Stage.SWAP_TO_LASSO, now, tick);
            return;
        }
        boolean inStunRange = client.player.distanceTo(target) <= STUN_RANGE;
        if (runtime.huntStunHoldStartedAt == 0L && !runtime.huntStunDelivered) {
            if (!inStunRange) {
                runtime.huntStunRangeSince = 0L;
            } else if (runtime.huntStunRangeSince == 0L) {
                runtime.huntStunRangeSince = now;
            }
            // Separate clocks: one for a stun that will not fire from in range,
            // one for a pest that never lets us get there.
            boolean stalled = runtime.huntStunRangeSince != 0L
                    && now - runtime.huntStunRangeSince > STUN_STAGE_TIMEOUT_MS;
            boolean chasedTooLong = now - runtime.huntStageEnteredAt > STUN_APPROACH_TIMEOUT_MS;
            if (stalled || chasedTooLong) {
                ClientUtils.sendDebugMessage("[PestHunting] Stun did not land ("
                        + (stalled ? "stalled in range" : "never in range")
                        + "). Throwing without it.");
                advance(runtime, Stage.SWAP_TO_LASSO, now, tick);
                return;
            }
        }

        // A missed throw returns here, so vary the hotbar change instead of always same-tick.
        if (runtime.huntDelayBeforeStunSwap) {
            if (runtime.huntSwapReadyTick == 0) {
                runtime.huntSwapReadyTick = tick + nextRetrySwapDelayTicks();
            }
            if (tick < runtime.huntSwapReadyTick) {
                return;
            }
            runtime.huntDelayBeforeStunSwap = false;
            runtime.huntSwapReadyTick = 0;
        }

        AccessorInventory inventory = (AccessorInventory) client.player.getInventory();
        if (inventory.getSelected() != runtime.stunVacuumSlot) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, runtime.stunVacuumSlot));
            runtime.huntStageEnteredTick = tick;
            return;
        }
        if (tick - runtime.huntStageEnteredTick < VACUUM_SETTLE_TICKS) {
            return;
        }

        if (runtime.huntSwapReadyTick == 0) {
            if (runtime.huntStunHoldStartedAt == 0L) {
                if (!inStunRange
                        || waitForLanding(runtime, target, now)
                        || !isAimedAtTarget(client, runtime, target, THROW_AIM_TOLERANCE_DEGREES)) {
                    return;
                }
                ClientUtils.beginUseHoldNow();
                runtime.huntStunHoldStartedAt = now;
                return;
            }
            if (now - runtime.huntStunHoldStartedAt < STUN_VACUUM_HOLD_MS) {
                return;
            }
            long heldFor = now - runtime.huntStunHoldStartedAt;
            releaseStunVacuum(runtime);
            runtime.huntStunDelivered = true;
            // Vary the hotbar swap by 1-5 ticks so the release into the swap
            // does not have a fixed one-tick signature.
            runtime.huntSwapReadyTick = tick + nextVacuumSwapDelayTicks();
            ClientUtils.sendDebugMessage(
                    "[PestHunting] Stunned with vacuum (" + heldFor + "ms hold).");
            return;
        }
        if (tick >= runtime.huntSwapReadyTick) {
            advance(runtime, Stage.SWAP_TO_LASSO, now, tick);
        }
    }

    private static void handleSwapToLasso(
            Minecraft client, PestDestroyerRuntime runtime, long now, int tick) {
        releaseStunVacuum(runtime);
        AccessorInventory inventory = (AccessorInventory) client.player.getInventory();
        if (inventory.getSelected() != runtime.lassoSlot) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, runtime.lassoSlot));
            // Dispatched on the client thread, so it has already applied; re-reading
            // lets the throw follow in this same tick instead of the next one.
            if (inventory.getSelected() != runtime.lassoSlot) {
                return;
            }
        }
        advance(runtime, Stage.THROW, now, tick);
    }

    private static void handleThrow(
            Minecraft client,
            Context context,
            PestDestroyerRuntime runtime,
            Entity target,
            boolean attached,
            long now,
            int tick) {
        if (attached) {
            runtime.huntThrownAt = now;
            advance(runtime, Stage.REEL, now, tick);
            return;
        }
        // Throwing in the same tick as the hotbar swap can still use the vacuum.
        if (tick - runtime.huntStageEnteredTick < LASSO_SETTLE_TICKS) {
            return;
        }
        // A lasso thrown from out of reach cannot attach and still burns a throw.
        if (client.player.distanceTo(target) > THROW_RANGE) {
            if (now - runtime.huntStageEnteredAt > THROW_APPROACH_TIMEOUT_MS) {
                ClientUtils.sendDebugMessage("[PestHunting] Could not close on the pest. Moving on.");
                abandonCatch(client, context, runtime, target);
            }
            return;
        }
        if (waitForLanding(runtime, target, now)
                || !isAimedAtTarget(client, runtime, target, THROW_AIM_TOLERANCE_DEGREES)) {
            return;
        }

        if (runtime.huntThrowCount >= AetherConfig.PEST_HUNTING_MAX_THROWS.get()) {
            ClientUtils.sendDebugMessage(
                    "[PestHunting] Lasso would not attach after "
                            + runtime.huntThrowCount + " throw(s). Moving on.");
            abandonCatch(client, context, runtime, target);
            return;
        }
        runtime.huntThrowCount++;

        ClientUtils.performUseClickNow();
        runtime.huntThrownAt = now;
        ClientUtils.sendDebugMessage(
                "[PestHunting] Threw lasso (attempt " + runtime.huntThrowCount + ").");
        advance(runtime, Stage.REEL, now, tick);
    }

    private static void handleReel(
            Minecraft client,
            Context context,
            PestDestroyerRuntime runtime,
            Entity target,
            boolean attached,
            boolean reelPromptUp,
            long now,
            int tick) {
        // Releasing keyUse here would cut short the click we just scheduled.
        if (!attached) {
            long quietFor = now - Math.max(runtime.huntThrownAt, runtime.huntLastAttachedAt);
            if (quietFor < (runtime.huntEverAttached ? DETACH_CONFIRM_MS : THROW_CONFIRM_MS)) {
                return;
            }
            if (runtime.huntThrowCount > AetherConfig.PEST_HUNTING_MAX_THROWS.get()) {
                abandonCatch(client, context, runtime, target);
                return;
            }
            ClientUtils.sendDebugMessage("[PestHunting] Lasso off. Re-stunning.");
            runtime.huntReelPromptLatched = false;
            runtime.huntSwapReadyTick = 0;
            runtime.huntStunDelivered = false;
            runtime.huntStunHoldStartedAt = 0L;
            runtime.huntDelayBeforeStunSwap = true;
            advance(runtime, Stage.STUN, now, tick);
            return;
        }

        // The prompt outlives the click, so latch it and wait for it to clear;
        // clicking again while it is still up reels the next layer too early.
        if (!reelPromptUp) {
            runtime.huntReelPromptTicks = 0;
            return;
        }
        if (runtime.huntReelPromptTicks < REEL_PROMPT_CONFIRM_TICKS
                || runtime.huntReelPromptLatched
                || now - runtime.huntLastReelClickAt < REEL_CLICK_COOLDOWN_MS) {
            return;
        }
        // The prompt is only up for a moment, so click on the tick it appears; a
        // reaction delay here spends the whole window and misses the reel.
        if (!isAimedAtTarget(client, runtime, target, REEL_AIM_TOLERANCE_DEGREES)) {
            return;
        }

        ClientUtils.performUseClickNow();
        runtime.huntReelCount++;
        runtime.huntReelPromptLatched = true;
        runtime.huntReelPromptArmed = false;
        runtime.huntLastReelClickAt = now;
        runtime.huntReelAwaitingResponseUntil = now + REEL_RESPONSE_WAIT_MS;
        ClientUtils.sendDebugMessage("[PestHunting] Reeled (" + runtime.huntReelCount + ").");
    }

    // -- Catch completion -----------------------------------------------------

    private static void finishCatch(
            Minecraft client,
            Context context,
            PestDestroyerRuntime runtime,
            Entity target,
            boolean caught,
            String reason) {
        int reels = runtime.huntReelCount;
        clearHunt(client, runtime);
        if (target != null) {
            context.markKilled(target);
            // Only a confirmed catch removes a pest. Counting a target we merely
            // lost track of drains the alive count and ends the run early.
            if (caught && context.recordTrackedPestKill(client, target)) {
                return;
            }
        }
        runtime.currentTarget = null;
        ClientUtils.sendDebugMessage(
                "[PestHunting] Catch finished (" + reason + ") after " + reels + " reel(s).");
        if (!context.switchToNextQueuedTarget(client)) {
            context.setState(PestDestroyer.State.CHECK_NEXT);
        }
    }

    private static void abandonCatch(
            Minecraft client,
            Context context,
            PestDestroyerRuntime runtime,
            Entity target) {
        clearHunt(client, runtime);
        if (target != null) {
            // Not a catch: the pest is still out there, so skip it for a while
            // instead of hiding it from the rest of the run.
            context.deferTarget(target);
        }
        runtime.currentTarget = null;
        if (!context.switchToNextQueuedTarget(client)) {
            context.setState(PestDestroyer.State.CHECK_NEXT);
        }
    }

    // -- Aim, movement, detection --------------------------------------------

    private static void maintainAim(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity target,
            float tolerance,
            long now) {
        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            return;
        }
        Vec3 aim = aimPoint(client, runtime, target);
        double distance = client.player.getEyePosition().distanceTo(aim);
        if (distance < MIN_AIM_DISTANCE) {
            return;
        }
        if (PestTargetController.isLookingAt(client, aim, tolerance)) {
            return;
        }
        // Tracking re-targets every tick, which is what following a moving pest
        // needs; initiateRotation would keep aiming where it used to be.
        RotationManager.trackRotation(
                client,
                steerPoint(runtime, aim, now),
                HUNT_AIM_SMOOTHING_MS,
                HUNT_AIM_MAX_TURN_SPEED);
    }

    /**
     * The reel only needs REEL_AIM_TOLERANCE_DEGREES, so a landed lasso must not
     * buy the tight throw correction that made the catch snap on camera.
     */
    static float huntAimTolerance(boolean attached, Stage stage) {
        return attached || stage == Stage.REEL
                ? ATTACHED_AIM_TOLERANCE_DEGREES
                : THROW_AIM_TOLERANCE_DEGREES;
    }

    /**
     * Holds the previous focus briefly so a leash that reads on and off for a
     * tick cannot bounce the camera between the pest and its marker stand.
     */
    private static Entity resolveFocus(
            PestDestroyerRuntime runtime, Entity next, long now) {
        Entity held = runtime.huntFocus;
        if (next == null || next == held) {
            runtime.huntPendingFocus = null;
            runtime.huntPendingFocusSince = 0L;
            return next == null ? held : next;
        }
        if (runtime.huntPendingFocus != next) {
            runtime.huntPendingFocus = next;
            runtime.huntPendingFocusSince = now;
        }
        if (!adoptsPendingFocus(held != null && !isGone(held), runtime.huntPendingFocusSince, now)) {
            return held;
        }
        runtime.huntFocus = next;
        runtime.huntPendingFocus = null;
        runtime.huntPendingFocusSince = 0L;
        return next;
    }

    static boolean adoptsPendingFocus(boolean heldUsable, long pendingSince, long now) {
        return !heldUsable
                || (pendingSince != 0L && now - pendingSince >= FOCUS_SWITCH_DEBOUNCE_MS);
    }

    private static void updateAimBlend(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity focus,
            boolean armed,
            long now) {
        if (focus == null) {
            return;
        }
        if (!armed) {
            runtime.huntAimBlendOffset = null;
            runtime.huntAimBlendStartedAt = 0L;
        }
        Vec3 point = aimPoint(client, runtime, focus);
        if (runtime.huntAimFocusId != focus.getId()) {
            if (armed && runtime.huntAimFocusId != -1 && runtime.huntLastAimPoint != null) {
                runtime.huntAimBlendOffset = runtime.huntLastAimPoint.subtract(point);
                runtime.huntAimBlendStartedAt = now;
            }
            runtime.huntAimFocusId = focus.getId();
        }
        runtime.huntLastAimPoint = point;
    }

    /**
     * Where the camera is steered: the live aim point plus the decaying remainder
     * of the jump the focus change introduced. Click gates keep using the real
     * point, so nothing fires while the blend still has the crosshair short.
     */
    private static Vec3 steerPoint(PestDestroyerRuntime runtime, Vec3 aim, long now) {
        if (runtime.huntAimBlendStartedAt == 0L || runtime.huntAimBlendOffset == null) {
            return aim;
        }
        double remaining = aimBlendRemaining(now - runtime.huntAimBlendStartedAt);
        if (remaining <= 0.0) {
            runtime.huntAimBlendOffset = null;
            runtime.huntAimBlendStartedAt = 0L;
            return aim;
        }
        return aim.add(runtime.huntAimBlendOffset.scale(remaining));
    }

    static double aimBlendRemaining(long elapsedMs) {
        if (elapsedMs >= AIM_BLEND_MS) {
            return 0.0;
        }
        double t = Math.max(0.0, (double) elapsedMs / AIM_BLEND_MS);
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    private static Vec3 aimPoint(
            Minecraft client, PestDestroyerRuntime runtime, Entity target) {
        return usesVacuumAim(runtime)
                ? PestCombatCoordinator.buildVacuumAimTarget(client, target)
                : huntAimPoint(client, target);
    }

    // Dropped as soon as the tap is out: its fixed downward pitch is tens of degrees off the throw aim.
    private static boolean usesVacuumAim(PestDestroyerRuntime runtime) {
        return runtime.huntStage == Stage.STUN
                && !runtime.huntStunDelivered
                && AetherConfig.PEST_HUNTING_VACUUM_STUN.get()
                && runtime.stunVacuumSlot >= 0;
    }

    private static int nextVacuumSwapDelayTicks() {
        return ThreadLocalRandom.current().nextInt(
                MIN_VACUUM_SWAP_TICKS, MAX_VACUUM_SWAP_TICKS + 1);
    }

    private static int nextRetrySwapDelayTicks() {
        return ThreadLocalRandom.current().nextInt(
                MIN_RETRY_SWAP_TICKS, MAX_RETRY_SWAP_TICKS + 1);
    }

    private static void maintainFollowDistance(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity target,
            double followDistance,
            boolean allowTranslation,
            boolean adjustAltitude) {
        double horizontal = horizontalDistanceTo(client, target);
        double follow = Math.min(followDistance, LASSO_INTERACTION_RANGE - 0.75);

        boolean offAxis = !facesTarget(client, target, FOLLOW_YAW_CONE_DEGREES);
        int followDirection = followDirection(
                horizontal, follow, offAxis, allowTranslation, runtime.huntFollowMove);
        runtime.huntFollowMove = followDirection;
        ClientUtils.setKeyMappingState(client.options.keyUp, followDirection > 0);
        ClientUtils.setKeyMappingState(client.options.keyDown, followDirection < 0);
        ClientUtils.setKeyMappingState(client.options.keyLeft, false);
        ClientUtils.setKeyMappingState(client.options.keyRight, false);
        ClientUtils.setKeyMappingState(client.options.keySprint,
                followDirection > 0 && horizontal > follow + SPRINT_GAP);

        double heightGap = target.getY() - client.player.getY();
        boolean flying = client.player.getAbilities().flying;
        ClientUtils.setKeyMappingState(client.options.keyJump,
                adjustAltitude && flying && heightGap > VERTICAL_ALIGN_TOLERANCE);
        ClientUtils.setKeyMappingState(client.options.keyShift,
                adjustAltitude && flying && heightGap < -VERTICAL_ALIGN_TOLERANCE);
    }

    /**
     * Returns 1 for forward, -1 for a braking/back-off input, and 0 for hold.
     * Latched on {@code previous}: a dead band is narrower than flight's stopping
     * distance, so on its own it answers every overshoot with the opposite key and
     * the hunter pumps forward and back. Once moving, run to the follow distance
     * and stop there, and only start again a full band away from it.
     */
    static int followDirection(
            double horizontal,
            double follow,
            boolean offAxis,
            boolean allowTranslation,
            int previous) {
        if (!allowTranslation || offAxis) {
            return 0;
        }
        if (previous > 0) {
            return horizontal > follow ? 1 : 0;
        }
        if (previous < 0) {
            return horizontal < follow ? -1 : 0;
        }
        if (horizontal > follow + FOLLOW_BAND) {
            return 1;
        }
        double backOffThreshold = Math.max(MIN_AIM_DISTANCE + 0.25, follow - FOLLOW_BAND);
        return horizontal < backOffThreshold ? -1 : 0;
    }

    /**
     * Yaw only: pitch is the altitude keys' problem, and the aim deliberately
     * sits above the pest, which a 3D cone would read as being off target.
     */
    private static boolean facesTarget(Minecraft client, Entity target, double maxYawError) {
        double dx = target.getX() - client.player.getX();
        double dz = target.getZ() - client.player.getZ();
        if (dx * dx + dz * dz < 1.0e-4) {
            return true;
        }
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return Math.abs(Mth.wrapDegrees(desiredYaw - client.player.getYRot())) <= maxYawError;
    }

    private static double horizontalDistanceTo(Minecraft client, Entity target) {
        double dx = client.player.getX() - target.getX();
        double dz = client.player.getZ() - target.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * A use-click that is not on the pest hits whatever is behind it, which in the
     * garden means opening a menu, so nothing clicks without confirmed aim.
     */
    private static boolean isAimedAtTarget(
            Minecraft client, PestDestroyerRuntime runtime, Entity target, float tolerance) {
        return PestTargetController.isLookingAt(
                client, aimPoint(client, runtime, target), tolerance);
    }

    private static boolean isGone(Entity target) {
        return target == null
                || target.isRemoved()
                || target instanceof LivingEntity living && living.isDeadOrDying();
    }

    private static Entity findLassoedPest(Minecraft client, Entity target) {
        if (client.level == null) {
            return null;
        }
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!isPestMob(entity) || !holdsOurLeash(client, entity)) {
                continue;
            }
            double distance = target == null ? 0.0 : entity.distanceToSqr(target);
            if (target == null || distance < closestDistance) {
                closest = entity;
                closestDistance = distance;
            }
        }
        // Do not steal another pest's leash when multiple catches are visible.
        return target == null || closestDistance <= 36.0 ? closest : null;
    }

    /**
     * The client never reported the leash the catch detection is built on, so
     * log what is actually around the pest on the first throw of a hunt.
     */
    private static void probeCatchState(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Entity focus,
            Entity lassoed,
            long now) {
        if (focus == null
                || client.level == null
                || runtime.huntThrownAt == 0L
                || runtime.huntThrowCount > 1) {
            return;
        }
        long since = now - runtime.huntThrownAt;
        if (since > CATCH_PROBE_WINDOW_MS
                || client.player.tickCount % CATCH_PROBE_INTERVAL_TICKS != 0) {
            return;
        }

        ClientUtils.sendDebugMessage("[PestHunting][probe] +" + since + "ms pest="
                + describeEntity(client, focus, focus)
                + " lassoed=" + (lassoed == null ? "none" : lassoed.getId()));
        AABB box = AABB.ofSize(
                focus.position(), CATCH_PROBE_SIZE, CATCH_PROBE_SIZE, CATCH_PROBE_SIZE);
        for (Entity entity : client.level.getEntities(client.player, box)) {
            if (entity != focus) {
                ClientUtils.sendDebugMessage(
                        "[PestHunting][probe]   " + describeEntity(client, entity, focus));
            }
        }
    }

    private static String describeEntity(Minecraft client, Entity entity, Entity focus) {
        StringBuilder text = new StringBuilder(entity.getClass().getSimpleName())
                .append('#').append(entity.getId())
                .append(String.format(" d=%.1f", Math.sqrt(entity.distanceToSqr(focus))));
        Component custom = entity.getCustomName();
        if (custom != null) {
            text.append(" name='").append(stripFormatting(custom.getString())).append('\'');
        }
        if (entity instanceof ArmorStand stand) {
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (!head.isEmpty()) {
                text.append(" head=").append(head.getItem());
            }
        }
        if (entity instanceof Leashable leashable) {
            Entity holder = leashable.getLeashHolder();
            text.append(" leash=").append(holder == null
                    ? "none"
                    : holder == client.player ? "player" : holder.getId());
        }
        if (entity.getVehicle() != null) {
            text.append(" riding#").append(entity.getVehicle().getId());
        }
        if (entity.isInvisible()) {
            text.append(" invisible");
        }
        return text.toString();
    }

    private static boolean isPestMob(Entity entity) {
        return entity instanceof Bat || entity instanceof Silverfish;
    }

    private static boolean holdsOurLeash(Minecraft client, Entity entity) {
        return entity instanceof Leashable leashable
                && leashable.getLeashHolder() == client.player;
    }

    static void releaseStunVacuum(PestDestroyerRuntime runtime) {
        if (runtime.huntStunHoldStartedAt == 0L) {
            return;
        }
        runtime.huntStunHoldStartedAt = 0L;
        ClientUtils.endUseHold();
    }

    private static void holdLassoPosition(Minecraft client) {
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyLeft, false);
        ClientUtils.setKeyMappingState(client.options.keyRight, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, false);
        ClientUtils.setKeyMappingState(client.options.keyShift, false);
    }

    private static boolean hasReelPrompt(Minecraft client, Entity target) {
        if (findMarker(client, target, true) != null) {
            return true;
        }
        if (client.level == null || target == null) {
            return false;
        }

        // Entity/marker interpolation can briefly separate the exact REEL
        // marker farther than the normal riding radius. Since only an exact
        // REEL-labelled marker qualifies, scanning the nearby catch area does
        // not confuse health or stamina armor stands with the prompt.
        AABB catchArea = AABB.ofSize(target.position(), 16.0, 10.0, 16.0);
        for (ArmorStand marker : client.level.getEntitiesOfClass(ArmorStand.class, catchArea)) {
            if (isReelPrompt(plainMarkerName(marker))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Empty for a nameless stand: getName() falls back to the vanilla "Armor
     * Stand" label, which made the lasso's cobweb stand look like a pest marker.
     */
    private static String plainMarkerName(ArmorStand marker) {
        Component custom = marker.getCustomName();
        if (custom != null) {
            return stripFormatting(custom.getString());
        }
        String plain = stripFormatting(marker.getName().getString());
        String typeName = stripFormatting(marker.getType().getDescription().getString());
        return plain.equals(typeName) ? "" : plain;
    }

    static String stripFormatting(String text) {
        return text.replaceAll("(?i)\\u00A7.", "").trim();
    }

    static boolean isReelPrompt(String plainName) {
        return plainName.toUpperCase(Locale.ROOT).contains(REEL_PROMPT);
    }

    /**
     * A cricket clears several blocks in one hop and lands somewhere else, so a
     * stun or a throw mid-hop is spent on where it no longer is.
     */
    private static boolean waitForLanding(PestDestroyerRuntime runtime, Entity target, long now) {
        if (!(target instanceof Silverfish)) {
            runtime.huntLandingWaitStartedAt = 0L;
            return false;
        }
        double y = target.getY();
        boolean hopping = !Double.isNaN(runtime.huntTargetY)
                && Math.abs(y - runtime.huntTargetY) > LANDING_Y_EPSILON;
        runtime.huntTargetY = y;
        // onGround on its own is not enough to wait on: server-driven pests can go
        // whole catches without ever reporting it, and that wait is dead time.
        if (target.onGround() || !hopping) {
            runtime.huntLandingWaitStartedAt = 0L;
            return false;
        }
        if (runtime.huntLandingWaitStartedAt == 0L) {
            runtime.huntLandingWaitStartedAt = now;
        }
        return now - runtime.huntLandingWaitStartedAt < LANDING_WAIT_TIMEOUT_MS;
    }

    /**
     * The health bar stand trails a hopping pest by a block or more, so it is only
     * worth aiming at when the target is a marker with no mob of its own.
     */
    private static Vec3 huntAimPoint(Minecraft client, Entity pest) {
        if (pest instanceof Bat || pest instanceof Silverfish) {
            return abovePest(pest);
        }
        ArmorStand healthBar = findMarker(client, pest, false);
        return healthBar != null ? healthBar.position() : abovePest(pest);
    }

    static double aimClearance(int entityId) {
        double span = MAX_AIM_CLEARANCE - MIN_AIM_CLEARANCE;
        int bucket = Math.floorMod(entityId, AIM_CLEARANCE_BUCKETS);
        return MIN_AIM_CLEARANCE + span * bucket / (AIM_CLEARANCE_BUCKETS - 1);
    }

    private static Vec3 abovePest(Entity pest) {
        return pest.position().add(0, pest.getBbHeight() + aimClearance(pest.getId()), 0);
    }

    private static ArmorStand findMarker(Minecraft client, Entity pest, boolean reelPrompt) {
        if (client.level == null || pest == null) {
            return null;
        }
        AABB searchBox = AABB.ofSize(
                pest.position().add(0, MARKER_SEARCH_HEIGHT_OFFSET, 0),
                MARKER_SEARCH_SIZE,
                MARKER_SEARCH_SIZE,
                MARKER_SEARCH_SIZE);
        ArmorStand closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (ArmorStand marker : client.level.getEntitiesOfClass(ArmorStand.class, searchBox)) {
            String plain = plainMarkerName(marker);
            // An empty name is the stamina bar or a prop stand such as the
            // thrown lasso's cobweb, neither of which the pest rides on.
            if (plain.isEmpty()
                    || isReelPrompt(plain) != reelPrompt
                    || !ridesOn(marker, pest, reelPrompt
                            ? REEL_MARKER_MAX_HORIZONTAL
                            : MARKER_MAX_HORIZONTAL)) {
                continue;
            }
            double distance = marker.distanceToSqr(pest);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = marker;
            }
        }
        return closest;
    }

    /**
     * Without this a pest whose own markers are gone borrows the neighbouring
     * pest's, and the aim snaps off to wherever that one is.
     */
    private static boolean ridesOn(ArmorStand marker, Entity pest, double maxHorizontal) {
        double dx = marker.getX() - pest.getX();
        double dz = marker.getZ() - pest.getZ();
        double dy = marker.getY() - pest.getY();
        return dx * dx + dz * dz <= maxHorizontal * maxHorizontal
                && dy >= -MARKER_MAX_HEIGHT_BELOW
                && dy <= MARKER_MAX_HEIGHT_ABOVE;
    }

    private static void advance(
            PestDestroyerRuntime runtime, Stage stage, long now, int tick) {
        runtime.huntStage = stage;
        runtime.huntStageEnteredAt = now;
        runtime.huntStageEnteredTick = tick;
    }
}
