package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.mixin.AccessorInventory
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.ProgrammaticAttackTracker
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

// stuns, lassos and reels a pest for its shard instead of vacuuming it
class PestHuntingController private constructor() {
    enum class Stage { STUN, SWAP_TO_LASSO, THROW, REEL }

    interface Context {
        fun runtime(): PestDestroyerRuntime
        fun setState(state: PestDestroyer.State)
        fun markKilled(entity: Entity)
        fun deferTarget(entity: Entity)
        fun recordTrackedPestKill(client: Minecraft, entity: Entity): Boolean
        fun switchToNextQueuedTarget(client: Minecraft): Boolean
    }

    companion object {
        private const val REEL_PROMPT = "REEL"
        private const val ESCAPE_PROMPT = "ESCAPED"
        private const val MARKER_SEARCH_SIZE = 6.0
        private const val MARKER_SEARCH_HEIGHT_OFFSET = 1.5
        private const val MARKER_MAX_HORIZONTAL = 2.0
        private const val REEL_MARKER_MAX_HORIZONTAL = 3.0
        private const val REEL_PROMPT_CONFIRM_TICKS = 2
        private const val MARKER_MAX_HEIGHT_ABOVE = 3.5
        private const val MARKER_MAX_HEIGHT_BELOW = 1.0
        private const val VACUUM_SETTLE_TICKS = 1
        private const val STUN_VACUUM_HOLD_MS = 500L
        private const val STUN_STAGE_TIMEOUT_MS = 3_000L
        private const val STUN_APPROACH_TIMEOUT_MS = 6_000L
        private const val THROW_APPROACH_TIMEOUT_MS = 6_000L
        private const val MIN_VACUUM_SWAP_TICKS = 1
        private const val MAX_VACUUM_SWAP_TICKS = 5
        private const val MIN_RETRY_SWAP_TICKS = 4
        private const val MAX_RETRY_SWAP_TICKS = 8
        private const val LASSO_SETTLE_TICKS = 0
        private const val THROW_CONFIRM_MS = 1_200L
        private const val REEL_CLICK_COOLDOWN_MS = 250L
        private const val DETACH_CONFIRM_MS = 2_500L
        private const val THROW_AIM_TOLERANCE_DEGREES = 10.0f
        private const val REEL_AIM_TOLERANCE_DEGREES = 25.0f
        private const val ATTACHED_AIM_TOLERANCE_DEGREES = 18.0f
        private const val AIM_BLEND_MS = 420L
        private const val FOCUS_SWITCH_DEBOUNCE_MS = 200L
        private const val REEL_RESPONSE_WAIT_MS = 500L
        private const val REEL_OVERLAY_SIGNAL_GRACE_MS = 300L
        private const val LANDING_WAIT_TIMEOUT_MS = 250L
        private const val LANDING_Y_EPSILON = 0.05
        private const val MIN_AIM_DISTANCE = 1.0
        private const val MIN_AIM_CLEARANCE = 0.15
        private const val MAX_AIM_CLEARANCE = 0.35
        private const val AIM_CLEARANCE_BUCKETS = 5
        private const val LASSO_INTERACTION_RANGE = 4.75
        private const val STUN_RANGE = 3.5
        private const val HUNT_FOLLOW_DISTANCE = 2.5
        private const val LEASHED_FOLLOW_DISTANCE = 2.0
        private const val SPRINT_GAP = 2.0
        private const val FOLLOW_BAND = 0.75
        private const val COAST_DRAG_TICKS = 9.0
        private const val MAX_CLOSING_SAMPLE = 1.0
        private const val CLOSING_RATE_SMOOTHING = 0.6
        private const val FOLLOW_YAW_CONE_DEGREES = 25.0
        private const val LEASH_CLOSE_DEBOUNCE_MS = 250L
        private const val AIM_WANDER_FRACTION = 0.3
        private const val MAX_AIM_WANDER_DEGREES = 3.0
        private const val LOOSE_AIM_TRIGGER_DEGREES = 12.0f
        private const val LOOSE_AIM_SETTLE_DEGREES = 6.0f
        private const val THROW_RANGE = LASSO_INTERACTION_RANGE + 0.25
        private const val HANDOFF_SLACK = 2.0
        private const val REPATH_DISTANCE = 16.0
        private const val VERTICAL_ALIGN_TOLERANCE = 1.0
        private const val MAX_STAGES_PER_TICK = 3
        private const val CATCH_PROBE_WINDOW_MS = 2_500L
        private const val CATCH_PROBE_INTERVAL_TICKS = 10
        private const val CATCH_PROBE_SIZE = 5.0

        @JvmStatic fun isEnabled(): Boolean = AetherConfig.PEST_HUNTING.get()
        @JvmStatic fun shouldLassoTarget(client: Minecraft, target: Entity): Boolean = PestHuntingPolicy.shouldLasso(client, target)
        @JvmStatic fun handoffRange(client: Minecraft, target: Entity, vacuumRange: Double): Double =
            if (shouldLassoTarget(client, target)) AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE.get() + HANDOFF_SLACK else vacuumRange

        @JvmStatic
        fun beginHunt(runtime: PestDestroyerRuntime, client: Minecraft) {
            runtime.huntStage = Stage.STUN
            runtime.huntStageEnteredAt = System.currentTimeMillis()
            runtime.huntStartedAt = System.currentTimeMillis()
            runtime.huntThrowCount = 0
            runtime.huntReelCount = 0
            runtime.huntThrownAt = 0L
            runtime.huntLastReelClickAt = 0L
            runtime.huntLastAttachedAt = 0L
            runtime.huntAttachedSince = 0L
            runtime.huntReelSignalAt = 0L
            runtime.huntEverAttached = false
            runtime.huntReelPromptLatched = false
            runtime.huntReelPromptTicks = 0
            runtime.huntReelPromptClearTicks = 0
            runtime.huntReelPromptArmed = true
            runtime.huntReelAwaitingResponseUntil = 0L
            runtime.huntSwapReadyTick = 0
            runtime.huntDelayBeforeStunSwap = false
            runtime.huntStunDelivered = false
            runtime.huntStunHoldStartedAt = 0L
            runtime.huntStunRangeSince = 0L
            runtime.huntStageEnteredTick = 0
            runtime.huntLandingWaitStartedAt = 0L
            runtime.huntTargetY = Double.NaN
            runtime.huntCaughtSignal = false
            runtime.huntEscapeSignal = false
            runtime.resetHuntAimState()
            runtime.lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(client)
        }

        @JvmStatic
        fun clearHunt(client: Minecraft?, runtime: PestDestroyerRuntime) {
            RotationManager.cancelRotation()
            releaseStunVacuum(runtime)
            runtime.huntStage = Stage.STUN
            runtime.huntStartedAt = 0L
            runtime.huntStageEnteredAt = 0L
            runtime.huntThrowCount = 0
            runtime.huntReelCount = 0
            runtime.huntThrownAt = 0L
            runtime.huntLastReelClickAt = 0L
            runtime.huntLastAttachedAt = 0L
            runtime.huntAttachedSince = 0L
            runtime.huntReelSignalAt = 0L
            runtime.huntEverAttached = false
            runtime.huntReelPromptLatched = false
            runtime.huntReelPromptTicks = 0
            runtime.huntReelPromptClearTicks = 0
            runtime.huntReelPromptArmed = true
            runtime.huntReelAwaitingResponseUntil = 0L
            runtime.huntSwapReadyTick = 0
            runtime.huntDelayBeforeStunSwap = false
            runtime.huntStunDelivered = false
            runtime.huntStunHoldStartedAt = 0L
            runtime.huntStunRangeSince = 0L
            runtime.huntStageEnteredTick = 0
            runtime.huntLandingWaitStartedAt = 0L
            runtime.huntTargetY = Double.NaN
            runtime.huntCaughtSignal = false
            runtime.huntEscapeSignal = false
            runtime.resetHuntAimState()
            runtime.lassoSlot = -1
            val options = client?.options
            if (options != null) {
                ClientUtils.setKeyMappingState(options.keyUse, false)
                ClientUtils.setKeyMappingState(options.keyAttack, false)
                ClientUtils.setKeyMappingState(options.keyUp, false)
                ClientUtils.setKeyMappingState(options.keyDown, false)
                ClientUtils.setKeyMappingState(options.keySprint, false)
                ClientUtils.setKeyMappingState(options.keyJump, false)
                ClientUtils.setKeyMappingState(options.keyShift, false)
            }
        }

        @JvmStatic fun onPestCaught(runtime: PestDestroyerRuntime) { runtime.huntCaughtSignal = true }

        @JvmStatic
        fun onOverlayMessage(runtime: PestDestroyerRuntime, message: String?) {
            if (message == null) return
            val plain = stripFormatting(message)
            if (isReelPrompt(plain)) runtime.huntReelSignalAt = System.currentTimeMillis()
            if (isEscapeMessage(plain)) runtime.huntEscapeSignal = true
        }

        @JvmStatic
        fun onEscapeMessage(runtime: PestDestroyerRuntime, message: String?) {
            if (message != null && isEscapeMessage(stripFormatting(message))) runtime.huntEscapeSignal = true
        }

        @JvmStatic
        fun isEscapeMessage(plainName: String): Boolean {
            val upper = plainName.uppercase(Locale.ROOT)
            return upper.contains(ESCAPE_PROMPT) && (upper.contains(REEL_PROMPT) || upper.contains("LASSO"))
        }

        @JvmStatic
        fun handleHuntPest(client: Minecraft, context: Context) {
            val runtime = context.runtime()
            val player = client.player ?: return
            var target = runtime.currentTarget
            if (runtime.huntCaughtSignal) { finishCatch(client, context, runtime, target, true, "caught"); return }
            val lassoed = findLassoedPest(client, target)
            if (isGone(target)) {
                if (lassoed == null) { finishCatch(client, context, runtime, target, runtime.huntEverAttached, "target gone"); return }
                runtime.currentTarget = lassoed
                target = lassoed
            }
            val huntTarget = target ?: return
            if (runtime.lassoSlot == -1) {
                runtime.lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(client)
                if (runtime.lassoSlot == -1) {
                    ClientUtils.sendMessage("§cNo lasso found for this lasso-routed pest. Leaving it untouched and stopping the hunt.", false)
                    clearHunt(client, runtime)
                    context.setState(PestDestroyer.State.FINISH)
                    return
                }
            }
            if (!AetherConfig.PEST_DESTROYER_WALK_MODE.get() && !player.abilities.flying && player.abilities.mayfly) {
                clearHunt(client, runtime)
                context.setState(PestDestroyer.State.FLY_UP)
                return
            }
            val now = System.currentTimeMillis()
            if (runtime.huntEscapeSignal) restartAfterEscape(client, runtime, now)
            if (now - runtime.huntStartedAt > AetherConfig.PEST_HUNTING_TIMEOUT_MS.get()) {
                ClientUtils.sendDebugMessage("[PestHunting] Catch timed out. Moving on.")
                abandonCatch(client, context, runtime, target)
                return
            }
            val activeTarget = target ?: return
            val focus = resolveFocus(runtime, if (isPestMob(activeTarget)) activeTarget else lassoed ?: activeTarget, now) ?: return
            val overlayReelSignal = runtime.huntReelSignalAt > runtime.huntLastReelClickAt && now - runtime.huntReelSignalAt <= REEL_OVERLAY_SIGNAL_GRACE_MS
            val reelPromptUp = hasReelPrompt(client, focus) || overlayReelSignal
            val attached = lassoed != null || (reelPromptUp && runtime.huntThrownAt != 0L)
            if (attached) {
                if (!runtime.huntEverAttached) ClientUtils.sendDebugMessage(String.format("[PestHunting] Lasso on (%s) at %.1f blocks.", if (lassoed != null) "leash" else "reel prompt", horizontalDistanceTo(client, focus)))
                runtime.huntEverAttached = true
                runtime.huntLastAttachedAt = now
                if (runtime.huntAttachedSince == 0L) runtime.huntAttachedSince = now
            } else runtime.huntAttachedSince = 0L
            if (reelPromptUp) {
                runtime.huntReelPromptTicks++
                runtime.huntReelPromptClearTicks = 0
            } else {
                runtime.huntReelPromptTicks = 0
                runtime.huntReelPromptClearTicks++
                if (now >= runtime.huntReelAwaitingResponseUntil && runtime.huntReelPromptClearTicks >= 3) {
                    runtime.huntReelPromptArmed = true
                    runtime.huntReelPromptLatched = false
                }
            }
            val clickPending = attached && runtime.huntReelPromptTicks >= REEL_PROMPT_CONFIRM_TICKS && runtime.huntReelPromptArmed && !runtime.huntReelPromptLatched
            updateAimBlend(client, runtime, focus, attached || runtime.huntStage == Stage.REEL, now)
            probeCatchState(client, runtime, focus, lassoed, now)
            val horizontal = horizontalDistanceTo(client, focus)
            updateClosingRate(runtime, focus, horizontal)
            val stunPhase = !attached && usesVacuumAim(runtime)
            val inActionRange = player.distanceTo(focus) <= if (stunPhase) STUN_RANGE else THROW_RANGE
            maintainAim(client, runtime, focus, huntAimTolerance(attached, runtime.huntStage), !attached && inActionRange, now)
            ProgrammaticAttackTracker.setHeld(client.options.keyAttack, false)
            ClientUtils.setKeyMappingState(client.options.keyAttack, false)
            ClientUtils.discardQueuedClicks(client.options.keyAttack)
            val withinLeashRange = horizontal <= AetherConfig.PEST_HUNTING_MAX_DISTANCE.get().toDouble()
            val clickWindow = clickPending || (inActionRange && !attached && runtime.huntStage != Stage.REEL && isAimedAtTarget(client, runtime, focus, THROW_AIM_TOLERANCE_DEGREES))
            if (attached) {
                if (clickPending) {
                    holdLassoPosition(client)
                    runtime.huntFollowMove = 0
                    runtime.huntLeashStretchSince = 0L
                } else maintainFollowDistance(client, runtime, focus, LEASHED_FOLLOW_DISTANCE, leashNeedsClosing(runtime, horizontal, now), true, false)
            } else maintainFollowDistance(client, runtime, focus, minOf(AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE.get().toDouble(), HUNT_FOLLOW_DISTANCE), !clickWindow, !clickWindow, runtime.huntThrownAt == 0L)

            if (runtime.huntStage != Stage.REEL && !attached) {
                if (horizontal > REPATH_DISTANCE) {
                    ClientUtils.sendDebugMessage("[PestHunting] Target drifted out of reach. Re-approaching.")
                    clearHunt(client, runtime)
                    PestTargetController.startPathToPest(client, focus)
                    context.setState(PestDestroyer.State.FLY_TO_PEST)
                    return
                }
                if (!withinLeashRange) { releaseStunVacuum(runtime); return }
            }
            val tick = player.tickCount
            repeat(MAX_STAGES_PER_TICK) {
                val before = runtime.huntStage
                when (runtime.huntStage) {
                    Stage.STUN -> handleStun(client, runtime, focus, attached, now, tick)
                    Stage.SWAP_TO_LASSO -> handleSwapToLasso(client, runtime, now, tick)
                    Stage.THROW -> handleThrow(client, context, runtime, focus, attached, now, tick)
                    Stage.REEL -> handleReel(client, context, runtime, focus, attached, reelPromptUp, now, tick)
                }
                if (runtime.huntStage.ordinal <= before.ordinal || runtime.state != PestDestroyer.State.HUNT_PEST) return
            }
        }

        private fun restartAfterEscape(client: Minecraft, runtime: PestDestroyerRuntime, now: Long) {
            runtime.huntEscapeSignal = false
            releaseStunVacuum(runtime)
            runtime.huntThrownAt = 0L
            runtime.huntLastAttachedAt = 0L
            runtime.huntAttachedSince = 0L
            runtime.huntEverAttached = false
            runtime.huntReelSignalAt = 0L
            runtime.huntReelPromptLatched = false
            runtime.huntReelPromptTicks = 0
            runtime.huntReelPromptClearTicks = 0
            runtime.huntReelPromptArmed = true
            runtime.huntReelAwaitingResponseUntil = 0L
            runtime.huntSwapReadyTick = 0
            runtime.huntDelayBeforeStunSwap = false
            runtime.huntStunDelivered = false
            runtime.huntStunRangeSince = 0L
            runtime.huntLandingWaitStartedAt = 0L
            advance(runtime, Stage.STUN, now, client.player?.tickCount ?: return)
            ClientUtils.sendDebugMessage("[PestHunting] Pest escaped the lasso. Re-stunning immediately.")
        }

        private fun handleStun(client: Minecraft, runtime: PestDestroyerRuntime, target: Entity, attached: Boolean, now: Long, tick: Int) {
            if (attached) { releaseStunVacuum(runtime); advance(runtime, Stage.SWAP_TO_LASSO, now, tick); return }
            if (!AetherConfig.PEST_HUNTING_VACUUM_STUN.get() || runtime.stunVacuumSlot < 0) { advance(runtime, Stage.SWAP_TO_LASSO, now, tick); return }
            val player = client.player ?: return
            val inStunRange = player.distanceTo(target) <= STUN_RANGE
            if (runtime.huntStunHoldStartedAt == 0L && !runtime.huntStunDelivered) {
                if (!inStunRange) runtime.huntStunRangeSince = 0L else if (runtime.huntStunRangeSince == 0L) runtime.huntStunRangeSince = now
                val stalled = runtime.huntStunRangeSince != 0L && now - runtime.huntStunRangeSince > STUN_STAGE_TIMEOUT_MS
                val chasedTooLong = now - runtime.huntStageEnteredAt > STUN_APPROACH_TIMEOUT_MS
                if (stalled || chasedTooLong) {
                    ClientUtils.sendDebugMessage("[PestHunting] Stun did not land (${if (stalled) "stalled in range" else "never in range"}). Throwing without it.")
                    advance(runtime, Stage.SWAP_TO_LASSO, now, tick)
                    return
                }
            }
            if (runtime.huntDelayBeforeStunSwap) {
                if (runtime.huntSwapReadyTick == 0) runtime.huntSwapReadyTick = tick + nextRetrySwapDelayTicks()
                if (tick < runtime.huntSwapReadyTick) return
                runtime.huntDelayBeforeStunSwap = false
                runtime.huntSwapReadyTick = 0
            }
            val inventory = player.inventory as AccessorInventory
            if (inventory.getSelected() != runtime.stunVacuumSlot) {
                client.execute { FailsafeManager.selectHotbarSlot(client, runtime.stunVacuumSlot) }
                if (inventory.getSelected() != runtime.stunVacuumSlot) { runtime.huntStageEnteredTick = tick; return }
            }
            if (tick - runtime.huntStageEnteredTick < VACUUM_SETTLE_TICKS) return
            if (runtime.huntSwapReadyTick == 0) {
                if (runtime.huntStunHoldStartedAt == 0L) {
                    if (!inStunRange || waitForLanding(runtime, target, now) || !isAimedAtTarget(client, runtime, target, THROW_AIM_TOLERANCE_DEGREES)) return
                    ClientUtils.beginUseHoldNow()
                    runtime.huntStunHoldStartedAt = now
                    return
                }
                if (now - runtime.huntStunHoldStartedAt < STUN_VACUUM_HOLD_MS) return
                val heldFor = now - runtime.huntStunHoldStartedAt
                releaseStunVacuum(runtime)
                runtime.huntStunDelivered = true
                runtime.huntSwapReadyTick = tick + nextVacuumSwapDelayTicks()
                ClientUtils.sendDebugMessage("[PestHunting] Stunned with vacuum (${heldFor}ms hold).")
                return
            }
            if (tick >= runtime.huntSwapReadyTick) advance(runtime, Stage.SWAP_TO_LASSO, now, tick)
        }

        private fun handleSwapToLasso(client: Minecraft, runtime: PestDestroyerRuntime, now: Long, tick: Int) {
            releaseStunVacuum(runtime)
            val player = client.player ?: return
            val inventory = player.inventory as AccessorInventory
            if (inventory.getSelected() != runtime.lassoSlot) {
                client.execute { FailsafeManager.selectHotbarSlot(client, runtime.lassoSlot) }
                if (inventory.getSelected() != runtime.lassoSlot) return
            }
            advance(runtime, Stage.THROW, now, tick)
        }

        private fun handleThrow(client: Minecraft, context: Context, runtime: PestDestroyerRuntime, target: Entity, attached: Boolean, now: Long, tick: Int) {
            if (attached) { runtime.huntThrownAt = now; advance(runtime, Stage.REEL, now, tick); return }
            if (tick - runtime.huntStageEnteredTick < LASSO_SETTLE_TICKS) return
            val player = client.player ?: return
            if (player.distanceTo(target) > THROW_RANGE) {
                if (now - runtime.huntStageEnteredAt > THROW_APPROACH_TIMEOUT_MS) {
                    ClientUtils.sendDebugMessage("[PestHunting] Could not close on the pest. Moving on.")
                    abandonCatch(client, context, runtime, target)
                }
                return
            }
            if (waitForLanding(runtime, target, now) || !isAimedAtTarget(client, runtime, target, THROW_AIM_TOLERANCE_DEGREES)) return
            if (runtime.huntThrowCount >= AetherConfig.PEST_HUNTING_MAX_THROWS.get()) {
                ClientUtils.sendDebugMessage("[PestHunting] Lasso would not attach after ${runtime.huntThrowCount} throw(s). Moving on.")
                abandonCatch(client, context, runtime, target)
                return
            }
            runtime.huntThrowCount++
            ClientUtils.performUseClickNow()
            runtime.huntThrownAt = now
            ClientUtils.sendDebugMessage("[PestHunting] Threw lasso (attempt ${runtime.huntThrowCount}).")
            advance(runtime, Stage.REEL, now, tick)
        }

        private fun handleReel(client: Minecraft, context: Context, runtime: PestDestroyerRuntime, target: Entity, attached: Boolean, reelPromptUp: Boolean, now: Long, tick: Int) {
            if (!attached) {
                val quietFor = now - maxOf(runtime.huntThrownAt, runtime.huntLastAttachedAt)
                if (quietFor < if (runtime.huntEverAttached) DETACH_CONFIRM_MS else THROW_CONFIRM_MS) return
                if (runtime.huntThrowCount > AetherConfig.PEST_HUNTING_MAX_THROWS.get()) { abandonCatch(client, context, runtime, target); return }
                ClientUtils.sendDebugMessage(String.format("[PestHunting] Lasso off at %.1f blocks. Re-stunning.", horizontalDistanceTo(client, target)))
                runtime.huntThrownAt = 0L
                runtime.huntReelPromptLatched = false
                runtime.huntSwapReadyTick = 0
                runtime.huntStunDelivered = false
                runtime.huntStunHoldStartedAt = 0L
                runtime.huntDelayBeforeStunSwap = true
                advance(runtime, Stage.STUN, now, tick)
                return
            }
            if (!reelPromptUp) { runtime.huntReelPromptTicks = 0; return }
            if (runtime.huntReelPromptTicks < REEL_PROMPT_CONFIRM_TICKS || runtime.huntReelPromptLatched || now - runtime.huntLastReelClickAt < REEL_CLICK_COOLDOWN_MS) return
            if (!isAimedAtTarget(client, runtime, target, REEL_AIM_TOLERANCE_DEGREES)) return
            ClientUtils.performUseClickNow()
            runtime.huntReelCount++
            runtime.huntReelPromptLatched = true
            runtime.huntReelPromptArmed = false
            runtime.huntLastReelClickAt = now
            runtime.huntReelAwaitingResponseUntil = now + REEL_RESPONSE_WAIT_MS
            ClientUtils.sendDebugMessage("[PestHunting] Reeled (${runtime.huntReelCount}).")
        }

        private fun leashNeedsClosing(runtime: PestDestroyerRuntime, horizontal: Double, now: Long): Boolean {
            if (horizontal <= LEASHED_FOLLOW_DISTANCE + FOLLOW_BAND) {
                runtime.huntLeashStretchSince = 0L
                return runtime.huntFollowMove > 0
            }
            if (runtime.huntLeashStretchSince == 0L) runtime.huntLeashStretchSince = now
            return runtime.huntFollowMove > 0 || now - runtime.huntLeashStretchSince >= LEASH_CLOSE_DEBOUNCE_MS
        }

        private fun finishCatch(client: Minecraft, context: Context, runtime: PestDestroyerRuntime, target: Entity?, caught: Boolean, reason: String) {
            val reels = runtime.huntReelCount
            clearHunt(client, runtime)
            if (target != null) {
                context.markKilled(target)
                if (caught && context.recordTrackedPestKill(client, target)) return
            }
            runtime.currentTarget = null
            ClientUtils.sendDebugMessage("[PestHunting] Catch finished ($reason) after $reels reel(s).")
            if (!context.switchToNextQueuedTarget(client)) context.setState(PestDestroyer.State.CHECK_NEXT)
        }

        private fun abandonCatch(client: Minecraft, context: Context, runtime: PestDestroyerRuntime, target: Entity?) {
            clearHunt(client, runtime)
            if (target != null) context.deferTarget(target)
            runtime.currentTarget = null
            if (!context.switchToNextQueuedTarget(client)) context.setState(PestDestroyer.State.CHECK_NEXT)
        }

        private fun maintainAim(client: Minecraft, runtime: PestDestroyerRuntime, target: Entity, tolerance: Float, precise: Boolean, now: Long) {
            if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) return
            val aim = aimPoint(client, runtime, target)
            val player = client.player ?: return
            if (player.eyePosition.distanceTo(aim) < MIN_AIM_DISTANCE) return
            if (!precise && !needsLooseCorrection(client, runtime, aim)) return
            RotationManager.trackRotation(client, wander(client, steerPoint(runtime, aim, now), wanderDegrees(tolerance), now),
                AetherConfig.PEST_HUNTING_TRACKING_SMOOTHING_MS.get(), AetherConfig.PEST_HUNTING_MAX_TURN_SPEED.get())
        }

