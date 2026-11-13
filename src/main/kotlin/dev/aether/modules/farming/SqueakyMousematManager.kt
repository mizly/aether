package dev.aether.modules.farming

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroWorkerThread
import dev.aether.mixin.AccessorInventory
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import java.util.regex.Matcher
import java.util.regex.Pattern

class SqueakyMousematManager private constructor() {
    companion object {
        private const val CLIENT_THREAD_TIMEOUT_MS = 1000L
        private const val SLOT_SWAP_TIMEOUT_MS = 1000L
        private const val MOUSEMAT_DELAY_MS = 250L
        private const val MOUSEMAT_ROTATION_SETTLE_TIMEOUT_MS = 3000L
        private const val MOUSEMAT_ROTATION_SETTLE_POLL_MS = 50L
        private const val MOUSEMAT_RETRY_DELAY_MS = 5000L
        private const val MAX_MOUSEMAT_ATTEMPTS = 3
        private const val ROTATION_MATCH_TOLERANCE_DEGREES = 2f
        private const val ITEM_NAME_FRAGMENT = "squeaky mousemat"
        private val SELECTED_YAW_PATTERN = Pattern.compile("Selected Yaw:\\s*(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE)
        private val SELECTED_PITCH_PATTERN = Pattern.compile("Selected Pitch:\\s*(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE)
        private val COOLDOWN = MousematCooldown(System::nanoTime)
        @Volatile private var reapplyAttemptArmed = false
        @JvmStatic fun armReapplyAttempt() { reapplyAttemptArmed = true }
        @JvmStatic fun clearReapplyAttempt() { reapplyAttemptArmed = false }
        @JvmStatic fun onAttack(client: Minecraft) { val player = client.player; if (player != null && isMousemat(player.mainHandItem)) COOLDOWN.recordUse() }
        @JvmStatic fun shouldUseBeforeFarming(client: Minecraft): Boolean { if (!consumeReapplyAttempt() || !AetherConfig.SQUEAKY_MOUSEMAT.get() || shouldSkipForPestExchange()) return false; val snapshot=getMousematSnapshot(client)?:return false; return isCurrentRotationDifferent(client,snapshot) }
        private fun consumeReapplyAttempt(): Boolean { val armed=reapplyAttemptArmed; reapplyAttemptArmed=false; return armed }
        @JvmStatic fun useIfNeeded(client: Minecraft): Boolean {
            if (!AetherConfig.SQUEAKY_MOUSEMAT.get() || shouldSkipForPestExchange()) return false
            val snapshot=getMousematSnapshot(client)?:return false
            if (!isCurrentRotationDifferent(client,snapshot)) return true
            client.execute(RotationManager::cancelRotation)
            for(attempt in 1..MAX_MOUSEMAT_ATTEMPTS) {
                if(!COOLDOWN.await({ MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)||shouldSkipForPestExchange() }, MacroWorkerThread::sleep)) { restoreFarmingToolIfExchangeHasPriority(client); return false }
                if(!selectHotbarSlotSync(client,snapshot.slot())) return false
                if(!sleepMousematDelay(client)) { restoreFarmingToolIfExchangeHasPriority(client); return false }
                if(shouldSkipForPestExchange()) { restoreFarmingToolIfExchangeHasPriority(client); return false }
                ClientUtils.performAttackClick(); FailsafeManager.addRotationGracePeriod(MOUSEMAT_ROTATION_SETTLE_TIMEOUT_MS+MOUSEMAT_DELAY_MS)
                if(waitForMousematRotation(client,snapshot)) { FailsafeManager.syncExpectedRotationFromClient(client); return true }
                if(attempt>=MAX_MOUSEMAT_ATTEMPTS) break
                ClientUtils.sendDebugMessage("Mousemat rotation mismatch after use, retrying in 5s ($attempt/$MAX_MOUSEMAT_ATTEMPTS)")
                if(!sleepMousematRetryDelay(client)) { restoreFarmingToolIfExchangeHasPriority(client); return false }
            }
            ClientUtils.sendDebugMessage("Mousemat rotation mismatch after 3 attempts"); return false
        }
        @JvmStatic fun getMacroRotationOverride(client: Minecraft?): RotationSnapshot? {
            if (!AetherConfig.SQUEAKY_MOUSEMAT.get() || shouldSkipForPestExchange()) return null
            val nonNullClient = client ?: return null
            if (getMousematSnapshot(nonNullClient) == null) return null
            return queryClientThread(nonNullClient, Supplier {
                val player = nonNullClient.player ?: return@Supplier null
                RotationSnapshot(Mth.wrapDegrees(player.yRot), Mth.clamp(player.xRot, -90.0f, 90.0f))
            }, null)
        }
        private fun sleepMousematDelay(client: Minecraft):Boolean { if(MacroWorkerThread.shouldAbortTask(client,MacroState.State.FARMING)||shouldSkipForPestExchange())return false;return MacroWorkerThread.sleep(MOUSEMAT_DELAY_MS)&&!MacroWorkerThread.shouldAbortTask(client,MacroState.State.FARMING)&&!shouldSkipForPestExchange() }
        private fun waitForMousematRotation(client: Minecraft,snapshot:MousematSnapshot):Boolean { val deadline=System.currentTimeMillis()+MOUSEMAT_ROTATION_SETTLE_TIMEOUT_MS;while(System.currentTimeMillis()<deadline){if(MacroWorkerThread.shouldAbortTask(client,MacroState.State.FARMING)||shouldSkipForPestExchange())return false;if(!isCurrentRotationDifferent(client,snapshot))return true;if(!MacroWorkerThread.sleep(MOUSEMAT_ROTATION_SETTLE_POLL_MS))return false};return !isCurrentRotationDifferent(client,snapshot) }
        private fun sleepMousematRetryDelay(client: Minecraft):Boolean { if(MacroWorkerThread.shouldAbortTask(client,MacroState.State.FARMING)||shouldSkipForPestExchange())return false;return MacroWorkerThread.sleep(MOUSEMAT_RETRY_DELAY_MS)&&!MacroWorkerThread.shouldAbortTask(client,MacroState.State.FARMING)&&!shouldSkipForPestExchange() }
        private fun shouldSkipForPestExchange()=AutoPestExchangeManager.shouldBlockFarmingResume()
        private fun restoreFarmingToolIfExchangeHasPriority(client:Minecraft){if(shouldSkipForPestExchange())GearManager.swapToFarmingToolSync(client)}
        private fun selectHotbarSlotSync(client:Minecraft,slot:Int):Boolean { if(slot !in 0..8)return false;client.execute{FailsafeManager.selectHotbarSlot(client,slot)};val start=System.currentTimeMillis();while(System.currentTimeMillis()-start<SLOT_SWAP_TIMEOUT_MS){if(MacroWorkerThread.shouldAbortTask(client,MacroState.State.FARMING))return false;if(getSelectedHotbarSlot(client)==slot)return true;if(!MacroWorkerThread.sleep(10))return false};return getSelectedHotbarSlot(client)==slot }
        private fun getMousematSnapshot(client: Minecraft): MousematSnapshot? = queryClientThread(client, Supplier {
            val player = client.player ?: return@Supplier null
            for (slot in 0 until 9) {
                val stack = player.inventory.getItem(slot)
                if (stack.isEmpty || !isMousemat(stack)) continue
                val rotation = readSelectedRotation(stack) ?: continue
                return@Supplier MousematSnapshot(slot, rotation.yaw(), rotation.pitch())
            }
            null
        }, null)
        private fun isCurrentRotationDifferent(client: Minecraft, snapshot: MousematSnapshot): Boolean = queryClientThread(client, Supplier {
            val player = client.player ?: return@Supplier false
            val yawDiff = Math.abs(Mth.wrapDegrees(Mth.wrapDegrees(player.yRot) - Mth.wrapDegrees(snapshot.selectedYaw())))
            val pitchDiff = Math.abs(Mth.clamp(player.xRot, -90.0f, 90.0f) - Mth.clamp(snapshot.selectedPitch(), -90.0f, 90.0f))
            yawDiff > ROTATION_MATCH_TOLERANCE_DEGREES || pitchDiff > ROTATION_MATCH_TOLERANCE_DEGREES
        }, false)
        private fun isMousemat(stack:ItemStack)=TablistUtils.stripColors(stack.hoverName.string).lowercase().contains(ITEM_NAME_FRAGMENT)
        private fun readSelectedRotation(stack: ItemStack): RotationSnapshot? {
            val lore: ItemLore = stack.get(DataComponents.LORE) ?: return null
            var yaw: Float? = null
            var pitch: Float? = null
            for (line: Component in lore.lines()) {
                val text = TablistUtils.stripColors(line.string)
                val yawMatcher = SELECTED_YAW_PATTERN.matcher(text)
                if (yawMatcher.find()) yaw = parseFloat(yawMatcher.group(1))
                val pitchMatcher = SELECTED_PITCH_PATTERN.matcher(text)
                if (pitchMatcher.find()) pitch = parseFloat(pitchMatcher.group(1))
            }
            val selectedYaw = yaw ?: return null
            val selectedPitch = pitch ?: return null
            return RotationSnapshot(selectedYaw, selectedPitch)
        }
        private fun parseFloat(value:String):Float?=try{java.lang.Float.parseFloat(value)}catch(_:NumberFormatException){null}
        private fun getSelectedHotbarSlot(client:Minecraft):Int=queryClientThread(client,Supplier { val player=client.player?:return@Supplier -1;(player.inventory as AccessorInventory).getSelected() },-1)
        private fun <T> queryClientThread(client:Minecraft?,supplier:Supplier<T>,fallback:T):T { if(client==null)return fallback;if(client.isSameThread)return supplier.get();val future=CompletableFuture<T>();client.execute{try{future.complete(supplier.get())}catch(_:Throwable){future.complete(fallback)}};return try{future.get(CLIENT_THREAD_TIMEOUT_MS,TimeUnit.MILLISECONDS)}catch(_:Exception){fallback} }
        private class MousematSnapshot(private val slot: Int, private val selectedYaw: Float, private val selectedPitch: Float) {
            fun slot(): Int = slot
            fun selectedYaw(): Float = selectedYaw
            fun selectedPitch(): Float = selectedPitch
        }
        class RotationSnapshot(private val yaw: Float, private val pitch: Float) {
            fun yaw(): Float = yaw
            fun pitch(): Float = pitch
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is RotationSnapshot) return false
                return java.lang.Float.compare(yaw, other.yaw) == 0 && java.lang.Float.compare(pitch, other.pitch) == 0
            }
            override fun hashCode(): Int = java.util.Objects.hash(yaw, pitch)
            override fun toString(): String = "RotationSnapshot[yaw=$yaw, pitch=$pitch]"
        }
    }
}
