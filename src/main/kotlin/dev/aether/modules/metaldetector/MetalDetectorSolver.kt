package dev.aether.modules.metaldetector

import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.metaldetector.helpers.MetalDetectorBackpackManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.HashMap
import java.util.List
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

class MetalDetectorSolver private constructor() {
    private enum class AutomationState { IDLE, PATHING, ROTATING, OPENING }
    companion object {
        private val TREASURE_DISTANCE_PATTERN = Pattern.compile("TREASURE[:\\s]*([0-9]+(?:\\.[0-9]+)?)m", Pattern.CASE_INSENSITIVE)
        private val COLOR_PATTERN = Pattern.compile("(?i)\u00A7[0-9A-FK-OR]")
        private val DEFAULT_RELATIVE_CHESTS = java.util.Arrays.asList<BlockPos>(BlockPos(-7,26,-2),BlockPos(-15,26,31),BlockPos(-17,26,19),BlockPos(47,25,33),BlockPos(36,26,45),BlockPos(48,27,45),BlockPos(45,27,-13),BlockPos(-38,26,21),BlockPos(42,26,27),BlockPos(29,27,-7),BlockPos(22,26,-15),BlockPos(-7,27,-26),BlockPos(-2,26,-6),BlockPos(43,27,-21),BlockPos(10,26,-11),BlockPos(17,26,49),BlockPos(19,26,-17),BlockPos(-35,27,35),BlockPos(25,27,5),BlockPos(-37,24,46),BlockPos(-24,26,49),BlockPos(-7,26,48),BlockPos(-14,27,-24),BlockPos(-18,27,44),BlockPos(-1,26,-23),BlockPos(41,25,-37),BlockPos(19,26,-38),BlockPos(-7,27,27),BlockPos(42,26,19),BlockPos(-33,27,31),BlockPos(6,27,25),BlockPos(-2,26,-17),BlockPos(-15,27,5),BlockPos(-20,27,-12),BlockPos(-25,26,30),BlockPos(28,27,-35),BlockPos(-19,27,-22),BlockPos(4,26,-15),BlockPos(36,26,17))
        private const val ATTACK_RANGE = 4.5
        private const val RETRY_SAME_TARGET_AFTER_MS = 3500L
        private const val ROTATE_TIMEOUT_MS = 2000L
        private const val OPEN_TIMEOUT_MS = 1400L
        private const val PATH_RETRY_DELAY_MS = 350L
        private const val PATH_IDLE_GRACE_MS = 300L
        private const val PATH_TIMEOUT_MS = 20_000L
        private const val ANCHOR_SCAN_COOLDOWN_MS = 3000L
        private const val MAX_ETHERWARP_PATH_FAILURES = 3
        private const val AUTO_SCAN_HORIZONTAL_RADIUS = 50
        private const val AUTO_SCAN_VERTICAL_RADIUS = 35
        private const val ROTATION_TOLERANCE = 4.0f
        private const val METAL_DETECTOR_ITEM_NAME = "metal detector"
        private val relativeChestCoords = ArrayList<BlockPos>()
        private val absoluteChestCoords = ArrayList<BlockPos>()
        private val predictedChestLocations = ArrayList<BlockPos>()
        private val attemptedUntil = HashMap<BlockPos, Long>()
        private var enabled = false
        private var coordsLoaded = false
        private var lobbyInitialized = false
        private var lastScanAt = 0L
        private var levelIdentity = Int.MIN_VALUE
        private var sessionChestsOpened = 0
        private var anchor: BlockPos? = null
        private var ignoreBlockPos: BlockPos? = null
        private var lastTreasureReadPos: Vec3? = null
        private var automationState = AutomationState.IDLE
        private var operationId = 0
        private var automationTarget: BlockPos? = null
        private var automationGoal: BlockPos? = null
        private val activeGoals = ArrayList<BlockPos>()
        private var activeGoalIndex = 0
        private var phaseStartedAt = 0L
        private var lastAutomationStartAt = 0L
        private var pathCallbackTriggered = false
        private var openAttemptTriggered = false
        private var etherwarpPathFailures = 0

        @JvmStatic fun toggle(client: Minecraft): Boolean { setEnabled(client, !enabled); return enabled }
        @JvmStatic fun setEnabled(client: Minecraft, value: Boolean) = setEnabled(client, value, true, true)
        @JvmStatic fun stopForMacro(client: Minecraft) = setEnabled(client, false, false, false)
        private fun setEnabled(client: Minecraft, value: Boolean, announce: Boolean, syncMacroState: Boolean) {
            ensureCoordinatesLoaded(client)
            if (enabled == value) return
            if (value) {
                if (!ensureMetalDetectorSelected(client)) { ClientUtils.sendMessage("\u00A7cNo metal detector found in hotbar.", false); return }
                resetSessionState(false); MetalDetectorBackpackManager.reset(); sessionChestsOpened = 0; levelIdentity = getLevelIdentity(client)
                if (announce) ClientUtils.sendMessage("\u00A7eScanning for metal detector anchor...", false)
                enabled = true; val scannedAnchor = scanForNearbyAnchor(client); if (scannedAnchor != null) applyAnchor(client, scannedAnchor, true)
                if (syncMacroState) MacroStateManager.setCurrentState(MacroState.State.METAL_DETECTING)
                if (announce && scannedAnchor != null) ClientUtils.sendMessage("\u00A7aMetal detector solver enabled at ${scannedAnchor.x} ${scannedAnchor.y} ${scannedAnchor.z} (\u00A7f${absoluteChestCoords.size}\u00A7a spots).", false)
                else if (announce) ClientUtils.sendMessage("\u00A7eMetal detector solver enabled. Waiting for nearby anchor blocks...", false)
            } else {
                enabled = false; stopAutomation(client, true); resetSessionState(false); MetalDetectorBackpackManager.reset()
                if (syncMacroState && MacroStateManager.getCurrentState() == MacroState.State.METAL_DETECTING) MacroStateManager.setCurrentState(MacroState.State.OFF)
                if (announce) ClientUtils.sendMessage("\u00A7eMetal detector solver disabled.", false)
            }
        }
        @JvmStatic fun isEnabled(): Boolean = enabled
        @JvmStatic fun getSessionChestsOpened(): Int = sessionChestsOpened
        @JvmStatic fun getScavengedToolCount(client: Minecraft): Int = MetalDetectorBackpackManager.getScavengedToolCount(client)
        @JvmStatic fun getFilledBackpacksSummary(): String = MetalDetectorBackpackManager.getFilledBackpacksSummary()
        @JvmStatic fun handleContainerMenu(client: Minecraft, screen: AbstractContainerScreen<*>) { if (enabled) MetalDetectorBackpackManager.handleContainerMenu(client, screen) }
        @JvmStatic fun update() {
            val client = Minecraft.getInstance(); if (!enabled || client == null || client.player == null || client.level == null) return
            val identity = getLevelIdentity(client)
            if (levelIdentity != identity) { stopAutomation(client, true); resetSessionState(false); levelIdentity = identity }
            if (MetalDetectorBackpackManager.shouldHandle(client)) { if (automationState != AutomationState.IDLE || PathfindingManager.isNavigating()) stopAutomation(client, true); MetalDetectorBackpackManager.update(); return }
            if (anchor == null || absoluteChestCoords.isEmpty()) tryInitializeAnchor(client, false, false)
            pruneAttemptedTargets()
            when (automationState) { AutomationState.IDLE -> tickIdle(client); AutomationState.PATHING -> tickPathing(client); AutomationState.ROTATING -> tickRotating(client); AutomationState.OPENING -> tickOpening(client) }
        }
        @JvmStatic fun onGameMessage(client: Minecraft?, text: String?, overlay: Boolean) {
            if (!enabled || client == null || client.player == null || client.level == null || text == null) return
            val clean = stripColors(text).trim(); if (clean.isEmpty()) return
            if (clean.contains("TREASURE")) { handleTreasureMessage(client, clean); return }
            if (!overlay && clean.startsWith("You found") && clean.contains("Metal Detector!")) handleTreasureFound(client)
        }
        @JvmStatic fun forceScan(client: Minecraft?) {
            if (client == null || client.player == null || client.level == null) return
            ensureCoordinatesLoaded(client); ClientUtils.sendMessage("\u00A7eScanning for metal detector anchor...", false)
            val scannedAnchor = scanForNearbyAnchor(client)
            if (scannedAnchor == null) { ClientUtils.sendMessage("\u00A7cNo metal detector anchor found nearby.", false); return }
            applyAnchor(client, scannedAnchor, true); ClientUtils.sendMessage("\u00A7aMetal detector anchor found at ${scannedAnchor.x} ${scannedAnchor.y} ${scannedAnchor.z} (\u00A7f${absoluteChestCoords.size}\u00A7a spots).", false)
        }
        @JvmStatic fun hasVisibleHighlights(): Boolean = enabled && (predictedChestLocations.isNotEmpty() || automationTarget != null || automationGoal != null || (anchor != null && absoluteChestCoords.isNotEmpty()))
        @JvmStatic fun renderWorld() {
            if (!hasVisibleHighlights()) return
            val a = anchor
            if (a != null && absoluteChestCoords.isNotEmpty()) {
                val anchorStyle = GizmoStyle.strokeAndFill(ARGB.color(210,255,255,255), 2.0f, ARGB.color(55,255,255,255))
                Gizmos.cuboid(AABB(a.x+0.3,a.y-0.7,a.z+0.3,a.x+0.7,a.y-0.3,a.z+0.7),anchorStyle).setAlwaysOnTop()
                val spotStyle = GizmoStyle.strokeAndFill(ARGB.color(160,255,70,70),1.0f,ARGB.color(28,255,70,70))
                for (chest in absoluteChestCoords) Gizmos.cuboid(AABB(chest.x+0.375,chest.y+0.375,chest.z+0.375,chest.x+0.625,chest.y+0.625,chest.z+0.625),spotStyle).setAlwaysOnTop()
            }
            val predictedStyle = GizmoStyle.strokeAndFill(ARGB.color(230,60,255,120),2.0f,ARGB.color(45,60,255,120))
            for (p in predictedChestLocations) Gizmos.cuboid(AABB(p.x.toDouble(),p.y.toDouble(),p.z.toDouble(),p.x+1.0,p.y+1.0,p.z+1.0),predictedStyle).setAlwaysOnTop()
            val target = automationTarget
            if (target != null) Gizmos.cuboid(AABB(target.x.toDouble(),target.y.toDouble(),target.z.toDouble(),target.x+1.0,target.y+1.0,target.z+1.0),GizmoStyle.strokeAndFill(ARGB.color(255,80,200,255),2.5f,ARGB.color(32,80,200,255))).setAlwaysOnTop()
            val goal = automationGoal
            if (goal != null) Gizmos.cuboid(AABB(goal.x.toDouble(),goal.y.toDouble(),goal.z.toDouble(),goal.x+1.0,goal.y+1.0,goal.z+1.0),GizmoStyle.strokeAndFill(ARGB.color(210,80,120,255),2.0f,ARGB.color(36,80,120,255))).setAlwaysOnTop()
        }
        private fun tickIdle(client: Minecraft) {
            if (predictedChestLocations.isEmpty()) {
                return
            }

            if (System.currentTimeMillis() - lastAutomationStartAt < PATH_RETRY_DELAY_MS) {
                return
            }

            val target = chooseBestTarget(client)
            if (target != null) {
                startTargetAutomation(client, target)
            }
        }

        private fun tickPathing(client: Minecraft) {
            if (automationTarget == null) {
                stopAutomation(client, true)
                return
            }

            val now = System.currentTimeMillis()
            if (now - phaseStartedAt > PATH_TIMEOUT_MS) {
                handleEtherwarpPathFailure(client, "MetalDetector: etherwarp path timed out")
                return
            }

            if (!PathfindingManager.isNavigating() && !pathCallbackTriggered && now - phaseStartedAt > PATH_IDLE_GRACE_MS) {
                handleEtherwarpPathFailure(client, "MetalDetector: etherwarp path interrupted")
            }
        }

        private fun tickRotating(client: Minecraft) {
            val target = automationTarget
            if (target == null) {
                stopAutomation(client, true)
                return
            }

            if (!isWithinAttackRange(client, target)) {
                failCurrentTarget(client, "MetalDetector: drifted out of range", PATH_RETRY_DELAY_MS)
                return
            }

            faceTarget(client, target)
            if (isLookingAtTarget(client, target) && isCrosshairOnTarget(client, target)) {
                if (!ensureMetalDetectorSelected(client)) {
                    failCurrentTarget(client, "MetalDetector: no hotbar metal detector found", RETRY_SAME_TARGET_AFTER_MS)
                    return
                }
                startOpenAttempt(client)
                return
            }

            if (System.currentTimeMillis() - phaseStartedAt > ROTATE_TIMEOUT_MS) {
                failCurrentTarget(client, "MetalDetector: could not face target", PATH_RETRY_DELAY_MS)
            }
        }

        private fun tickOpening(client: Minecraft) {
            val target = automationTarget
            if (target == null) {
                stopAutomation(client, true)
                return
            }

            if (!isWithinAttackRange(client, target)) {
                failCurrentTarget(client, "MetalDetector: drifted out of range while opening", PATH_RETRY_DELAY_MS)
                return
            }

            faceTarget(client, target)
            if (ClientUtils.isInventoryScreenOpen()) {
                return
            }

            if (!openAttemptTriggered) {
                if (isLookingAtTarget(client, target) && isCrosshairOnTarget(client, target)) {
                    startOpenAttempt(client)
                } else if (System.currentTimeMillis() - phaseStartedAt > ROTATE_TIMEOUT_MS) {
                    failCurrentTarget(client, "MetalDetector: could not re-align for chest open", PATH_RETRY_DELAY_MS)
                }
                return
            }

            if (System.currentTimeMillis() - phaseStartedAt > OPEN_TIMEOUT_MS) {
                failCurrentTarget(client, "MetalDetector: chest open attempt failed", RETRY_SAME_TARGET_AFTER_MS)
            }
        }

        private fun ensureMetalDetectorSelected(client: Minecraft): Boolean { if(client.player==null)return false;val slot=GearManager.findHotbarItemSlot(client,METAL_DETECTOR_ITEM_NAME);if(slot<0)return false;if(FailsafeManager.getCurrentSelectedSlot(client)!=slot)FailsafeManager.selectHotbarSlot(client,slot);return true }
        private fun handleTreasureMessage(client: Minecraft, clean: String) {
            if (!lobbyInitialized) tryInitializeAnchor(client, false, false)
            if (anchor == null || absoluteChestCoords.isEmpty()) return
            val player = client.player ?: return
            val playerPos = player.position(); val previous = lastTreasureReadPos
            if (previous == null) { lastTreasureReadPos = playerPos; return }
            if (playerPos.distanceToSqr(previous) > 1.0e-4) { lastTreasureReadPos = playerPos; return }
            val matcher = TREASURE_DISTANCE_PATTERN.matcher(clean); if (!matcher.find()) return
            val distance = try { java.lang.Double.parseDouble(matcher.group(1)) } catch (_: NumberFormatException) { return }
            for (chest in absoluteChestCoords) { if (roundTenth(Math.sqrt(distanceSq(playerPos,chest.x.toDouble(),chest.y+1.0,chest.z.toDouble()))) != distance) continue; if (ignoreBlockPos != null && ignoreBlockPos == chest) { ignoreBlockPos=null; return }; addPredictedChest(client,chest) }
        }
        private fun handleTreasureFound(client: Minecraft) { sessionChestsOpened++;val target=automationTarget;if(target!=null){ignoreBlockPos=target.immutable();removePredictedChest(target)}else if(predictedChestLocations.isNotEmpty())ignoreBlockPos=predictedChestLocations[0].immutable();predictedChestLocations.clear();attemptedUntil.clear();stopAutomation(client,true) }
        private fun startTargetAutomation(client: Minecraft,target:BlockPos){operationId++;automationTarget=target.immutable();automationGoal=null;activeGoals.clear();activeGoals.add(automationTarget!!);activeGoalIndex=0;etherwarpPathFailures=0;phaseStartedAt=System.currentTimeMillis();lastAutomationStartAt=phaseStartedAt;pathCallbackTriggered=false;openAttemptTriggered=false;ClientUtils.setKeyMappingState(client.options.keyAttack,false);ClientUtils.setKeyMappingState(client.options.keyUse,false);RotationManager.cancelRotation();automationState=AutomationState.PATHING;startNextGoalPath(client,operationId)}
        private fun startNextGoalPath(client: Minecraft, opId: Int) {
            if (opId != operationId || automationTarget == null || automationState != AutomationState.PATHING) {
                return
            }

            if (activeGoalIndex >= activeGoals.size) {
                failCurrentTarget(client, "MetalDetector: no walk path to predicted chest", RETRY_SAME_TARGET_AFTER_MS)
                return
            }

            val goal = activeGoals[activeGoalIndex]
            val target = automationTarget
            automationGoal = goal.immutable()
            phaseStartedAt = System.currentTimeMillis()
            pathCallbackTriggered = false

            ClientUtils.sendDebugMessage("MetalDetector: etherwarping to ${target!!.x} ${target!!.y} ${target!!.z}")
            PathfindingManager.startConfiguredEtherwarp(client,
                goal.x, goal.y, goal.z,
                Runnable {
                    if (opId != operationId || automationState != AutomationState.PATHING) {
                        return@Runnable
                    }
                    activeGoalIndex++
                    pathCallbackTriggered = true
                    automationState = AutomationState.ROTATING
                    phaseStartedAt = System.currentTimeMillis()
                },
                Runnable {
                    if (opId != operationId || automationState != AutomationState.PATHING) {
                        return@Runnable
                    }
                    handleEtherwarpPathFailure(client, "MetalDetector: etherwarp path failed")
                })
        }

        private fun handleEtherwarpPathFailure(client: Minecraft,reason:String){etherwarpPathFailures++;ClientUtils.sendDebugMessage("$reason (failure $etherwarpPathFailures/$MAX_ETHERWARP_PATH_FAILURES)");if(etherwarpPathFailures>=MAX_ETHERWARP_PATH_FAILURES){failCurrentTarget(client,"MetalDetector: etherwarp path failed 3 times",RETRY_SAME_TARGET_AFTER_MS);return};pathCallbackTriggered=false;phaseStartedAt=System.currentTimeMillis();startNextGoalPath(client,operationId)}
        private fun failCurrentTarget(client: Minecraft,reason:String,delay:Long){automationTarget?.let{markTargetAttempted(it,delay)};ClientUtils.sendDebugMessage(reason);stopAutomation(client,true)}
        private fun stopAutomation(client: Minecraft?,cancelPath:Boolean){operationId++;automationState=AutomationState.IDLE;automationTarget=null;automationGoal=null;activeGoals.clear();activeGoalIndex=0;phaseStartedAt=0L;lastAutomationStartAt=System.currentTimeMillis();pathCallbackTriggered=false;openAttemptTriggered=false;etherwarpPathFailures=0;if(client?.options!=null){ClientUtils.setKeyMappingState(client.options.keyAttack,false);ClientUtils.setKeyMappingState(client.options.keyUse,false)};RotationManager.cancelRotation();if(cancelPath)PathfindingManager.stop(false)}
        private fun chooseBestTarget(client: Minecraft):BlockPos?{val player=client.player?:return null;var best:BlockPos?=null;var score=Double.MAX_VALUE;for(chest in predictedChestLocations){if(!canRetryTarget(chest))continue;val current=distanceSq(player.position(),chest.x+0.5,chest.y+0.5,chest.z+0.5);if(current<score){score=current;best=chest}};return best}
        private fun startOpenAttempt(client: Minecraft){automationState=AutomationState.OPENING;openAttemptTriggered=true;phaseStartedAt=System.currentTimeMillis();ClientUtils.performAttackClick()}
        private fun isWithinAttackRange(client: Minecraft,target:BlockPos):Boolean{val player=client.player?:return false;return player.position().distanceTo(Vec3(target.x+0.5,target.y+0.5,target.z+0.5))<=ATTACK_RANGE}
        private fun faceTarget(client: Minecraft,target:BlockPos){RotationManager.forceRotation(client,Vec3(target.x+0.5,target.y+0.5,target.z+0.5),100L)}
        private fun isLookingAtTarget(client: Minecraft,target:BlockPos):Boolean{val player=client.player?:return false;return RotationUtils.isLookingAt(player.yRot,player.xRot,player.eyePosition,Vec3(target.x+0.5,target.y+0.5,target.z+0.5),ROTATION_TOLERANCE)}
        private fun isCrosshairOnTarget(client: Minecraft,target:BlockPos):Boolean{val player=client.player?:return false;val level=client.level?:return false;val eye=player.eyePosition;val end=eye.add(player.getViewVector(1.0f).scale(ATTACK_RANGE));val hit:BlockHitResult=level.clip(ClipContext(eye,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));return hit.type==HitResult.Type.BLOCK&&hit.blockPos==target}
        private fun addPredictedChest(client: Minecraft,chest:BlockPos){for(existing in predictedChestLocations)if(existing==chest)return;predictedChestLocations.add(chest.immutable());ClientUtils.sendDebugMessage("MetalDetector: predicted chest at ${chest.x} ${chest.y} ${chest.z}")}
        private fun removePredictedChest(target:BlockPos){predictedChestLocations.removeIf{it==target}}
        private fun markTargetAttempted(target:BlockPos,delay:Long){attemptedUntil[target.immutable()]=System.currentTimeMillis()+delay}
        private fun pruneAttemptedTargets(){val now=System.currentTimeMillis();attemptedUntil.entries.removeIf{it.value<=now}}
        private fun canRetryTarget(target:BlockPos):Boolean{val time=attemptedUntil[target];return time==null||time<=System.currentTimeMillis()}
        private fun applyAnchor(client: Minecraft,newAnchor:BlockPos,announce:Boolean){anchor=newAnchor.immutable();lobbyInitialized=true;computeAbsoluteFromAnchor();if(announce)ClientUtils.sendDebugMessage("MetalDetector: anchor at ${anchor!!.x} ${anchor!!.y} ${anchor!!.z}")}
        private fun computeAbsoluteFromAnchor(){absoluteChestCoords.clear();val a=anchor?:return;for(rel in relativeChestCoords)absoluteChestCoords.add(BlockPos(a.x-rel.x,a.y-rel.y,a.z-rel.z))}
        private fun scanForNearbyAnchor(client: Minecraft):BlockPos?{var found=scanForAnchor(client,AUTO_SCAN_HORIZONTAL_RADIUS,AUTO_SCAN_VERTICAL_RADIUS,AUTO_SCAN_HORIZONTAL_RADIUS);if(found==null)found=scanForAnchor(client,16,AUTO_SCAN_VERTICAL_RADIUS,16);return found}
        private fun tryInitializeAnchor(client: Minecraft,force:Boolean,announce:Boolean):Boolean{val now=System.currentTimeMillis();if(!force&&now-lastScanAt<ANCHOR_SCAN_COOLDOWN_MS)return anchor!=null&&absoluteChestCoords.isNotEmpty();lastScanAt=now;val found=scanForNearbyAnchor(client)?:return false;applyAnchor(client,found,announce);return true}
        private fun scanForAnchor(client: Minecraft,rx:Int,ry:Int,rz:Int):BlockPos?{val player=client.player?:return null;val level=client.level?:return null;val base=player.blockPosition();for(x in base.x-rx..base.x+rx)for(y in base.y+ry downTo base.y-ry)for(z in base.z-rz..base.z+rz){val state=level.getBlockState(BlockPos(x,y,z));if(!isQuartzLike(state))continue;val barrier=BlockPos(x,y+13,z);if(level.getBlockState(barrier).`is`(Blocks.BARRIER))return verifyAnchor(client,barrier)};return null}
        private fun verifyAnchor(client: Minecraft,start:BlockPos):BlockPos{val level=client.level?:return start;val cursor=start.mutable();if(!level.getBlockState(cursor).`is`(Blocks.BARRIER))return cursor.immutable();var moved:Boolean;do{moved=false;if(level.getBlockState(cursor.offset(1,0,0)).`is`(Blocks.BARRIER)){cursor.move(1,0,0);moved=true};if(level.getBlockState(cursor.offset(0,-1,0)).`is`(Blocks.BARRIER)){cursor.move(0,-1,0);moved=true};if(level.getBlockState(cursor.offset(0,0,1)).`is`(Blocks.BARRIER)){cursor.move(0,0,1);moved=true}}while(moved);return cursor.immutable()}
        private fun isQuartzLike(state:BlockState):Boolean=!state.isAir&&BuiltInRegistries.BLOCK.getKey(state.block).path.contains("quartz")
        private fun ensureCoordinatesLoaded(client: Minecraft){if(coordsLoaded)return;coordsLoaded=true;relativeChestCoords.clear();relativeChestCoords.addAll(DEFAULT_RELATIVE_CHESTS);ClientUtils.sendDebugMessage("MetalDetector: using local chest coordinates")}
        private fun resetSessionState(keepPredictions:Boolean){anchor=null;absoluteChestCoords.clear();ignoreBlockPos=null;lastTreasureReadPos=null;lobbyInitialized=false;lastScanAt=0L;attemptedUntil.clear();openAttemptTriggered=false;if(!keepPredictions)predictedChestLocations.clear()}
        private fun getLevelIdentity(client: Minecraft):Int{val level=client.level?:return Int.MIN_VALUE;return System.identityHashCode(level)}
        private fun roundTenth(value:Double):Double=Math.round(value*10.0)/10.0
        private fun distanceSq(pos:Vec3,x:Double,y:Double,z:Double):Double{val dx=pos.x-x;val dy=pos.y-y;val dz=pos.z-z;return dx*dx+dy*dy+dz*dz}
        private fun stripColors(text:String?):String=COLOR_PATTERN.matcher(text?:"").replaceAll("")
    }
}
