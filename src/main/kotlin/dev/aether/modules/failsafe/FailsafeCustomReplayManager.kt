package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.config.entries.StringEntry
import dev.aether.modules.movement.MovementPlaybackManager
import dev.aether.util.AetherLang
import dev.aether.util.ClientUtils
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

object FailsafeCustomReplayManager {
    const val RANDOM_LOCAL_OPTION = "Random (Local)"
    const val RANDOM_GLOBAL_OPTION = "Random (Global)"
    private const val LEGACY_RANDOM_OPTION = "Random"
    private val replayRoot = FabricLoader.getInstance().configDir.resolve("aether").resolve("movement").resolve("failsafes")

    enum class FailsafeReplayType(val folderName: String, val replayEntry: StringEntry) {
        INVENTORY_SLOT("inventory_slot", AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_CUSTOM_REPLAY),
        GUI_OPENED("gui_opened", AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_CUSTOM_REPLAY),
        BPS("bps", AetherConfig.FAILSAFE_BPS_CUSTOM_REPLAY),
        GHOST_BLOCK("ghost_block", AetherConfig.FAILSAFE_GHOST_BLOCK_CUSTOM_REPLAY),
        DIRT_CHECK("dirt_check", AetherConfig.FAILSAFE_DIRT_CHECK_CUSTOM_REPLAY),
        ROTATION("rotation", AetherConfig.FAILSAFE_ROTATION_CUSTOM_REPLAY),
        PEST_ROTATION("pest_rotation", AetherConfig.FAILSAFE_PEST_ROTATION_CUSTOM_REPLAY),
        WORLD_CHANGE("world_change", AetherConfig.FAILSAFE_WORLD_CHANGE_CUSTOM_REPLAY)
    }

    @JvmStatic fun playConfiguredReplay(client: Minecraft, type: FailsafeReplayType?) {
        if (type == null) return
        val selected = sanitizeReplayName(type.replayEntry.get())
        if (isRandomGlobalOption(selected)) {
            val replays = getAvailableGlobalReplays()
            if (replays.isEmpty()) { ClientUtils.sendMessage("§c${AetherLang.localize("No custom failsafe replays found.")}", false); return }
            val replay = replays[ThreadLocalRandom.current().nextInt(replays.size)]
            MovementPlaybackManager.playFromDirectory(client, replay.parent, replay.fileName.toString()); return
        }
        val replays = getAvailableReplays(type)
        if (replays.isEmpty()) { ClientUtils.sendMessage("§c${AetherLang.localize("No custom failsafe replays found.")}", false); return }
        var replay = if (isRandomLocalOption(selected)) replays[ThreadLocalRandom.current().nextInt(replays.size)] else selected
        if (!replays.contains(replay)) {
            replay = replays.first()
            type.replayEntry.set(replay)
            AetherConfig.save()
        }
        MovementPlaybackManager.playFromDirectory(client, getReplayDirectory(type), replay)
    }

    @JvmStatic fun getAvailableReplayOptions(type: FailsafeReplayType): List<String> = mutableListOf(RANDOM_LOCAL_OPTION, RANDOM_GLOBAL_OPTION).apply { addAll(getAvailableReplays(type)) }
    @JvmStatic fun getSelectedReplayIndex(type: FailsafeReplayType, options: List<String>): Int {
        val selected = sanitizeReplayName(type.replayEntry.get())
        if (selected.equals(LEGACY_RANDOM_OPTION, true)) return 0
        return options.indexOf(selected).takeIf { it >= 0 } ?: 0
    }
    @JvmStatic fun setSelectedReplay(type: FailsafeReplayType, options: List<String>, index: Int) {
        if (index !in options.indices) return
        type.replayEntry.set(options[index]); AetherConfig.save()
    }
    @JvmStatic fun refreshReplayOptions(type: FailsafeReplayType, options: MutableList<String>) { options.clear(); options.addAll(getAvailableReplayOptions(type)) }
    @JvmStatic fun openReplayFolder(type: FailsafeReplayType) {
        val directory = getReplayDirectory(type)
        try {
            Files.createDirectories(directory)
            if (isWindows()) ProcessBuilder("explorer.exe", directory.toAbsolutePath().toString()).start()
            else if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(directory.toFile())
        } catch (e: IOException) { System.err.println("[Aether] Failed to open failsafe replay folder: ${e.message}") }
    }
    private fun getAvailableReplays(type: FailsafeReplayType) = MovementPlaybackManager.listReplayFiles(getReplayDirectory(type))
    private fun getAvailableGlobalReplays(): List<Path> {
        if (!Files.isDirectory(replayRoot)) return emptyList()
        return try {
            Files.walk(replayRoot).use { files -> files.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".json") }
                .sorted { left, right -> replayRoot.relativize(left).toString().compareTo(replayRoot.relativize(right).toString(), true) }
                .toList() }
        } catch (_: IOException) { emptyList() }
    }
    private fun getReplayDirectory(type: FailsafeReplayType) = replayRoot.resolve(type.folderName)
    private fun sanitizeReplayName(name: String?): String {
        if (name.isNullOrBlank()) return RANDOM_LOCAL_OPTION
        val fileName = Path.of(name).fileName.toString()
        if (isRandomLocalOption(fileName) || isRandomGlobalOption(fileName) || fileName.equals(LEGACY_RANDOM_OPTION, true)) return fileName
        return if (fileName.lowercase(Locale.ROOT).endsWith(".json")) fileName else "$fileName.json"
    }
    private fun isRandomLocalOption(name: String) = name.equals(RANDOM_LOCAL_OPTION, true) || name.equals(LEGACY_RANDOM_OPTION, true)
    private fun isRandomGlobalOption(name: String) = name.equals(RANDOM_GLOBAL_OPTION, true)
    private fun isWindows() = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
}