        private fun wanderDegrees(tolerance: Float): Double = minOf(tolerance * AIM_WANDER_FRACTION, MAX_AIM_WANDER_DEGREES)

        private fun needsLooseCorrection(client: Minecraft, runtime: PestDestroyerRuntime, aim: Vec3): Boolean {
            if (runtime.huntAimCorrecting) {
                if (!PestTargetController.isLookingAt(client, aim, LOOSE_AIM_SETTLE_DEGREES)) return true
                runtime.huntAimCorrecting = false
                return false
            }
            if (PestTargetController.isLookingAt(client, aim, LOOSE_AIM_TRIGGER_DEGREES)) return false
            runtime.huntAimCorrecting = true
            return true
        }

        @JvmStatic
        fun huntAimTolerance(attached: Boolean, stage: Stage): Float =
            if (attached || stage == Stage.REEL) ATTACHED_AIM_TOLERANCE_DEGREES else THROW_AIM_TOLERANCE_DEGREES

        private fun resolveFocus(runtime: PestDestroyerRuntime, next: Entity?, now: Long): Entity? {
            val held = runtime.huntFocus
            if (next == null || next == held) {
                runtime.huntPendingFocus = null
                runtime.huntPendingFocusSince = 0L
                return next ?: held
            }
            if (runtime.huntPendingFocus != next) {
                runtime.huntPendingFocus = next
                runtime.huntPendingFocusSince = now
            }
            if (!adoptsPendingFocus(held != null && !isGone(held), runtime.huntPendingFocusSince, now)) return held
            runtime.huntFocus = next
            runtime.huntPendingFocus = null
            runtime.huntPendingFocusSince = 0L
            return next
        }

