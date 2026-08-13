package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

final class PestTargetController {
   static final double AOTV_RANGE = 12.0;
   static final double AOTV_GAP_MULTIPLIER = 1.6;
   private static final int TARGET_SWITCH_ROTATION_MS = 90;
   private static final double TARGET_REACH_DISTANCE = 12.0;
   private static final double PRE_TRIGGER_RATIO = 0.67;
   private static final double PRE_TRIGGER_DISTANCE = 8.040000000000001;
   private static final double PRE_MOVE_MIN_NEXT_DIST = 2.5;

   private PestTargetController() {
   }

   static void startPathToPest(Minecraft var0, Entity var1) {
      boolean var2 = PestHuntingController.shouldLassoTarget(var0, var1);
      int var3 = Mth.floor(var1.getX());
      int var4 = Mth.floor(var1.getY()) + (var2 ? 0 : 3);
      int var5 = Mth.floor(var1.getZ());
      PathfindingManager.startPathfind(var0, var3, var4, var5, true);
   }

   static void engage(Minecraft var0, PestDestroyerRuntime var1, PestTargetController.Context var2, Entity var3) {
      var1.currentTarget = var3;
      var1.arrivedAtCurrentTargetViaAotv = false;
      var1.navigation.waypointCycleCount = 0;
      var1.navigation.getLocationAttempts = 0;
      resetRotationForHandoff(var1);
      double var4 = var0.player.distanceTo(var3);
      ClientUtils.sendDebugMessage("[PestDestroyer] Found pest at " + formatPosition(var3.position()) + " (dist: " + String.format("%.1f", var4) + ")");
      if (var4 > 19.200000000000003 && var1.aotvSlot == -1) {
         var1.aotvSlot = PestLoadoutHelper.findAotvHotbarSlot(var0);
      }

      if (var4 <= PestHuntingController.handoffRange(var0, var3, var1.vacuumRange)) {
         if (!PestHuntingController.shouldLassoTarget(var0, var3)) {
            rotateToTarget(var0, var3);
         }

         var1.aotvSlot = -1;
         beginTerminalState(var0, var1, var2);
      } else if (var4 > 19.200000000000003 && var1.aotvSlot != -1 && AetherConfig.PEST_AOTV_BETWEEN.get()) {
         var1.aotvUseCount = 0;
         ClientUtils.sendDebugMessage("[PestDestroyer] Distance too large (" + String.format("%.1f", var4) + "). Using AOTV to close gap.");
         var2.setState(PestDestroyer.State.AOTV_BETWEEN_PESTS);
      } else {
         rotateToTarget(var0, var3);
         var1.aotvSlot = -1;
         startPathToPest(var0, var3);
         var2.setState(PestDestroyer.State.FLY_TO_PEST);
      }
   }

   static void beginTerminalState(Minecraft var0, PestDestroyerRuntime var1, PestLeaveOneController.Context var2) {
      boolean var3 = PestHuntingController.shouldLassoTarget(var0, var1.currentTarget);
      var1.currentTargetUsesLasso = var3;
      ClientUtils.sendDebugMessage(
         "[PestDestroyer] Target route: " + (var3 ? "LASSO" : "VACUUM") + " (type=" + PestHuntingPolicy.findPestTypeIndex(var0, var1.currentTarget) + ")"
      );
      if (var3) {
         PestHuntingController.beginHunt(var1, var0);
      }

      var2.setState(var3 ? PestDestroyer.State.HUNT_PEST : PestDestroyer.State.KILL_PEST);
   }

   static boolean switchToNextQueuedTarget(Minecraft var0, PestDestroyerRuntime var1, PestTargetController.Context var2) {
      if (var2.tryLeaveOneOnCurrentPlot(var0)) {
         return true;
      }

      Entity var3 = nextQueuedPest(var0, var1);
      if (var3 == null) {
         rebuildQueue(var0, var1, var2);
         var3 = nextQueuedPest(var0, var1);
      }

      if (var3 == null) {
         return false;
      }

      engage(var0, var1, var2, var3);
      return true;
   }

   static void maybePreMoveToNextTarget(Minecraft var0, Entity var1, double var2) {
      if (var1 != null && !(var2 > 8.040000000000001)) {
         double var4 = var0.player.distanceTo(var1);
         ClientUtils.setKeyMappingState(var0.options.keyDown, false);
         ClientUtils.setKeyMappingState(var0.options.keyUp, var4 > 2.5);
      } else {
         ClientUtils.setKeyMappingState(var0.options.keyUp, false);
      }
   }

   static Entity peekNextQueuedPest(Minecraft var0, PestDestroyerRuntime var1) {
      return PestTargetTracker.peekNextQueuedPest(var0, var1.pestTargetQueue, var1.killedEntities);
   }

   static void rebuildQueue(Minecraft var0, PestDestroyerRuntime var1, PestTargetController.Context var2) {
      updateReservedPest(var0, var1, var2);
      PestTargetTracker.rebuildPestTargetQueue(var0, var1.pestTargetQueue, var1.killedEntities, var1.navigation.leaveOneReservedEntityId);
   }

   static Entity nextQueuedPest(Minecraft var0, PestDestroyerRuntime var1) {
      return PestTargetTracker.getNextQueuedPest(var0, var1.pestTargetQueue, var1.killedEntities);
   }

