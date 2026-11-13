package dev.aether.modules.farming

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.farming.AbstractFarmingMacro
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.util.AetherLang
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB

class FastLaneSwitchManager private constructor() {
    companion object {
        private const val PLOT_SIZE = 96
        private const val PLOT_OFFSET = 48
        private const val GARDEN_MIN = -240
        private const val GARDEN_MAX = 240
        private const val MIN_HEIGHT = 66
        private const val MAX_HEIGHT = MIN_HEIGHT + 36
        private const val MIN_SPEED_BLOCKS_PER_SECOND = 0.25
        private const val SWITCH_LEAD_BLOCKS = 0.20
        private const val BOUNDARY_HALF_THICKNESS = 0.035
        private const val STATE_CHANGE_SUPPRESS_MS = 0L

        private var trackedState = AbstractFarmingMacro.State.NONE
        private var previousCoord = 0.0
        private var speedBlocksPerSecond = 0.0
        private var lastTickMs = 0L
        private var suppressFastSwitchUntilMs = 0L

        @JvmStatic fun resetRuntime() {
            trackedState = AbstractFarmingMacro.State.NONE
            previousCoord = 0.0
            speedBlocksPerSecond = 0.0
            lastTickMs = 0L
            suppressFastSwitchUntilMs = System.currentTimeMillis() + STATE_CHANGE_SUPPRESS_MS
        }

        @JvmStatic fun tick(mc: Minecraft, state: AbstractFarmingMacro.State) {
            if (mc.player == null || !isRowState(state)) {
                trackedState = state
                lastTickMs = 0L
                speedBlocksPerSecond = 0.0
                return
            }
            val coord = currentAxisCoord(mc)
            val now = System.currentTimeMillis()
            if (trackedState != state || lastTickMs == 0L) {
                trackedState = state
                previousCoord = coord
                lastTickMs = now
                speedBlocksPerSecond = 0.0
                return
            }
            val elapsedMs = maxOf(1L, now - lastTickMs)
            val instantSpeed = kotlin.math.abs(coord - previousCoord) * 1000.0 / elapsedMs
            speedBlocksPerSecond = if (speedBlocksPerSecond <= 0.0) instantSpeed else speedBlocksPerSecond * 0.65 + instantSpeed * 0.35
            previousCoord = coord
            lastTickMs = now
        }

        @JvmStatic fun onStateChanged(from: AbstractFarmingMacro.State, to: AbstractFarmingMacro.State) {
            trackedState = to
            lastTickMs = 0L
            speedBlocksPerSecond = 0.0
            suppressFastSwitchUntilMs = System.currentTimeMillis() + STATE_CHANGE_SUPPRESS_MS
        }

        @JvmStatic fun shouldFastSwitch(mc: Minecraft, state: AbstractFarmingMacro.State): Boolean {
            if (!isEnabledForCurrentFarm() || mc.player == null || !isRowState(state)) return false
            if (System.currentTimeMillis() < suppressFastSwitchUntilMs) return false
            val target = getTargetBoundary(mc, state)
            return target.valid && target.distanceBlocks <= SWITCH_LEAD_BLOCKS
        }

        @JvmStatic fun getDisplayText(): String {
            val macro = FarmingMacroManager.getActiveMacro()
            val mc = Minecraft.getInstance()
            if (!isEnabledForCurrentFarm() || macro == null || mc.player == null || MacroStateManager.currentState != MacroState.State.FARMING || !isRowState(macro.currentState)) return "---"
            val target = getTargetBoundary(mc, macro.currentState)
            if (!target.valid) return AetherLang.localize("Set Bounds")
            if (target.distanceBlocks <= SWITCH_LEAD_BLOCKS) return AetherLang.localize("Now")
            if (speedBlocksPerSecond < MIN_SPEED_BLOCKS_PER_SECOND) return AetherLang.localize("Paused")
            return formatDuration((target.distanceBlocks / speedBlocksPerSecond * 1000.0).toLong())
        }

        @JvmStatic fun hasEstimate(): Boolean {
            val macro = FarmingMacroManager.getActiveMacro()
            val mc = Minecraft.getInstance()
            if (!isEnabledForCurrentFarm() || macro == null || mc.player == null) return false
            return getTargetBoundary(mc, macro.currentState).valid && speedBlocksPerSecond >= MIN_SPEED_BLOCKS_PER_SECOND
        }

        @JvmStatic fun hasVisibleHighlights(): Boolean {
            val mc = Minecraft.getInstance()
            return isEnabledForCurrentFarm() && mc.level != null && mc.player != null && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN && hasValidBoundaries()
        }

        @JvmStatic fun renderWorld() {
            if (!hasVisibleHighlights()) return
            val mc = Minecraft.getInstance()
            val plot = currentPlotBounds(mc)
            val minBoundary = minOf(AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get(), AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get())
            val maxBoundary = maxOf(AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get(), AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get())
            renderBoundary(plot, minBoundary, ARGB.color(190, 80, 180, 255), ARGB.color(55, 80, 180, 255))
            renderBoundary(plot, maxBoundary, ARGB.color(190, 255, 180, 80), ARGB.color(55, 255, 180, 80))
        }

        private fun renderBoundary(plot: PlotBounds, boundary: Int, strokeColor: Int, fillColor: Int) {
            val center = boundaryTriggerCoord(boundary)
            val box = if (AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.get().equals("X", ignoreCase = true)) {
                AABB(center - BOUNDARY_HALF_THICKNESS, MIN_HEIGHT.toDouble(), plot.minZ().toDouble(), center + BOUNDARY_HALF_THICKNESS, MAX_HEIGHT.toDouble(), plot.maxZ().toDouble())
            } else {
                AABB(plot.minX().toDouble(), MIN_HEIGHT.toDouble(), center - BOUNDARY_HALF_THICKNESS, plot.maxX().toDouble(), MAX_HEIGHT.toDouble(), center + BOUNDARY_HALF_THICKNESS)
            }
            Gizmos.cuboid(box, GizmoStyle.strokeAndFill(strokeColor, 2.0f, fillColor))
        }

        private fun getTargetBoundary(mc: Minecraft, state: AbstractFarmingMacro.State): BoundaryTarget {
            if (!hasValidBoundaries() || !isRowState(state) || mc.player == null) return BoundaryTarget.invalid()
            val direction = axisDirection(mc, state)
            if (kotlin.math.abs(direction) < 0.05) return BoundaryTarget.invalid()
            val coord = currentAxisCoord(mc)
            val left = AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get()
            val right = AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get()
            val boundary = boundaryTriggerCoord(if (direction > 0.0) maxOf(left, right) else minOf(left, right))
            val distance = if (direction > 0.0) boundary - coord else coord - boundary
            return BoundaryTarget(true, maxOf(0.0, distance))
        }

        private fun boundaryTriggerCoord(boundaryBlock: Int) = boundaryBlock + 0.5

        private fun axisDirection(mc: Minecraft, state: AbstractFarmingMacro.State): Double {
            val player = mc.player ?: return 0.0
            val rad = Math.toRadians(player.yRot.toDouble())
            val (x, z) = when (state) {
                AbstractFarmingMacro.State.LEFT -> kotlin.math.cos(rad) to kotlin.math.sin(rad)
                AbstractFarmingMacro.State.RIGHT -> -kotlin.math.cos(rad) to -kotlin.math.sin(rad)
                AbstractFarmingMacro.State.FORWARD -> -kotlin.math.sin(rad) to kotlin.math.cos(rad)
                AbstractFarmingMacro.State.BACKWARD -> kotlin.math.sin(rad) to -kotlin.math.cos(rad)
                else -> return 0.0
            }
            return if (AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.get().equals("X", ignoreCase = true)) x else z
        }

        private fun currentAxisCoord(mc: Minecraft): Double {
            val player = mc.player ?: return 0.0
            return if (AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS.get().equals("X", ignoreCase = true)) player.x else player.z
        }

        private fun currentPlotBounds(mc: Minecraft): PlotBounds {
            val player = mc.player ?: return PlotBounds(0, 0, 0, 0)
            val plotX = Math.floor((player.x + PLOT_OFFSET) / PLOT_SIZE).toInt()
            val plotZ = Math.floor((player.z + PLOT_OFFSET) / PLOT_SIZE).toInt()
            val minX = Mth.clamp(plotX * PLOT_SIZE - PLOT_OFFSET, GARDEN_MIN, GARDEN_MAX)
            val minZ = Mth.clamp(plotZ * PLOT_SIZE - PLOT_OFFSET, GARDEN_MIN, GARDEN_MAX)
            return PlotBounds(minX, minZ, Mth.clamp(minX + PLOT_SIZE, GARDEN_MIN, GARDEN_MAX), Mth.clamp(minZ + PLOT_SIZE, GARDEN_MIN, GARDEN_MAX))
        }

        private fun hasValidBoundaries() = AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY.get() != AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY.get()
        private fun isEnabledForCurrentFarm() = AetherConfig.MACRO_FAST_LANE_SWITCH.get() && AetherConfig.FARM_TYPE.get() != "CUSTOM"
        private fun isRowState(state: AbstractFarmingMacro.State) = state == AbstractFarmingMacro.State.LEFT || state == AbstractFarmingMacro.State.RIGHT || state == AbstractFarmingMacro.State.FORWARD || state == AbstractFarmingMacro.State.BACKWARD

        private fun formatDuration(ms: Long): String {
            val totalSeconds = maxOf(0L, (ms + 500L) / 1000L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
        }

        class BoundaryTarget(val valid: Boolean, val distanceBlocks: Double) {
            companion object { fun invalid() = BoundaryTarget(false, 0.0) }
        }
        private class PlotBounds(private val minX: Int, private val minZ: Int, private val maxX: Int, private val maxZ: Int) {
            fun minX(): Int = minX
            fun minZ(): Int = minZ
            fun maxX(): Int = maxX
            fun maxZ(): Int = maxZ
        }
    }
}