        @JvmStatic
        fun adoptsPendingFocus(heldUsable: Boolean, pendingSince: Long, now: Long): Boolean =
            !heldUsable || pendingSince != 0L && now - pendingSince >= FOCUS_SWITCH_DEBOUNCE_MS

        private fun updateAimBlend(client: Minecraft, runtime: PestDestroyerRuntime, focus: Entity?, armed: Boolean, now: Long) {
            if (focus == null) return
            if (!armed) {
                runtime.huntAimBlendOffset = null
                runtime.huntAimBlendStartedAt = 0L
            }
            val point = aimPoint(client, runtime, focus)
            if (runtime.huntAimFocusId != focus.id) {
                val lastAimPoint = runtime.huntLastAimPoint
                if (armed && runtime.huntAimFocusId != -1 && lastAimPoint != null) {
                    runtime.huntAimBlendOffset = lastAimPoint.subtract(point)
                    runtime.huntAimBlendStartedAt = now
                }
                runtime.huntAimFocusId = focus.id
            }
            runtime.huntLastAimPoint = point
        }

        private fun steerPoint(runtime: PestDestroyerRuntime, aim: Vec3, now: Long): Vec3 {
            if (runtime.huntAimBlendStartedAt == 0L || runtime.huntAimBlendOffset == null) return aim
            val remaining = aimBlendRemaining(now - runtime.huntAimBlendStartedAt)
            if (remaining <= 0.0) {
                runtime.huntAimBlendOffset = null
                runtime.huntAimBlendStartedAt = 0L
                return aim
            }
            val offset = runtime.huntAimBlendOffset ?: return aim
            return aim.add(offset.scale(remaining))
        }

