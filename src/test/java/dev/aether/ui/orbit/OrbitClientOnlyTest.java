package dev.aether.ui.orbit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

// the orbit menu only renders: it must never talk to the server or move the real player
class OrbitClientOnlyTest {
    private static final Pattern FORBIDDEN = Pattern.compile(
            "getConnection|\\.connection\\b|ClientPacketListener|Serverbound|sendPacket|\\.send\\(|sendCommand|sendChat"
                    + "|player\\.(setYRot|setXRot|setPos|moveTo|absMoveTo|setDeltaMovement|teleportTo|lookAt)"
                    + "|CommandUtils|ClientUtils\\.sendCommand");

    @Test
    void orbitSourcesNeverReachTheServer() throws IOException {
        List<String> hits = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/aether/ui/orbit");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (FORBIDDEN.matcher(lines.get(i)).find()) {
                        hits.add(root.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).trim());
                    }
                }
            }
        }
        assertEquals(List.of(), hits);
    }
}
