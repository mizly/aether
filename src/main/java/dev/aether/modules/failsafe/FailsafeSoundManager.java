package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.util.AetherResources;
import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FailsafeSoundManager {

    private static final String DEFAULT_SOUND_FILE = "fnaf.mp3";
    private static final String FALLBACK_SOUND_FILE = "aether_alert_fallback.wav";
    private static final String DEFAULT_SOUND_RESOURCE = "assets/aether/sounds/" + DEFAULT_SOUND_FILE;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".mp3", ".wav", ".aif", ".aiff", ".au");
    private static final Path SOUND_DIR = FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("sounds");
    private static final ExecutorService PLAYBACK_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Aether-FailsafeSound");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object CLIP_LOCK = new Object();

    private static volatile Clip activeClip;

    private FailsafeSoundManager() {
    }

    public static void init() {
        ensureSoundDirectory();
        ensureBundledDefaultSound();
        ensureGeneratedFallbackSound();
        normalizeConfiguredSound();
    }

    public static String getDefaultSoundFileName() {
        return DEFAULT_SOUND_FILE;
    }

    public static void refresh() {
        init();
    }

    public static void openSoundFolder() {
        ensureSoundDirectory();
        try {
            if (isWindows()) {
                new ProcessBuilder("explorer.exe", SOUND_DIR.toAbsolutePath().toString()).start();
                return;
            }

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(SOUND_DIR.toFile());
            }
        } catch (IOException e) {
            System.err.println("[Aether] Failed to open failsafe sound folder: " + e.getMessage());
        }
    }

    public static List<String> getAvailableSounds() {
        ensureSoundDirectory();
        ensureBundledDefaultSound();

        try (var files = Files.list(SOUND_DIR)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(FailsafeSoundManager::isSupportedSoundFile)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            System.err.println("[Aether] Failed to list failsafe sounds: " + e.getMessage());
            return List.of();
        }
    }

    public static void playConfiguredSound(FailsafeAction action) {
        if (!AetherConfig.FAILSAFE_SOUND_ENABLED.get()) {
            return;
        }

        float volume = clampVolume(AetherConfig.FAILSAFE_SOUND_VOLUME.get());
        if (volume <= 0.0f) {
            return;
        }

        String requested = requestedSoundFor(action);
        PLAYBACK_EXECUTOR.execute(() -> {
            Path soundPath = resolvePlayableSound(requested);
            if (soundPath != null) {
                playSound(soundPath, volume);
            }
        });
    }

    /** Which sound file the user picked for this action, falling back to the shared default. */
    private static String requestedSoundFor(FailsafeAction action) {
        String perAction = switch (action) {
            case STOP -> AetherConfig.FAILSAFE_SOUND_FILE_STOP.get();
            // A CUSTOM replay keeps the macro alive, so it shares the "ignore" cue.
            case IGNORE, CUSTOM -> AetherConfig.FAILSAFE_SOUND_FILE_IGNORE.get();
        };
        String sanitized = sanitizeSoundName(perAction);
        if (!sanitized.isBlank()) {
            return sanitized;
        }
        return sanitizeSoundName(AetherConfig.FAILSAFE_SOUND_FILE.get());
    }

    /**
     * Resolves a requested sound name to a playable file without mutating config, trying the
     * request, then the shared default, then the bundled sounds, then any other playable file.
     */
    private static Path resolvePlayableSound(String requested) {
        List<String> available = getAvailableSounds();
        if (available.isEmpty()) {
            return null;
        }

        List<String> candidates = new ArrayList<>();
        candidates.add(requested);
        candidates.add(sanitizeSoundName(AetherConfig.FAILSAFE_SOUND_FILE.get()));
        candidates.add(DEFAULT_SOUND_FILE);
        candidates.add(FALLBACK_SOUND_FILE);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || !available.contains(candidate)) {
                continue;
            }
            Path path = SOUND_DIR.resolve(candidate);
            if (isPlayableSoundFile(path)) {
                return path;
            }
        }

        for (String candidate : available) {
            Path path = SOUND_DIR.resolve(candidate);
            if (isPlayableSoundFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static float clampVolume(float volume) {
        if (Float.isNaN(volume)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, volume));
    }

    private static void playSound(Path soundPath, float volume) {
        try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(soundPath.toFile())) {
            AudioFormat sourceFormat = sourceStream.getFormat();
            AudioInputStream playbackStream = sourceStream;

            if (!AudioFormat.Encoding.PCM_SIGNED.equals(sourceFormat.getEncoding())) {
                AudioFormat decodedFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        sourceFormat.getSampleRate(),
                        16,
                        sourceFormat.getChannels(),
                        sourceFormat.getChannels() * 2,
                        sourceFormat.getSampleRate(),
                        false);
                playbackStream = AudioSystem.getAudioInputStream(decodedFormat, sourceStream);
            }

            try (AudioInputStream streamToOpen = playbackStream) {
                Clip clip = AudioSystem.getClip();
                clip.open(streamToOpen);
                applyVolume(clip, volume);
                clip.addLineListener(event -> onClipEvent(clip, event));
                replaceActiveClip(clip);
                clip.start();
            }
        } catch (Exception e) {
            System.err.println("[Aether] Failed to play failsafe sound '" + soundPath.getFileName() + "': "
                    + e.getMessage());
        }
    }

    private static void onClipEvent(Clip clip, LineEvent event) {
        if (event.getType() != LineEvent.Type.STOP && event.getType() != LineEvent.Type.CLOSE) {
            return;
        }

        synchronized (CLIP_LOCK) {
            if (activeClip == clip) {
                activeClip = null;
            }
        }

        if (clip.isOpen()) {
            clip.close();
        }
    }

    private static void replaceActiveClip(Clip newClip) {
        synchronized (CLIP_LOCK) {
            if (activeClip != null) {
                activeClip.stop();
                activeClip.close();
            }
            activeClip = newClip;
        }
    }

    private static void applyVolume(Clip clip, float volume) {
        if (clip.isControlSupported(BooleanControl.Type.MUTE)) {
            ((BooleanControl) clip.getControl(BooleanControl.Type.MUTE)).setValue(false);
        }

        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            // Convert the 0.0-1.0 linear volume to decibels; 1.0 -> 0 dB (full), 0.05 -> ~-26 dB.
            float targetGain = volume <= 0.0f
                    ? gainControl.getMinimum()
                    : (float) (20.0 * Math.log10(volume));
            targetGain = Math.max(gainControl.getMinimum(), Math.min(targetGain, gainControl.getMaximum()));
            gainControl.setValue(targetGain);
        }
    }

    private static Path resolveSelectedSoundPath() {
        List<String> availableSounds = getAvailableSounds();
        if (availableSounds.isEmpty()) {
            return null;
        }

        String configured = sanitizeSoundName(AetherConfig.FAILSAFE_SOUND_FILE.get());
        String selected = availableSounds.contains(configured)
                ? configured
                : pickFallbackSound(availableSounds);

        if (!selected.equals(configured)) {
            AetherConfig.FAILSAFE_SOUND_FILE.set(selected);
            AetherConfig.save();
        }

        Path selectedPath = SOUND_DIR.resolve(selected);
        if (isPlayableSoundFile(selectedPath)) {
            return selectedPath;
        }

        String fallback = pickPlayableFallbackSound(availableSounds, selected);
        if (fallback == null) {
            System.err.println("[Aether] No playable failsafe sounds available in " + SOUND_DIR.toAbsolutePath());
            return null;
        }

        Path fallbackPath = SOUND_DIR.resolve(fallback);
        if (!fallback.equals(selected)) {
            AetherConfig.FAILSAFE_SOUND_FILE.set(fallback);
            AetherConfig.save();
        }
        return fallbackPath;
    }

    private static String pickFallbackSound(List<String> availableSounds) {
        if (availableSounds.contains(DEFAULT_SOUND_FILE)) {
            return DEFAULT_SOUND_FILE;
        }
        if (availableSounds.contains(FALLBACK_SOUND_FILE)) {
            return FALLBACK_SOUND_FILE;
        }
        return availableSounds.getFirst();
    }

    private static String pickPlayableFallbackSound(List<String> availableSounds, String currentlySelected) {
        for (String candidate : availableSounds) {
            if (candidate.equals(currentlySelected)) {
                continue;
            }
            Path candidatePath = SOUND_DIR.resolve(candidate);
            if (isPlayableSoundFile(candidatePath)) {
                return candidate;
            }
        }
        return null;
    }

    private static void normalizeConfiguredSound() {
        Path selectedSound = resolveSelectedSoundPath();
        if (selectedSound == null) {
            return;
        }

        String normalized = selectedSound.getFileName().toString();
        if (!normalized.equals(AetherConfig.FAILSAFE_SOUND_FILE.get())) {
            AetherConfig.FAILSAFE_SOUND_FILE.set(normalized);
            AetherConfig.save();
        }
    }

    private static void ensureSoundDirectory() {
        try {
            Files.createDirectories(SOUND_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create failsafe sound directory: " + SOUND_DIR, e);
        }
    }

    private static void ensureBundledDefaultSound() {
        Path defaultSoundPath = SOUND_DIR.resolve(DEFAULT_SOUND_FILE);
        if (Files.exists(defaultSoundPath)) {
            return;
        }

        try (InputStream inputStream = AetherResources.open(DEFAULT_SOUND_RESOURCE)) {
            if (inputStream == null) {
                System.err.println("[Aether] Bundled failsafe sound missing: " + DEFAULT_SOUND_RESOURCE);
                return;
            }
            Files.copy(inputStream, defaultSoundPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Aether] Failed to copy bundled failsafe sound: " + e.getMessage());
        }
    }

    private static void ensureGeneratedFallbackSound() {
        Path fallbackSoundPath = SOUND_DIR.resolve(FALLBACK_SOUND_FILE);
        if (Files.exists(fallbackSoundPath)) {
            return;
        }

        try (OutputStream outputStream = Files.newOutputStream(fallbackSoundPath)) {
            byte[] pcmData = buildFallbackTonePcm();
            writeWaveFile(outputStream, pcmData, 44_100, 1, 16);
        } catch (IOException e) {
            System.err.println("[Aether] Failed to create fallback failsafe sound: " + e.getMessage());
        }
    }

    private static boolean isPlayableSoundFile(Path soundPath) {
        try (AudioInputStream ignored = AudioSystem.getAudioInputStream(soundPath.toFile())) {
            return true;
        } catch (Exception e) {
            System.err.println("[Aether] Failsafe sound '" + soundPath.getFileName() + "' is not playable: " + e.getMessage());
            return false;
        }
    }

    private static byte[] buildFallbackTonePcm() {
        int sampleRate = 44_100;
        double durationSeconds = 0.65;
        int totalSamples = (int) (sampleRate * durationSeconds);
        byte[] pcm = new byte[totalSamples * 2];
        double frequencyHz = 880.0;

        for (int i = 0; i < totalSamples; i++) {
            double envelope = Math.exp(-3.0 * i / totalSamples);
            short sample = (short) (Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate) * 0.8 * Short.MAX_VALUE * envelope);
            int offset = i * 2;
            pcm[offset] = (byte) (sample & 0xFF);
            pcm[offset + 1] = (byte) ((sample >>> 8) & 0xFF);
        }
        return pcm;
    }

    private static void writeWaveFile(OutputStream outputStream, byte[] pcmData, int sampleRate, int channels, int bitsPerSample)
            throws IOException {
        int blockAlign = channels * bitsPerSample / 8;
        int byteRate = sampleRate * blockAlign;
        int dataSize = pcmData.length;
        int riffSize = 36 + dataSize;

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        header.putInt(riffSize);
        header.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        header.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        header.putInt(dataSize);

        outputStream.write(header.array());
        outputStream.write(pcmData);
    }

    private static boolean isSupportedSoundFile(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    }

    private static String sanitizeSoundName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return Path.of(name).getFileName().toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