        @JvmStatic
        fun aimBlendRemaining(elapsedMs: Long): Double {
            if (elapsedMs >= AIM_BLEND_MS) return 0.0
            val t = maxOf(0.0, elapsedMs.toDouble() / AIM_BLEND_MS)
            return 1.0 - t * t * (3.0 - 2.0 * t)
        }

        private fun wander(client: Minecraft, point: Vec3, degrees: Double, now: Long): Vec3 {
            val player = client.player ?: return point
            val toPoint = point.subtract(player.eyePosition)
            val distance = toPoint.length()
            if (degrees <= 0.0 || distance < 1.0e-3) return point
            val seconds = now / 1000.0
            val reach = distance * Math.tan(Math.toRadians(degrees))
            val lateral = reach * (Math.sin(seconds * 1.30) * 0.6 + Math.sin(seconds * 0.47) * 0.4)
            val vertical = reach * (Math.sin(seconds * 0.83) + 1.0) * 0.5
            val horizontal = kotlin.math.sqrt(toPoint.x * toPoint.x + toPoint.z * toPoint.z)
            if (horizontal < 1.0e-3) return point.add(0.0, vertical, 0.0)
            val right = Vec3(-toPoint.z / horizontal, 0.0, toPoint.x / horizontal)
            return point.add(right.scale(lateral)).add(0.0, vertical, 0.0)
        }

