package dev.aether.modules.gear

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroWorkerThread
import dev.aether.mixin.AccessorInventory
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.farming.FarmingTools
import dev.aether.modules.gear.helpers.LoadoutManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore

class GearManager private constructor(){companion object{
 @Volatile private var pendingFinalResumeRetries=0
 private const val MAX_FINAL_RESUME_RETRIES=3
 private const val FINAL_RESUME_RETRY_DELAY_MS=700L
 @JvmStatic fun reset(){LoadoutManager.resetState();pendingFinalResumeRetries=0}
 @JvmStatic fun triggerPrepSwap(client:Minecraft){}
 @JvmStatic fun triggerLoadoutSwap(client:Minecraft,slot:Int)=LoadoutManager.triggerLoadoutSwap(client,slot)
 @JvmStatic fun ensureLoadoutSlot(client:Minecraft,slot:Int)=LoadoutManager.ensureLoadoutSlot(client,slot)
 @JvmStatic fun handleLoadoutMenu(client:Minecraft,screen:AbstractContainerScreen<*>)=LoadoutManager.handleLoadoutMenu(client,screen)
 @JvmStatic fun finalResume(client:Minecraft){if(PestManager.isCleaningInProgress())return;if(AutoPestExchangeManager.shouldBlockFarmingResume()){pendingFinalResumeRetries=0;ClientUtils.sendDebugMessage("Final resume deferred because pest exchange has priority.");return};if(!hasAnyGearSwapTasksEnabled()){pendingFinalResumeRetries=0;client.execute{if(PestManager.isCleaningInProgress())return@execute;dev.aether.macro.MacroStateManager.setCurrentState(dev.aether.macro.MacroState.State.FARMING);swapToFarmingTool(client);ClientUtils.sendDebugMessage("Finalizing gear swap. Restarting farming macro...");dev.aether.macro.farming.FarmingMacroManager.enable(client,dev.aether.macro.farming.FarmingMacroManager.createMacroFromConfig())};return};ClientUtils.waitForGearAndGui();swapToFarmingToolSync(client);if(!waitForContainerCloseSync(client,3500)){ClientUtils.sendDebugMessage("§cFinalizing gear swap aborted: container did not close in time. Not restarting script yet.");scheduleFinalResumeRetry(client);return};pendingFinalResumeRetries=0;if(PestManager.isCleaningInProgress())return;client.execute{if(PestManager.isCleaningInProgress())return@execute;dev.aether.macro.MacroStateManager.setCurrentState(dev.aether.macro.MacroState.State.FARMING);swapToFarmingTool(client);ClientUtils.sendDebugMessage("Finalizing gear swap. Restarting farming macro...");dev.aether.macro.farming.FarmingMacroManager.enable(client,dev.aether.macro.farming.FarmingMacroManager.createMacroFromConfig())}}
 @JvmStatic fun hasAnyGearSwapTasksEnabled():Boolean{if(!AetherConfig.AUTO_LOADOUT_ENABLED.get())return false;val farming=AetherConfig.LOADOUT_SLOT_FARMING.get();return AetherConfig.LOADOUT_SLOT_PEST.get()!=farming||AetherConfig.LOADOUT_SLOT_PEST_KILL.get()!=farming||AetherConfig.LOADOUT_SLOT_VISITOR.get()!=farming}
 private fun scheduleFinalResumeRetry(client:Minecraft){if(pendingFinalResumeRetries>=MAX_FINAL_RESUME_RETRIES){ClientUtils.sendDebugMessage("§cFinalizing gear swap retry limit reached. Manual restart may be required.");return};pendingFinalResumeRetries++;val attempt=pendingFinalResumeRetries;ClientUtils.sendDebugMessage("§eRetrying final resume ($attempt/$MAX_FINAL_RESUME_RETRIES)...");MacroWorkerThread.getInstance().submit("GearManager-FinalResumeRetry-$attempt"){MacroWorkerThread.sleep(FINAL_RESUME_RETRY_DELAY_MS);if(PestManager.isCleaningInProgress())return@submit;finalResume(client)}}
 private fun waitForContainerCloseSync(client:Minecraft,timeoutMs:Long):Boolean{val start=System.currentTimeMillis();var lastForceCloseAttempt=0L;while(System.currentTimeMillis()-start<timeoutMs){val player=client.player?:return false;val hasScreen=client.gui.screen()!=null;val hasContainer=player.containerMenu!=null&&player.inventoryMenu!=null&&player.containerMenu.containerId!=player.inventoryMenu.containerId;if(!hasScreen&&!hasContainer)return true;if(hasContainer&&System.currentTimeMillis()-lastForceCloseAttempt>=250){client.execute{val screen=client.gui.screen();if(screen!=null)screen.onClose()else client.gui.setScreen(null)};lastForceCloseAttempt=System.currentTimeMillis()};try{Thread.sleep(50)}catch(_:InterruptedException){return false}};return false}
 @JvmStatic fun findAspectOfTheVoidSlot(client:Minecraft):Int{val player=client.player?:return -1;for(i in 0 until 36){val stack=player.inventory.getItem(i);if(!stack.isEmpty){val name=stack.hoverName.string;if(name.contains("Aspect of the Void")||name.contains("Aspect of the End")||name.contains("AOTV")||name.contains("AOTE"))return i}};return -1}
 @JvmStatic fun findEtherwarpAspectOfTheVoidHotbarSlot(client:Minecraft):Int{val player=client.player?:return -1;for(i in 0..8){val stack=player.inventory.getItem(i);if(stack.isEmpty||!isAspectOfTheVoid(stack)||!hasEtherTransmission(stack))continue;return i};return -1}
 @JvmStatic fun findFarmingToolSlot(client:Minecraft):Int{val player=client.player?:return -1;for(i in 0..8){val stack=player.inventory.getItem(i);if(!stack.isEmpty&&FarmingTools.isFarmingTool(stack))return i};return -1}
 @JvmStatic fun findHotbarItemSlot(client:Minecraft,nameFragment:String?):Int{val player=client.player?:return -1;if(nameFragment.isNullOrBlank())return -1;val needle=nameFragment.lowercase();val current=player.mainHandItem;if(!current.isEmpty&&current.hoverName.string.lowercase().contains(needle))return (player.inventory as AccessorInventory).getSelected();for(i in 0..8){val stack=player.inventory.getItem(i);if(!stack.isEmpty&&stack.hoverName.string.lowercase().contains(needle))return i};return -1}
 @JvmStatic fun swapToAOTV(client:Minecraft){val slot=findAspectOfTheVoidSlot(client);if(slot!=-1)client.execute{FailsafeManager.selectHotbarSlot(client,slot)}}
 @JvmStatic fun swapToAOTVSync(client:Minecraft){val slot=findAspectOfTheVoidSlot(client);if(slot==-1)return;client.execute{FailsafeManager.selectHotbarSlot(client,slot)};if(!client.isSameThread)waitForSlot(client,slot)}
 @JvmStatic fun swapToEtherwarpAotvSync(client:Minecraft):Boolean{val slot=findEtherwarpAspectOfTheVoidHotbarSlot(client);if(slot==-1)return false;client.execute{FailsafeManager.selectHotbarSlot(client,slot)};if(!client.isSameThread)waitForSlot(client,slot);return true}
 @JvmStatic fun swapToFarmingTool(client:Minecraft){val slot=findFarmingToolSlot(client);if(slot!=-1){val p=client.player?:return;val name=p.inventory.getItem(slot).hoverName.string;FailsafeManager.selectHotbarSlot(client,slot);ClientUtils.sendMessage("Equipped farming tool: $name",true)}else ClientUtils.sendDebugMessage("§c[GearManager] No farming tool found in hotbar!")}
 @JvmStatic fun swapToFarmingToolSync(client:Minecraft):Boolean{val slot=findFarmingToolSlot(client);if(slot==-1){ClientUtils.sendDebugMessage("§c[GearManager] Sync swap failed: No farming tool found in hotbar!");return false};if(client.isSameThread)FailsafeManager.selectHotbarSlot(client,slot)else client.execute{FailsafeManager.selectHotbarSlot(client,slot)};if(!client.isSameThread)waitForSlot(client,slot);val p=client.player?:return false;return (p.inventory as AccessorInventory).getSelected()==slot}
 @JvmStatic fun swapToNamedHotbarItemSync(client:Minecraft,nameFragment:String):Boolean{val slot=findHotbarItemSlot(client,nameFragment);if(slot==-1){ClientUtils.sendDebugMessage("§c[GearManager] Sync swap failed: No hotbar item found matching '$nameFragment'!");return false};client.execute{FailsafeManager.selectHotbarSlot(client,slot)};if(!client.isSameThread)waitForSlot(client,slot);return true}
 private fun waitForSlot(client:Minecraft,slot:Int){try{val start=System.currentTimeMillis();while(System.currentTimeMillis()-start<1500){val player=client.player?:return;if((player.inventory as AccessorInventory).getSelected()==slot)break;Thread.sleep(10)}}catch(_:InterruptedException){}}
 @JvmStatic fun cleanupTick(){val client=Minecraft.getInstance();LoadoutManager.cancelIfDisabled(client);LoadoutManager.tickWardrobeWatchdog();if(LoadoutManager.loadoutCleanupTicks>0){LoadoutManager.loadoutCleanupTicks--;val p=client.player;if(p!=null)try{p.containerMenu?.let{it.setCarried(ItemStack.EMPTY);it.broadcastChanges()};p.inventoryMenu?.let{it.setCarried(ItemStack.EMPTY);it.broadcastChanges()};p.closeContainer()}catch(_:Exception){};if(client.mouseHandler!=null)client.mouseHandler.releaseMouse()}}
 private fun isAspectOfTheVoid(stack:ItemStack):Boolean{val name=stack.hoverName.string;return name.contains("Aspect of the Void")||name.contains("Aspect of the End")||name.contains("AOTV")||name.contains("AOTE")}
 private fun hasEtherTransmission(stack:ItemStack):Boolean{val lore:ItemLore=stack.get(DataComponents.LORE)?:return false;for(line:Component in lore.lines())if(line.string.contains("Ether Transmission"))return true;return false}
}}
