package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

final class PestCombatCoordinator {
   private static final long STUCK_PATH_RETRY_DELAY_MS = 300L;
   private static final long AOTV_POST_CLICK_GRACE_MS = 250L;
   private static final double AOTV_CONFIRM_DISTANCE = 2.0;
   private static final double AOTV_CONFIRM_DISTANCE_SQ = 4.0;
   private static final float AOTV_AIM_TOLERANCE_DEGREES = 2.0F;
   private static final double POST_AOTV_LOOK_DOWN_HORIZONTAL_DISTANCE = 3.0;
   private static final double VACUUM_REAPPROACH_BUFFER = 6.0;
   private static final double TARGET_REACQUIRE_CONE_DEGREES = 120.0;
   private static final double S_BRAKE_ENTER_DISTANCE = 2.0;
   private static final double S_BRAKE_EXIT_DISTANCE = 4.0;
   private static final double S_BRAKE_MIN_SPEED = 0.2;
   private static final double KILL_FORWARD_HOLD_DISTANCE = 5.0;

   private PestCombatCoordinator() {
   }

   static void handleFlyToPest(Minecraft var0, PestCombatCoordinator.Context var1, double var2, int var4, long var5) {
      Entity var7 = var1.getCurrentTarget();
      if (var7 != null && !var7.isRemoved() && !(var7 instanceof LivingEntity var8 && var8.isDeadOrDying())) {
         double var12 = var0.player.distanceTo(var7);
         if (var12 <= var2 * 1.5 && !FailsafeManager.shouldSuppressPestCleanerRotation(var0) && shouldRotateForCombatAim(var1, var0, var7)) {
            Vec3 var10 = buildCombatAimTarget(var0, var7);
            if (!var1.isLookingAt(var0, var10, AetherConfig.PEST_FOV_RANGE.get())) {
               keepFlyPathHeading(var0, var10, 80L, AetherConfig.PEST_FOV_RANGE.get());
            }
         }

         if (var12 <= var2) {
            PathfindingManager.stop();
            var1.setState(PestDestroyer.State.APPROACH_PEST);
         } else if (!PathfindingManager.isNavigating()) {
            long var13 = System.currentTimeMillis();
            if (var1.getFlyRetryAfterUnflyAt() <= var13) {
               ClientUtils.sendDebugMessage("[PestDestroyer] Fly route ended before reaching pest. Repathing now.");
               var1.setStuckTicks(0);
               var1.setFlyRetryAfterUnflyAt(var13 + 300L);
               var1.startPathToPest(var0, var7);
            }
         } else {
            var1.setStuckTicks(0);
            if (System.currentTimeMillis() - var1.getStateEnteredAt() > var5) {
               ClientUtils.sendDebugMessage("[PestDestroyer] Fly-to-pest timed out. Checking for next pest.");
               PathfindingManager.stop();
               var1.markKilled(var7);
               var1.setState(PestDestroyer.State.CHECK_NEXT);
            }
         }
      } else {
         PathfindingManager.stop();
         var1.setState(PestDestroyer.State.CHECK_NEXT);
      }
   }

   static void handleApproachPest(Minecraft var0, PestCombatCoordinator.Context var1, double var2, int var4) {
      Entity var5 = var1.getCurrentTarget();
      if (var5 != null && !var5.isRemoved() && !(var5 instanceof LivingEntity var6 && var6.isDeadOrDying())) {
         double var9 = var0.player.distanceTo(var5);
         var1.setApproachTicks(var1.getApproachTicks() + 1);
         if (!PestHuntingController.shouldLassoTarget(var0, var5)
            && !FailsafeManager.shouldSuppressPestCleanerRotation(var0)
            && shouldRotateForCombatAim(var1, var0, var5)) {
            Vec3 var8 = buildCombatAimTarget(var0, var5);
            keepApproachPathHeading(var0, var8, 120L);
         }

         if (var9 <= PestHuntingController.handoffRange(var0, var5, var2)) {
            var1.beginTerminalState(var0);
         } else {
            if (!PathfindingManager.isNavigating()) {
               var1.startPathToPest(var0, var5);
            }

            if (var1.getApproachTicks() > var4) {
               ClientUtils.sendDebugMessage("[PestDestroyer] Approach timed out.");
               PathfindingManager.stop();
               var1.markKilled(var5);
               var1.setState(PestDestroyer.State.CHECK_NEXT);
            }
         }
      } else {
         var1.setState(PestDestroyer.State.CHECK_NEXT);
      }
   }