        private fun aimPoint(client: Minecraft, runtime: PestDestroyerRuntime, target: Entity): Vec3 =
            if (usesVacuumAim(runtime)) PestCombatCoordinator.buildVacuumAimTarget(client, target) else huntAimPoint(client, target)

        private fun usesVacuumAim(runtime: PestDestroyerRuntime): Boolean = runtime.huntStage == Stage.STUN && !runtime.huntStunDelivered && AetherConfig.PEST_HUNTING_VACUUM_STUN.get() && runtime.stunVacuumSlot >= 0
        private fun nextVacuumSwapDelayTicks(): Int = ThreadLocalRandom.current().nextInt(MIN_VACUUM_SWAP_TICKS, MAX_VACUUM_SWAP_TICKS + 1)
        private fun nextRetrySwapDelayTicks(): Int = ThreadLocalRandom.current().nextInt(MIN_RETRY_SWAP_TICKS, MAX_RETRY_SWAP_TICKS + 1)

        private fun maintainFollowDistance(client: Minecraft, runtime: PestDestroyerRuntime, target: Entity, followDistance: Double,
                                           allowTranslation: Boolean, adjustAltitude: Boolean, allowBackOff: Boolean) {
            val horizontal = horizontalDistanceTo(client, target)
            val follow = minOf(followDistance, LASSO_INTERACTION_RANGE - 0.75)
            val projected = horizontal - maxOf(0.0, runtime.huntCloseRate) * COAST_DRAG_TICKS
            val offAxis = !facesTarget(client, target, FOLLOW_YAW_CONE_DEGREES)
            val followDirection = followDirection(horizontal, projected, follow, offAxis, allowTranslation, allowBackOff, runtime.huntFollowMove)
            runtime.huntFollowMove = followDirection
            ClientUtils.setKeyMappingState(client.options.keyUp, followDirection > 0)
            ClientUtils.setKeyMappingState(client.options.keyDown, followDirection < 0)
            ClientUtils.setKeyMappingState(client.options.keyLeft, false)
            ClientUtils.setKeyMappingState(client.options.keyRight, false)
            ClientUtils.setKeyMappingState(client.options.keySprint, followDirection > 0 && projected > follow + SPRINT_GAP)
            val player = client.player ?: return
            val heightGap = target.y - player.y
            val flying = player.abilities.flying
            ClientUtils.setKeyMappingState(client.options.keyJump, adjustAltitude && flying && heightGap > VERTICAL_ALIGN_TOLERANCE)
            ClientUtils.setKeyMappingState(client.options.keyShift, adjustAltitude && flying && heightGap < -VERTICAL_ALIGN_TOLERANCE)
        }

