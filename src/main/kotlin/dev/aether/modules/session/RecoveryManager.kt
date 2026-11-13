package dev.aether.modules.session

import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.farming.SqueakyMousematManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.AetherLang
import dev.aether.util.ClientUtils
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

class RecoveryManager {
    enum class RecoveryMode {
        WORLD_CHANGE,
        LIMBO
    }

    private enum class WorldChangeRecoveryPhase {
        IDLE,
        COMMAND_SEQUENCE,
        WALK_PATH,
        PURE_ETHERWARP,
        WALK_ETHERWARP,
        ALIGNING,
        RESUME_DELAY
    }

    companion object {
        private val commandSequence = RecoverySequence()
        private const val WORLD_CHANGE_ALIGN_TIMEOUT_MS = 1200L
        private const val WORLD_CHANGE_RESUME_DELAY_MS = 1000L
        private var lastRecoveryActionTime: Long = 0
        private var recoveryMode: RecoveryMode? = null
        @Volatile private var recoveryComplete: Boolean = false
        private var worldChangePhase = WorldChangeRecoveryPhase.IDLE
        private var worldChangeTargetPosition: Vec3? = null
        private var worldChangeWaitUntilMs: Long = 0L
        private var worldChangeUsedWalkAssist: Boolean = false
        private var worldChangeAlignClicked: Boolean = false
        private var navigationWorld: ClientLevel? = null

        @JvmStatic
        fun reset() {
            commandSequence.reset(System.currentTimeMillis())
            lastRecoveryActionTime = 0
            recoveryMode = null
            recoveryComplete = false
            worldChangePhase = WorldChangeRecoveryPhase.IDLE
            worldChangeTargetPosition = null
            worldChangeWaitUntilMs = 0L
            worldChangeUsedWalkAssist = false
            worldChangeAlignClicked = false
            navigationWorld = null
        }

        @JvmStatic
        fun beginRecovery() {
            prepareRecovery()
            ClientUtils.sendMessage("\u00A7e" + AetherLang.localize(
                "Recovery: waiting for SkyBlock and the Garden before resuming..."), false)
        }

        @JvmStatic
        fun beginLimboRecovery() {
            prepareRecovery()
            recoveryMode = RecoveryMode.LIMBO
            ClientUtils.sendMessage("\u00A7e" + AetherLang.localize(
                "Limbo recovery: waiting for SkyBlock and the Garden before resuming..."), false)
        }

        @JvmStatic
        fun beginWorldChangeRecovery(savedPosition: Vec3) {
            prepareRecovery()
            recoveryMode = RecoveryMode.WORLD_CHANGE
            worldChangePhase = WorldChangeRecoveryPhase.COMMAND_SEQUENCE
            worldChangeTargetPosition = savedPosition
        }

        private fun prepareRecovery() {
            MacroStateManager.stopMacro(Minecraft.getInstance(), "Preparing location-verified recovery", false)
            MacroStateManager.setCurrentState(MacroState.State.RECOVERING)
        }

        @JvmStatic
        fun isResumeReady(): Boolean = recoveryComplete

        @JvmStatic
        fun isWorldChangeRecoveryActive(): Boolean = recoveryMode == RecoveryMode.WORLD_CHANGE
            && worldChangePhase != WorldChangeRecoveryPhase.IDLE && worldChangeTargetPosition != null

        @JvmStatic
        fun update() {
            val client = Minecraft.getInstance()
            if (MacroStateManager.getCurrentState() != MacroState.State.RECOVERING || recoveryComplete) {
                return
            }
            if (recoveryMode == RecoveryMode.WORLD_CHANGE) {
                updateWorldChangeRecovery(client)
                return
            }

            updateCommandSequence(client, false)
        }

        private fun updateCommandSequence(client: Minecraft?, worldChangeRecovery: Boolean) {
            val now = System.currentTimeMillis()
            val action = commandSequence.update(now, if (client == null) null else client.level,
                ClientUtils.getCurrentLocation(), isClientReady(client))
            if (action == RecoverySequence.Action.RESUME) {
                if (worldChangeRecovery) {
                    navigationWorld = client?.level
                    startWorldChangePrimaryRecovery(client!!)
                } else {
                    completeRecovery(client)
                }
            } else if (action.command != null) {
                ClientUtils.sendMessage("\u00A7e" + String.format(AetherLang.localize(
                    "Recovery: running %s, waiting for the destination to load..."), action.command), false)
                ClientUtils.sendCommand(action.command)
            }
        }

        private fun isClientReady(client: Minecraft?): Boolean {
            if (client == null) return false
            val player = client.player ?: return false
            val level = client.level ?: return false
            return client.getConnection() != null && client.gui.screen() == null
                && player.isAlive() && level.hasChunkAt(player.blockPosition())
        }

        private fun canResumeFarming(client: Minecraft?): Boolean =
            MacroStateManager.getCurrentState() == MacroState.State.RECOVERING
                && isClientReady(client) && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN

        private fun completeRecovery(client: Minecraft?) {
            if (client == null || !canResumeFarming(client)) {
                return
            }
            recoveryComplete = true
            recoveryMode = null
            ClientUtils.sendMessage("\u00A7aRecovery successful. Resuming farming...", false)
            ClientUtils.sendDebugMessage("Starting farming macro after confirming SkyBlock and Garden arrival")
            DynamicRestManager.scheduleNextRest()
            resumeFarming(client)
        }

        private fun resumeFarming(client: Minecraft) {
            FailsafeManager.syncSelectedSlotFromClient(client)
            GearManager.swapToFarmingTool(client)
            FailsafeManager.syncSelectedSlotFromClient(client)
            MacroStateManager.setCurrentState(MacroState.State.FARMING)
            SqueakyMousematManager.armReapplyAttempt()
            FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig())
            FailsafeManager.syncSelectedSlotFromClient(client)
        }

