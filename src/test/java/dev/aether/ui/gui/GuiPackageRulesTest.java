package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GuiPackageRulesTest {
    private static final Path SOURCES = Path.of("src/main/java/dev/aether/ui/gui");

    // the legacy scissor stack, absolute alpha and global text scale would silently undo the canvas state
    private static final Pattern LEGACY_STATE =
            Pattern.compile("\\.(pushScissor|popScissor|scissor|resetScissor|globalAlpha|setTextScale)\\s*\\(");
    private static final Pattern MINECRAFT_SINGLETON = Pattern.compile("Minecraft\\s*\\.\\s*getInstance\\s*\\(");
    private static final Pattern WALL_CLOCK = Pattern.compile("System\\s*(\\.|::)\\s*(nanoTime|currentTimeMillis)\\b");

    @Test
    void guiCodeNeverTouchesLegacyCanvasState() throws IOException {
        assertEquals(List.of(), offenders(LEGACY_STATE, null));
    }

    @Test
    void guiCodeStaysMinecraftFree() throws IOException {
        assertEquals(List.of(), offenders(MINECRAFT_SINGLETON, null));
    }

    @Test
    void onlyTheClockReadsTheWallClock() throws IOException {
        assertEquals(List.of(), offenders(WALL_CLOCK, "GuiClock.java"));
    }

    private static List<String> offenders(Pattern pattern, String allowedFile) throws IOException {
        assertTrue(Files.isDirectory(SOURCES), "run from the project root");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals(allowedFile)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        found.add(SOURCES.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        return found;
    }
}
