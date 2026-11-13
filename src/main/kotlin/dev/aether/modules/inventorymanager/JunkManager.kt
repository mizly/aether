package dev.aether.modules.inventorymanager

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.farming.FarmingTools
import dev.aether.modules.gear.GearManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.PestPrepSwapManager
import dev.aether.modules.visitor.VisitorManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

class JunkManager private constructor(){companion object{
 @JvmField @Volatile var isDropping=false
 @JvmField @Volatile var isPreparingToDrop=false
 @JvmField @Volatile var interactionTime=0L
 @JvmField @Volatile var noJunkStartTime=0L
 private var lastDropTime=0L
 private const val DROP_COOLDOWN_MS=30000L
 @JvmStatic fun reset(){isDropping=false;isPreparingToDrop=false;interactionTime=0;noJunkStartTime=0;lastDropTime=0}
 private fun isPriorityEventActive(client:Minecraft)=MacroStateManager.getCurrentState()!=MacroState.State.FARMING||PestManager.isCleaningInProgress()||PestPrepSwapManager.wasPrepSwappedForCurrentCycle()||(AetherConfig.AUTO_VISITOR.get()&&VisitorManager.getVisitorCount(client)>=AetherConfig.VISITOR_THRESHOLD.get())||BookCombineManager.isCombining||BookCombineManager.isPreparingToCombine||GeorgeManager.isSelling||GeorgeManager.isPreparingToSell||AutoSellManager.isSelling||AutoSellManager.isPreparingToSell
 @JvmStatic fun update(){val client=Minecraft.getInstance();if(!AetherConfig.AUTO_DROP_JUNK.get()||client.player==null)return;if(System.currentTimeMillis()-lastDropTime<DROP_COOLDOWN_MS)return;if(isPreparingToDrop){if(isPriorityEventActive(client)){isPreparingToDrop=false;lastDropTime=System.currentTimeMillis();ClientUtils.sendMessage("§cAborting Junk Drop prep due to priority event.",false)};return};if(isDropping){if(MacroStateManager.getCurrentState()!=MacroState.State.DROPPING_JUNK||PestManager.isCleaningInProgress()||PestPrepSwapManager.wasPrepSwappedForCurrentCycle()){isDropping=false;if(MacroStateManager.getCurrentState()==MacroState.State.DROPPING_JUNK)MacroStateManager.setCurrentState(MacroState.State.FARMING);ClientUtils.sendMessage("§cAborting Junk Drop due to priority event.",false);lastDropTime=System.currentTimeMillis();return};if(client.gui.screen()==null&&System.currentTimeMillis()-interactionTime>1500){val p=client.player;if(countJunkItems(client)>0&&p!=null){interactionTime=System.currentTimeMillis();noJunkStartTime=0;client.execute{client.gui.setScreen(InventoryScreen(p))}}else if(noJunkStartTime==0L)noJunkStartTime=System.currentTimeMillis() else if(System.currentTimeMillis()-noJunkStartTime>=2000){finishDropping(client);noJunkStartTime=0}};return};if(isPriorityEventActive(client)||LoadoutManager.isSwappingLoadout)return;val count=countJunkItems(client);if(count>=AetherConfig.JUNK_THRESHOLD.get())triggerAutomaticDrop(client,count)}
 @JvmStatic fun countJunkItems(client:Minecraft):Int{val p=client.player?:return 0;val junk=ArrayList(AetherConfig.JUNK_ITEMS.get());if(junk.isEmpty())return 0;var count=0;for(i in 0 until 36)if(isJunkItem(p.inventory.getItem(i),junk))count++;return count}
 private fun isJunkItem(stack:ItemStack?,junkList:List<String>):Boolean{if(stack==null||stack.isEmpty)return false;if(FarmingTools.isFarmingTool(stack))return false;val name=stack.hoverName.string.replace(Regex("(?i)\u00A7."),"");for(j in junkList){if(j.isBlank())continue;if(name.contains(j))return true};val lore=stack.get(net.minecraft.core.component.DataComponents.LORE);if(lore!=null)for(line in lore.lines()){val text=line.string.replace(Regex("(?i)\u00A7."),"");for(j in junkList)if(j.isNotBlank()&&text.contains(j))return true};return false}
 private fun triggerAutomaticDrop(client:Minecraft,count:Int){ClientUtils.sendMessage("§eJunk detected ($count items), preparing to drop...",false);ClientUtils.forceReleaseKeys();isPreparingToDrop=true;isDropping=false;MacroWorkerThread.getInstance().submit("JunkDrop-Trigger"){try{MacroWorkerThread.sleep(400);if(!isPreparingToDrop)return@submit;ClientUtils.sendDebugMessage("Disabling farming macro: Preparing to drop junk");client.execute{dev.aether.macro.farming.FarmingMacroManager.disable(client)};MacroWorkerThread.sleep(400);dev.aether.util.CommandUtils.setSpawn();dev.aether.util.CommandUtils.plotTp(AetherConfig.DROP_JUNK_PLOT_TP.get());MacroWorkerThread.sleep(250);if(!isPreparingToDrop)return@submit;isPreparingToDrop=false;MacroStateManager.setCurrentState(MacroState.State.DROPPING_JUNK);isDropping=true;interactionTime=System.currentTimeMillis();noJunkStartTime=0;client.execute{client.gui.setScreen(InventoryScreen(client.player!!))}}catch(e:Exception){e.printStackTrace();isPreparingToDrop=false;isDropping=false}}}
 @JvmStatic fun handleInventoryMenu(client:Minecraft,screen:AbstractContainerScreen<*>?){if(!isDropping||screen !is InventoryScreen)return;val now=System.currentTimeMillis();if(now-interactionTime<ConfigHelpers.getRandomizedDelay(AetherConfig.JUNK_ITEM_DROP_DELAY_MIN.get(),AetherConfig.JUNK_ITEM_DROP_DELAY_MAX.get()))return;var junkSlot=-1;val junk=ArrayList(AetherConfig.JUNK_ITEMS.get());for(i in 9..44){if(i>=screen.menu.slots.size)break;val slot:Slot=screen.menu.slots[i];if(isJunkItem(slot.item,junk)){junkSlot=i;break}};if(junkSlot!=-1){ClientUtils.performSlotClick(screen,junkSlot,1,ContainerInput.THROW);interactionTime=now;noJunkStartTime=0}else if(noJunkStartTime==0L)noJunkStartTime=now else if(now-noJunkStartTime>=2000){finishDropping(client);noJunkStartTime=0}}
 private fun finishDropping(client:Minecraft){val p=client.player;if(p!=null&&client.gui.screen()!=null)p.closeContainer();isDropping=false;lastDropTime=System.currentTimeMillis();if(MacroStateManager.getCurrentState()==MacroState.State.DROPPING_JUNK)MacroStateManager.setCurrentState(MacroState.State.FARMING);ClientUtils.sendMessage("§aJunk drop finished. Resuming script...",true);MacroWorkerThread.getInstance().submit("JunkDrop-Finish"){try{dev.aether.util.CommandUtils.warpGarden();MacroWorkerThread.sleep(250);ClientUtils.waitForGearAndGui();if(MacroStateManager.getCurrentState()==MacroState.State.FARMING)client.execute{GearManager.swapToFarmingTool(client);ClientUtils.sendDebugMessage("Restarting farming macro after junk drop");dev.aether.macro.farming.FarmingMacroManager.enable(client,dev.aether.macro.farming.FarmingMacroManager.createMacroFromConfig())}}catch(_:Exception){}}}
}}