        private fun updateWorldChangeRecovery(client: Minecraft?) {
            if (client == null || client.player == null || client.level == null) {
                return
            }

            val targetPosition = worldChangeTargetPosition
            if (targetPosition == null) {
                ClientUtils.sendMessage("\u00A7cWorld change recovery failed: no saved position.", false)
                MacroStateManager.stopMacro(client, "World change recovery failed: no saved position", false)
                return
            }

            if (worldChangePhase == WorldChangeRecoveryPhase.COMMAND_SEQUENCE) {
                updateCommandSequence(client, true)
                return
            }

            if (client.level != navigationWorld || !canResumeFarming(client)) {
                worldChangePhase = WorldChangeRecoveryPhase.COMMAND_SEQUENCE
                PathfindingManager.stop()
                RotationManager.cancelRotation()
                ClientUtils.forceReleaseKeys()
                worldChangeUsedWalkAssist = false
                worldChangeAlignClicked = false
                commandSequence.reset(System.currentTimeMillis())
                return
            }

            if (worldChangePhase == WorldChangeRecoveryPhase.ALIGNING && !RotationManager.isRotating()) {
                if (!worldChangeAlignClicked) {
                    worldChangeAlignClicked = true
                    GearManager.swapToAOTVSync(client)
                    ClientUtils.performUseClick()
                    lastRecoveryActionTime = System.currentTimeMillis()
                    return
                }

                if (System.currentTimeMillis() - lastRecoveryActionTime >= WORLD_CHANGE_ALIGN_TIMEOUT_MS) {
                    finishWorldChangeRecovery(client)
                }
                return
            }

            if (worldChangePhase == WorldChangeRecoveryPhase.RESUME_DELAY) {
                if (System.currentTimeMillis() < worldChangeWaitUntilMs) {
                    return
                }
                completeWorldChangeRecovery(client)
            }
        }

        private fun startWorldChangeWalkAssist(client: Minecraft) {
            val targetPosition = worldChangeTargetPosition
            if (recoveryMode != RecoveryMode.WORLD_CHANGE || targetPosition == null) {
                return
            }

            worldChangePhase = WorldChangeRecoveryPhase.WALK_ETHERWARP
            worldChangeUsedWalkAssist = true
            ClientUtils.sendMessage("\u00A7eWorld change recovery: pure etherwarp failed, trying walk-assisted etherwarp...", false)
            PathfindingManager.startConfiguredEtherwarp(
                client,
                Mth.floor(targetPosition.x), Mth.floor(targetPosition.y), Mth.floor(targetPosition.z),
                { client.execute { finishWorldChangeNavigation(client, true) } },
                { client.execute {
                    ClientUtils.sendMessage("\u00A7cWorld change recovery failed. Stopping farming.", false)
                    MacroStateManager.stopMacro(client, "World change recovery path failed", false)
                } }
            )
        }