        @JvmStatic
        fun followDirection(horizontal: Double, projected: Double, follow: Double, offAxis: Boolean, allowTranslation: Boolean, allowBackOff: Boolean, previous: Int): Int {
            if (!allowTranslation || offAxis) return 0
            if (previous > 0) return if (projected > follow) 1 else 0
            if (previous < 0) return if (allowBackOff && horizontal < follow) -1 else 0
            if (projected > follow + FOLLOW_BAND) return 1
            val backOffThreshold = maxOf(MIN_AIM_DISTANCE + 0.25, follow - FOLLOW_BAND)
            return if (allowBackOff && horizontal < backOffThreshold) -1 else 0
        }
        @JvmStatic
        fun facesTarget(offset: Vec3, yaw: Float): Boolean {
            val horizontal = offset.horizontalDistance()
            if (horizontal < 0.25) return false
            val angle = Math.toRadians(yaw.toDouble())
            return (-offset.x * Math.sin(angle) + offset.z * Math.cos(angle)) / horizontal >= 0.5
        }

        @JvmStatic
        fun followDistance(configured: Double, range: Double, heightDifference: Double): Double {
            val usableRange = maxOf(0.0, range - 1.0)
            return minOf(configured, Math.sqrt(maxOf(0.0, usableRange * usableRange - heightDifference * heightDifference)))
        }



