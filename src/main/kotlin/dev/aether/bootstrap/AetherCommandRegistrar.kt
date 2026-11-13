package dev.aether.bootstrap

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.aether.config.AetherConfig
import dev.aether.macro.MacroStateManager
import dev.aether.modules.ComposterManager
import dev.aether.modules.GreenhouseManager
import dev.aether.modules.SupercraftManager
import dev.aether.modules.TablistSetupManager
import dev.aether.modules.discord.DiscordStatusManager
import dev.aether.modules.failsafe.FailsafeTestManager
import dev.aether.modules.farming.BedrockPlotMaker
import dev.aether.modules.forge.ForgeManager
import dev.aether.modules.interaction.EntityInteractManager
import dev.aether.modules.inventorymanager.AutoSellManager
import dev.aether.modules.irc.IrcManager
import dev.aether.modules.metaldetector.MetalDetectorSolver
import dev.aether.modules.movement.MovementPlaybackManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pathfinding.debug.PathVisualizer
import dev.aether.modules.pest.DynamicPestsManager
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.pest.helpers.PestExchangeManager
import dev.aether.modules.pest.helpers.PestTrapManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.modules.visitor.VisitorsMacro
import dev.aether.util.AetherLang
import dev.aether.util.BazaarUtils
import dev.aether.util.ClientUtils
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.Minecraft
import java.util.List
import java.util.concurrent.CompletableFuture