   static Entity findClosestPest(Minecraft var0, PestDestroyerRuntime var1, PestTargetController.Context var2) {
      updateReservedPest(var0, var1, var2);
      return PestTargetTracker.findClosestPest(var0, var1.killedEntities, var1.navigation.leaveOneReservedEntityId);
   }

   static int countVisiblePestSkulls(Minecraft var0) {
      return PestTargetTracker.countVisiblePestSkulls(var0);
   }

   static boolean hasPestSkullMarkerForTarget(Minecraft var0, Entity var1) {
      return PestTargetTracker.hasPestSkullMarkerForTarget(var0, var1);
   }

   static void onEntityDeath(Minecraft var0, PestDestroyerRuntime var1, PestTargetController.Context var2, Entity var3) {
      if (var1.active) {
         if (!var1.killedEntities.contains(var3)) {
            var1.killedEntities.add(var3);
         }

         if (var1.currentTarget != null && var1.currentTarget.equals(var3)) {
            if (var0.options != null) {
               ClientUtils.setKeyMappingState(var0.options.keyUse, false);
               ClientUtils.setKeyMappingState(var0.options.keyDown, false);
            }

            if (!recordTrackedKill(var0, var1, var2, var3)) {
               var1.currentTarget = null;
               var2.setState(PestDestroyer.State.CHECK_NEXT);
            }
         }
      }
   }

   static boolean reconcileTrackedKills(Minecraft var0, PestDestroyerRuntime var1, PestTargetController.Context var2) {
      if (var1.active && var1.state == PestDestroyer.State.KILL_PEST) {
         LinkedHashMap var3 = new LinkedHashMap();
         if (var1.currentTarget != null) {
            var3.put(var1.currentTarget.getId(), var1.currentTarget);
         }

         for (Entity var5 : var1.pestTargetQueue) {
            var3.putIfAbsent(var5.getId(), var5);
         }

         int var8 = 0;
         boolean var9 = false;

         for (Entity var7 : var3.values()) {
            if (isDead(var7)) {
               if (!var1.killedEntities.contains(var7)) {
                  var1.killedEntities.add(var7);
               }

               if (var1.claimKilledPestEntityId(var7.getId())) {
                  var8++;
               }

               if (var7 == var1.currentTarget) {
                  var9 = true;
               }
            }
         }

         if (var8 == 0) {
            return false;
         }

         var1.pestTargetQueue.removeIf(PestTargetController::isDead);
         PestManager.decrementPredictedAliveCount(var0, var8);
         if (!var1.active) {
            return true;
         }

         if (var9) {
            var1.currentTarget = null;
            if (var0.options != null) {
               ClientUtils.setKeyMappingState(var0.options.keyUse, false);
               ClientUtils.setKeyMappingState(var0.options.keyDown, false);
            }

            var2.setState(PestDestroyer.State.CHECK_NEXT);
         }

         return true;
      } else {
         return false;
      }
   }

   static boolean recordTrackedKill(Minecraft var0, PestDestroyerRuntime var1, PestTargetController.Context var2, Entity var3) {
      if (var3 == null) {
         return false;
      }

      if (!var1.killedEntities.contains(var3)) {
         var1.killedEntities.add(var3);
      }

      if (!var1.claimKilledPestEntityId(var3.getId())) {
         return false;
      }

      PestManager.decrementPredictedAliveCount(var0);
      return PestLeaveOneController.recordTrackedKill(var0, var1, var2) || !var1.active;
   }

   private static void updateReservedPest(Minecraft var0, PestDestroyerRuntime var1, PestTargetController.Context var2) {
      if (!PestLeaveOneController.isTrackingPlot(var1, var2.getEffectivePlot(var0))) {
         var1.navigation.leaveOneReservedEntityId = -1;
      } else {
         int var3 = var1.navigation.leaveOneReservedEntityId;
         boolean var4 = var3 != -1 && PestTargetTracker.isAvailablePest(var0, var1.killedEntities, var3);
         Entity var5 = PestTargetTracker.findMostIsolatedPest(var0, var1.killedEntities);
         if (var5 != null) {
            var1.navigation.leaveOneReservedEntityId = var5.getId();
         } else if (!var4) {
            var1.navigation.leaveOneReservedEntityId = -1;
         }
      }
   }

   private static boolean isDead(Entity var0) {
      return var0.isRemoved() || var0 instanceof LivingEntity var1 && var1.isDeadOrDying();
   }

   static boolean isLookingAt(Minecraft var0, Vec3 var1, float var2) {
      return var0.player == null ? false : RotationUtils.isLookingAt(var0.player.getYRot(), var0.player.getXRot(), var0.player.getEyePosition(), var1, var2);
   }

   private static void rotateToTarget(Minecraft var0, Entity var1) {
   }

   private static void resetRotationForHandoff(PestDestroyerRuntime var0) {
      if (var0.state == PestDestroyer.State.APPROACH_PEST
         || var0.state == PestDestroyer.State.KILL_PEST
         || var0.state == PestDestroyer.State.AOTV_BETWEEN_PESTS) {
         RotationManager.cancelRotation();
      }
   }

   private static String formatPosition(Vec3 var0) {
      return String.format("%.0f, %.0f, %.0f", var0.x, var0.y, var0.z);
   }

   interface Context extends PestLeaveOneController.Context {
      boolean tryLeaveOneOnCurrentPlot(Minecraft var1);
   }
}
