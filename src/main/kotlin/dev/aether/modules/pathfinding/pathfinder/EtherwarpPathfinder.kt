package dev.aether.modules.pathfinding.pathfinder

import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.debug.PathVisualizer
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper
import dev.aether.modules.pathfinding.movement.WalkabilityChecker
import dev.aether.modules.pathfinding.wrapper.PathPosition
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap
import java.util.LinkedList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.Function
import java.util.function.Predicate

class EtherwarpPathfinder(private val mc: Minecraft, private val checker: WalkabilityChecker) {
    private val aborted = AtomicBoolean(false)
    private val candidateCache: MutableMap<CandidateCacheKey, kotlin.collections.List<PathPosition>> = HashMap()
    private val opennessCache = Long2DoubleOpenHashMap()
    private val balconyIsolationCache = Long2DoubleOpenHashMap()
    @Volatile private var exploredCount: Long = 0
    init { opennessCache.defaultReturnValue(Double.NaN); balconyIsolationCache.defaultReturnValue(Double.NaN) }

    fun findPath(start: PathPosition, target: PathPosition): CompletionStage<Result?> = CompletableFuture.supplyAsync { findPathSync(start, target) }
    fun findPathSync(start: PathPosition, target: PathPosition): Result? = search(start.floor(), target.floor())
    fun abort() { aborted.set(true) }
    fun getExploredCount(): Long = exploredCount