   static void handleKillPest(Minecraft var0, PestCombatCoordinator.Context var1, int var2, long var3) {
      Entity var5 = var1.getCurrentTarget();
      if (var5 == null || var5.isRemoved() || var5 instanceof LivingEntity var6 && var6.isDeadOrDying()) {
         ClientUtils.setKeyMappingState(var0.options.keyUse, false);
         if (var5 == null || !var5.isRemoved() && !(var5 instanceof LivingEntity var7 && var7.isDeadOrDying()) || !var1.recordTrackedPestKill(var0, var5)) {
            var1.setState(PestDestroyer.State.CHECK_NEXT);
         }
      } else if (var0.player != null) {
         if (isOutsideForwardCone(var0, var5, 120.0)) {
            ClientUtils.setKeyMappingState(var0.options.keyUse, false);
            ClientUtils.setKeyMappingState(var0.options.keyDown, false);
            ClientUtils.setKeyMappingState(var0.options.keyUp, false);
            PathfindingManager.stop();
            var1.setTargetWithoutSkullTicks(0);
            if (!FailsafeManager.shouldSuppressPestCleanerRotation(var0)) {
               RotationManager.smoothForceRotation(var0, buildCombatAimTarget(var0, var5), 120L);
            }

            ClientUtils.sendDebugMessage("[PestDestroyer] Target moved behind forward cone. Turning to reacquire.");
         } else {
            double var13 = var0.player.distanceTo(var5);
            if (var1.getVacuumSlot() == -1) {
               var1.setVacuumSlot(var1.findVacuumHotbarSlot(var0));
            }

            if (var1.getVacuumSlot() != -1 && ((AccessorInventory)var0.player.getInventory()).getSelected() != var1.getVacuumSlot()) {
               var0.execute(() -> FailsafeManager.selectHotbarSlot(var0, var1.getVacuumSlot()));
            } else {
               if (var13 <= var1.getVacuumRange()) {
                  boolean var8 = var1.shouldTemporarilyReleaseKillVacuum(var0, true, true);
                  ClientUtils.setKeyMappingState(var0.options.keyUse, !var8);
                  ClientUtils.setKeyMappingState(var0.options.keyUp, var13 > 5.0);
                  if (PathfindingManager.isNavigating()) {
                     PathfindingManager.stop();
                  }

                  if (!FailsafeManager.shouldSuppressPestCleanerRotation(var0) && shouldRotateForCombatAim(var1, var0, var5)) {
                     Vec3 var9 = buildCombatAimTarget(var0, var5);
                     RotationManager.smoothForceRotation(var0, var9, 120L);
                  }

                  double var14 = Math.abs(var0.player.getDeltaMovement().x) + Math.abs(var0.player.getDeltaMovement().z);
                  boolean var11 = var0.options.keyDown.isDown();
                  boolean var12 = (var11 ? var13 < 4.0 : var13 < 2.0) && var14 > 0.2;
                  ClientUtils.setKeyMappingState(var0.options.keyDown, var12);
                  if (!var1.hasPestSkullMarkerForTarget(var0, var5)) {
                     var1.setTargetWithoutSkullTicks(var1.getTargetWithoutSkullTicks() + 1);
                     if (var1.getTargetWithoutSkullTicks() >= var2) {
                        ClientUtils.setKeyMappingState(var0.options.keyUse, false);
                        ClientUtils.setKeyMappingState(var0.options.keyDown, false);
                        var1.markKilled(var5);
                        if (var1.recordTrackedPestKill(var0, var5)) {
                           return;
                        }

                        ClientUtils.sendDebugMessage("[PestDestroyer] Pest skull disappeared. Switching target immediately.");
                        if (!var1.switchToNextQueuedTarget(var0)) {
                           var1.setState(PestDestroyer.State.CHECK_NEXT);
                        }

                        return;
                     }
                  } else {
                     var1.setTargetWithoutSkullTicks(0);
                  }
               } else {
                  var1.shouldTemporarilyReleaseKillVacuum(var0, true, false);
                  ClientUtils.setKeyMappingState(var0.options.keyUse, false);
                  ClientUtils.setKeyMappingState(var0.options.keyDown, false);
                  var1.setTargetWithoutSkullTicks(0);
                  ClientUtils.setKeyMappingState(var0.options.keyUp, var13 > 5.0);
                  if (var13 > var1.getVacuumRange() + 6.0) {
                     var1.setState(PestDestroyer.State.APPROACH_PEST);
                     return;
                  }
               }

               if (System.currentTimeMillis() - var1.getStateEnteredAt() > var3) {
                  ClientUtils.setKeyMappingState(var0.options.keyUse, false);
                  ClientUtils.setKeyMappingState(var0.options.keyDown, false);
                  ClientUtils.setKeyMappingState(var0.options.keyUp, false);
                  ClientUtils.sendDebugMessage("[PestDestroyer] Kill pest timed out. Moving on.");
                  var1.markKilled(var5);
                  var1.setTargetWithoutSkullTicks(0);
                  if (!var1.switchToNextQueuedTarget(var0)) {
                     var1.setState(PestDestroyer.State.CHECK_NEXT);
                  }
               }
            }
         }
      }
   }

