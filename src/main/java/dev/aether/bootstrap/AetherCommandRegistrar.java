package dev.aether.bootstrap;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.aether.bootstrap.AetherUiActions;
import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.ComposterManager;
import dev.aether.modules.GreenhouseManager;
import dev.aether.modules.SupercraftManager;
import dev.aether.modules.discord.DiscordStatusManager;
import dev.aether.modules.forge.ForgeManager;
import dev.aether.modules.failsafe.FailsafeTestManager;
import dev.aether.modules.interaction.EntityInteractManager;
import dev.aether.modules.metaldetector.MetalDetectorSolver;
import dev.aether.modules.inventorymanager.AutoSellManager;
import dev.aether.modules.movement.MovementPlaybackManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pathfinding.debug.PathVisualizer;
import dev.aether.modules.pest.DynamicPestsManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.PestExchangeManager;
import dev.aether.modules.pest.helpers.PestTrapManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.modules.visitor.VisitorsMacro;
import dev.aether.util.AetherLang;
import dev.aether.util.BazaarUtils;
import dev.aether.util.ClientUtils;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class AetherCommandRegistrar {
    private AetherCommandRegistrar() {
    }

    public static void register() {
        registerLegacyCommandIntercepts();
        registerClientCommands();
    }

    private static void registerLegacyCommandIntercepts() {
        ClientSendMessageEvents.CHAT.register(message ->
                MovementPlaybackManager.recordOutgoingChat(message, false));
        ClientSendMessageEvents.COMMAND.register(command -> {
            MovementPlaybackManager.recordOutgoingChat(command, true);
            if (command.equalsIgnoreCase("call george")) {
                dev.aether.modules.inventorymanager.GeorgeManager.onCallGeorgeSent();
            }

            String normalized = command.toLowerCase().trim();
            if (normalized.startsWith("aether goto ")) {
                startCoordinatePathfind(command, false, "\u00A7eUsage: /aether goto <x> <y> <z>");
            }
            if (normalized.startsWith("aether flyto ")) {
                startCoordinatePathfind(command, true, "\u00A7eUsage: /aether flyto <x> <y> <z>");
            }
            if (normalized.equals("aether debug path")) {
                PathVisualizer.toggle();
                ClientUtils.sendMessage("\u00A7aPath visualizer: " + (PathVisualizer.isEnabled() ? "ON" : "OFF"), false);
            }
            if (normalized.startsWith("aether pathtest ")) {
                startPathTest(command);
            }
        });
    }

    private static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    ClientCommands.literal("aether")
                            .executes(ctx -> {
                                AetherUiActions.toggleMainGui();
                                return 1;
                            })
                            .then(ClientCommands.literal("farming")
                                    .executes(ctx -> {
                                        Minecraft client = Minecraft.getInstance();
                                        if (MacroStateManager.getCurrentState() != MacroState.State.OFF) {
                                            ClientUtils.sendMessage("\u00A7cA macro is already running.", false);
                                            return 0;
                                        }

                                        AetherKeybindHandler.startFarmingMacro(client);
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("stop")
                                    .executes(ctx -> {
                                        Minecraft client = Minecraft.getInstance();
                                        if (MacroStateManager.getCurrentState() == MacroState.State.OFF) {
                                            ClientUtils.sendMessage("\u00A7eNo macro is currently running.", false);
                                            return 0;
                                        }

                                        MacroStateManager.stopMacro(client);
                                        ClientUtils.sendMessage("\u00A7eStopped active macro.", false);
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("status")
                                    .executes(ctx -> {
                                        Minecraft client = Minecraft.getInstance();
                                        if (DiscordStatusManager.requestManualStatusUpdate()) {
                                            ClientUtils.sendMessage("\u00A7eSending Discord webhook status update.", false);
                                            return 1;
                                        }

                                        ClientUtils.sendMessage("\u00A7cUnable to send Discord status update. Check your webhook URL or wait for the current screenshot to finish.",
                                                false);
                                        return 0;
                                    }))
                            .then(ClientCommands.literal("printscoreboard")
                                    .executes(ctx -> printScoreboard(Minecraft.getInstance())))
                            .then(ClientCommands.literal("rotate")
                                    .then(ClientCommands.argument("pitch", FloatArgumentType.floatArg(-90.0f, 90.0f))
                                            .suggests((ctx, builder) -> suggestAngles(builder, "-90", "-45", "0", "45", "90"))
                                            .then(ClientCommands.argument("yaw", FloatArgumentType.floatArg())
                                                    .suggests((ctx, builder) -> suggestAngles(builder,
                                                            "-180", "-135", "-90", "-45", "0", "45", "90", "135", "180"))
                                                    .executes(ctx -> {
                                                        float pitch = FloatArgumentType.getFloat(ctx, "pitch");
                                                        float yaw = FloatArgumentType.getFloat(ctx, "yaw");
                                                        return rotateToPitchYaw(Minecraft.getInstance(), pitch, yaw);
                                                    }))))
                            .then(ClientCommands.literal("movement")
                                    .executes(ctx -> {
                                        sendMovementHelp();
                                        return 1;
                                    })
                                    .then(ClientCommands.literal("record")
                                            .executes(ctx -> {
                                                MovementPlaybackManager.startRecording();
                                                return 1;
                                            }))
                                    .then(ClientCommands.literal("stop")
                                            .executes(ctx -> {
                                                MovementPlaybackManager.stop();
                                                return 1;
                                            }))
                                    .then(ClientCommands.literal("folder")
                                            .executes(ctx -> {
                                                MovementPlaybackManager.openMovementFolder();
                                                return 1;
                                            }))
                                    .then(ClientCommands.literal("play")
                                            .then(ClientCommands.argument("replay_file", StringArgumentType.greedyString())
                                                    .suggests((ctx, builder) -> suggestMovementReplays(builder))
                                                    .executes(ctx -> {
                                                        String replayFile = StringArgumentType.getString(ctx, "replay_file");
                                                        MovementPlaybackManager.play(replayFile);
                                                        return 1;
                                                    }))))
                            .then(ClientCommands.literal("testfailsafe")
                                    .executes(ctx -> {
                                        sendFailsafeTestHelp();
                                        return 1;
                                    })
                                    .then(ClientCommands.literal("inventoryslot")
                                            .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                                                    .suggests((ctx, builder) -> suggestAngles(builder,
                                                            "1", "2", "3", "4", "5", "6", "7", "8", "9"))
                                                    .executes(ctx -> {
                                                        int slot = IntegerArgumentType.getInteger(ctx, "slot");
                                                        FailsafeTestManager.scheduleInventorySlot(slot);
                                                        return 1;
                                                    })))
                                    .then(ClientCommands.literal("rotation")
                                            .then(ClientCommands.argument("pitch", FloatArgumentType.floatArg())
                                                    .suggests((ctx, builder) -> suggestAngles(builder, "-30", "-15", "0", "15", "30"))
                                                    .then(ClientCommands.argument("yaw", FloatArgumentType.floatArg())
                                                            .suggests((ctx, builder) -> suggestAngles(builder,
                                                                    "-90", "-45", "-30", "-15", "15", "30", "45", "90"))
                                                            .executes(ctx -> {
                                                                float pitch = FloatArgumentType.getFloat(ctx, "pitch");
                                                                float yaw = FloatArgumentType.getFloat(ctx, "yaw");
                                                                FailsafeTestManager.scheduleRotation(pitch, yaw);
                                                                return 1;
                                                            }))))
                                    .then(ClientCommands.literal("guiflash")
                                            .then(ClientCommands.argument("duration", IntegerArgumentType.integer(1))
                                                    .executes(ctx -> {
                                                        int duration = IntegerArgumentType.getInteger(ctx, "duration");
                                                        FailsafeTestManager.scheduleGuiFlash(duration);
                                                        return 1;
                                                    }))))
                            .then(ClientCommands.literal("pathfind")
                                    .executes(ctx -> {
                                        sendPathfindHelp();
                                        return 1;
                                    })
                                    .then(ClientCommands.literal("stop")
                                            .executes(ctx -> {
                                                PathfindingManager.stop();
                                                ClientUtils.sendMessage("\u00A7ePathfinder stopped.", false);
                                                return 1;
                                            }))
                                    .then(ClientCommands.literal("fly")
                                            .executes(ctx -> {
                                                sendPathfindHelp();
                                                return 1;
                                            })
                                            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                                    .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                            .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                                    .executes(ctx -> {
                                                                        int x = IntegerArgumentType.getInteger(ctx, "x");
                                                                        int y = IntegerArgumentType.getInteger(ctx, "y");
                                                                        int z = IntegerArgumentType.getInteger(ctx, "z");
                                                                        PathfindingManager.startDebugFlyPathfind(
                                                                                Minecraft.getInstance(), x, y, z);
                                                                        return 1;
                                                                    })))))
                                    .then(ClientCommands.literal("etherwarp")
                                            .executes(ctx -> {
                                                sendPathfindHelp();
                                                return 1;
                                            })
                                            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                                    .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                            .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                                    .executes(ctx -> {
                                                                        int x = IntegerArgumentType.getInteger(ctx, "x");
                                                                        int y = IntegerArgumentType.getInteger(ctx, "y");
                                                                        int z = IntegerArgumentType.getInteger(ctx, "z");
                                                                        PathfindingManager.startDebugEtherwarpPathfind(
                                                                                Minecraft.getInstance(), x, y, z);
                                                                        return 1;
                                                                    })))))
                                    .then(ClientCommands.literal("walk")
                                            .executes(ctx -> {
                                                sendPathfindHelp();
                                                return 1;
                                            })
                                            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                                    .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                            .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                                    .executes(ctx -> {
                                                                        int x = IntegerArgumentType.getInteger(ctx, "x");
                                                                        int y = IntegerArgumentType.getInteger(ctx, "y");
                                                                        int z = IntegerArgumentType.getInteger(ctx, "z");
                                                                        PathfindingManager.startDebugPathfind(
                                                                                Minecraft.getInstance(), x, y, z);
                                                                        return 1;
                                                                    })))))
                                    .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                            .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                    .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                            .executes(ctx -> {
                                                                int x = IntegerArgumentType.getInteger(ctx, "x");
                                                                int y = IntegerArgumentType.getInteger(ctx, "y");
                                                                int z = IntegerArgumentType.getInteger(ctx, "z");
                                                                PathfindingManager.startDebugPathfind(
                                                                        Minecraft.getInstance(), x, y, z);
                                                                return 1;
                                                            })))))
                            .then(ClientCommands.literal("pestexchange")
                                    .executes(ctx -> {
                                        PestExchangeManager.start(Minecraft.getInstance());
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("metaldetector")
                                    .executes(ctx -> {
                                        MetalDetectorSolver.toggle(Minecraft.getInstance());
                                        return 1;
                                    })
                                    .then(ClientCommands.literal("scan")
                                            .executes(ctx -> {
                                                MetalDetectorSolver.forceScan(Minecraft.getInstance());
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("bazaar")
                                    .executes(ctx -> {
                                        ClientUtils.sendMessage("\u00A7eUsage: /aether bazaar <item> <count>", false);
                                        return 1;
                                    })
                                    .then(ClientCommands.argument("item", StringArgumentType.string())
                                            .then(ClientCommands.argument("count", IntegerArgumentType.integer(1))
                                                    .executes(ctx -> {
                                                        String item = StringArgumentType.getString(ctx, "item");
                                                        int count = IntegerArgumentType.getInteger(ctx, "count");
                                                        BazaarUtils.buy(Minecraft.getInstance(), item, count, success -> {
                                                            if (!success) {
                                                                Minecraft.getInstance().execute(() -> {
                                                                    if (Minecraft.getInstance().player != null) {
                                                                        ClientUtils.sendMessage("\u00A7cBazaar buy failed.", false);
                                                                    }
                                                                });
                                                            }
                                                        });
                                                        return 1;
                                                    }))))
                            .then(ClientCommands.literal("visitors")
                                    .executes(ctx -> {
                                        VisitorsMacro.start(Minecraft.getInstance(), true);
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("pestdestroyer")
                                    .executes(ctx -> {
                                        Minecraft client = Minecraft.getInstance();
                                        if (PestDestroyer.isActive()) {
                                            PestDestroyer.stop(client);
                                            ClientUtils.sendMessage("\u00A7ePest Destroyer stopped.", false);
                                        } else {
                                            PestDestroyer.start(client);
                                        }
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("dynamicpest")
                                    .then(ClientCommands.argument("crop", StringArgumentType.greedyString())
                                            .suggests((ctx, builder) -> suggestDynamicPestCrops(builder))
                                            .executes(ctx -> {
                                                String crop = StringArgumentType.getString(ctx, "crop");
                                                return triggerDynamicPest(crop);
                                            })))
                            .then(ClientCommands.literal("autosell")
                                    .executes(ctx -> {
                                        Minecraft client = Minecraft.getInstance();
                                        if (AutoSellManager.isSelling || AutoSellManager.isPreparingToSell) {
                                            ClientUtils.sendMessage("\u00A7cAutoSell is already running.", false);
                                        } else {
                                            AutoSellManager.manualTrigger(client);
                                        }
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("pesttraps")
                                    .executes(ctx -> {
                                        PestTrapManager.start(Minecraft.getInstance());
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("greenhouseharvest")
                                    .executes(ctx -> {
                                        GreenhouseManager.harvest();
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("debugskulls")
                                    .executes(ctx -> {
                                        GreenhouseManager.debugScanSkulls();
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("composter")
                                    .executes(ctx -> {
                                        ComposterManager.manualTrigger();
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("supercraft")
                                    .executes(ctx -> {
                                        SupercraftManager.manualTrigger();
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("refilltraps")
                                    .executes(ctx -> {
                                        PestTrapManager.startRefill(Minecraft.getInstance());
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("forge")
                                    .executes(ctx -> {
                                        ForgeManager.start(Minecraft.getInstance());
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("interact")
                                    .then(ClientCommands.argument("entity_name", StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                String entityName = StringArgumentType.getString(ctx, "entity_name");
                                                EntityInteractManager.start(Minecraft.getInstance(), entityName);
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("config")
                                    .executes(ctx -> {
                                        sendConfigHelp();
                                        return 1;
                                    })
                                    .then(ClientCommands.literal("export")
                                            .executes(ctx -> exportConfig()))
                                    .then(ClientCommands.literal("import")
                                            .executes(ctx -> {
                                                sendConfigHelp();
                                                return 1;
                                            })
                                            .then(ClientCommands.argument("config_string", StringArgumentType.greedyString())
                                                    .executes(ctx -> importConfig(
                                                            StringArgumentType.getString(ctx, "config_string")))))));
        });
    }

    private static void startCoordinatePathfind(String command, boolean fly, String usage) {
        String[] args = command.trim().split("\\s+");
        if (args.length != 5) {
            return;
        }

        try {
            int x = Integer.parseInt(args[2]);
            int y = Integer.parseInt(args[3]);
            int z = Integer.parseInt(args[4]);
            if (fly) {
                PathfindingManager.startDebugFlyPathfind(Minecraft.getInstance(), x, y, z);
            } else {
                PathfindingManager.startDebugPathfind(Minecraft.getInstance(), x, y, z);
            }
        } catch (NumberFormatException ignored) {
            ClientUtils.sendMessage(usage, false);
        }
    }

    private static void startPathTest(String command) {
        String[] args = command.trim().split("\\s+");
        if (args.length != 5) {
            return;
        }

        try {
            int x = Integer.parseInt(args[2]);
            int y = Integer.parseInt(args[3]);
            int z = Integer.parseInt(args[4]);
            PathfindingManager.startPathTest(Minecraft.getInstance(), x, y, z);
        } catch (NumberFormatException ignored) {
            ClientUtils.sendMessage("\u00A7eUsage: /aether pathtest <x> <y> <z>", false);
        }
    }

    private static void sendPathfindHelp() {
        ClientUtils.sendMessage("\u00A7ePathfind commands:", false);
        ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7fx y z \u00A78- walk to coords", false);
        ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7fwalk x y z \u00A78- walk to coords", false);
        ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7ffly x y z \u00A78- fly to coords", false);
        ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7fetherwarp x y z \u00A78- etherwarp to coords", false);
        ClientUtils.sendMessage("\u00A77  /aether pathfind \u00A7fstop \u00A78- stop pathfinding", false);
    }

    private static void sendFailsafeTestHelp() {
        ClientUtils.sendMessage("\u00A7eFailsafe test commands:", false);
        ClientUtils.sendMessage("\u00A77  /aether testfailsafe \u00A7finventoryslot <slot> \u00A78- switch hotbar slot", false);
        ClientUtils.sendMessage("\u00A77  /aether testfailsafe \u00A7frotation <pitch> <yaw>", false);
        ClientUtils.sendMessage("\u00A77  /aether testfailsafe \u00A7fguiflash <durationTicks>", false);
    }

    private static void sendConfigHelp() {
        ClientUtils.sendMessage("\u00A7eConfig commands:", false);
        ClientUtils.sendMessage("\u00A77  /aether config \u00A7fexport \u00A78- copy your config to the clipboard", false);
        ClientUtils.sendMessage("\u00A77  /aether config \u00A7fimport <string> \u00A78- apply a pasted config string", false);
    }

    private static int exportConfig() {
        Minecraft client = Minecraft.getInstance();
        String json = AetherConfig.exportSanitizedJson();
        client.keyboardHandler.setClipboard(json);
        ClientUtils.sendMessage(String.format(
                "\u00A7aConfig copied to clipboard (%d chars). Sensitive fields (license key, webhook, usernames) were blanked.",
                json.length()), false);
        return 1;
    }

    private static int importConfig(String json) {
        if (AetherConfig.importFromJson(json)) {
            AetherBootstrapHooks.onConfigProfileLoaded(AetherConfig.getConfigFile());
            ClientUtils.sendMessage("\u00A7aConfig imported and applied.", false);
            return 1;
        }
        ClientUtils.sendMessage("\u00A7cConfig import failed - the string is not valid config JSON.", false);
        return 0;
    }

    private static void sendMovementHelp() {
        ClientUtils.sendMessage("\u00A7eMovement commands:", false);
        ClientUtils.sendMessage("\u00A77  /aether movement \u00A7frecord \u00A78- record movement events", false);
        ClientUtils.sendMessage("\u00A77  /aether movement \u00A7fstop \u00A78- stop recording or playback", false);
        ClientUtils.sendMessage("\u00A77  /aether movement \u00A7ffolder \u00A78- open the movement folder", false);
        ClientUtils.sendMessage("\u00A77  /aether movement \u00A7fplay <replay_file> \u00A78- play a recording", false);
    }

    private static int rotateToPitchYaw(Minecraft client, float pitch, float yaw) {
        if (client.player == null) {
            return 0;
        }

        if (RotationManager.isRotating()) {
            RotationManager.cancelRotation();
        }

        RotationManager.rotateToYawPitch(client, yaw, pitch, 0L);
        ClientUtils.sendMessage(String.format("\u00A7eRotating to pitch %.1f yaw %.1f.", pitch, yaw), false);
        return 1;
    }

    private static int printScoreboard(Minecraft client) {
        List<String> lines = ClientUtils.getSidebarLines(client);
        if (lines.isEmpty()) {
            ClientUtils.sendMessage("\u00A7c" + AetherLang.localize("No scoreboard lines found."), false);
            return 0;
        }

        ClientUtils.sendMessage("\u00A7e" + AetherLang.localize("Scoreboard lines (%d):").formatted(lines.size()), false);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).isBlank() ? AetherLang.localize("[blank]") : lines.get(i);
            ClientUtils.sendMessage("\u00A77" + (i + 1) + ". " + line, false);
        }
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestAngles(SuggestionsBuilder builder, String... values) {
        String remaining = builder.getRemaining();
        for (String value : values) {
            if (value.startsWith(remaining)) {
                builder.suggest(value);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestMovementReplays(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String file : MovementPlaybackManager.listReplayFiles()) {
            if (file.toLowerCase().startsWith(remaining)) {
                builder.suggest(file);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestDynamicPestCrops(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String crop : DynamicPestsManager.getAvailableCrops()) {
            if (crop.toLowerCase().startsWith(remaining)) {
                builder.suggest(crop);
            }
        }
        return builder.buildFuture();
    }

    private static int triggerDynamicPest(String crop) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            return 0;
        }

        if (DynamicPestsManager.triggerTestApply(client, crop)) {
            ClientUtils.sendMessage(AetherLang.localize("Dynamic Pests test triggered for %s.").formatted(crop), false);
            return 1;
        }

        ClientUtils.sendMessage(AetherLang.localize("Unable to trigger Dynamic Pests test."), false);
        return 0;
    }
}
