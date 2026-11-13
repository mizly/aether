package dev.aether.modules.pathfinding.movement

import dev.aether.modules.pathfinding.execution.FlightPathClearance
import dev.aether.modules.pathfinding.wrapper.PathPosition
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.function.BiPredicate
import java.util.function.Function

class FlightCollisionChecker {
    private val collisions: Function<AABB, Iterable<AABB>>
    private val clearanceCache=Long2ByteOpenHashMap();private val clearanceCostCache=Long2DoubleOpenHashMap()
    constructor(checker:WalkabilityChecker){clearanceCostCache.defaultReturnValue(Double.NaN);val cache=Long2ObjectOpenHashMap<kotlin.collections.List<AABB>>();collisions=Function{search->val result=ArrayList<AABB>();for(x in Mth.floor(search.minX)-1..Mth.floor(search.maxX)+1)for(z in Mth.floor(search.minZ)-1..Mth.floor(search.maxZ)+1)for(y in Mth.floor(search.minY)-1..Mth.floor(search.maxY)+1){val pos=BlockPos(x,y,z);var boxes=cache.get(pos.asLong());if(boxes==null){boxes=if(checker.getLevel().isOutsideBuildHeight(y)||!checker.getLevel().hasChunkAt(pos)||checker.isDangerous(x,y,z))listOf<AABB>(AABB(pos))else checker.getState(x,y,z).getCollisionShape(checker.getLevel(),pos).toAabbs().map{it.move(pos)};cache.put(pos.asLong(),boxes)};for(box in boxes)if(box.intersects(search))result.add(box)};result}}
    internal constructor(collisions:Function<AABB,Iterable<AABB>>){this.collisions=collisions;clearanceCostCache.defaultReturnValue(Double.NaN)}
    fun hasClearance(position:PathPosition):Boolean{val key=BlockPos.asLong(position.flooredX(),position.flooredY(),position.flooredZ());val cached=clearanceCache.get(key);if(cached.toInt()!=0)return cached.toInt()==2;val clear=isClear(position,position);clearanceCache.put(key,if(clear)2.toByte()else 1.toByte());return clear}
    fun isClear(from:PathPosition,to:PathPosition):Boolean{val start=waypoint(from);return FlightPathClearance.isClear(BODY.move(start),waypoint(to).subtract(start),collisions)}
    fun findStart(feet:Vec3,bounds:AABB):PathPosition?{val origin=PathPosition(feet.x,feet.y,feet.z).floor();var best:PathPosition?=if(canReachStart(feet,bounds,origin))origin else null;var bestDistance=if(best==null)Double.POSITIVE_INFINITY else feet.distanceToSqr(waypoint(best));for(x in -START_SEARCH_RADIUS..START_SEARCH_RADIUS)for(y in -START_SEARCH_RADIUS..START_SEARCH_RADIUS)for(z in -START_SEARCH_RADIUS..START_SEARCH_RADIUS){val candidate=origin.add(x.toDouble(),y.toDouble(),z.toDouble());val target=waypoint(candidate);val distance=feet.distanceToSqr(target);if(distance>=bestDistance||!canReachStart(feet,bounds,candidate))continue;best=candidate;bestDistance=distance};return best}
    private fun canReachStart(feet:Vec3,bounds:AABB,candidate:PathPosition):Boolean{if(!hasClearance(candidate))return false;val target=waypoint(candidate);return FlightPathClearance.isClear(bounds,target.subtract(feet),collisions)||FlightPathClearance.clearCorner(feet,target,BiPredicate{from,to->FlightPathClearance.isClear(bounds.move(from.subtract(feet)),to.subtract(from),collisions)})!=null}
    fun clearanceCost(position:PathPosition):Double{val key=BlockPos.asLong(position.flooredX(),position.flooredY(),position.flooredZ());val cached=clearanceCostCache.get(key);if(!java.lang.Double.isNaN(cached))return cached;val body=BODY.move(waypoint(position));var cost=0.0;if(!isFree(body.expandTowards(0.0,HEADROOM_PROBE,0.0)))cost+=TIGHT_HEADROOM_COST;if(!isFree(body.inflate(NEAR_WALL_PROBE,0.0,NEAR_WALL_PROBE)))cost+=NEAR_WALL_COST else if(!isFree(body.inflate(WIDE_WALL_PROBE,0.0,WIDE_WALL_PROBE)))cost+=WIDE_WALL_COST;clearanceCostCache.put(key,cost);return cost}
    private fun isFree(box:AABB)=FlightPathClearance.isClear(box,Vec3.ZERO,collisions)
    companion object{private val BODY=AABB(-0.35,0.0,-0.35,0.35,1.8,0.35);private const val START_SEARCH_RADIUS=3;private const val HEADROOM_PROBE=0.35;private const val NEAR_WALL_PROBE=0.25;private const val WIDE_WALL_PROBE=0.6;private const val TIGHT_HEADROOM_COST=0.9;private const val NEAR_WALL_COST=0.55;private const val WIDE_WALL_COST=0.3;@JvmStatic fun over(collisions:Function<AABB,Iterable<AABB>>)=FlightCollisionChecker(collisions);@JvmStatic fun waypoint(position:PathPosition)=Vec3(position.flooredX()+0.5,position.flooredY()+0.15,position.flooredZ()+0.5)}
}
