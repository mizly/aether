package dev.aether.modules.metaldetector.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroStateManager
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.ArrayList
import java.util.HashSet

class MetalDetectorBackpackManager private constructor() {
    private enum class Phase { IDLE, WAITING_STORAGE_MENU, WAITING_BACKPACK_MENU, FILLING_BACKPACK }
    private class BackpackStatus(val backpackNumber: Int) {
        var emptySlots = -1
        var totalSlots = -1
        fun isFull(): Boolean = emptySlots == 0 && totalSlots >= 0
    }
    companion object {
        private const val SCAVENGED_KEYWORD="scavenged"
        private const val EMPTY_KEYWORD="empty"
        private const val LOCKED_KEYWORD="locked"
        private const val STORAGE_SLOT_START=27
        private const val STORAGE_SLOT_END=44
        private const val PLAYER_INVENTORY_SLOTS=36
        private const val MENU_TIMEOUT_MS=4000L
        private val backpacks=ArrayList<BackpackStatus>()
        private var backpackListKnown=false
        private var currentBackpackIndex=0
        private var phase=Phase.IDLE
        private var phaseStartedAt=0L
        private var lastActionAt=0L
        @JvmStatic fun reset(){backpacks.clear();backpackListKnown=false;currentBackpackIndex=0;phase=Phase.IDLE;phaseStartedAt=0L;lastActionAt=0L}
        @JvmStatic fun shouldHandle(client:Minecraft):Boolean=phase!=Phase.IDLE||getScavengedToolCount(client)>=4
        @JvmStatic fun update(){val client=Minecraft.getInstance();if(client==null||client.player==null)return;advancePastFullBackpacks();when(phase){Phase.IDLE->handleIdle(client);Phase.WAITING_STORAGE_MENU->if(client.gui.screen()==null&&isTimedOut())openStorageMenu(client);Phase.WAITING_BACKPACK_MENU->if(client.gui.screen()==null&&isTimedOut())openCurrentBackpack(client);Phase.FILLING_BACKPACK->if(client.gui.screen()==null){phase=Phase.IDLE;phaseStartedAt=0L}}}
        @JvmStatic fun handleContainerMenu(client:Minecraft?,screen:AbstractContainerScreen<*>?){if(client==null||client.player==null||screen==null)return;if(phase==Phase.WAITING_STORAGE_MENU){scanStorageMenu(client,screen);return};if(phase==Phase.WAITING_BACKPACK_MENU||phase==Phase.FILLING_BACKPACK)handleBackpackMenu(client,screen)}
        @JvmStatic fun getFilledBackpacksSummary():String{if(!backpackListKnown)return "Unknown";if(backpacks.isEmpty())return "0/0";var filled=0;for(backpack in backpacks)if(backpack.isFull())filled++;return "$filled/${backpacks.size}"}
        @JvmStatic fun getScavengedToolCount(client:Minecraft?):Int{val player=client?.player?:return 0;var count=0;for(slot in 0 until PLAYER_INVENTORY_SLOTS)if(isScavengedTool(player.inventory.getItem(slot)))count++;return count}
        private fun handleIdle(client:Minecraft){if(client.gui.screen()!=null||getScavengedToolCount(client)<4)return;if(!backpackListKnown){openStorageMenu(client);return};if(currentBackpackIndex>=backpacks.size){stopForFullBackpacks(client);return};openCurrentBackpack(client)}
        private fun openStorageMenu(client:Minecraft){if(!canPerformAction())return;ClientUtils.sendDebugMessage("MetalDetectorBackpack: opening storage");ClientUtils.sendCommand("/st");markActionPerformed();phase=Phase.WAITING_STORAGE_MENU;phaseStartedAt=System.currentTimeMillis()}
        private fun scanStorageMenu(client:Minecraft,screen:AbstractContainerScreen<*>){if(!canPerformAction())return;backpacks.clear();val blacklisted=getBlacklistedBackpacks();val lastSlot=Math.min(STORAGE_SLOT_END,screen.menu.slots.size-1);for(i in STORAGE_SLOT_START..lastSlot){val slot=screen.menu.slots[i];if(!slot.hasItem())continue;val name=cleanName(slot.item);if(name.contains(EMPTY_KEYWORD)||name.contains(LOCKED_KEYWORD))continue;val number=i-STORAGE_SLOT_START+1;if(blacklisted.contains(number))continue;backpacks.add(BackpackStatus(number))};backpackListKnown=true;currentBackpackIndex=0;advancePastFullBackpacks();phase=Phase.IDLE;phaseStartedAt=0L;client.player!!.closeContainer();markActionPerformed();if(backpacks.isEmpty()){ClientUtils.sendMessage("\u00A7cNo usable backpacks found in /st. Stopping macro.",false);MacroStateManager.stopMacro(client,"MetalDetectorBackpack: no usable backpacks found")}else ClientUtils.sendDebugMessage("MetalDetectorBackpack: found ${backpacks.size} usable backpacks")}
        private fun openCurrentBackpack(client:Minecraft){if(!canPerformAction())return;val current=getCurrentBackpack();if(current==null){stopForFullBackpacks(client);return};ClientUtils.sendDebugMessage("MetalDetectorBackpack: opening backpack ${current.backpackNumber}");ClientUtils.sendCommand("/bp ${current.backpackNumber}");markActionPerformed();phase=Phase.WAITING_BACKPACK_MENU;phaseStartedAt=System.currentTimeMillis()}
        private fun handleBackpackMenu(client:Minecraft,screen:AbstractContainerScreen<*>){val current=getCurrentBackpack();if(current==null){stopForFullBackpacks(client);return};val total=screen.menu.slots.size;if(total<=PLAYER_INVENTORY_SLOTS||!canPerformAction())return;val container=total-PLAYER_INVENTORY_SLOTS;val start=Math.min(9,container);val usable=Math.max(0,container-start);var empty=0;for(i in start until container)if(!screen.menu.slots[i].hasItem())empty++;current.totalSlots=usable;current.emptySlots=empty;phase=Phase.FILLING_BACKPACK;phaseStartedAt=System.currentTimeMillis();if(usable==0||empty==0){currentBackpackIndex++;advancePastFullBackpacks();client.player!!.closeContainer();markActionPerformed();phase=Phase.IDLE;phaseStartedAt=0L;if(currentBackpackIndex>=backpacks.size)stopForFullBackpacks(client);return};val slot=findScavengedInventorySlot(screen,container);if(slot==-1){client.player!!.closeContainer();markActionPerformed();phase=Phase.IDLE;phaseStartedAt=0L;return};ClientUtils.performSlotClick(screen,slot,0,ContainerInput.QUICK_MOVE);markActionPerformed()}
        private fun findScavengedInventorySlot(screen:AbstractContainerScreen<*>,container:Int):Int{for(i in container until screen.menu.slots.size){val slot=screen.menu.slots[i];if(slot.hasItem()&&isScavengedTool(slot.item))return i};return -1}
        private fun getCurrentBackpack():BackpackStatus?=if(currentBackpackIndex<0||currentBackpackIndex>=backpacks.size)null else backpacks[currentBackpackIndex]
        private fun advancePastFullBackpacks(){while(currentBackpackIndex<backpacks.size){val b=backpacks[currentBackpackIndex];if(b.emptySlots!=0||b.totalSlots<0)break;currentBackpackIndex++}}
        private fun isTimedOut():Boolean=phaseStartedAt>0L&&System.currentTimeMillis()-phaseStartedAt>=MENU_TIMEOUT_MS
        private fun canPerformAction():Boolean=System.currentTimeMillis()-lastActionAt>=ClientUtils.getGuiClickDelayMs(lastActionAt==0L)
        private fun markActionPerformed(){lastActionAt=System.currentTimeMillis()}
        private fun stopForFullBackpacks(client:Minecraft){ClientUtils.sendMessage("\u00A7eAll known backpacks are full. Stopping macro.",false);MacroStateManager.stopMacro(client,"MetalDetectorBackpack: all backpacks are full")}
        private fun isScavengedTool(stack:ItemStack?):Boolean=stack!=null&&!stack.isEmpty&&cleanName(stack).contains(SCAVENGED_KEYWORD)
        private fun getBlacklistedBackpacks():Set<Int>{val result=HashSet<Int>();for(raw in AetherConfig.METAL_DETECTOR_BACKPACK_BLACKLIST.get()){if(raw==null)continue;val value=raw.trim();if(!value.matches(Regex("\\d+")))continue;try{val number=Integer.parseInt(value);if(number in 1..18)result.add(number)}catch(_:NumberFormatException){}};return result}
        private fun cleanName(stack:ItemStack):String=TablistUtils.stripColors(stack.hoverName.string).trim().lowercase()
    }
}
