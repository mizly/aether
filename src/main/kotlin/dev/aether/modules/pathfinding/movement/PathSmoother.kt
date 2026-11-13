package dev.aether.modules.pathfinding.movement

import dev.aether.modules.pathfinding.Node
import java.util.ArrayList

class PathSmoother private constructor() {
    companion object {
        private const val OPEN_SKIP_BUDGET = 12
        private const val MID_SKIP_BUDGET = 7
        private const val TIGHT_SKIP_BUDGET = 4
        private const val OPEN_WALL_SCORE_MAX = 2
        private const val MID_WALL_SCORE_MAX = 6
        private const val CONSTRAINED_CORNER_ANGLE_DEG = 35.0

        @JvmStatic fun smooth(raw: kotlin.collections.List<Node>?, checker: WalkabilityChecker?): kotlin.collections.List<Node>? {
            if (raw == null || raw.size <= 2) return raw
            val nodes: kotlin.collections.List<Node> = WalkingStepNormalizer.normalize(raw, checker)
            val result = ArrayList<Node>(); result.add(nodes[0]); var anchorIdx = 0
            while (anchorIdx < nodes.size - 1) {
                val anchor = nodes[anchorIdx]; var furthest = anchorIdx + 1
                val maxCand = Math.min(nodes.size - 1, anchorIdx + computeAdaptiveSkipBudget(anchor, checker))
                for (cand in anchorIdx + 2..maxCand) {
                    val prev = nodes[cand - 1]; val candidate = nodes[cand]
                    if (prev.position.flooredY() != anchor.position.flooredY() || candidate.position.flooredY() != anchor.position.flooredY()) break
                    if (checker != null && wouldSkipConstrainedCorner(nodes, anchorIdx, cand, checker)) break
                    if (checker != null && hasLineOfSight(anchor, candidate, checker)) furthest = cand else break
                }
                anchorIdx = furthest; result.add(nodes[anchorIdx])
            }
            return result.toList()
        }

        @JvmStatic fun smoothFly(raw: kotlin.collections.List<Node>?, checker: WalkabilityChecker): kotlin.collections.List<Node>? {
            if (raw == null || raw.size <= 2) return raw
            val result = ArrayList<Node>(); result.add(raw[0]); var anchorIdx = 0
            while (anchorIdx < raw.size - 1) {
                val anchor = raw[anchorIdx]; var furthest = anchorIdx + 1
                for (cand in anchorIdx + 2 until raw.size) { if (hasFlyLineOfSight(anchor, raw[cand], checker)) furthest = cand else break }
                anchorIdx = furthest; result.add(raw[anchorIdx])
            }
            return result.toList()
        }

        private fun hasLineOfSight(from: Node, to: Node, checker: WalkabilityChecker): Boolean {
            val x0=from.position.flooredX(); val y0=from.position.flooredY(); val z0=from.position.flooredZ(); val x1=to.position.flooredX(); val y1=to.position.flooredY(); val z1=to.position.flooredZ()
            val dx=Math.abs(x1-x0); val dy=Math.abs(y1-y0); val dz=Math.abs(z1-z0); val sx=if(x0<x1)1 else -1; val sy=if(y0<y1)1 else -1; val sz=if(z0<z1)1 else -1
            var x=x0; var y=y0; var z=z0
            if(dx>=dy&&dx>=dz){var e1=2*dy-dx;var e2=2*dz-dx;for(i in 0 until dx){if(!clearForSmoothing(checker,x,y,z))return false;val px=x;val pz=z;if(e1>0){y+=sy;e1-=2*dx};if(e2>0){z+=sz;e2-=2*dx};e1+=2*dy;e2+=2*dz;x+=sx;if(diagonalStepBlocked(checker,px,pz,x,y,z))return false}}
            else if(dy>=dx&&dy>=dz){var e1=2*dx-dy;var e2=2*dz-dy;for(i in 0 until dy){if(!clearForSmoothing(checker,x,y,z))return false;val px=x;val pz=z;if(e1>0){x+=sx;e1-=2*dy};if(e2>0){z+=sz;e2-=2*dy};e1+=2*dx;e2+=2*dz;y+=sy;if(diagonalStepBlocked(checker,px,pz,x,y,z))return false}}
            else{var e1=2*dx-dz;var e2=2*dy-dz;for(i in 0 until dz){if(!clearForSmoothing(checker,x,y,z))return false;val px=x;val pz=z;if(e1>0){x+=sx;e1-=2*dz};if(e2>0){y+=sy;e2-=2*dz};e1+=2*dx;e2+=2*dy;z+=sz;if(diagonalStepBlocked(checker,px,pz,x,y,z))return false}}
            return clearForSmoothing(checker,x1,y1,z1)
        }
        private fun hasFlyLineOfSight(from:Node,to:Node,checker:WalkabilityChecker):Boolean {
            val x0=from.position.flooredX();val y0=from.position.flooredY();val z0=from.position.flooredZ();val x1=to.position.flooredX();val y1=to.position.flooredY();val z1=to.position.flooredZ()
            val dx=Math.abs(x1-x0);val dy=Math.abs(y1-y0);val dz=Math.abs(z1-z0);val sx=if(x0<x1)1 else -1;val sy=if(y0<y1)1 else -1;val sz=if(z0<z1)1 else -1;var x=x0;var y=y0;var z=z0
            if(dx>=dy&&dx>=dz){var e1=2*dy-dx;var e2=2*dz-dx;for(i in 0 until dx){if(checker.isSolid(x,y,z))return false;if(e1>0){y+=sy;e1-=2*dx};if(e2>0){z+=sz;e2-=2*dx};e1+=2*dy;e2+=2*dz;x+=sx}}
            else if(dy>=dx&&dy>=dz){var e1=2*dx-dy;var e2=2*dz-dy;for(i in 0 until dy){if(checker.isSolid(x,y,z))return false;if(e1>0){x+=sx;e1-=2*dy};if(e2>0){z+=sz;e2-=2*dy};e1+=2*dx;e2+=2*dz;y+=sy}}
            else{var e1=2*dx-dz;var e2=2*dy-dz;for(i in 0 until dz){if(checker.isSolid(x,y,z))return false;if(e1>0){x+=sx;e1-=2*dz};if(e2>0){y+=sy;e2-=2*dz};e1+=2*dx;e2+=2*dy;z+=sz}}
            return !checker.isSolid(x1,y1,z1)
        }
        private fun clearForSmoothing(checker:WalkabilityChecker,x:Int,y:Int,z:Int)=checker.isWalkable(x,y,z)
        private fun computeAdaptiveSkipBudget(anchor:Node,checker:WalkabilityChecker?):Int{val score=computeWallScore(anchor,checker);if(score<=OPEN_WALL_SCORE_MAX)return OPEN_SKIP_BUDGET;if(score<=MID_WALL_SCORE_MAX)return MID_SKIP_BUDGET;return TIGHT_SKIP_BUDGET}
        private fun computeWallScore(anchor:Node,checker:WalkabilityChecker?):Int{if(checker==null)return 0;val x=anchor.position.flooredX();val y=anchor.position.flooredY();val z=anchor.position.flooredZ();var score=0;val dx=intArrayOf(1,-1,0,0,1,1,-1,-1);val dz=intArrayOf(0,0,1,-1,1,-1,1,-1);for(i in dx.indices){val nx=x+dx[i];val nz=z+dz[i];if(checker.isFullWall(nx,y,nz))score++;if(checker.isFullWall(nx,y+1,nz))score++};return score}
        private fun wouldSkipConstrainedCorner(raw:kotlin.collections.List<Node>,anchorIdx:Int,candidateIdx:Int,checker:WalkabilityChecker):Boolean{for(i in anchorIdx+1 until candidateIdx)if(isConstrainedCorner(raw,i,checker))return true;return false}
        private fun isConstrainedCorner(raw:kotlin.collections.List<Node>,idx:Int,checker:WalkabilityChecker):Boolean{val prev=raw[idx-1];val cur=raw[idx];val next=raw[idx+1];val ix=cur.position.centeredX()-prev.position.centeredX();val iz=cur.position.centeredZ()-prev.position.centeredZ();val ox=next.position.centeredX()-cur.position.centeredX();val oz=next.position.centeredZ()-cur.position.centeredZ();val il=Math.sqrt(ix*ix+iz*iz);val ol=Math.sqrt(ox*ox+oz*oz);if(il<1.0e-3||ol<1.0e-3)return false;val dot=(ix/il)*(ox/ol)+(iz/il)*(oz/ol);val angle=Math.toDegrees(Math.acos(Math.max(-1.0,Math.min(1.0,dot))));return angle>=CONSTRAINED_CORNER_ANGLE_DEG&&computeWallScore(cur,checker)>0}
        private fun diagonalStepBlocked(checker:WalkabilityChecker,fromX:Int,fromZ:Int,toX:Int,toY:Int,toZ:Int):Boolean{if(fromX==toX||fromZ==toZ)return false;return sideCellBlocked(checker,toX,toY,fromZ)||sideCellBlocked(checker,fromX,toY,toZ)}
        private fun sideCellBlocked(checker:WalkabilityChecker,x:Int,y:Int,z:Int):Boolean = !checker.isPassable(x,y,z)||!checker.isPassable(x,y+1,z)||checker.isFullWall(x,y,z)||checker.isFullWall(x,y+1,z)
    }
}
