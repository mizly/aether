package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.ProgrammaticAttackTracker;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class PestHuntingController {
   private static final String REEL_PROMPT = "REEL";
   private static final double MARKER_SEARCH_SIZE = 6.0;
   private static final double MARKER_SEARCH_HEIGHT_OFFSET = 1.5;
   private static final double MARKER_MAX_HORIZONTAL = 2.0;
   private static final double REEL_MARKER_MAX_HORIZONTAL = 3.0;
   private static final int REEL_PROMPT_CONFIRM_TICKS = 2;
   private static final double MARKER_MAX_HEIGHT_ABOVE = 3.5;
   private static final double MARKER_MAX_HEIGHT_BELOW = 1.0;
   private static final int VACUUM_SETTLE_TICKS = 1;
   private static final int MIN_VACUUM_SWAP_TICKS = 1;
   private static final int MAX_VACUUM_SWAP_TICKS = 5;
   private static final int LASSO_SETTLE_TICKS = 0;
   private static final long THROW_CONFIRM_MS = 1500L;
   private static final long REEL_CLICK_COOLDOWN_MS = 250L;
   private static final long DETACH_CONFIRM_MS = 2500L;
   private static final float THROW_AIM_TOLERANCE_DEGREES = 10.0F;
   private static final float REEL_AIM_TOLERANCE_DEGREES = 25.0F;
   private static final long THROW_AIM_DURATION_MS = 60L;
   private static final float TRACK_REAIM_TOLERANCE_DEGREES = 12.0F;
   private static final float AIM_HUMANIZE_DEGREES = 6.0F;
   private static final long POST_REEL_AIM_HOLD_MS = 350L;
   private static final long POST_THROW_AIM_HOLD_MS = 700L;
   private static final long REEL_RESPONSE_WAIT_MS = 500L;
   private static final long REEL_STUCK_PROMPT_RETRY_MS = 900L;
   private static final long REEL_OVERLAY_SIGNAL_GRACE_MS = 300L;
   private static final long REEL_MISSING_WATCHDOG_MS = 6000L;
   private static final long REEL_WATCHDOG_CLICK_COOLDOWN_MS = 3000L;
   private static final long LANDING_WAIT_TIMEOUT_MS = 250L;
   private static final double LANDING_Y_EPSILON = 0.05;
   private static final double MIN_AIM_DISTANCE = 1.5;
   private static final double CLOSE_PASS_DISTANCE = 3.0;
   private static final float CLOSE_PASS_TURN_DEGREES = 60.0F;
   private static final double HANDOFF_SLACK = 2.0;
   private static final double REPATH_DISTANCE = 16.0;
   private static final double VERTICAL_ALIGN_TOLERANCE = 1.0;
   private static final int MAX_STAGES_PER_TICK = 3;

   private PestHuntingController() {
   }

   static boolean isEnabled() {
      return AetherConfig.PEST_HUNTING.get();
   }

   static boolean shouldLassoTarget(Minecraft var0, Entity var1) {
      return PestHuntingPolicy.shouldLasso(var0, var1);
   }

   static double handoffRange(Minecraft var0, Entity var1, double var2) {
      return shouldLassoTarget(var0, var1) ? AetherConfig.PEST_HUNTING_FOLLOW_DISTANCE.get().floatValue() + 2.0 : var2;
   }

   static void beginHunt(PestDestroyerRuntime var0, Minecraft var1) {
      var0.huntStage = PestHuntingController.Stage.STUN;
      var0.huntStageEnteredAt = System.currentTimeMillis();
      var0.huntStartedAt = System.currentTimeMillis();
      var0.huntThrowCount = 0;
      var0.huntReelCount = 0;
      var0.huntThrownAt = 0L;
      var0.huntLastReelClickAt = 0L;
      var0.huntLastAttachedAt = 0L;
      var0.huntAttachedSince = 0L;
      var0.huntReelSignalAt = 0L;
      var0.huntEverAttached = false;
      var0.huntReelPromptLatched = false;
      var0.huntReelPromptTicks = 0;
      var0.huntReelAwaitingResponseUntil = 0L;
      var0.huntSwapReadyTick = 0;
      var0.huntDelayBeforeStunSwap = false;
      var0.huntStageEnteredTick = 0;
      var0.huntLandingWaitStartedAt = 0L;
      var0.huntTargetY = Double.NaN;
      var0.huntCaughtSignal = false;
      var0.lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(var1);
   }

   static void clearHunt(Minecraft var0, PestDestroyerRuntime var1) {
      RotationManager.cancelRotation();
      var1.huntStage = PestHuntingController.Stage.STUN;
      var1.huntStartedAt = 0L;
      var1.huntStageEnteredAt = 0L;
      var1.huntThrowCount = 0;
      var1.huntReelCount = 0;
      var1.huntThrownAt = 0L;
      var1.huntLastReelClickAt = 0L;
      var1.huntLastAttachedAt = 0L;
      var1.huntAttachedSince = 0L;
      var1.huntReelSignalAt = 0L;
      var1.huntEverAttached = false;
      var1.huntReelPromptLatched = false;
      var1.huntReelPromptTicks = 0;
      var1.huntReelAwaitingResponseUntil = 0L;
      var1.huntSwapReadyTick = 0;
      var1.huntDelayBeforeStunSwap = false;
      var1.huntStageEnteredTick = 0;
      var1.huntLandingWaitStartedAt = 0L;
      var1.huntTargetY = Double.NaN;
      var1.huntCaughtSignal = false;
      var1.lassoSlot = -1;
      if (var0 != null && var0.options != null) {
         ClientUtils.setKeyMappingState(var0.options.keyUse, false);
         ClientUtils.setKeyMappingState(var0.options.keyAttack, false);
         ClientUtils.setKeyMappingState(var0.options.keyUp, false);
         ClientUtils.setKeyMappingState(var0.options.keyDown, false);
         ClientUtils.setKeyMappingState(var0.options.keySprint, false);
         ClientUtils.setKeyMappingState(var0.options.keyJump, false);
         ClientUtils.setKeyMappingState(var0.options.keyShift, false);
      }
   }

   static void onPestCaught(PestDestroyerRuntime var0) {
      var0.huntCaughtSignal = true;
   }

   static void onOverlayMessage(PestDestroyerRuntime var0, String var1) {
      if (var1 != null && isReelPrompt(stripFormatting(var1))) {
         var0.huntReelSignalAt = System.currentTimeMillis();
      }
   }

   static void handleHuntPest(Minecraft var0, PestHuntingController.Context var1) {
      PestDestroyerRuntime var2 = var1.runtime();
      Entity var3 = var2.currentTarget;
      if (var2.huntCaughtSignal) {
         finishCatch(var0, var1, var2, var3, true, "caught");
      } else {
         Entity var4 = findLassoedPest(var0, var3);
         if (isGone(var3)) {
            if (var4 == null) {
               finishCatch(var0, var1, var2, var3, var2.huntEverAttached, "target gone");
               return;
            }

            var2.currentTarget = var4;
            var3 = var4;
         }

         if (var2.lassoSlot == -1) {
            var2.lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(var0);
            if (var2.lassoSlot == -1) {
               ClientUtils.sendMessage("\u00a7cNo lasso found for this lasso-routed pest. Leaving it untouched and stopping the hunt.", false);
               clearHunt(var0, var2);
               var1.setState(PestDestroyer.State.FINISH);
               return;
            }
         }

         if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop();
         }

         if (!var0.player.getAbilities().flying && var0.player.getAbilities().mayfly) {
            clearHunt(var0, var2);
            var1.setState(PestDestroyer.State.FLY_UP);
         } else {
            long var5 = System.currentTimeMillis();
            if (var5 - var2.huntStartedAt > AetherConfig.PEST_HUNTING_TIMEOUT_MS.get().intValue()) {
               ClientUtils.sendDebugMessage("[PestHunting] Catch timed out. Moving on.");
               abandonCatch(var0, var1, var2, var3);
            } else {
               Entity var7 = var4 != null ? var4 : var3;
               boolean var8 = var4 != null;
               if (var8) {
                  var2.huntEverAttached = true;
                  var2.huntLastAttachedAt = var5;
                  if (var2.huntAttachedSince == 0L) {
                     var2.huntAttachedSince = var5;
                  }
               } else {
                  var2.huntAttachedSince = 0L;
               }

               boolean var9 = var5 - var2.huntReelSignalAt <= 300L;
               boolean var10 = var8 && (hasReelPrompt(var0, var7) || var9);
               if (var10) {
                  var2.huntReelPromptTicks++;
                  var2.huntReelPromptClearTicks = 0;
                  if (var2.huntReelPromptLatched && var5 >= var2.huntReelAwaitingResponseUntil && var5 - var2.huntLastReelClickAt >= 86400000L) {
                     var2.huntReelPromptLatched = false;
                     var2.huntReelPromptArmed = true;
                  }
               } else {
                  var2.huntReelPromptTicks = 0;
                  var2.huntReelPromptClearTicks++;
                  if (var5 >= var2.huntReelAwaitingResponseUntil && var2.huntReelPromptClearTicks >= 3) {
                     var2.huntReelPromptArmed = true;
                     var2.huntReelPromptLatched = false;
                  }
               }

               boolean var11 = var8 && var2.huntReelPromptTicks >= 2 && var2.huntReelPromptArmed && !var2.huntReelPromptLatched;
               boolean var12 = var8 || var2.huntStage != PestHuntingController.Stage.REEL;
               maintainAim(var0, var2, var7, var12, var11, var5);
               ProgrammaticAttackTracker.setHeld(var0.options.keyAttack, false);
               ClientUtils.setKeyMappingState(var0.options.keyAttack, false);
               ClientUtils.discardQueuedClicks(var0.options.keyAttack);
               boolean var13 = var11 || !var8 && var2.huntStage != PestHuntingController.Stage.REEL;
               if (var8) {
                  holdLassoPosition(var0);
               } else {
                  maintainFollowDistance(var0, var7, !var13);
               }

               if (var2.huntStage != PestHuntingController.Stage.REEL && !var8) {
                  double var14 = horizontalDistanceTo(var0, var7);
                  if (var14 > 16.0) {
                     ClientUtils.sendDebugMessage("[PestHunting] Target drifted out of reach. Re-approaching.");
                     clearHunt(var0, var2);
                     PestTargetController.startPathToPest(var0, var7);
                     var1.setState(PestDestroyer.State.FLY_TO_PEST);
                     return;
                  }

                  if (var14 > AetherConfig.PEST_HUNTING_MAX_DISTANCE.get().floatValue()) {
                     return;
                  }
               }

               int var17 = var0.player.tickCount;

               for (int var15 = 0; var15 < 3; var15++) {
                  PestHuntingController.Stage var16 = var2.huntStage;
                  switch (var2.huntStage) {
                     case STUN:
                        handleStun(var0, var2, var7, var5, var17);
                        break;
                     case SWAP_TO_LASSO:
                        handleSwapToLasso(var0, var2, var5, var17);
                        break;
                     case THROW:
                        handleThrow(var0, var1, var2, var7, var8, var5, var17);
                        break;
                     case REEL:
                        handleReel(var0, var1, var2, var7, var8, var10, var5, var17);
                  }

                  if (var2.huntStage.ordinal() <= var16.ordinal() || var2.state != PestDestroyer.State.HUNT_PEST) {
                     return;
                  }
               }
            }
         }
      }
   }

   private static void handleStun(Minecraft var0, PestDestroyerRuntime var1, Entity var2, long var3, int var5) {
      if (!(horizontalDistanceTo(var0, var2) > 64.0)) {
         if (AetherConfig.PEST_HUNTING_VACUUM_STUN.get() && var1.stunVacuumSlot >= 0) {
            if (var1.huntDelayBeforeStunSwap) {
               if (var1.huntSwapReadyTick == 0) {
                  var1.huntSwapReadyTick = var5 + nextVacuumSwapDelayTicks();
               }

               if (var5 < var1.huntSwapReadyTick) {
                  return;
               }

               var1.huntDelayBeforeStunSwap = false;
               var1.huntSwapReadyTick = 0;
            }

            AccessorInventory var6 = (AccessorInventory)var0.player.getInventory();
            if (var6.getSelected() != var1.stunVacuumSlot) {
               var0.execute(() -> FailsafeManager.selectHotbarSlot(var0, var1.stunVacuumSlot));
               var1.huntStageEnteredTick = var5;
            } else if (var5 - var1.huntStageEnteredTick >= 1) {
               if (var1.huntSwapReadyTick == 0) {
                  if (!waitForLanding(var1, var2, var3) && isAimedAtTarget(var0, var1, var2, 10.0F)) {
                     ClientUtils.performUseClickInstant();
                     var1.huntSwapReadyTick = var5 + nextVacuumSwapDelayTicks();
                     ClientUtils.sendDebugMessage("[PestHunting] Stunned with vacuum.");
                  }
               } else {
                  if (var5 >= var1.huntSwapReadyTick) {
                     advance(var1, PestHuntingController.Stage.SWAP_TO_LASSO, var3, var5);
                  }
               }
            }
         } else {
            advance(var1, PestHuntingController.Stage.SWAP_TO_LASSO, var3, var5);
         }
      }
   }

   private static void handleSwapToLasso(Minecraft var0, PestDestroyerRuntime var1, long var2, int var4) {
      AccessorInventory var5 = (AccessorInventory)var0.player.getInventory();
      if (var5.getSelected() != var1.lassoSlot) {
         var0.execute(() -> FailsafeManager.selectHotbarSlot(var0, var1.lassoSlot));
         if (var5.getSelected() != var1.lassoSlot) {
            return;
         }
      }

      advance(var1, PestHuntingController.Stage.THROW, var2, var4);
   }

   private static void handleThrow(
      Minecraft var0, PestHuntingController.Context var1, PestDestroyerRuntime var2, Entity var3, boolean var4, long var5, int var7
   ) {
      if (!(horizontalDistanceTo(var0, var3) > 64.0)) {
         if (var4) {
            var2.huntThrownAt = var5;
            advance(var2, PestHuntingController.Stage.REEL, var5, var7);
         } else if (var7 - var2.huntStageEnteredTick >= 0) {
            if (!waitForLanding(var2, var3, var5) && isAimedAtTarget(var0, var2, var3, 10.0F)) {
               if (var2.huntThrowCount >= AetherConfig.PEST_HUNTING_MAX_THROWS.get()) {
                  ClientUtils.sendDebugMessage("[PestHunting] Lasso would not attach after " + var2.huntThrowCount + " throw(s). Moving on.");
                  abandonCatch(var0, var1, var2, var3);
               } else {
                  var2.huntThrowCount++;
                  ClientUtils.performUseClickNow();
                  var2.huntThrownAt = var5;
                  ClientUtils.sendDebugMessage("[PestHunting] Threw lasso (attempt " + var2.huntThrowCount + ").");
                  advance(var2, PestHuntingController.Stage.REEL, var5, var7);
               }
            }
         }
      }
   }

   private static void handleReel(
      Minecraft var0, PestHuntingController.Context var1, PestDestroyerRuntime var2, Entity var3, boolean var4, boolean var5, long var6, int var8
   ) {
      if (!var4) {
         long var9 = var6 - Math.max(var2.huntThrownAt, var2.huntLastAttachedAt);
         if (var9 >= (var2.huntEverAttached ? 2500L : 900L)) {
            if (var2.huntThrowCount > AetherConfig.PEST_HUNTING_MAX_THROWS.get()) {
               abandonCatch(var0, var1, var2, var3);
            } else {
               ClientUtils.sendDebugMessage("[PestHunting] Lasso off. Re-stunning.");
               var2.huntReelPromptLatched = false;
               var2.huntSwapReadyTick = 0;
               var2.huntDelayBeforeStunSwap = true;
               advance(var2, PestHuntingController.Stage.STUN, var6, var8);
            }
         }
      } else if (!var5) {
         var2.huntReelPromptTicks = 0;
         if (var2.huntAttachedSince > 0L
            && var6 - var2.huntAttachedSince >= 86400000L
            && var6 - var2.huntLastReelClickAt >= 86400000L
            && isAimedAtTarget(var0, var2, var3, 25.0F)) {
            ClientUtils.performUseClickNow();
            var2.huntReelCount++;
            var2.huntLastReelClickAt = var6;
            var2.huntReelAwaitingResponseUntil = var6 + 500L;
            var2.huntAttachedSince = var6;
            ClientUtils.sendDebugMessage("[PestHunting] REEL prompt missing for 6s; sent one guarded recovery click.");
         }
      } else if (var2.huntReelPromptTicks >= 2 && !var2.huntReelPromptLatched && var6 - var2.huntLastReelClickAt >= 250L) {
         if (isAimedAtTarget(var0, var2, var3, 25.0F)) {
            ClientUtils.performUseClickNow();
            var2.huntReelCount++;
            var2.huntReelPromptLatched = true;
            var2.huntReelPromptArmed = false;
            var2.huntLastReelClickAt = var6;
            var2.huntReelAwaitingResponseUntil = var6 + 500L;
            ClientUtils.sendDebugMessage("[PestHunting] Reeled (" + var2.huntReelCount + ").");
         }
      }
   }

   private static void finishCatch(Minecraft var0, PestHuntingController.Context var1, PestDestroyerRuntime var2, Entity var3, boolean var4, String var5) {
      int var6 = var2.huntReelCount;
      clearHunt(var0, var2);
      if (var3 != null) {
         var1.markKilled(var3);
         if (var4 && var1.recordTrackedPestKill(var0, var3)) {
            return;
         }
      }

      var2.currentTarget = null;
      ClientUtils.sendDebugMessage("[PestHunting] Catch finished (" + var5 + ") after " + var6 + " reel(s).");
      if (!var1.switchToNextQueuedTarget(var0)) {
         var1.setState(PestDestroyer.State.CHECK_NEXT);
      }
   }

   private static void abandonCatch(Minecraft var0, PestHuntingController.Context var1, PestDestroyerRuntime var2, Entity var3) {
      clearHunt(var0, var2);
      if (var3 != null) {
         var1.markKilled(var3);
      }

      var2.currentTarget = null;
      if (!var1.switchToNextQueuedTarget(var0)) {
         var1.setState(PestDestroyer.State.CHECK_NEXT);
      }
   }

   private static void maintainAim(Minecraft var0, PestDestroyerRuntime var1, Entity var2, boolean var3, boolean var4, long var5) {
      if (!FailsafeManager.shouldSuppressPestCleanerRotation(var0)) {
         if (var4 || !isPullInProgress(var1, var5)) {
            Vec3 var7 = var1.huntStage == PestHuntingController.Stage.STUN && AetherConfig.PEST_HUNTING_VACUUM_STUN.get() && var1.stunVacuumSlot >= 0
               ? PestCombatCoordinator.buildVacuumAimTarget(var0, var2)
               : huntAimPoint(var0, var2);
            double var8 = var0.player.getEyePosition().distanceTo(var7);
            if (!(var8 < 1.5)) {
               if (!(var8 < 1.5) || PestTargetController.isLookingAt(var0, var7, 60.0F)) {
                  if (var3) {
                     if (!PestTargetController.isLookingAt(var0, var7, 10.0F)) {
                        RotationManager.smoothForceRotation(var0, var7, 60L);
                     }
                  } else {
                     if (!PestTargetController.isLookingAt(var0, var7, 12.0F)) {
                        RotationManager.initiatePestRotation(var0, var7, AetherConfig.ROTATION_TIME.get().intValue(), 6.0F);
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean isPullInProgress(PestDestroyerRuntime var0, long var1) {
      return false;
   }

   private static int nextVacuumSwapDelayTicks() {
      return ThreadLocalRandom.current().nextInt(1, 6);
   }

   private static void maintainFollowDistance(Minecraft var0, Entity var1, boolean var2) {
      double var3 = horizontalDistanceTo(var0, var1);
      double var5 = 4.0;
      boolean var7 = PestCombatCoordinator.isOutsideForwardCone(var0, var1, 90.0);
      ClientUtils.setKeyMappingState(var0.options.keyUp, var3 > var5 + 0.75 && !var7);
      ClientUtils.setKeyMappingState(var0.options.keyDown, false);
      ClientUtils.setKeyMappingState(var0.options.keySprint, false);
      double var8 = var1.getY() - var0.player.getY();
      boolean var10 = var0.player.getAbilities().flying;
      ClientUtils.setKeyMappingState(var0.options.keyJump, true && var10 && var8 > 1.0);
      ClientUtils.setKeyMappingState(var0.options.keyShift, var2 && var10 && var8 < -1.0);
   }

   private static double horizontalDistanceTo(Minecraft var0, Entity var1) {
      double var2 = var0.player.getX() - var1.getX();
      double var4 = var0.player.getZ() - var1.getZ();
      return Math.sqrt(var2 * var2 + var4 * var4);
   }

   private static boolean isAimedAtTarget(Minecraft var0, PestDestroyerRuntime var1, Entity var2, float var3) {
      Vec3 var4 = var1.huntStage == PestHuntingController.Stage.STUN && AetherConfig.PEST_HUNTING_VACUUM_STUN.get() && var1.stunVacuumSlot >= 0
         ? PestCombatCoordinator.buildVacuumAimTarget(var0, var2)
         : huntAimPoint(var0, var2);
      return PestTargetController.isLookingAt(var0, var4, var3);
   }

   private static boolean isGone(Entity var0) {
      return var0 == null || var0.isRemoved() || var0 instanceof LivingEntity var1 && var1.isDeadOrDying();
   }

   private static Entity findLassoedPest(Minecraft var0, Entity var1) {
      if (var0.level == null) {
         return null;
      }

      Entity var2 = null;
      double var3 = Double.MAX_VALUE;

      for (Entity var6 : var0.level.entitiesForRendering()) {
         if ((var6 instanceof Bat || var6 instanceof Silverfish) && var6 instanceof Leashable var7 && var7.getLeashHolder() == var0.player) {
            double var8 = var1 == null ? 0.0 : var6.distanceToSqr(var1);
            if (var1 == null || var8 < var3) {
               var2 = var6;
               var3 = var8;
            }
         }
      }

      return var1 != null && !(var3 <= 36.0) ? null : var2;
   }

   private static void holdLassoPosition(Minecraft var0) {
      ClientUtils.setKeyMappingState(var0.options.keyUp, false);
      ClientUtils.setKeyMappingState(var0.options.keyDown, false);
      ClientUtils.setKeyMappingState(var0.options.keyLeft, false);
      ClientUtils.setKeyMappingState(var0.options.keyRight, false);
      ClientUtils.setKeyMappingState(var0.options.keySprint, false);
      ClientUtils.setKeyMappingState(var0.options.keyJump, false);
      ClientUtils.setKeyMappingState(var0.options.keyShift, false);
   }

   private static boolean hasReelPrompt(Minecraft var0, Entity var1) {
      if (findMarker(var0, var1, true) != null) {
         return true;
      }

      if (var0.level != null && var1 != null) {
         AABB var2 = AABB.ofSize(var1.position(), 16.0, 10.0, 16.0);

         for (ArmorStand var4 : var0.level.getEntitiesOfClass(ArmorStand.class, var2)) {
            Component var5 = var4.getCustomName();
            if (var5 == null) {
               var5 = var4.getName();
            }

            if (isReelPrompt(stripFormatting(var5.getString()))) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   static String stripFormatting(String var0) {
      return var0.replaceAll("(?i)\\u00A7.", "").trim();
   }

   static boolean isReelPrompt(String var0) {
      return var0.toUpperCase(Locale.ROOT).contains("REEL");
   }

   private static boolean waitForLanding(PestDestroyerRuntime var0, Entity var1, long var2) {
      if (!(var1 instanceof Silverfish)) {
         var0.huntLandingWaitStartedAt = 0L;
         return false;
      }

      double var4 = var1.getY();
      boolean var6 = !Double.isNaN(var0.huntTargetY) && Math.abs(var4 - var0.huntTargetY) > 0.05;
      var0.huntTargetY = var4;
      if (!var1.onGround() && var6) {
         if (var0.huntLandingWaitStartedAt == 0L) {
            var0.huntLandingWaitStartedAt = var2;
         }

         return var2 - var0.huntLandingWaitStartedAt < 250L;
      } else {
         var0.huntLandingWaitStartedAt = 0L;
         return false;
      }
   }

   private static Vec3 huntAimPoint(Minecraft var0, Entity var1) {
      if (!(var1 instanceof Bat) && !(var1 instanceof Silverfish)) {
         ArmorStand var2 = findMarker(var0, var1, false);
         return var2 != null ? var2.position() : eyePosition(var1);
      } else {
         return eyePosition(var1);
      }
   }

   private static ArmorStand findMarker(Minecraft var0, Entity var1, boolean var2) {
      if (var0.level != null && var1 != null) {
         AABB var3 = AABB.ofSize(var1.position().add(0.0, 1.5, 0.0), 6.0, 6.0, 6.0);
         ArmorStand var4 = null;
         double var5 = Double.MAX_VALUE;

         for (ArmorStand var8 : var0.level.getEntitiesOfClass(ArmorStand.class, var3)) {
            Component var9 = var8.getCustomName();
            if (var9 == null) {
               var9 = var8.getName();
            }

            String var10 = stripFormatting(var9.getString());
            if (!var10.isEmpty() && isReelPrompt(var10) == var2 && ridesOn(var8, var1, var2 ? 3.0 : 2.0)) {
               double var11 = var8.distanceToSqr(var1);
               if (var11 < var5) {
                  var5 = var11;
                  var4 = var8;
               }
            }
         }

         return var4;
      } else {
         return null;
      }
   }

   private static boolean ridesOn(ArmorStand var0, Entity var1, double var2) {
      double var4 = var0.getX() - var1.getX();
      double var6 = var0.getZ() - var1.getZ();
      double var8 = var0.getY() - var1.getY();
      return var4 * var4 + var6 * var6 <= var2 * var2 && var8 >= -1.0 && var8 <= 3.5;
   }

   private static Vec3 eyePosition(Entity var0) {
      return var0.position().add(0.0, var0.getEyeHeight(var0.getPose()), 0.0);
   }

   private static void advance(PestDestroyerRuntime var0, PestHuntingController.Stage var1, long var2, int var4) {
      var0.huntStage = var1;
      var0.huntStageEnteredAt = var2;
      var0.huntStageEnteredTick = var4;
   }

   interface Context {
      PestDestroyerRuntime runtime();

      void setState(PestDestroyer.State var1);

      void markKilled(Entity var1);

      boolean recordTrackedPestKill(Minecraft var1, Entity var2);

      boolean switchToNextQueuedTarget(Minecraft var1);
   }

   enum Stage {
      STUN,
      SWAP_TO_LASSO,
      THROW,
      REEL;
   }
}