class AetherCommandRegistrar private constructor() {
    companion object {
        @JvmStatic
        fun register() {
            registerLegacyCommandIntercepts()
            registerClientCommands()
        }

        private fun registerLegacyCommandIntercepts() {
            // only fires for chat, never for commands, so slash commands still run normally.
            ClientSendMessageEvents.ALLOW_CHAT.register { message -> !IrcManager.interceptChat(message) }
            ClientSendMessageEvents.CHAT.register { message -> MovementPlaybackManager.recordOutgoingChat(message, false) }
            ClientSendMessageEvents.COMMAND.register { command ->
                MovementPlaybackManager.recordOutgoingChat(command, true)
                if (command.equals("call george", ignoreCase = true)) {
                    dev.aether.modules.inventorymanager.GeorgeManager.onCallGeorgeSent()
                }

                val normalized = command.lowercase().trim()
                if (normalized.startsWith("aether goto ")) {
                    startCoordinatePathfind(command, false, "\u00A7eUsage: /aether goto <x> <y> <z>")
                }
                if (normalized.startsWith("aether flyto ")) {
                    startCoordinatePathfind(command, true, "\u00A7eUsage: /aether flyto <x> <y> <z>")
                }
                if (normalized == "aether debug path") {
                    PathVisualizer.toggle()
                    ClientUtils.sendMessage("\u00A7aPath visualizer: " + (if (PathVisualizer.isEnabled()) "ON" else "OFF"), false)
                }
                if (normalized.startsWith("aether pathtest ")) {
                    startPathTest(command)
                }
            }
        }

        private fun setIrcEnabled(enabled: Boolean): Int {
            IrcManager.setEnabled(enabled)
            ClientUtils.sendMessage(if (enabled) "§airc is on" else "§eirc is off", false)
            return 1
        }

        private fun registerClientCommands() {
            ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
                dispatcher.register(
                    ClientCommands.literal("aether")
                        .executes {
                            AetherUiActions.toggleMainGui()
                            1
                        }
                        .then(ClientCommands.literal("farming").executes {
                            val client = Minecraft.getInstance()
                            if (MacroStateManager.isAutomationRunning()) {
                                ClientUtils.sendMessage("\u00A7cA macro is already running.", false)
                                return@executes 0
                            }
                            AetherKeybindHandler.startFarmingMacro(client)
                            1
                        })
                        .then(ClientCommands.literal("bedrock")
                            .executes { startBedrock(null) }
                            .then(ClientCommands.argument("plot", IntegerArgumentType.integer(1, 24))
                                .executes { ctx -> startBedrock(IntegerArgumentType.getInteger(ctx, "plot")) }))
                        .then(ClientCommands.literal("stop").executes {
                            val client = Minecraft.getInstance()
                            if (!MacroStateManager.isAutomationRunning()) {
                                ClientUtils.sendMessage("\u00A7eNo macro is currently running.", false)
                                return@executes 0
                            }
                            MacroStateManager.stopMacro(client)
                            ClientUtils.sendMessage("\u00A7eStopped active macro.", false)
                            1
                        })
                        .then(ClientCommands.literal("status").executes {
                            if (DiscordStatusManager.requestManualStatusUpdate()) {
                                ClientUtils.sendMessage("\u00A7eSending Discord webhook status update.", false)
                                return@executes 1
                            }
                            ClientUtils.sendMessage("\u00A7cUnable to send Discord status update. Check your webhook URL or wait for the current screenshot to finish.", false)
                            0
                        })
                        .then(ClientCommands.literal("setup").executes { TablistSetupManager.start(Minecraft.getInstance()) })
                        .then(ClientCommands.literal("printscoreboard").executes { printScoreboard(Minecraft.getInstance()) })
                        .then(ClientCommands.literal("rotate")
                            .then(ClientCommands.argument("pitch", FloatArgumentType.floatArg(-90.0f, 90.0f))
                                .suggests { _, builder -> suggestAngles(builder, "-90", "-45", "0", "45", "90") }
                                .then(ClientCommands.argument("yaw", FloatArgumentType.floatArg())
                                    .suggests { _, builder -> suggestAngles(builder, "-180", "-135", "-90", "-45", "0", "45", "90", "135", "180") }
                                    .executes { ctx -> rotateToPitchYaw(Minecraft.getInstance(), FloatArgumentType.getFloat(ctx, "pitch"), FloatArgumentType.getFloat(ctx, "yaw")) })))
                        .then(ClientCommands.literal("movement")
                            .executes { sendMovementHelp(); 1 }
                            .then(ClientCommands.literal("record").executes { MovementPlaybackManager.startRecording(); 1 })
                            .then(ClientCommands.literal("stop").executes { MovementPlaybackManager.stop(); 1 })
                            .then(ClientCommands.literal("folder").executes { MovementPlaybackManager.openMovementFolder(); 1 })
                            .then(ClientCommands.literal("play")
                                .then(ClientCommands.argument("replay_file", StringArgumentType.greedyString())
                                    .suggests { _, builder -> suggestMovementReplays(builder) }
                                    .executes { ctx -> MovementPlaybackManager.play(StringArgumentType.getString(ctx, "replay_file")); 1 })))
                        .then(ClientCommands.literal("testfailsafe")
                            .executes { sendFailsafeTestHelp(); 1 }
                            .then(ClientCommands.literal("inventoryslot")
                                .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                                    .suggests { _, builder -> suggestAngles(builder, "1", "2", "3", "4", "5", "6", "7", "8", "9") }
                                    .executes { ctx -> FailsafeTestManager.scheduleInventorySlot(IntegerArgumentType.getInteger(ctx, "slot")); 1 }))
                            .then(ClientCommands.literal("rotation")
                                .then(ClientCommands.argument("pitch", FloatArgumentType.floatArg())
                                    .suggests { _, builder -> suggestAngles(builder, "-30", "-15", "0", "15", "30") }
                                    .then(ClientCommands.argument("yaw", FloatArgumentType.floatArg())
                                        .suggests { _, builder -> suggestAngles(builder, "-90", "-45", "-30", "-15", "15", "30", "45", "90") }
                                        .executes { ctx -> FailsafeTestManager.scheduleRotation(FloatArgumentType.getFloat(ctx, "pitch"), FloatArgumentType.getFloat(ctx, "yaw")); 1 })))
                            .then(ClientCommands.literal("guiflash")
                                .then(ClientCommands.argument("duration", IntegerArgumentType.integer(1))
                                    .executes { ctx -> FailsafeTestManager.scheduleGuiFlash(IntegerArgumentType.getInteger(ctx, "duration")); 1 })))
                        .then(ClientCommands.literal("pathfind")
                            .executes { sendPathfindHelp(); 1 }
                            .then(ClientCommands.literal("stop").executes { PathfindingManager.stop(); ClientUtils.sendMessage("\u00A7ePathfinder stopped.", false); 1 })
                            .then(debugPathCommand("fly") { client, x, y, z -> PathfindingManager.startDebugFlyPathfind(client, x, y, z) })
                            .then(debugPathCommand("etherwarp") { client, x, y, z -> PathfindingManager.startDebugEtherwarpPathfind(client, x, y, z) })
                            .then(debugPathCommand("walk") { client, x, y, z -> PathfindingManager.startDebugPathfind(client, x, y, z) })
                            .then(coordinatePathCommand { client, x, y, z -> PathfindingManager.startDebugPathfind(client, x, y, z) })
                        )
                        .then(ClientCommands.literal("pestexchange").executes { PestExchangeManager.start(Minecraft.getInstance()); 1 })
                        .then(ClientCommands.literal("metaldetector")
                            .executes { MetalDetectorSolver.toggle(Minecraft.getInstance()); 1 }
                            .then(ClientCommands.literal("scan").executes { MetalDetectorSolver.forceScan(Minecraft.getInstance()); 1 }))
                        .then(ClientCommands.literal("bazaar")
                            .executes { ClientUtils.sendMessage("\u00A7eUsage: /aether bazaar <item> <count>", false); 1 }
                            .then(ClientCommands.argument("item", StringArgumentType.string())
                                .then(ClientCommands.argument("count", IntegerArgumentType.integer(1))
                                    .executes { ctx ->
                                        val item = StringArgumentType.getString(ctx, "item")
                                        val count = IntegerArgumentType.getInteger(ctx, "count")
                                        BazaarUtils.buy(Minecraft.getInstance(), item, count) { success ->
                                            if (!success) Minecraft.getInstance().execute {
                                                if (Minecraft.getInstance().player != null) ClientUtils.sendMessage("\u00A7cBazaar buy failed.", false)
                                            }
                                        }
                                        1
                                    })))
                        .then(ClientCommands.literal("irc")
                            .executes { IrcManager.toggleRedirect(); 1 }
                            .then(ClientCommands.literal("on").executes { setIrcEnabled(true) })
                            .then(ClientCommands.literal("off").executes { setIrcEnabled(false) })
                            .then(ClientCommands.literal("chat")
                                // without this the bare subcommand fails to parse and leaks to the server.
                                .executes { ClientUtils.sendMessage("§cUsage: /aether irc chat <message>", false); 1 }
                                .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                                    .executes { ctx -> IrcManager.sendChat(StringArgumentType.getString(ctx, "message")); 1 })))
                        .then(ClientCommands.literal("visitors").executes { VisitorsMacro.start(Minecraft.getInstance(), true); 1 })
                        .then(ClientCommands.literal("pestdestroyer").executes {
                            val client = Minecraft.getInstance()
                            if (PestDestroyer.isActive()) {
                                PestDestroyer.stop(client)
                                ClientUtils.sendMessage("\u00A7ePest Destroyer stopped.", false)
                            } else PestDestroyer.start(client)
                            1
                        })
                        .then(ClientCommands.literal("dynamicpest")
                            .then(ClientCommands.argument("crop", StringArgumentType.greedyString())
                                .suggests { _, builder -> suggestDynamicPestCrops(builder) }
                                .executes { ctx -> triggerDynamicPest(StringArgumentType.getString(ctx, "crop")) }))
                        .then(ClientCommands.literal("autosell").executes {
                            val client = Minecraft.getInstance()
                            if (AutoSellManager.isSelling || AutoSellManager.isPreparingToSell) ClientUtils.sendMessage("\u00A7cAutoSell is already running.", false)
                            else AutoSellManager.manualTrigger(client)
                            1
                        })
                        .then(ClientCommands.literal("pesttraps").executes { PestTrapManager.start(Minecraft.getInstance()); 1 })
                        .then(ClientCommands.literal("greenhouseharvest").executes { GreenhouseManager.harvest(); 1 })
                        .then(ClientCommands.literal("debugskulls").executes { GreenhouseManager.debugScanSkulls(); 1 })
                        .then(ClientCommands.literal("composter").executes { ComposterManager.manualTrigger(); 1 })
                        .then(ClientCommands.literal("supercraft").executes { SupercraftManager.manualTrigger(); 1 })
                        .then(ClientCommands.literal("refilltraps").executes { PestTrapManager.startRefill(Minecraft.getInstance()); 1 })
                        .then(ClientCommands.literal("forge").executes { ForgeManager.start(Minecraft.getInstance()); 1 })
                        .then(ClientCommands.literal("interact")
                            .then(ClientCommands.argument("entity_name", StringArgumentType.greedyString())
                                .executes { ctx -> EntityInteractManager.start(Minecraft.getInstance(), StringArgumentType.getString(ctx, "entity_name")); 1 }))
                        .then(ClientCommands.literal("config")
                            .executes { sendConfigHelp(); 1 }
                            .then(ClientCommands.literal("export").executes { exportConfig() })
                            .then(ClientCommands.literal("import")
                                .executes { sendConfigHelp(); 1 }
                                .then(ClientCommands.argument("config_string", StringArgumentType.greedyString())
                                    .executes { ctx -> importConfig(StringArgumentType.getString(ctx, "config_string")) })))
                )
            }
        }

        private fun coordinatePathCommand(action: (Minecraft, Int, Int, Int) -> Unit) =
            ClientCommands.argument("x", IntegerArgumentType.integer())
                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                    .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                        .executes { ctx -> action(Minecraft.getInstance(), IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")); 1 }))

        private fun debugPathCommand(name: String, action: (Minecraft, Int, Int, Int) -> Unit) =
            ClientCommands.literal(name)
                .executes { sendPathfindHelp(); 1 }
                .then(coordinatePathCommand(action))

        private fun startBedrock(plot: Int?): Int {
            if (plot != null) {
                AetherConfig.BEDROCK_PLOT_MAKER_PLOT.set(Integer.toString(plot))
                AetherConfig.save()
            }
            BedrockPlotMaker.start(Minecraft.getInstance())
            return if (BedrockPlotMaker.isRunning()) 1 else 0
        }

        private fun startCoordinatePathfind(command: String, fly: Boolean, usage: String) {
            val args = command.trim().split(Regex("\\s+"))
            if (args.size != 5) return
            try {
                val x = Integer.parseInt(args[2])
                val y = Integer.parseInt(args[3])
                val z = Integer.parseInt(args[4])
                if (fly) PathfindingManager.startDebugFlyPathfind(Minecraft.getInstance(), x, y, z)
                else PathfindingManager.startDebugPathfind(Minecraft.getInstance(), x, y, z)
            } catch (_: NumberFormatException) {
                ClientUtils.sendMessage(usage, false)
            }
        }

        private fun startPathTest(command: String) {
            val args = command.trim().split(Regex("\\s+"))
            if (args.size != 5) return
            try {
                PathfindingManager.startPathTest(Minecraft.getInstance(), Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]))
            } catch (_: NumberFormatException) {
                ClientUtils.sendMessage("\u00A7eUsage: /aether pathtest <x> <y> <z>", false)
            }
        }

        private fun sendPathfindHelp() {
            ClientUtils.sendMessage("\u00A7ePathfind commands:", false)
            ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7fx y z \u00A78- walk to coords", false)
            ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7fwalk x y z \u00A78- walk to coords", false)
            ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7ffly x y z \u00A78- fly to coords", false)
            ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7fetherwarp x y z \u00A78- etherwarp to coords", false)
            ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7fstop \u00A78- stop pathfinding", false)
        }

        private fun sendFailsafeTestHelp() {
            ClientUtils.sendMessage("\u00A7eFailsafe test commands:", false)
            ClientUtils.sendMessage("\u00A77  /aether testfailsafe \u00A7finventoryslot <slot> \u00A78- switch hotbar slot", false)
            ClientUtils.sendMessage("\u00A77  /aether testfailsafe \u00A7frotation <pitch> <yaw>", false)
            ClientUtils.sendMessage("\u00A77  /aether testfailsafe \u00A7fguiflash <durationTicks>", false)
        }

        private fun sendConfigHelp() {
            ClientUtils.sendMessage("\u00A7eConfig commands:", false)
            ClientUtils.sendMessage("\u00A77  /aether config \u00A7fexport \u00A78- copy your config to the clipboard", false)
            ClientUtils.sendMessage("\u00A77  /aether config \u00A7fimport <string> \u00A78- apply a pasted config string", false)
        }

        private fun exportConfig(): Int {
            val client = Minecraft.getInstance()
            val json = AetherConfig.exportSanitizedJson()
            client.keyboardHandler.setClipboard(json)
            ClientUtils.sendMessage(String.format("\u00A7aConfig copied to clipboard (%d chars). Sensitive fields (license key, webhook, usernames) were blanked.", json.length), false)
            return 1
        }

        private fun importConfig(json: String): Int {
            if (AetherConfig.importFromJson(json)) {
                AetherBootstrapHooks.onConfigProfileLoaded(AetherConfig.getConfigFile())
                ClientUtils.sendMessage("\u00A7aConfig imported and applied.", false)
                return 1
            }
            ClientUtils.sendMessage("\u00A7cConfig import failed - the string is not valid config JSON.", false)
            return 0
        }

        private fun sendMovementHelp() {
            ClientUtils.sendMessage("\u00A7eMovement commands:", false)
            ClientUtils.sendMessage("\u00A77  /aether movement \u00A7frecord \u00A78- record movement events", false)
            ClientUtils.sendMessage("\u00A77  /aether movement \u00A7fstop \u00A78- stop recording or playback", false)
            ClientUtils.sendMessage("\u00A77  /aether movement \u00A7ffolder \u00A78- open the movement folder", false)
            ClientUtils.sendMessage("\u00A77  /aether movement \u00A7fplay <replay_file> \u00A78- play a recording", false)
        }

        private fun rotateToPitchYaw(client: Minecraft, pitch: Float, yaw: Float): Int {
            if (client.player == null) return 0
            if (RotationManager.isRotating()) RotationManager.cancelRotation()
            RotationManager.rotateToYawPitch(client, yaw, pitch, 0L)
            ClientUtils.sendMessage(String.format("\u00A7eRotating to pitch %.1f yaw %.1f.", pitch, yaw), false)
            return 1
        }

        private fun printScoreboard(client: Minecraft): Int {
            val lines: List<String> = ClientUtils.getSidebarLines()
            if (lines.isEmpty()) {
                ClientUtils.sendMessage("\u00A7c" + AetherLang.localize("No scoreboard lines found."), false)
                return 0
            }
            ClientUtils.sendMessage("\u00A7e" + String.format(AetherLang.localize("Scoreboard lines (%d):"), lines.size), false)
            for (i in lines.indices) {
                val line = if (lines[i].isBlank()) AetherLang.localize("[blank]") else lines[i]
                ClientUtils.sendMessage("\u00A77" + (i + 1) + ". " + line, false)
            }
            return 1
        }

        private fun suggestAngles(builder: SuggestionsBuilder, vararg values: String): CompletableFuture<Suggestions> {
            val remaining = builder.remaining
            for (value in values) if (value.startsWith(remaining)) builder.suggest(value)
            return builder.buildFuture()
        }

        private fun suggestMovementReplays(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
            val remaining = builder.remaining.lowercase()
            for (file in MovementPlaybackManager.listReplayFiles()) if (file.lowercase().startsWith(remaining)) builder.suggest(file)
            return builder.buildFuture()
        }

        private fun suggestDynamicPestCrops(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
            val remaining = builder.remaining.lowercase()
            for (crop in DynamicPestsManager.getAvailableCrops()) if (crop.lowercase().startsWith(remaining)) builder.suggest(crop)
            return builder.buildFuture()
        }

        private fun triggerDynamicPest(crop: String): Int {
            val client = Minecraft.getInstance()
            if (client.player == null || client.connection == null) return 0
            if (DynamicPestsManager.triggerTestApply(client, crop)) {
                ClientUtils.sendMessage(String.format(AetherLang.localize("Dynamic Pests test triggered for %s."), crop), false)
                return 1
            }
            ClientUtils.sendMessage(AetherLang.localize("Unable to trigger Dynamic Pests test."), false)
            return 0
        }
    }
}
