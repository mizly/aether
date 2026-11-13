package dev.aether.modules.inventorymanager

import dev.aether.config.AetherConfig
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot

class BoosterCookieManager private constructor(){companion object{
 @JvmField @Volatile var interactionTime=0L
 @JvmField @Volatile var interactionStage=0
 @JvmStatic fun handleBoosterCookieMenu(client:Minecraft,screen:AbstractContainerScreen<*>?){if(!AetherConfig.AUTOSELL_PASSIVE.get()||screen==null||client.player==null)return;val title=screen.title.string.lowercase();if(title!="booster cookie"&&title!="trades")return;if(interactionTime==0L){interactionTime=System.currentTimeMillis();return};val now=System.currentTimeMillis();if(now-interactionTime<ClientUtils.getGuiClickDelayMs(interactionStage==0))return;if(interactionStage==0){val slots=screen.menu.slots;val start=slots.size-36;for(i in start until slots.size){val slot:Slot=slots[i];if(!slot.hasItem())continue;val name=slot.item.hoverName.string.replace(Regex("(?i)\\u00A7."),"").lowercase();for(target in AetherConfig.AUTO_SELL_ITEMS.get()){if(target.isBlank())continue;if(name.contains(target.lowercase())){ClientUtils.performSlotClick(screen,i,0,ContainerInput.QUICK_MOVE);interactionTime=now;break}};if(interactionTime==now)break}}}
}}
