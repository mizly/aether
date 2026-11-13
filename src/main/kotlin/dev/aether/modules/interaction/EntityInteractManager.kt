package dev.aether.modules.interaction

import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.EntityUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

class EntityInteractManager private constructor() {
    companion object {
        private const val PATH_TIMEOUT_MS = 20_000L
        private const val INTERACT_ROTATION_MS = 150L
        private const val INTERACT_RANGE_THRESHOLD = 2.5f
        private const val WALK_TARGET_RADIUS_SQ = 4.0
        @JvmStatic fun start(client: Minecraft?, entityName: String?) {
            if (client == null || client.player == null) return
            val targetName = entityName?.trim() ?: ""
            if (targetName.isEmpty()) { ClientUtils.sendMessage("§eUsage: /aether interact <entity_name>", false); return }
            MacroWorkerThread.getInstance().submit("Interact-$targetName") { run(client, targetName) }
        }
        private fun run(client: Minecraft, entityName: String) {
            if (shouldAbort(client)) return
            var target = EntityUtils.findEntity(client, entityName)
            if (target == null) { ClientUtils.sendMessage("§cCould not find entity: §e$entityName", false); dumpNearbyEntityDebug(client, entityName, "initial lookup"); return }
            ClientUtils.sendMessage("§ePathfinding to §e${target.name.string}§6...", false)
            if (client.player!!.distanceTo(target) > INTERACT_RANGE_THRESHOLD) {
                var walkTarget = findBestWalkingTarget(client, target) ?: target.blockPosition()
                val x=walkTarget.x; val y=walkTarget.y; val z=walkTarget.z; val pathTarget=target
                MacroWorkerThread.runOnClient(client) { PathfindingManager.startPathfind(client,x,y,z,false,pathTarget) }
                MacroWorkerThread.sleepRandom(255,90)
                val deadline=System.currentTimeMillis()+PATH_TIMEOUT_MS
                while(!shouldAbort(client)&&PathfindingManager.isNavigating()&&System.currentTimeMillis()<deadline) MacroWorkerThread.sleep(100)
                if(shouldAbort(client))return
                if(PathfindingManager.isNavigating()){PathfindingManager.stop();ClientUtils.sendMessage("§cTimed out pathfinding to entity: §e$entityName",false);return}
            }
            if(shouldAbort(client))return
            val refreshed=EntityUtils.findEntity(client,entityName)
            if(refreshed!=null)target=refreshed else {ClientUtils.sendDebugMessage("Interact: target missing after pathing for \"$entityName\"");dumpNearbyEntityDebug(client,entityName,"post-path lookup")}
            ClientUtils.sendMessage("§eInteracting with §e${target.name.string}§6...",false)
            val targetPos=Vec3(target.x,target.eyeY,target.z)
            MacroWorkerThread.runOnClient(client){RotationManager.initiateRotation(client,targetPos,INTERACT_ROTATION_MS)}
            MacroWorkerThread.sleepRandom(INTERACT_ROTATION_MS+60L,30L)
            if(shouldAbort(client))return
            MacroWorkerThread.runOnClient(client,ClientUtils::performUseClick)
            MacroWorkerThread.sleepRandom(85,30)
            ClientUtils.sendMessage("§aInteracted with §e${target.name.string}",false)
        }
        private fun shouldAbort(client:Minecraft)=client.player==null||MacroWorkerThread.getInstance().isCancelled()
        private fun dumpNearbyEntityDebug(client:Minecraft,entityName:String,phase:String){ClientUtils.sendDebugMessage("Interact: no match for \"$entityName\" during $phase");for(line in EntityUtils.describeNearbyEntities(client,8.0,12))ClientUtils.sendDebugMessage("Interact: $line")}
        private fun findBestWalkingTarget(client:Minecraft,entity:Entity):BlockPos? {
            if(client.level==null||client.player==null)return null
            val base=entity.blockPosition();var best:BlockPos?=null;var bestDistSq=Double.MAX_VALUE
            for(x in -3..3)for(z in -3..3)for(y in -2..2){val pos=base.offset(x,y,z);if(pos.distToCenterSqr(entity.position())>WALK_TARGET_RADIUS_SQ||!isWalkable(client,pos))continue;val dist=pos.distToCenterSqr(client.player!!.position());if(dist<bestDistSq){bestDistSq=dist;best=pos}}
            return best
        }
        private fun isWalkable(client:Minecraft,pos:BlockPos):Boolean {val level=client.level?:return false;val below=pos.below();val above=pos.above();return !level.getBlockState(below).getCollisionShape(level,below).isEmpty&&level.getBlockState(pos).getCollisionShape(level,pos).isEmpty&&level.getBlockState(above).getCollisionShape(level,above).isEmpty}
    }
}
