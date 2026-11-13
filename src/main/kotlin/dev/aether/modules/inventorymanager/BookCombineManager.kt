package dev.aether.modules.inventorymanager

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.MacroWorkerThread
import dev.aether.util.ClientUtils
import dev.aether.util.EnchantmentUtils
import dev.aether.modules.gear.GearManager
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.PestPrepSwapManager
import dev.aether.modules.visitor.VisitorManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import java.util.LinkedHashMap
import java.util.ArrayList
import java.util.regex.Pattern

class BookCombineManager private constructor() {
 companion object {
  @JvmField @Volatile var interactionTime=0L
  @JvmField @Volatile var interactionStage=0
  @Volatile private var pendingSlot0=-1
  @Volatile private var pendingSlot1=-1
  @JvmField @Volatile var isCombining=false
  @JvmField @Volatile var isPreparingToCombine=false
  @JvmStatic fun reset(){isCombining=false;isPreparingToCombine=false;interactionStage=0;interactionTime=0;pendingSlot0=-1;pendingSlot1=-1}
  @JvmStatic fun handleAnvilMenu(client: Minecraft,screen: AbstractContainerScreen<*>?) {
   if(!AetherConfig.AUTO_BOOK_COMBINE.get()||screen==null||client.player==null)return
   if(!AetherConfig.ALWAYS_ACTIVE_COMBINE.get()&&!isCombining)return
   val now=System.currentTimeMillis();val delay=if(interactionStage==0)ClientUtils.getGuiClickDelayMs(true) else ConfigHelpers.getRandomizedDelay(AetherConfig.BOOK_COMBINE_DELAY.get()).toLong();if(now-interactionTime<delay)return
   if(!screen.title.string.lowercase().contains("anvil"))return
   val total=screen.menu.slots.size;if(total<54)return
   if(interactionStage==0){val pairs=getInventoryBooks(screen);for((key,slots) in pairs){if(slots.size<2||isMaxLevel(key))continue;pendingSlot0=slots[0];pendingSlot1=slots[1];ClientUtils.sendMessage("\u00A77BookCombine: combining "+key+"' (slots "+pendingSlot0+" + "+pendingSlot1+")",true);ClientUtils.performSlotClick(screen,pendingSlot0,0,ContainerInput.QUICK_MOVE);interactionTime=now;interactionStage=1;return};pendingSlot0=-1;pendingSlot1=-1;finishCombining(client)
   }else if(interactionStage==1){if(pendingSlot1==-1){interactionStage=0;return};ClientUtils.performSlotClick(screen,pendingSlot1,0,ContainerInput.QUICK_MOVE);interactionTime=now;interactionStage=2
   }else if(interactionStage==2){ClientUtils.performSlotClick(screen,22,0,ContainerInput.PICKUP);interactionTime=now;interactionStage=3
   }else if(interactionStage==3){ClientUtils.performSlotClick(screen,22,0,ContainerInput.PICKUP);interactionTime=now;interactionStage=0;pendingSlot0=-1;pendingSlot1=-1;val more=getInventoryBooks(screen).any{it.value.size>=2&&!isMaxLevel(it.key)};if(!more&&isCombining)finishCombining(client)}
  }
  private fun isPriorityEventActive(client: Minecraft)=dev.aether.macro.MacroStateManager.getCurrentState()!=dev.aether.macro.MacroState.State.FARMING||PestManager.isCleaningInProgress()||PestPrepSwapManager.wasPrepSwappedForCurrentCycle()||(AetherConfig.AUTO_VISITOR.get()&&VisitorManager.getVisitorCount(client)>=AetherConfig.VISITOR_THRESHOLD.get())||LoadoutManager.isSwappingLoadout||AutoSellManager.isSelling||AutoSellManager.isPreparingToSell||GeorgeManager.isSelling||GeorgeManager.isPreparingToSell||JunkManager.isDropping||JunkManager.isPreparingToDrop||(AetherConfig.AUTO_DROP_JUNK.get()&&JunkManager.countJunkItems(client)>=AetherConfig.JUNK_THRESHOLD.get())
  @JvmStatic fun update(){val client=Minecraft.getInstance();val p=client.player;if(!AetherConfig.AUTO_BOOK_COMBINE.get()||p==null)return;if(isPreparingToCombine){if(isPriorityEventActive(client)){isPreparingToCombine=false;ClientUtils.sendMessage("\u00A7cAborting Book Combine prep due to priority event.",false)};return};if(isCombining){if(isPriorityEventActive(client)){isCombining=false;ClientUtils.sendMessage("\u00A7cAborting Book Combine due to priority event.",false);return};if(client.gui.screen()==null&&System.currentTimeMillis()-interactionTime>1500)finishCombining(client);return};if(isPriorityEventActive(client))return;val count=countBooksInInventory(p);if(count>=AetherConfig.BOOK_THRESHOLD.get())triggerAutomaticCombine(client,count)}
  private fun triggerAutomaticCombine(client: Minecraft,count:Int){ClientUtils.sendMessage("\u00A7eAuto combining books ("+count+" books in inventory)...",false);ClientUtils.forceReleaseKeys();isPreparingToCombine=true;isCombining=false;MacroWorkerThread.getInstance().submit("BookCombine-Trigger"){try{if(MacroWorkerThread.shouldAbortTask(client,dev.aether.macro.MacroState.State.FARMING)){isPreparingToCombine=false;return@submit};ClientUtils.sendDebugMessage("Disabling farming macro: Preparing book combine");client.execute{dev.aether.macro.farming.FarmingMacroManager.disable(client)};MacroWorkerThread.sleep(400);if(isPreparingToCombine){isPreparingToCombine=false;isCombining=true;interactionStage=0;interactionTime=System.currentTimeMillis();ClientUtils.sendCommand("/av")}}catch(e:Exception){e.printStackTrace()}}}
  private fun finishCombining(client: Minecraft){val automatic=isCombining;isCombining=false;isPreparingToCombine=false;interactionStage=0;interactionTime=0;pendingSlot0=-1;pendingSlot1=-1;if(!automatic)return;val p=client.player;if(p!=null&&client.gui.screen()!=null)client.execute{p.closeContainer()};if(p!=null)ClientUtils.sendMessage("\u00A7aBook Combine finished. Resuming script...",true);if(dev.aether.macro.MacroStateManager.getCurrentState()==dev.aether.macro.MacroState.State.FARMING)MacroWorkerThread.getInstance().submit("BookCombine-Finish"){if(MacroWorkerThread.shouldAbortTask(client,dev.aether.macro.MacroState.State.FARMING))return@submit;val started=System.currentTimeMillis();while(client.gui.screen()!=null&&System.currentTimeMillis()-started<3000)MacroWorkerThread.sleep(50);MacroWorkerThread.sleep(300);if(MacroWorkerThread.shouldAbortTask(client,dev.aether.macro.MacroState.State.FARMING))return@submit;client.execute{GearManager.swapToFarmingTool(client)};MacroWorkerThread.sleep(200);if(MacroWorkerThread.shouldAbortTask(client,dev.aether.macro.MacroState.State.FARMING))return@submit;ClientUtils.sendDebugMessage("Restarting farming macro after book combine");client.execute{dev.aether.macro.farming.FarmingMacroManager.enable(client,dev.aether.macro.farming.FarmingMacroManager.createMacroFromConfig())}}
  }
  @JvmStatic fun getBookCountInInventory(client: Minecraft):Int{val p=client.player?:return 0;return countBooksInInventory(p)}
  private fun countBooksInInventory(player: net.minecraft.world.entity.player.Player):Int{var count=0;for(i in 0 until 36){val stack=player.inventory.getItem(i);if(!stack.isEmpty&&stack.item.toString().lowercase().contains("enchanted_book")&&!isExemptBook(stack))count++};return count}
  private fun isExemptBook(stack:ItemStack):Boolean{val name=stack.hoverName.string.lowercase();if(name.contains("sunder")||name.contains("pesterminator 5")||name.contains("pesterminator v"))return true;val lore:ItemLore?=stack.get(DataComponents.LORE);if(lore!=null)for(line:Component in lore.lines()){val text=line.string.lowercase();if(text.contains("sunder")||text.contains("pesterminator 5")||text.contains("pesterminator v"))return true};return false}
  private fun getInventoryBooks(screen:AbstractContainerScreen<*>):Map<String,MutableList<Int>>{val pairs=LinkedHashMap<String,MutableList<Int>>();val slots=screen.menu.slots;for(i in slots.size-36 until slots.size){val slot:Slot=slots[i];if(!slot.hasItem())continue;val stack=slot.item;if(!stack.item.toString().lowercase().contains("enchanted_book")||isExemptBook(stack))continue;val key=getBookKey(stack)?:continue;pairs.computeIfAbsent(key){ArrayList()}.add(slot.index)};return pairs}
  private fun getBookKey(stack:ItemStack):String?{val lore:ItemLore?=stack.get(DataComponents.LORE);val nonEmpty=ArrayList<String>();if(lore!=null)for(line in lore.lines()){val text=line.string.replace(Regex("(?i)\\u00A7."),"").trim();if(text.isNotEmpty())nonEmpty.add(text)};val pattern=Pattern.compile("(.*)\\s+([IVXLCDM]+|\\d+)$",Pattern.CASE_INSENSITIVE);for(s in nonEmpty){val m=pattern.matcher(s);if(m.find()){val part=m.group(1).trim();val level=m.group(2).trim();val matched=EnchantmentUtils.findEnchantmentNameIn(part);return if(matched!=null)"$matched $level" else s}};for(s in nonEmpty){val matched=EnchantmentUtils.findEnchantmentNameIn(s);if(matched!=null)return matched};if(nonEmpty.isNotEmpty())return nonEmpty[0];val hover=stack.hoverName.string.replace(Regex("(?i)\\u00A7."),"").trim();return if(hover.isEmpty())null else hover}
  private fun isMaxLevel(key:String):Boolean{val space=key.lastIndexOf(' ');var name:String;var level:String;if(space==-1){name=key;level="1"}else{val suffix=key.substring(space+1).trim();if(suffix.matches(Regex("^[IVXLCDM]+$"))||suffix.matches(Regex("^[0-9]+$"))){name=key.substring(0,space).trim();level=suffix}else{name=key;level="1"}};return EnchantmentUtils.parseLevel(level)>=EnchantmentUtils.getMaxLevel(name)}
 }
}