        private fun updateClosingRate(runtime: PestDestroyerRuntime, target: Entity, horizontal: Double) {
            if (runtime.huntCloseRateTargetId != target.id || runtime.huntLastHorizontal.isNaN()) {
                runtime.huntCloseRateTargetId = target.id
                runtime.huntLastHorizontal = horizontal
                runtime.huntCloseRate = 0.0
                return
            }
            val sample = Mth.clamp(runtime.huntLastHorizontal - horizontal, -MAX_CLOSING_SAMPLE, MAX_CLOSING_SAMPLE)
            runtime.huntLastHorizontal = horizontal
            runtime.huntCloseRate = runtime.huntCloseRate * CLOSING_RATE_SMOOTHING + sample * (1.0 - CLOSING_RATE_SMOOTHING)
        }

        private fun facesTarget(client: Minecraft, target: Entity, maxYawError: Double): Boolean {
            val player = client.player ?: return false
            val dx = target.x - player.x
            val dz = target.z - player.z
            if (dx * dx + dz * dz < 1.0e-4) return true
            val desiredYaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
            return kotlin.math.abs(Mth.wrapDegrees(desiredYaw - player.yRot)) <= maxYawError
        }

        private fun horizontalDistanceTo(client: Minecraft, target: Entity): Double {
            val player = client.player ?: return 0.0
            val dx = player.x - target.x
            val dz = player.z - target.z
            return kotlin.math.sqrt(dx * dx + dz * dz)
        }

        private fun isAimedAtTarget(client: Minecraft, runtime: PestDestroyerRuntime, target: Entity, tolerance: Float): Boolean =
            PestTargetController.isLookingAt(client, aimPoint(client, runtime, target), tolerance)

        private fun isGone(target: Entity?): Boolean = target == null || target.isRemoved || target is LivingEntity && target.isDeadOrDying

        private fun findLassoedPest(client: Minecraft, target: Entity?): Entity? {
            if (client.level == null) return null
            var closest: Entity? = null
            var closestDistance = Double.MAX_VALUE
            val level = client.level ?: return null
            for (entity in level.entitiesForRendering()) {
                if (!isPestMob(entity) || !holdsOurLeash(client, entity)) continue
                val distance = if (target == null) 0.0 else entity.distanceToSqr(target)
                if (target == null || distance < closestDistance) { closest = entity; closestDistance = distance }
            }
            return if (target == null || closestDistance <= 36.0) closest else null
        }

        private fun probeCatchState(client: Minecraft, runtime: PestDestroyerRuntime, focus: Entity?, lassoed: Entity?, now: Long) {
            if (focus == null || client.level == null || runtime.huntThrownAt == 0L || runtime.huntThrowCount > 1) return
            val since = now - runtime.huntThrownAt
            val player = client.player ?: return
            if (since > CATCH_PROBE_WINDOW_MS || player.tickCount % CATCH_PROBE_INTERVAL_TICKS != 0) return
            ClientUtils.sendDebugMessage("[PestHunting][probe] +${since}ms pest=${describeEntity(client, focus, focus)} lassoed=${lassoed?.id ?: "none"}")
            val box = AABB.ofSize(focus.position(), CATCH_PROBE_SIZE, CATCH_PROBE_SIZE, CATCH_PROBE_SIZE)
            val level = client.level ?: return
            for (entity in level.getEntities(player, box)) if (entity != focus) ClientUtils.sendDebugMessage("[PestHunting][probe]   ${describeEntity(client, entity, focus)}")
        }

        private fun describeEntity(client: Minecraft, entity: Entity, focus: Entity): String {
            val text = StringBuilder(entity.javaClass.simpleName).append('#').append(entity.id)
                .append(String.format(" d=%.1f", kotlin.math.sqrt(entity.distanceToSqr(focus))))
            val custom = entity.customName
            if (custom != null) text.append(" name='").append(stripFormatting(custom.string)).append('\'')
            if (entity is ArmorStand) {
                val head = entity.getItemBySlot(EquipmentSlot.HEAD)
                if (!head.isEmpty) text.append(" head=").append(head.item)
            }
            if (entity is Leashable) {
                val holder = entity.leashHolder
                text.append(" leash=").append(if (holder == null) "none" else if (holder == client.player) "player" else holder.id)
            }
            val vehicle = entity.vehicle
            if (vehicle != null) text.append(" riding#").append(vehicle.id)
            if (entity.isInvisible) text.append(" invisible")
            return text.toString()
        }