   static void handleAotvBetweenPests(Minecraft var0, PestCombatCoordinator.Context var1, double var2, double var4, long var6) {
      Entity var8 = var1.getCurrentTarget();
      if (var8 != null && !var8.isRemoved() && !(var8 instanceof LivingEntity var9 && var9.isDeadOrDying())) {
         if (var1.getAotvSlot() == -1) {
            var1.setAotvSlot(var1.findAotvHotbarSlot(var0));
            if (var1.getAotvSlot() == -1) {
               clearAotvBetweenPests(var0, var1);
               ClientUtils.sendDebugMessage("[PestDestroyer] No AOTV found. Falling back to pathfinding.");
               var1.startPathToPest(var0, var8);
               var1.setState(PestDestroyer.State.FLY_TO_PEST);
               return;
            }

            var1.setStateEnteredAt(System.currentTimeMillis());
         }

         long var25 = System.currentTimeMillis();
         long var11 = Math.max(var1.getStateEnteredAt(), Math.max(var1.getAotvLastUseAt(), var1.getAotvPendingUseAt()));
         if (var25 - var11 > var6) {
            clearAotvBetweenPests(var0, var1);
            ClientUtils.sendDebugMessage("[PestDestroyer] AOTV state timed out. Falling back to pathfinding.");
            var1.startPathToPest(var0, var8);
            var1.setState(PestDestroyer.State.FLY_TO_PEST);
         } else {
            double var13 = var2 * var4;
            double var15 = var0.player.distanceTo(var8);
            if (!finishAotvIfClose(var0, var1, var8, var15, var13)) {
               Vec3 var17 = getEntityEyePosition(var8);
               Vec3 var18 = var8.position().add(0.0, var8.getEyeHeight(var8.getPose()), 0.0);
               if (var0.player.getY() < var18.y && !ClientUtils.hasLineOfSight(var0.player, var18)) {
                  ClientUtils.sendDebugMessage("[PestDestroyer] No LOS and below pest (" + var8.getDisplayName().getString() + "), flying up for vision...");
                  ClientUtils.setKeyMappingState(var0.options.keyJump, true);
                  ClientUtils.setKeyMappingState(var0.options.keyUp, false);
                  ClientUtils.setKeyMappingState(var0.options.keySprint, false);
               } else {
                  ClientUtils.setKeyMappingState(var0.options.keyJump, false);
                  if (var1.getAotvSlot() != -1 && ((AccessorInventory)var0.player.getInventory()).getSelected() != var1.getAotvSlot()) {
                     var0.execute(() -> FailsafeManager.selectHotbarSlot(var0, var1.getAotvSlot()));
                  } else {
                     boolean var19 = var1.isLookingAt(var0, var17, 2.0F);
                     boolean var20 = FailsafeManager.shouldSuppressPestCleanerRotation(var0);
                     if (!var20) {
                        ClientUtils.setKeyMappingState(var0.options.keyUp, false);
                        ClientUtils.setKeyMappingState(var0.options.keySprint, false);
                     }

                     if (!var19) {
                        if (!var20 && !RotationManager.isRotating()) {
                           RotationManager.initiateRotation(var0, var17, AetherConfig.ROTATION_TIME.get().intValue());
                        }

                        if (!var20) {
                           return;
                        }
                     }

                     if (var20 || !RotationManager.isRotating()) {
                        if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get() && var1.getAotvPendingUseAt() != 0L) {
                           double var26 = getAotvMovedDistance(var0, var1);
                           if (var26 >= 2.0) {
                              var1.setAotvLastUseAt(var1.getAotvPendingUseAt());
                              var1.setAotvPendingUseAt(0L);
                              var1.setAotvPostClickGraceUntil(0L);
                              var1.setAotvUseCount(var1.getAotvUseCount() + 1);
                              ClientUtils.sendDebugMessage("[PestDestroyer] AOTV confirmed by movement: " + String.format("%.2f", var26) + " blocks.");
                              var15 = var0.player.distanceTo(var8);
                              if (finishAotvIfClose(var0, var1, var8, var15, var13)) {
                                 return;
                              }
                           } else {
                              if (var1.getAotvPostClickGraceUntil() > var25) {
                                 ClientUtils.sendDebugMessage(
                                    "[PestDestroyer] Waiting for AOTV confirm: moved "
                                       + String.format("%.2f", var26)
                                       + "/"
                                       + String.format("%.2f", 2.0)
                                       + " blocks."
                                 );
                                 return;
                              }

                              ClientUtils.sendDebugMessage(
                                 "[PestDestroyer] AOTV confirm failed: moved "
                                    + String.format("%.2f", var26)
                                    + "/"
                                    + String.format("%.2f", 2.0)
                                    + " blocks. Retrying."
                              );
                              var1.setAotvPendingUseAt(0L);
                              var1.setAotvPostClickGraceUntil(0L);
                           }
                        } else if (var1.getAotvPostClickGraceUntil() > var25) {
                           double var21 = getAotvMovedDistanceSq(var0, var1);
                           if (var21 <= 4.0) {
                              return;
                           }

                           var1.setAotvPostClickGraceUntil(0L);
                           var15 = var0.player.distanceTo(var8);
                           if (finishAotvIfClose(var0, var1, var8, var15, var13)) {
                              return;
                           }
                        }

                        long var27 = var1.getAotvNextUseAt();
                        if (var27 == 0L) {
                           long var23 = var1.getAotvLastUseAt() == 0L ? var1.getStateEnteredAt() : var1.getAotvLastUseAt();
                           var27 = var23 + ConfigHelpers.getRandomizedDelay(AetherConfig.PEST_AOTV_DELAY_MIN.get(), AetherConfig.PEST_AOTV_DELAY_MAX.get());
                           var1.setAotvNextUseAt(var27);
                        }

                        if (var25 >= var27) {
                           ClientUtils.sendDebugMessage(
                              "[PestDestroyer] Using AOTV (" + (var1.getAotvUseCount() + 1) + "). Distance: " + String.format("%.1f", var15)
                           );
                           ClientUtils.performUseClick();
                           FailsafeManager.addRotationGracePeriod(250L);
                           var1.setAotvPostClickGraceUntil(var25 + 250L);
                           var1.setAotvLastUsePlayerX(var0.player.getX());
                           var1.setAotvLastUsePlayerY(var0.player.getY());
                           var1.setAotvLastUsePlayerZ(var0.player.getZ());
                           if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get()) {
                              var1.setAotvPendingUseAt(var25);
                              ClientUtils.sendDebugMessage("[PestDestroyer] Waiting for AOTV position confirm (>= " + String.format("%.0f", 2.0) + " blocks).");
                           } else {
                              var1.setAotvLastUseAt(var25);
                              var1.setAotvUseCount(var1.getAotvUseCount() + 1);
                           }

                           var1.setAotvNextUseAt(0L);
                        }

                        if (var1.getAotvUseCount() > 10) {
                           clearAotvBetweenPests(var0, var1);
                           ClientUtils.sendDebugMessage("[PestDestroyer] AOTV usage exceeded maximum. Falling back to pathfinding.");
                           var1.startPathToPest(var0, var8);
                           var1.setState(PestDestroyer.State.FLY_TO_PEST);
                        }
                     }
                  }
               }
            }
         }
      } else {
         clearAotvBetweenPests(var0, var1);
         var1.setState(PestDestroyer.State.CHECK_NEXT);
      }
   }

   private static boolean finishAotvIfClose(Minecraft var0, PestCombatCoordinator.Context var1, Entity var2, double var3, double var5) {
      if (var3 > var5) {
         return false;
      }

      boolean var7 = var1.getAotvUseCount() > 0;
      clearAotvBetweenPests(var0, var1);
      var1.setArrivedAtCurrentTargetViaAotv(var7);
      ClientUtils.sendDebugMessage("[PestDestroyer] AOTV closed gap. Distance now " + String.format("%.1f", var3) + ". Switching to pathfinding.");
      if (var3 <= PestHuntingController.handoffRange(var0, var2, var1.getVacuumRange())) {
         var1.beginTerminalState(var0);
      } else {
         var1.startPathToPest(var0, var2);
         var1.setState(PestDestroyer.State.FLY_TO_PEST);
      }

      return true;
   }

   static boolean isOutsideForwardCone(Minecraft var0, Entity var1, double var2) {
      if (var0 != null && var0.player != null && var1 != null && !(var2 <= 0.0)) {
         Vec3 var4 = getEntityEyePosition(var1).subtract(var0.player.getEyePosition());
         if (var4.lengthSqr() == 0.0) {
            return false;
         }

         double var5 = var0.player.getViewVector(1.0F).normalize().dot(var4.normalize());
         double var7 = Math.cos(Math.toRadians(var2));
         return var5 < var7;
      } else {
         return false;
      }
   }

   private static Vec3 getEntityEyePosition(Entity var0) {
      return var0.position().add(0.0, var0.getEyeHeight(var0.getPose()), 0.0);
   }

   private static void clearAotvBetweenPests(Minecraft var0, PestCombatCoordinator.Context var1) {
      ClientUtils.setKeyMappingState(var0.options.keyUse, false);
      ClientUtils.setKeyMappingState(var0.options.keyUp, false);
      ClientUtils.setKeyMappingState(var0.options.keySprint, false);
      RotationManager.cancelRotation();
      var1.setAotvSlot(-1);
      var1.setAotvUseCount(0);
      var1.setAotvLastUseAt(0L);
      var1.setAotvNextUseAt(0L);
      var1.setAotvPostClickGraceUntil(0L);
      var1.setAotvPendingUseAt(0L);
   }

   private static double getAotvMovedDistance(Minecraft var0, PestCombatCoordinator.Context var1) {
      return Math.sqrt(getAotvMovedDistanceSq(var0, var1));
   }

   private static double getAotvMovedDistanceSq(Minecraft var0, PestCombatCoordinator.Context var1) {
      double var2 = var0.player.getX() - var1.getAotvLastUsePlayerX();
      double var4 = var0.player.getY() - var1.getAotvLastUsePlayerY();
      double var6 = var0.player.getZ() - var1.getAotvLastUsePlayerZ();
      return var2 * var2 + var4 * var4 + var6 * var6;
   }

   private static boolean shouldRotateForCombatAim(PestCombatCoordinator.Context var0, Minecraft var1, Entity var2) {
      if (!var0.didArriveAtCurrentTargetViaAotv()) {
         return true;
      }

      double var3 = var1.player.getX() - var2.getX();
      double var5 = var1.player.getZ() - var2.getZ();
      double var7 = Math.sqrt(var3 * var3 + var5 * var5);
      return var7 <= 3.0;
   }

   static Vec3 buildCombatAimTarget(Minecraft var0, Entity var1) {
      if (PestDestroyer.isCatchInProgress()) {
         return var1.position().add(0.0, var1.getEyeHeight(var1.getPose()), 0.0);
      } else {
         return PestHuntingController.shouldLassoTarget(var0, var1)
            ? var1.position().add(0.0, var1.getEyeHeight(var1.getPose()), 0.0)
            : buildVacuumAimTarget(var0, var1);
      }
   }

   static Vec3 buildVacuumAimTarget(Minecraft var0, Entity var1) {
      Vec3 var2 = var0.player.getEyePosition();
      Vec3 var3 = var1.position().add(0.0, var1.getEyeHeight(var1.getPose()), 0.0);
      if (var2.y > var3.y) {
         double var4 = Math.sqrt((var3.x - var2.x) * (var3.x - var2.x) + (var3.z - var2.z) * (var3.z - var2.z));
         float var6 = getAbovePestPitch(var1);
         double var7 = var2.y + Math.tan(Math.toRadians(-var6)) * var4;
         return new Vec3(var3.x, var7, var3.z);
      } else {
         return var3;
      }
   }

   private static float getAbovePestPitch(Entity var0) {
      return PestPitchRange.configured().bucketFor(var0.getId());
   }

   interface Context {
      PestDestroyerRuntime runtime();

      default Entity getCurrentTarget() {
         return this.runtime().currentTarget;
      }

      default int getVacuumSlot() {
         return this.runtime().killVacuumSlot >= 0 ? this.runtime().killVacuumSlot : this.runtime().vacuumSlot;
      }

      default void setVacuumSlot(int var1) {
         this.runtime().vacuumSlot = var1;
      }

      default double getVacuumRange() {
         return this.runtime().vacuumRange;
      }

      default int getAotvSlot() {
         return this.runtime().aotvSlot;
      }

      default void setAotvSlot(int var1) {
         this.runtime().aotvSlot = var1;
      }

      default int getAotvUseCount() {
         return this.runtime().aotvUseCount;
      }

      default void setAotvUseCount(int var1) {
         this.runtime().aotvUseCount = var1;
      }

      default long getAotvLastUseAt() {
         return this.runtime().aotvLastUseAt;
      }

      default void setAotvLastUseAt(long var1) {
         this.runtime().aotvLastUseAt = var1;
      }

      default long getAotvNextUseAt() {
         return this.runtime().aotvNextUseAt;
      }

      default void setAotvNextUseAt(long var1) {
         this.runtime().aotvNextUseAt = var1;
      }

      default long getAotvPostClickGraceUntil() {
         return this.runtime().aotvPostClickGraceUntil;
      }

      default void setAotvPostClickGraceUntil(long var1) {
         this.runtime().aotvPostClickGraceUntil = var1;
      }

      default long getAotvPendingUseAt() {
         return this.runtime().aotvPendingUseAt;
      }

      default void setAotvPendingUseAt(long var1) {
         this.runtime().aotvPendingUseAt = var1;
      }

      default double getAotvLastUsePlayerX() {
         return this.runtime().aotvLastUsePlayerX;
      }

      default void setAotvLastUsePlayerX(double var1) {
         this.runtime().aotvLastUsePlayerX = var1;
      }

      default double getAotvLastUsePlayerY() {
         return this.runtime().aotvLastUsePlayerY;
      }

      default void setAotvLastUsePlayerY(double var1) {
         this.runtime().aotvLastUsePlayerY = var1;
      }

      default double getAotvLastUsePlayerZ() {
         return this.runtime().aotvLastUsePlayerZ;
      }

      default void setAotvLastUsePlayerZ(double var1) {
         this.runtime().aotvLastUsePlayerZ = var1;
      }

      default boolean didArriveAtCurrentTargetViaAotv() {
         return this.runtime().arrivedAtCurrentTargetViaAotv;
      }

      default void setArrivedAtCurrentTargetViaAotv(boolean var1) {
         this.runtime().arrivedAtCurrentTargetViaAotv = var1;
      }

      default long getStateEnteredAt() {
         return this.runtime().stateEnteredAt;
      }

      default void setStateEnteredAt(long var1) {
         this.runtime().stateEnteredAt = var1;
      }

      default int getStuckTicks() {
         return this.runtime().stuckTicks;
      }

      default void setStuckTicks(int var1) {
         this.runtime().stuckTicks = var1;
      }

      default long getFlyRetryAfterUnflyAt() {
         return this.runtime().flyRetryAfterUnflyAt;
      }

      default void setFlyRetryAfterUnflyAt(long var1) {
         this.runtime().flyRetryAfterUnflyAt = var1;
      }

      default int getApproachTicks() {
         return this.runtime().approachTicks;
      }

      default void setApproachTicks(int var1) {
         this.runtime().approachTicks = var1;
      }

      default int getTargetWithoutSkullTicks() {
         return this.runtime().targetWithoutSkullTicks;
      }

      default void setTargetWithoutSkullTicks(int var1) {
         this.runtime().targetWithoutSkullTicks = var1;
      }

      boolean isLookingAt(Minecraft var1, Vec3 var2, float var3);

      void setState(PestDestroyer.State var1);

      void beginTerminalState(Minecraft var1);

      void startPathToPest(Minecraft var1, Entity var2);

      boolean switchToNextQueuedTarget(Minecraft var1);

      Entity peekNextQueuedPest(Minecraft var1);

      void maybePreMoveToNextTarget(Minecraft var1, Entity var2, double var3);

      boolean hasPestSkullMarkerForTarget(Minecraft var1, Entity var2);

      void markKilled(Entity var1);

      boolean recordTrackedPestKill(Minecraft var1, Entity var2);

      boolean shouldTemporarilyReleaseKillVacuum(Minecraft var1, boolean var2, boolean var3);

      int findVacuumHotbarSlot(Minecraft var1);

      int findAotvHotbarSlot(Minecraft var1);
   }
}