        private fun startWorldChangePrimaryRecovery(client: Minecraft) {
            val targetPosition = worldChangeTargetPosition
            val player = client.player
            if (player == null || targetPosition == null) {
                return
            }

            val currentY = Mth.floor(player.getY())
            val targetY = Mth.floor(targetPosition.y)
            if (targetY > currentY) {
                ClientUtils.sendDebugMessage("World change recovery: target is above current position, prioritizing etherwarp")
                startWorldChangeEtherwarpRecovery(client)
                return
            }

            ClientUtils.sendDebugMessage("World change recovery: target is level or below current position, prioritizing walk")
            startWorldChangeWalkRecovery(client)
        }

        private fun startWorldChangeWalkRecovery(client: Minecraft) {
            val targetPosition = worldChangeTargetPosition
            if (recoveryMode != RecoveryMode.WORLD_CHANGE || targetPosition == null) {
                return
            }

            worldChangePhase = WorldChangeRecoveryPhase.WALK_PATH
            ClientUtils.sendMessage("\u00A7eWorld change recovery: walking back to the saved position...", false)
            PathfindingManager.startConfiguredWalk(
                client,
                Mth.floor(targetPosition.x), Mth.floor(targetPosition.y), Mth.floor(targetPosition.z),
                { client.execute { finishWorldChangeNavigation(client, true) } },
                { client.execute { startWorldChangeEtherwarpRecovery(client) } },
                true, 0.5, false, true
            )
        }

        private fun startWorldChangeEtherwarpRecovery(client: Minecraft) {
            val targetPosition = worldChangeTargetPosition
            if (recoveryMode != RecoveryMode.WORLD_CHANGE || targetPosition == null) {
                return
            }

            worldChangePhase = WorldChangeRecoveryPhase.PURE_ETHERWARP
            worldChangeUsedWalkAssist = false
            ClientUtils.sendMessage("\u00A7eWorld change recovery: walking failed, trying etherwarp...", false)
            PathfindingManager.startConfiguredPureEtherwarp(
                client,
                Mth.floor(targetPosition.x), Mth.floor(targetPosition.y), Mth.floor(targetPosition.z),
                { client.execute { finishWorldChangeNavigation(client, false) } },
                { client.execute { startWorldChangeWalkAssist(client) } }
            )
        }

        private fun finishWorldChangeNavigation(client: Minecraft, alignAfterNavigation: Boolean) {
            if (recoveryMode != RecoveryMode.WORLD_CHANGE) {
                return
            }

            if (alignAfterNavigation || worldChangeUsedWalkAssist) {
                performWorldChangeAotvAlign(client)
                return
            }

            finishWorldChangeRecovery(client)
        }

        private fun performWorldChangeAotvAlign(client: Minecraft?) {
            val targetPosition = worldChangeTargetPosition
            val player = client?.player
            if (client == null || player == null || targetPosition == null) {
                finishWorldChangeRecovery(client)
                return
            }

            val slot = GearManager.findAspectOfTheVoidSlot(client)
            if (slot < 0 || slot > 8) {
                ClientUtils.sendDebugMessage("World change recovery align skipped: no AOTV/AOTE in hotbar")
                finishWorldChangeRecovery(client)
                return
            }

            worldChangePhase = WorldChangeRecoveryPhase.ALIGNING
            worldChangeAlignClicked = false
            val target = Vec3(
                Math.floor(targetPosition.x) + 0.5,
                Math.floor(targetPosition.y) - 0.5,
                Math.floor(targetPosition.z) + 0.5)
            val rotation = RotationUtils.calculateLookAt(player.getEyePosition(), target)
            RotationManager.cancelRotation()
            RotationManager.rotateToYawPitch(client, rotation.yaw, rotation.pitch, 120L)
        }

        private fun finishWorldChangeRecovery(client: Minecraft?) {
            if (client == null) {
                return
            }

            worldChangePhase = WorldChangeRecoveryPhase.RESUME_DELAY
            worldChangeWaitUntilMs = System.currentTimeMillis() + WORLD_CHANGE_RESUME_DELAY_MS
        }

        private fun completeWorldChangeRecovery(client: Minecraft) {
            if (!canResumeFarming(client) || client.level != navigationWorld) {
                return
            }
            recoveryComplete = true
            recoveryMode = null
            worldChangePhase = WorldChangeRecoveryPhase.IDLE
            worldChangeTargetPosition = null
            worldChangeWaitUntilMs = 0L
            worldChangeUsedWalkAssist = false
            worldChangeAlignClicked = false
            navigationWorld = null
            DynamicRestManager.scheduleNextRest()
            ClientUtils.sendMessage("\u00A7aWorld change recovery complete. Resuming farming...", false)
            resumeFarming(client)
        }
    }
}
