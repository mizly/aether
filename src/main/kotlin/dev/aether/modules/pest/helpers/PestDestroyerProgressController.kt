package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft

// run-level completion, timeout and plot-priority checks, kept out of the state handlers so the coordinator only orchestrates
internal class PestDestroyerProgressController private constructor() {
    interface Context {
        fun shouldFinishForAliveCount(client: Minecraft, aliveCount: Int): Boolean

        fun getAliveFinishReason(aliveCount: Int): String

        fun filterSkippedInfestedPlots(plots: Set<String>): Set<String>

        fun getEffectivePlot(client: Minecraft): String

        fun plotsEqual(first: String, second: String): Boolean

        fun setState(state: PestDestroyer.State)

        fun finish(client: Minecraft)
    }

    companion object {
        @JvmStatic
        fun tick(
            client: Minecraft,
            runtime: PestDestroyerRuntime,
            stuckTimeoutMs: Long,
            context: Context
        ): Boolean {
            val aliveNow = PestManager.getPestDestroyerCompletionAliveCountNow(client)
            val finishReading = aliveNow >= 0 &&
                context.shouldFinishForAliveCount(client, aliveNow)

            if (finishReading) {
                if (PestCompletionGuard.shouldAcceptFinishReading(runtime.activatedAt, false)) {
                    runtime.zeroPestTabTicks++
                    if (PestCompletionGuard.isConfirmed(runtime.zeroPestTabTicks)) {
                        ClientUtils.setKeyMappingState(client.options.keyUse, false)
                        ClientUtils.setKeyMappingState(client.options.keyDown, false)
                        ClientUtils.sendDebugMessage(
                            "PestDestroyer: tablist reports " +
                                context.getAliveFinishReason(aliveNow) +
                                ". Finishing."
                        )
                        context.finish(client)
                        return true
                    }
                } else {
                    if (PestCompletionGuard.isInStartupGrace(runtime.activatedAt) &&
                        runtime.zeroPestTabTicks == 0
                    ) {
                        ClientUtils.sendDebugMessage(
                            "PestDestroyer: ignoring finish-level tab reading during startup grace."
                        )
                    }
                    runtime.zeroPestTabTicks = 0
                }
            }

            val player = client.player ?: return false
            if (player.tickCount % 10 == 0 &&
                isPlotRefreshState(runtime.state)
            ) {
                val rawInfested = PestManager.getInfestedPlotsFromTab(client)
                val infested: Set<String> = context.filterSkippedInfestedPlots(rawInfested)
                if (infested.isNotEmpty()) {
                    val firstPlot = infested.iterator().next()
                    val currentPlot = context.getEffectivePlot(client)
                    val currentStillActionable = infested.stream()
                        .anyMatch { plot -> context.plotsEqual(plot, currentPlot) }
                    if (!currentStillActionable &&
                        isImmediatePlotExitState(runtime.state)
                    ) {
                        ClientUtils.sendDebugMessage(
                            "[PestDestroyer] Plot " +
                                currentPlot +
                                " no longer first in tab. Leaving immediately for " +
                                firstPlot
                        )
                        runtime.navigation.plotQueue.clear()
                        runtime.navigation.plotQueue.addAll(infested)
                        runtime.navigation.currentPlotIdx = 0
                        runtime.navigation.plotTpSent = false
                        context.setState(PestDestroyer.State.TELEPORT_TO_PLOT)
                        return true
                    }
                } else if (rawInfested.isNotEmpty() && finishReading) {
                    ClientUtils.sendDebugMessage(
                        "PestDestroyer: only skipped leave-one plots remain. Finishing."
                    )
                    context.finish(client)
                    return true
                }
            }

            if (System.currentTimeMillis() - runtime.activatedAt > stuckTimeoutMs) {
                ClientUtils.sendMessage(
                    "\u00A7cPest destroyer timed out after 5 minutes. Returning to farm.",
                    false
                )
                context.finish(client)
                return true
            }

            if (!AetherConfig.PEST_DESTROYER_WALK_MODE.get() &&
                shouldEnsureFlying(runtime.state) &&
                !player.abilities.flying &&
                player.abilities.mayfly
            ) {
                context.setState(PestDestroyer.State.FLY_UP)
                return true
            }
            return false
        }

        private fun isPlotRefreshState(state: PestDestroyer.State): Boolean {
            return state != PestDestroyer.State.TELEPORT_TO_PLOT &&
                state != PestDestroyer.State.IDLE &&
                state != PestDestroyer.State.FINISH
        }

        private fun isImmediatePlotExitState(state: PestDestroyer.State): Boolean {
            return state == PestDestroyer.State.CHECK_NEXT ||
                state == PestDestroyer.State.GET_LOCATION ||
                state == PestDestroyer.State.FLY_TO_WAYPOINT
        }

        private fun shouldEnsureFlying(state: PestDestroyer.State): Boolean {
            return state != PestDestroyer.State.IDLE &&
                state != PestDestroyer.State.FINISH &&
                state != PestDestroyer.State.TELEPORT_TO_PLOT &&
                state != PestDestroyer.State.EQUIP_VACUUM &&
                state != PestDestroyer.State.FLY_UP
        }
    }
}
