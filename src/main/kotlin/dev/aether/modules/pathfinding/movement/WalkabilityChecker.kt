package dev.aether.modules.pathfinding.movement

import dev.aether.modules.pathfinding.util.BlockPosUtil
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.BlockPos.MutableBlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.shapes.VoxelShape

class WalkabilityChecker(private val level:Level){
    private val flagCache=Long2ByteOpenHashMap(8192);private val stateCache=Long2ObjectOpenHashMap<BlockState>(8192);private val topYCache=Long2DoubleOpenHashMap(8192);private val fullWallCache=Long2ByteOpenHashMap(8192);private val mutablePos=MutableBlockPos()
    init{flagCache.defaultReturnValue(0.toByte());topYCache.defaultReturnValue(TOP_Y_NOT_CACHED);fullWallCache.defaultReturnValue(0.toByte())}
    fun clearCache(){flagCache.clear();stateCache.clear();topYCache.clear();fullWallCache.clear()}
    fun getLevel():Level=level
    fun isSolid(x:Int,y:Int,z:Int)=getFlags(x,y,z).toInt() and FLAG_SOLID.toInt()!=0
    fun isPassable(x:Int,y:Int,z:Int)=getFlags(x,y,z).toInt() and FLAG_PASSABLE.toInt()!=0
    fun isAir(x:Int,y:Int,z:Int)=getState(x,y,z).isAir
    fun isWalkable(x:Int,y:Int,z:Int)=isPassable(x,y,z)&&isPassable(x,y+1,z)&&hasWalkableTop(x,y-1,z)&&!isDangerous(x,y,z)&&!isDangerous(x,y+1,z)
    fun isDangerous(x:Int,y:Int,z:Int)=getFlags(x,y,z).toInt() and FLAG_DANGEROUS.toInt()!=0
    fun isWater(x:Int,y:Int,z:Int)=getFlags(x,y,z).toInt() and FLAG_WATER.toInt()!=0
    fun isClimbable(x:Int,y:Int,z:Int)=getFlags(x,y,z).toInt() and FLAG_CLIMBABLE.toInt()!=0
    fun hasWalkableTop(x:Int,y:Int,z:Int)=getTopY(x,y,z)>=0.5
    fun isFullWallBlock(x:Int,y:Int,z:Int):Boolean{val key=BlockPosUtil.pack(x,y,z);val cached=fullWallCache.get(key);if(cached.toInt()!=0)return cached.toInt()==2;val state=getState(x,y,z);val result=state.canOcclude()&&state.isCollisionShapeFullBlock(level,mutablePos.set(x,y,z));fullWallCache.put(key,if(result)2.toByte()else 1.toByte());return result}
    fun isFullWall(x:Int,y:Int,z:Int)=isSolid(x,y,z)&&getTopY(x,y,z)>=0.95
    fun wouldSuffocate(x:Int,y:Int,z:Int)=isSolid(x,y+1,z)&&!isPassable(x,y+1,z)
    fun safeToFall(fromX:Int,fromY:Int,fromZ:Int,toX:Int,toY:Int,toZ:Int):Boolean{if(fromY-toY<=3)return true;if(isWater(toX,toY,toZ))return true;for(y in toY+1 until fromY)if(isWater(toX,y,toZ))return true;return false}
    fun getTopY(x:Int,y:Int,z:Int):Double{val key=BlockPosUtil.pack(x,y,z);val cached=topYCache.get(key);if(!java.lang.Double.isNaN(cached))return cached;val shape=getState(x,y,z).getCollisionShape(level,mutablePos.set(x,y,z));val top=if(shape.isEmpty)0.0 else shape.bounds().maxY;topYCache.put(key,top);return top}
    fun canOcclude(x:Int,y:Int,z:Int)=getState(x,y,z).canOcclude()
    fun getState(x:Int,y:Int,z:Int):BlockState{val key=BlockPosUtil.pack(x,y,z);var state=stateCache.get(key);if(state!=null)return state;state=level.getBlockState(mutablePos.set(x,y,z));stateCache.put(key,state);return state}
    private fun getFlags(x:Int,y:Int,z:Int):Byte{val key=BlockPosUtil.pack(x,y,z);val cached=flagCache.get(key);if(cached.toInt() and FLAG_COMPUTED.toInt()!=0)return cached;val flags=computeFlags(x,y,z);flagCache.put(key,(flags.toInt() or FLAG_COMPUTED.toInt()).toByte());return flags}
    private fun computeFlags(x:Int,y:Int,z:Int):Byte{val state=getState(x,y,z);var flags:Byte=0;val top=getTopY(x,y,z);if(top>=0.5&&!isStatePassable(state,x,y,z))flags=(flags.toInt() or FLAG_SOLID.toInt()).toByte();if(isStatePassable(state,x,y,z))flags=(flags.toInt() or FLAG_PASSABLE.toInt()).toByte();val fluid:FluidState=state.getFluidState();if(!fluid.isEmpty&&fluid.type.isSame(net.minecraft.world.level.material.Fluids.WATER))flags=(flags.toInt() or FLAG_WATER.toInt()).toByte();if(state.block is LiquidBlock&&state.block==Blocks.WATER)flags=(flags.toInt() or FLAG_WATER.toInt()).toByte();if(state.block is LadderBlock||state.block is VineBlock)flags=(flags.toInt() or FLAG_CLIMBABLE.toInt()).toByte();val block=state.block;if(block==Blocks.LAVA||block==Blocks.FIRE||block==Blocks.SOUL_FIRE||block==Blocks.MAGMA_BLOCK||block==Blocks.CACTUS||block==Blocks.SWEET_BERRY_BUSH)flags=(flags.toInt() or FLAG_DANGEROUS.toInt()).toByte();val fs=state.getFluidState();if(!fs.isEmpty&&fs.type.isSame(net.minecraft.world.level.material.Fluids.LAVA))flags=(flags.toInt() or FLAG_DANGEROUS.toInt()).toByte();return flags}
    private fun isStatePassable(state:BlockState,x:Int,y:Int,z:Int):Boolean{if(state.isAir)return true;val block=state.block;if(block is BushBlock||block is TallGrassBlock||block is FlowerBlock||block is DoublePlantBlock||block is TorchBlock||block is WallTorchBlock)return true;if(block is LiquidBlock||block is SignBlock||block is BannerBlock||block is WallBannerBlock)return true;if(block is CarpetBlock||block is BasePressurePlateBlock||block is BaseRailBlock)return true;if(block==Blocks.SNOW||block is ButtonBlock||block is VineBlock||block is LadderBlock)return true;if(block is TrapDoorBlock)return state.getValue(TrapDoorBlock.OPEN);if(block is DoorBlock)return state.getValue(DoorBlock.OPEN);if(block is FenceGateBlock)return state.getValue(FenceGateBlock.OPEN);val shape:VoxelShape=state.getCollisionShape(level,mutablePos.set(x,y,z));return shape==null||shape.isEmpty}
    companion object{private const val FLAG_SOLID:Byte=0x01;private const val FLAG_PASSABLE:Byte=0x02;private const val FLAG_DANGEROUS:Byte=0x04;private const val FLAG_WATER:Byte=0x08;private const val FLAG_CLIMBABLE:Byte=0x10;private const val FLAG_COMPUTED:Byte=0x80.toByte();private const val TOP_Y_NOT_CACHED=Double.NaN}
}
