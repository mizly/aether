package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.util.AetherResources
import net.fabricmc.loader.api.FabricLoader
import java.awt.Desktop
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.BooleanControl
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent

object FailsafeSoundManager {
    private const val DEFAULT_SOUND_FILE = "fnaf.mp3"
    private const val FALLBACK_SOUND_FILE = "aether_alert_fallback.wav"
    private const val DEFAULT_SOUND_RESOURCE = "assets/aether/sounds/$DEFAULT_SOUND_FILE"
    private val supportedExtensions = setOf(".mp3", ".wav", ".aif", ".aiff", ".au")
    private val soundDir = FabricLoader.getInstance().configDir.resolve("aether").resolve("sounds")
    private val playbackExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Aether-FailsafeSound").apply { isDaemon = true } }
    private val clipLock = Any()
    @Volatile private var activeClip: Clip? = null

    @JvmStatic fun init() { ensureSoundDirectory(); ensureBundledDefaultSound(); ensureGeneratedFallbackSound(); normalizeConfiguredSound() }
    @JvmStatic fun getDefaultSoundFileName() = DEFAULT_SOUND_FILE
    @JvmStatic fun refresh() = init()
    @JvmStatic fun openSoundFolder() {
        ensureSoundDirectory()
        try {
            if (isWindows()) { ProcessBuilder("explorer.exe", soundDir.toAbsolutePath().toString()).start(); return }
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(soundDir.toFile())
        } catch (e: IOException) { System.err.println("[Aether] Failed to open failsafe sound folder: ${e.message}") }
    }
    @JvmStatic fun getAvailableSounds(): List<String> {
        ensureSoundDirectory(); ensureBundledDefaultSound()
        return try {
            Files.list(soundDir).use { files -> files.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.filter { isSupportedSoundFile(it) }.sorted(String.CASE_INSENSITIVE_ORDER).toList() }
        } catch (e: IOException) { System.err.println("[Aether] Failed to list failsafe sounds: ${e.message}"); emptyList() }
    }
    @JvmStatic fun playConfiguredSound(action: FailsafeAction) {
        if (!AetherConfig.FAILSAFE_SOUND_ENABLED.get()) return
        val volume = clampVolume(AetherConfig.FAILSAFE_SOUND_VOLUME.get())
        if (volume <= 0.0f) return
        val requested = requestedSoundFor(action)
        playbackExecutor.execute { resolvePlayableSound(requested)?.let { playSound(it, volume) } }
    }
    @JvmStatic fun playConfiguredSoundFile(requested: String?) {
        if (!AetherConfig.FAILSAFE_SOUND_ENABLED.get()) return
        val volume = clampVolume(AetherConfig.FAILSAFE_SOUND_VOLUME.get())
        if (volume <= 0.0f) return
        val sanitized = sanitizeSoundName(requested)
        playbackExecutor.execute { resolvePlayableSound(sanitized)?.let { playSound(it, volume) } }
    }
    private fun requestedSoundFor(action: FailsafeAction): String {
        val perAction = when (action) {
            FailsafeAction.STOP -> AetherConfig.FAILSAFE_SOUND_FILE_STOP.get()
            FailsafeAction.IGNORE, FailsafeAction.CUSTOM -> AetherConfig.FAILSAFE_SOUND_FILE_IGNORE.get()
        }
        val sanitized = sanitizeSoundName(perAction)
        return if (sanitized.isNotBlank()) sanitized else sanitizeSoundName(AetherConfig.FAILSAFE_SOUND_FILE.get())
    }
    private fun resolvePlayableSound(requested: String): Path? {
        val available = getAvailableSounds()
        if (available.isEmpty()) return null
        val candidates = listOf(requested, sanitizeSoundName(AetherConfig.FAILSAFE_SOUND_FILE.get()), DEFAULT_SOUND_FILE, FALLBACK_SOUND_FILE)
        for (candidate in candidates) {
            if (candidate.isBlank() || candidate !in available) continue
            val path = soundDir.resolve(candidate)
            if (isPlayableSoundFile(path)) return path
        }
        for (candidate in available) { val path = soundDir.resolve(candidate); if (isPlayableSoundFile(path)) return path }
        return null
    }
    private fun clampVolume(volume: Float) = if (volume.isNaN()) 0.0f else maxOf(0.0f, minOf(1.0f, volume))
    private fun playSound(soundPath: Path, volume: Float) {
        try {
            AudioSystem.getAudioInputStream(soundPath.toFile()).use { sourceStream ->
                val sourceFormat = sourceStream.format
                val playbackStream: AudioInputStream = if (AudioFormat.Encoding.PCM_SIGNED == sourceFormat.encoding) sourceStream else {
                    val decodedFormat = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sourceFormat.sampleRate, 16, sourceFormat.channels, sourceFormat.channels * 2, sourceFormat.sampleRate, false)
                    AudioSystem.getAudioInputStream(decodedFormat, sourceStream)
                }
                playbackStream.use { streamToOpen ->
                    val clip = AudioSystem.getClip()
                    clip.open(streamToOpen); applyVolume(clip, volume)
                    clip.addLineListener { event -> onClipEvent(clip, event) }
                    replaceActiveClip(clip); clip.start()
                }
            }
        } catch (e: Exception) { System.err.println("[Aether] Failed to play failsafe sound '${soundPath.fileName}': ${e.message}") }
    }
    private fun onClipEvent(clip: Clip, event: LineEvent) {
        if (event.type != LineEvent.Type.STOP && event.type != LineEvent.Type.CLOSE) return
        synchronized(clipLock) { if (activeClip == clip) activeClip = null }
        if (clip.isOpen) clip.close()
    }
    private fun replaceActiveClip(newClip: Clip) {
        synchronized(clipLock) { activeClip?.let { it.stop(); it.close() }; activeClip = newClip }
    }
    private fun applyVolume(clip: Clip, volume: Float) {
        if (clip.isControlSupported(BooleanControl.Type.MUTE)) (clip.getControl(BooleanControl.Type.MUTE) as BooleanControl).value = false
        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            val gainControl = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val requestedGain = if (volume <= 0.0f) gainControl.minimum else (20.0 * Math.log10(volume.toDouble())).toFloat()
            gainControl.value = maxOf(gainControl.minimum, minOf(requestedGain, gainControl.maximum))
        }
    }
    private fun resolveSelectedSoundPath(): Path? {
        val availableSounds = getAvailableSounds()
        if (availableSounds.isEmpty()) return null
        val configured = sanitizeSoundName(AetherConfig.FAILSAFE_SOUND_FILE.get())
        val selected = if (configured in availableSounds) configured else pickFallbackSound(availableSounds)
        if (selected != configured) { AetherConfig.FAILSAFE_SOUND_FILE.set(selected); AetherConfig.save() }
        val selectedPath = soundDir.resolve(selected)
        if (isPlayableSoundFile(selectedPath)) return selectedPath
        val fallback = pickPlayableFallbackSound(availableSounds, selected)
        if (fallback == null) { System.err.println("[Aether] No playable failsafe sounds available in ${soundDir.toAbsolutePath()}"); return null }
        val fallbackPath = soundDir.resolve(fallback)
        if (fallback != selected) { AetherConfig.FAILSAFE_SOUND_FILE.set(fallback); AetherConfig.save() }
        return fallbackPath
    }
    private fun pickFallbackSound(availableSounds: List<String>) = when {
        DEFAULT_SOUND_FILE in availableSounds -> DEFAULT_SOUND_FILE
        FALLBACK_SOUND_FILE in availableSounds -> FALLBACK_SOUND_FILE
        else -> availableSounds.first()
    }
    private fun pickPlayableFallbackSound(availableSounds: List<String>, currentlySelected: String): String? = availableSounds.firstOrNull { it != currentlySelected && isPlayableSoundFile(soundDir.resolve(it)) }
    private fun normalizeConfiguredSound() {
        val selectedSound = resolveSelectedSoundPath() ?: return
        val normalized = selectedSound.fileName.toString()
        if (normalized != AetherConfig.FAILSAFE_SOUND_FILE.get()) { AetherConfig.FAILSAFE_SOUND_FILE.set(normalized); AetherConfig.save() }
    }
    private fun ensureSoundDirectory() {
        try { Files.createDirectories(soundDir) } catch (e: IOException) { throw IllegalStateException("Failed to create failsafe sound directory: $soundDir", e) }
    }
    private fun ensureBundledDefaultSound() {
        val defaultSoundPath = soundDir.resolve(DEFAULT_SOUND_FILE)
        if (Files.exists(defaultSoundPath)) return
        try {
            val inputStream = AetherResources.open(DEFAULT_SOUND_RESOURCE)
            if (inputStream == null) { System.err.println("[Aether] Bundled failsafe sound missing: $DEFAULT_SOUND_RESOURCE"); return }
            inputStream.use { Files.copy(it, defaultSoundPath, StandardCopyOption.REPLACE_EXISTING) }
        } catch (e: IOException) { System.err.println("[Aether] Failed to copy bundled failsafe sound: ${e.message}") }
    }
    private fun ensureGeneratedFallbackSound() {
        val fallbackSoundPath = soundDir.resolve(FALLBACK_SOUND_FILE)
        if (Files.exists(fallbackSoundPath)) return
        try { Files.newOutputStream(fallbackSoundPath).use { writeWaveFile(it, buildFallbackTonePcm(), 44_100, 1, 16) } }
        catch (e: IOException) { System.err.println("[Aether] Failed to create fallback failsafe sound: ${e.message}") }
    }
    private fun isPlayableSoundFile(soundPath: Path): Boolean {
        return try { AudioSystem.getAudioInputStream(soundPath.toFile()).use { true } }
        catch (e: Exception) { System.err.println("[Aether] Failsafe sound '${soundPath.fileName}' is not playable: ${e.message}"); false }
    }
    private fun buildFallbackTonePcm(): ByteArray {
        val sampleRate = 44_100; val totalSamples = (sampleRate * 0.65).toInt(); val pcm = ByteArray(totalSamples * 2); val frequencyHz = 880.0
        for (i in 0 until totalSamples) {
            val envelope = Math.exp(-3.0 * i / totalSamples)
            val sample = (Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate) * 0.8 * Short.MAX_VALUE * envelope).toInt().toShort()
            val offset = i * 2; pcm[offset] = (sample.toInt() and 0xFF).toByte(); pcm[offset + 1] = (sample.toInt() ushr 8 and 0xFF).toByte()
        }
        return pcm
    }
    @Throws(IOException::class)
    private fun writeWaveFile(outputStream: OutputStream, pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val blockAlign = channels * bitsPerSample / 8; val byteRate = sampleRate * blockAlign; val dataSize = pcmData.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())).putInt(36 + dataSize)
        header.put(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        header.put(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte())).putInt(16)
        header.putShort(1.toShort()).putShort(channels.toShort()).putInt(sampleRate).putInt(byteRate).putShort(blockAlign.toShort()).putShort(bitsPerSample.toShort())
        header.put(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte())).putInt(dataSize)
        outputStream.write(header.array()); outputStream.write(pcmData)
    }
    private fun isSupportedSoundFile(name: String): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        return supportedExtensions.any { lowerName.endsWith(it) }
    }
    private fun sanitizeSoundName(name: String?): String = if (name.isNullOrBlank()) "" else Path.of(name).fileName.toString()
    private fun isWindows() = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
}