    private fun search(start: PathPosition, target: PathPosition): Result? {
        exploredCount=0
        if(mc.level==null||mc.player==null||!EtherwarpHelper.isValidLandingFeet(checker,target))return null
        val direct=searchPass(start,target,BASE_MAX_EXPANSIONS,0.0);exploredCount+=direct.exploredCount()
        if(direct.path()!=null||aborted.get())return direct.path()?.let{Result(it,exploredCount)}
        val detour=searchPass(start,target,DETOUR_MAX_EXPANSIONS,DETOUR_HEURISTIC_SLACK);exploredCount+=detour.exploredCount()
        return detour.path()?.let{Result(it,exploredCount)}
    }
    private fun searchPass(start:PathPosition,target:PathPosition,maxExpansions:Int,slack:Double):SearchOutcome{
        val open=java.util.PriorityQueue<SearchNode>(Comparator.comparingDouble<SearchNode>{it.priorityCost()}.thenComparingInt{it.warps()}.thenComparingDouble{it.heuristicCost()}.thenComparingDouble{-it.balconyScore()}.thenComparingDouble{-it.opennessScore()})
        val warps=Long2IntOpenHashMap();warps.defaultReturnValue(Int.MAX_VALUE);val distances=Long2DoubleOpenHashMap();distances.defaultReturnValue(Double.NEGATIVE_INFINITY)
        val h=heuristicCost(start,target,slack);val o=opennessScore(start);open.add(SearchNode(start,null,0,0.0,h,o,0.0,priorityCost(0,h,o,0.0)));warps.put(pack(start),0);distances.put(pack(start),0.0);var count=0L
        while(!open.isEmpty()&&count<maxExpansions){if(aborted.get())return SearchOutcome(null,count);val current=open.poll();val key=pack(current.position());if(current.warps()!=warps.get(key)||current.shortcutDistance()<distances.get(key))continue;count++
            if(PathVisualizer.shouldCaptureExploredNodes())PathVisualizer.addExplored(current.position().flooredX(),current.position().flooredY(),current.position().flooredZ());if(current.position()==target)return SearchOutcome(buildPath(current),count);if(current.warps()>=MAX_WARPS)continue
            if(EtherwarpHelper.canEtherwarp(mc,checker,current.position(),target)){var parent=current;while(true){val ancestor=parent.parent()?:break;if(!EtherwarpHelper.canEtherwarp(mc,checker,ancestor.position(),target))break;parent=ancestor};val goal=SearchNode(target,parent,parent.warps()+1,parent.position().distance(target),0.0,0.0,0.0,(parent.warps()+1).toDouble());return SearchOutcome(buildPath(goal),count)}
            val neighbors=ArrayList(getCandidates(current.position(),target));neighbors.sortWith(Comparator.comparingDouble<PathPosition>{prioritizedHeuristicCost(current.position(),it,target,slack)}.thenComparingDouble{-balconyScore(current.position(),it)}.thenComparingDouble{-opennessScore(it)}.thenComparingDouble{-current.position().distance(it)})
            for(neighbor in neighbors){var parent=current;var nextWarps=current.warps()+1;val ancestor=current.parent();if(ancestor!=null&&EtherwarpHelper.canEtherwarp(mc,checker,ancestor.position(),neighbor)){parent=ancestor;nextWarps=ancestor.warps()+1};val nkey=pack(neighbor);val shortcut=parent.position().distance(neighbor);val prior=warps.get(nkey);if(nextWarps>prior||(nextWarps==prior&&shortcut<=distances.get(nkey)))continue;warps.put(nkey,nextWarps);distances.put(nkey,shortcut);val nh=heuristicCost(neighbor,target,slack);val no=opennessScore(neighbor);val nb=balconyScore(parent.position(),neighbor);open.add(SearchNode(neighbor,parent,nextWarps,shortcut,nh,no,nb,priorityCost(nextWarps,nh,no,nb)))}
        };return SearchOutcome(null,count)
    }
    private fun getCandidates(from:PathPosition,target:PathPosition):kotlin.collections.List<PathPosition>{val key=CandidateCacheKey(pack(from),pack(target));candidateCache[key]?.let{return it};val candidates=ArrayList<PathPosition>();val seen=LongOpenHashSet();val eye=EtherwarpHelper.getEyePosition(mc,from)?:return emptyList<PathPosition>();val rotation=rotationToTargetBlock(eye,target);for(p in scanBalconyCandidates(from,eye,rotation))if(seen.add(pack(p)))candidates.add(p);val goals=ArrayList<PathPosition>();addGoalDirectedCandidates(from,target,eye,goals,seen);candidates.addAll(goals);if(hasStrongGoalProgress(from,target,goals)){val result=candidates.toList();candidateCache[key]=result;return result};for(radius in TARGET_RAYCAST_RING_RADII){if(aborted.get())return emptyList();scanRaycastRing(from,eye,rotation,radius,candidates,seen);if(candidates.size>=TARGET_RAYCAST_CANDIDATE_LIMIT)break};val result=candidates.toList();candidateCache[key]=result;return result}
    private fun scanBalconyCandidates(from:PathPosition,eye:Vec3,rotation:Rotation):kotlin.collections.List<PathPosition>{val out=ArrayList<PathPosition>();val seen=LongOpenHashSet();val columns=LongOpenHashSet();for(pitch in BALCONY_SCAN_PITCHES)for(yaw in BALCONY_SCAN_YAW_OFFSETS){if(aborted.get()||out.size>=UPPER_CANDIDATE_LIMIT)break;val hit=raycastBlock(eye,rotation.yaw()+yaw,pitch)?:continue;if(!columns.add(BlockPos.asLong(hit.x,0,hit.z)))continue;val landing=findUpperLanding(eye,hit,from.flooredY()+1,Function{b->EtherwarpHelper.resolveTargetFeet(checker,b.x,b.y,b.z)},Predicate{p->isUsefulUpperLanding(from,p)&&EtherwarpHelper.findVisibleTargetPoint(mc,checker,eye,p)!=null},BooleanSupplier{aborted.get()});if(landing!=null&&seen.add(pack(landing)))out.add(landing)};return out.stream().sorted(Comparator.comparingDouble<PathPosition>{-balconyScore(from,it)}.thenComparingDouble{-from.distance(it)}).toList()}
    private fun isUsefulUpperLanding(from:PathPosition,p:PathPosition):Boolean{val rise=p.flooredY()-from.flooredY();return rise>0&&(rise>=HIGH_VANTAGE_MIN_RISE||balconyIsolationScore(p)>=BALCONY_MIN_ISOLATION)}
    private fun addGoalDirectedCandidates(from:PathPosition,target:PathPosition,eye:Vec3,out:MutableList<PathPosition>,seen:LongOpenHashSet){val ideal=idealHopPosition(from,target);val dx=target.x-from.x;val dz=target.z-from.z;val d=Math.sqrt(dx*dx+dz*dz);val px=if(d<1.0e-6)1.0 else -dz/d;val pz=if(d<1.0e-6)0.0 else dx/d;for(offset in GOAL_LATERAL_OFFSETS)addGoalColumnCandidate(from,target,eye,Mth.floor(ideal.x+px*offset),Mth.floor(ideal.z+pz*offset),ideal.flooredY(),out,seen)}
    private fun addGoalColumnCandidate(from:PathPosition,target:PathPosition,eye:Vec3,x:Int,z:Int,expected:Int,out:MutableList<PathPosition>,seen:LongOpenHashSet){val tried=LongOpenHashSet();for(base in intArrayOf(expected,from.flooredY(),target.flooredY()))for(offset in GOAL_VERTICAL_OFFSETS){val y=base+offset-1;if(!tried.add(BlockPos.asLong(x,y,z)))continue;val landing=EtherwarpHelper.resolveTargetFeet(checker,x,y,z)?:continue;if(landing==from||!seen.add(pack(landing)))continue;if(EtherwarpHelper.findVisibleTargetPoint(mc,checker,eye,landing)!=null){out.add(landing);return}}}
    private fun hasStrongGoalProgress(from:PathPosition,target:PathPosition,candidates:kotlin.collections.List<PathPosition>):Boolean{val distance=from.distance(target);val required=Math.min(GOAL_DIRECTED_MIN_PROGRESS,distance*0.35);for(p in candidates)if(p.distance(target)<=distance-required)return true;return false}
    private fun scanRaycastRing(from:PathPosition,eye:Vec3,center:Rotation,radius:Int,out:MutableList<PathPosition>,seen:LongOpenHashSet){if(radius==0){addRaycastCandidate(from,eye,center.yaw(),center.pitch(),out,seen);return};for(o in arrayOf(intArrayOf(-radius,-radius),intArrayOf(0,-radius),intArrayOf(radius,-radius),intArrayOf(-radius,0),intArrayOf(radius,0),intArrayOf(-radius,radius),intArrayOf(0,radius),intArrayOf(radius,radius)))addRaycastCandidate(from,eye,center.yaw()+o[0],center.pitch()+o[1],out,seen)}
    private fun addRaycastCandidate(from:PathPosition,eye:Vec3,yaw:Double,pitch:Double,out:MutableList<PathPosition>,seen:LongOpenHashSet){if(out.size>=TARGET_RAYCAST_CANDIDATE_LIMIT||aborted.get())return;val hit=raycastBlock(eye,yaw,pitch)?:return;val landing=EtherwarpHelper.resolveTargetFeet(checker,hit.x,hit.y,hit.z);if(landing!=null&&landing!=from&&seen.add(pack(landing))&&EtherwarpHelper.findVisibleTargetPoint(mc,checker,eye,landing)!=null)out.add(landing)}
    private fun raycastBlock(eye:Vec3,yaw:Double,pitch:Double):BlockPos?{val level=mc.level?:return null;val player=mc.player?:return null;val dir=directionFromRotation(Mth.wrapDegrees(yaw).toFloat(),Mth.clamp(pitch,-84.0,84.0).toFloat());val hit:BlockHitResult=level.clip(ClipContext(eye,eye.add(dir.scale(EtherwarpHelper.MAX_ETHERWARP_DISTANCE)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));return if(hit.type==HitResult.Type.BLOCK)hit.blockPos else null}
    private fun rotationToTargetBlock(eye:Vec3,target:PathPosition):Rotation{val b=EtherwarpHelper.getTargetBlock(target);val delta=Vec3(b.x+0.5,b.y+0.5,b.z+0.5).subtract(eye);val h=Math.sqrt(delta.x*delta.x+delta.z*delta.z);return Rotation(Mth.wrapDegrees(Math.toDegrees(Math.atan2(-delta.x,delta.z))),Mth.clamp(Math.toDegrees(Math.atan2(-delta.y,h)),-84.0,84.0))}
    private fun directionFromRotation(yaw:Float,pitch:Float):Vec3{val yr=yaw.toDouble()*(Math.PI/180.0);val pr=pitch.toDouble()*(Math.PI/180.0);val c=Math.cos(pr);return Vec3(-Math.sin(yr)*c,-Math.sin(pr),Math.cos(yr)*c)}
    private fun prioritizedHeuristicCost(c:PathPosition,p:PathPosition,t:PathPosition,s:Double)=heuristicCost(p,t,s)-opennessScore(p)*OPENNESS_PRIORITY_WEIGHT-balconyScore(c,p)*BALCONY_PRIORITY_WEIGHT
    private fun balconyScore(from:PathPosition,to:PathPosition)=balconyPriority(balconyIsolationScore(to),to.flooredY()-from.flooredY())
    private fun balconyIsolationScore(p:PathPosition):Double{val key=pack(p);val cached=balconyIsolationCache.get(key);if(!java.lang.Double.isNaN(cached))return cached;val x=p.flooredX();val y=p.flooredY()-1;val z=p.flooredZ();var supported=0.0;var total=0.0;for(dx in -BALCONY_RADIUS..BALCONY_RADIUS)for(dz in -BALCONY_RADIUS..BALCONY_RADIUS){if(dx==0&&dz==0)continue;val w=1.0/(1.0+Math.sqrt((dx*dx+dz*dz).toDouble()));total+=w;if(checker.hasWalkableTop(x+dx,y,z+dz))supported+=w};val result=supportIsolationScore(supported,total)*opennessScore(p);balconyIsolationCache.put(key,result);return result}
    private fun opennessScore(p:PathPosition):Double{val key=pack(p);val cached=opennessCache.get(key);if(!java.lang.Double.isNaN(cached))return cached;val value=computeOpennessScore(p);opennessCache.put(key,value);return value}
    private fun computeOpennessScore(p:PathPosition):Double{val x=p.flooredX();val y=p.flooredY();val z=p.flooredZ();var open=0.0;var total=0.0;for(dy in 0..2)for(dx in -OPENNESS_RADIUS..OPENNESS_RADIUS)for(dz in -OPENNESS_RADIUS..OPENNESS_RADIUS){if(dx==0&&dz==0&&dy<=1)continue;val w=opennessSampleWeight(dx,dy,dz);total+=w;open+=w*opennessSampleScore(x+dx,y+dy,z+dz)};return if(total<=0.0)0.0 else open/total}
    private fun opennessSampleScore(x:Int,y:Int,z:Int):Double{if(checker.isDangerous(x,y,z))return 0.0;if(checker.isAir(x,y,z))return 1.0;return if(checker.isPassable(x,y,z))OPENNESS_PASSABLE_SCORE else 0.0}
    companion object{
        private const val OPENNESS_RADIUS=3;private const val BALCONY_RADIUS=4;private const val BASE_MAX_EXPANSIONS=192;private const val DETOUR_MAX_EXPANSIONS=64;private const val MAX_WARPS=12;private const val TARGET_RAYCAST_CANDIDATE_LIMIT=24;private const val UPPER_CANDIDATE_LIMIT=8
        private val TARGET_RAYCAST_RING_RADII=intArrayOf(0,1,2,3,5,8,13,21,34,55,84);private const val GOAL_DIRECTED_MAX_HOP=57.0;private const val GOAL_DIRECTED_MIN_PROGRESS=18.0;private val GOAL_LATERAL_OFFSETS=doubleArrayOf(0.0,-2.0,2.0,-5.0,5.0,-9.0,9.0);private val GOAL_VERTICAL_OFFSETS=intArrayOf(0,-1,1,-2,2,-4,4,-8,8)
        private const val DETOUR_HEURISTIC_SLACK=12.0;private const val WARP_COUNT_HEURISTIC_WEIGHT=0.75;private const val CONTINUOUS_HEURISTIC_WEIGHT=0.25;private const val VERTICAL_HEURISTIC_WEIGHT=0.05;private const val OPENNESS_PRIORITY_WEIGHT=0.18;private const val BALCONY_PRIORITY_WEIGHT=0.90;private const val BALCONY_MIN_ISOLATION=0.30;private const val HIGH_VANTAGE_MIN_RISE=5;private const val OPENNESS_PASSABLE_SCORE=0.35
        private val BALCONY_SCAN_PITCHES=doubleArrayOf(0.0,-10.0,-22.0,-35.0,-48.0,-62.0);private val BALCONY_SCAN_YAW_OFFSETS=doubleArrayOf(0.0,10.0,-10.0,20.0,-20.0,30.0,-30.0,60.0,-60.0,90.0,-90.0,120.0,-120.0,150.0,-150.0,180.0)
        @JvmStatic fun findUpperLanding(eye:Vec3,hit:BlockPos,minY:Int,resolve:Function<BlockPos,PathPosition?>,reachable:Predicate<PathPosition>,aborted:BooleanSupplier):PathPosition?{val dx=Math.max(0.0,Math.abs(hit.x+0.5-eye.x)-0.5);val dz=Math.max(0.0,Math.abs(hit.z+0.5-eye.z)-0.5);val vertical=EtherwarpHelper.MAX_ETHERWARP_DISTANCE_SQ+1.0-dx*dx-dz*dz;if(vertical<0.0)return null;val maxY=Mth.floor(eye.y+Math.sqrt(vertical));for(y in minY-1..maxY){if(aborted.asBoolean)break;val p=resolve.apply(BlockPos(hit.x,y,hit.z));if(p!=null&&p.flooredY()>=minY&&reachable.test(p))return p};return null}
        @JvmStatic fun idealHopPosition(from:PathPosition,to:PathPosition):PathPosition{val d=from.distance(to);if(d<=GOAL_DIRECTED_MAX_HOP)return to;val n=Math.max(1,Math.ceil(d/GOAL_DIRECTED_MAX_HOP).toInt());val f=1.0/n;return PathPosition(from.x+(to.x-from.x)*f,from.y+(to.y-from.y)*f,from.z+(to.z-from.z)*f)}
        @JvmStatic fun heuristicCost(from:PathPosition,to:PathPosition,slack:Double):Double{val d=Math.max(0.0,from.distance(to)-slack);val n=d/EtherwarpHelper.MAX_ETHERWARP_DISTANCE;val w=if(d<=1.0e-6)0.0 else Math.ceil(n-1.0e-6);return w*WARP_COUNT_HEURISTIC_WEIGHT+n*CONTINUOUS_HEURISTIC_WEIGHT+Math.abs(from.y-to.y)/EtherwarpHelper.MAX_ETHERWARP_DISTANCE*VERTICAL_HEURISTIC_WEIGHT}
        @JvmStatic fun supportIsolationScore(supported:Double,total:Double):Double=if(total<=0.0)0.0 else Mth.clamp(1.0-supported/total,0.0,1.0)
        @JvmStatic fun balconyPriority(isolation:Double,rise:Int):Double{val bonus=Mth.clamp(rise/8.0,0.0,1.0);return Mth.clamp(isolation,0.0,1.0)*0.40+bonus*0.60}
        private fun priorityCost(w:Int,h:Double,o:Double,b:Double)=w+h-o*OPENNESS_PRIORITY_WEIGHT-b*BALCONY_PRIORITY_WEIGHT
        private fun opennessSampleWeight(dx:Int,dy:Int,dz:Int):Double{val d=Math.sqrt((dx*dx+dz*dz).toDouble());val w=1.0/(1.0+d);if(dy==2){if(d<0.5)return 0.8;return w*0.5};return w}
        private fun pack(p:PathPosition)=BlockPos.asLong(p.flooredX(),p.flooredY(),p.flooredZ())
        private fun buildPath(goal:SearchNode):kotlin.collections.List<Node>{val path=LinkedList<Node>();var cursor:SearchNode?=goal;while(cursor!=null){val node=Node(cursor.position());node.moveType=Node.MoveType.ETHERWARP;node.isKeynode=true;path.addFirst(node);cursor=cursor.parent()};return path.toList()}
    }
    class Result(private val path:kotlin.collections.List<Node>,private val exploredCount:Long){fun path():kotlin.collections.List<Node> = path;fun exploredCount()=exploredCount}
    private class SearchOutcome(private val path:kotlin.collections.List<Node>?,private val exploredCount:Long){fun path():kotlin.collections.List<Node>? = path;fun exploredCount()=exploredCount}
    private class CandidateCacheKey(private val from:Long,private val target:Long){override fun equals(other:Any?)=other is CandidateCacheKey&&from==other.from&&target==other.target;override fun hashCode()=java.util.Objects.hash(from,target)}
    private class Rotation(private val yaw:Double,private val pitch:Double){fun yaw()=yaw;fun pitch()=pitch}
    private class SearchNode(private val position:PathPosition,private val parent:SearchNode?,private val warps:Int,private val shortcutDistance:Double,private val heuristicCost:Double,private val opennessScore:Double,private val balconyScore:Double,private val priorityCost:Double){fun position()=position;fun parent()=parent;fun warps()=warps;fun shortcutDistance()=shortcutDistance;fun heuristicCost()=heuristicCost;fun opennessScore()=opennessScore;fun balconyScore()=balconyScore;fun priorityCost()=priorityCost}
}
