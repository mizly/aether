package dev.aether.modules.farming

import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

class UngrabMouse private constructor(){companion object{
 @Volatile private var mouseUngrabbed=false
 @Volatile private var macroRequested=false
 @Volatile private var visualRequested=false
 @Volatile private var freecamSuspend=false
 @Volatile private var freelookSuspend=false
 @Volatile private var restoreOnResume=false
 private fun isSuspended()=freecamSuspend||freelookSuspend
 @JvmStatic fun isMouseUngrabbed():Boolean{val mc=Minecraft.getInstance();return mouseUngrabbed&&!isSuspended()&&mc.player!=null&&mc.level!=null&&mc.gui.screen()==null}
 @JvmStatic fun isVisualUngrabEnabled()=visualRequested
 @JvmStatic fun ungrabMouse()=requestMacroUngrab()
 @JvmStatic fun regrabMouse()=clearMacroUngrab()
 @JvmStatic fun requestMacroUngrab(){macroRequested=true;syncRequestedState()}
 @JvmStatic fun clearMacroUngrab(){macroRequested=false;syncRequestedState()}
 @JvmStatic fun requestVisualUngrab(){visualRequested=true;syncRequestedState()}
 @JvmStatic fun clearVisualUngrab(){visualRequested=false;syncRequestedState()}
 @JvmStatic fun suspendForFreecam()=suspend(true)
 @JvmStatic fun resumeAfterFreecam()=resume(true)
 @JvmStatic fun suspendForFreelook()=suspend(false)
 @JvmStatic fun resumeAfterFreelook()=resume(false)
 private fun suspend(freecam:Boolean){val wasSuspended=isSuspended();if(freecam)freecamSuspend=true else freelookSuspend=true;if(wasSuspended)return;restoreOnResume=mouseUngrabbed||isUngrabRequested()||restoreOnResume;val wasUngrabbed=mouseUngrabbed;mouseUngrabbed=false;if(!wasUngrabbed)return;val mc=Minecraft.getInstance();mc.execute{if(!mc.mouseHandler.isMouseGrabbed()&&mc.gui.screen()==null){mc.mouseHandler.grabMouse();ClientUtils.reapplyProgrammaticKeyStates(mc)}}}
 private fun resume(freecam:Boolean){if(freecam)freecamSuspend=false else freelookSuspend=false;if(isSuspended())return;val shouldRestore=restoreOnResume;restoreOnResume=false;if(shouldRestore)syncRequestedState()}
 private fun syncRequestedState(){val shouldUngrab=isUngrabRequested();if(isSuspended()){restoreOnResume=shouldUngrab;return};if(mouseUngrabbed==shouldUngrab)return;mouseUngrabbed=shouldUngrab;val mc=Minecraft.getInstance();mc.execute{if(shouldUngrab){if(mc.mouseHandler.isMouseGrabbed())mc.mouseHandler.releaseMouse();return@execute};if(!mc.mouseHandler.isMouseGrabbed()&&mc.gui.screen()==null){mc.mouseHandler.grabMouse();ClientUtils.reapplyProgrammaticKeyStates(mc)}}}
 private fun isUngrabRequested()=macroRequested||visualRequested
}}