        private fun isPestMob(entity: Entity): Boolean = entity is Bat || entity is Silverfish
        private fun holdsOurLeash(client: Minecraft, entity: Entity): Boolean = entity is Leashable && entity.leashHolder == client.player

        @JvmStatic
        fun releaseStunVacuum(runtime: PestDestroyerRuntime) {
            if (runtime.huntStunHoldStartedAt == 0L) return
            runtime.huntStunHoldStartedAt = 0L
            ClientUtils.endUseHold()
        }

        private fun holdLassoPosition(client: Minecraft) {
            ClientUtils.setKeyMappingState(client.options.keyUp, false)
            ClientUtils.setKeyMappingState(client.options.keyDown, false)
            ClientUtils.setKeyMappingState(client.options.keyLeft, false)
            ClientUtils.setKeyMappingState(client.options.keyRight, false)
            ClientUtils.setKeyMappingState(client.options.keySprint, false)
            ClientUtils.setKeyMappingState(client.options.keyJump, false)
            ClientUtils.setKeyMappingState(client.options.keyShift, false)
        }

        private fun hasReelPrompt(client: Minecraft, target: Entity?): Boolean {
            if (findMarker(client, target, true) != null) return true
            if (client.level == null || target == null) return false
            val catchArea = AABB.ofSize(target.position(), 16.0, 10.0, 16.0)
            val level = client.level ?: return false
            for (marker in level.getEntitiesOfClass(ArmorStand::class.java, catchArea)) if (isReelPrompt(plainMarkerName(marker))) return true
            return false
        }

        private fun plainMarkerName(marker: ArmorStand): String {
            val custom = marker.customName
            if (custom != null) return stripFormatting(custom.string)
            val plain = stripFormatting(marker.name.string)
            val typeName = stripFormatting(marker.type.description.string)
            return if (plain == typeName) "" else plain
        }

        @JvmStatic fun stripFormatting(text: String): String = text.replace(Regex("(?i)\\u00A7."), "").trim()
        @JvmStatic fun isReelPrompt(plainName: String): Boolean = plainName.uppercase(Locale.ROOT).contains(REEL_PROMPT)

        private fun waitForLanding(runtime: PestDestroyerRuntime, target: Entity, now: Long): Boolean {
            if (target !is Silverfish) { runtime.huntLandingWaitStartedAt = 0L; return false }
            val y = target.y
            val hopping = !runtime.huntTargetY.isNaN() && kotlin.math.abs(y - runtime.huntTargetY) > LANDING_Y_EPSILON
            runtime.huntTargetY = y
            if (target.onGround() || !hopping) { runtime.huntLandingWaitStartedAt = 0L; return false }
            if (runtime.huntLandingWaitStartedAt == 0L) runtime.huntLandingWaitStartedAt = now
            return now - runtime.huntLandingWaitStartedAt < LANDING_WAIT_TIMEOUT_MS
        }

        private fun huntAimPoint(client: Minecraft, pest: Entity): Vec3 {
            if (pest is Bat || pest is Silverfish) return abovePest(pest)
            val healthBar = findMarker(client, pest, false)
            return healthBar?.position() ?: abovePest(pest)
        }

        @JvmStatic
        fun aimClearance(entityId: Int): Double {
            val span = MAX_AIM_CLEARANCE - MIN_AIM_CLEARANCE
            val bucket = Math.floorMod(entityId, AIM_CLEARANCE_BUCKETS)
            return MIN_AIM_CLEARANCE + span * bucket / (AIM_CLEARANCE_BUCKETS - 1)
        }

        private fun abovePest(pest: Entity): Vec3 = pest.position().add(0.0, pest.bbHeight + aimClearance(pest.id), 0.0)

        private fun findMarker(client: Minecraft, pest: Entity?, reelPrompt: Boolean): ArmorStand? {
            if (client.level == null || pest == null) return null
            val searchBox = AABB.ofSize(pest.position().add(0.0, MARKER_SEARCH_HEIGHT_OFFSET, 0.0), MARKER_SEARCH_SIZE, MARKER_SEARCH_SIZE, MARKER_SEARCH_SIZE)
            var closest: ArmorStand? = null
            var closestDistance = Double.MAX_VALUE
            val level = client.level ?: return null
            for (marker in level.getEntitiesOfClass(ArmorStand::class.java, searchBox)) {
                val plain = plainMarkerName(marker)
                if (plain.isEmpty() || isReelPrompt(plain) != reelPrompt || !ridesOn(marker, pest, if (reelPrompt) REEL_MARKER_MAX_HORIZONTAL else MARKER_MAX_HORIZONTAL)) continue
                val distance = marker.distanceToSqr(pest)
                if (distance < closestDistance) { closestDistance = distance; closest = marker }
            }
            return closest
        }

        private fun ridesOn(marker: ArmorStand, pest: Entity, maxHorizontal: Double): Boolean {
            val dx = marker.x - pest.x
            val dz = marker.z - pest.z
            val dy = marker.y - pest.y
            return dx * dx + dz * dz <= maxHorizontal * maxHorizontal && dy >= -MARKER_MAX_HEIGHT_BELOW && dy <= MARKER_MAX_HEIGHT_ABOVE
        }

        private fun advance(runtime: PestDestroyerRuntime, stage: Stage, now: Long, tick: Int) {
            runtime.huntStage = stage
            runtime.huntStageEnteredAt = now
            runtime.huntStageEnteredTick = tick
        }
    }
}
