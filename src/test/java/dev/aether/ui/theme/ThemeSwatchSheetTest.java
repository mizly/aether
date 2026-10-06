package dev.aether.ui.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

// AETHER_THEME_SHEET=1 renders every preset side by side to build/reports/theme-presets/presets.png
@EnabledIfEnvironmentVariable(named = "AETHER_THEME_SHEET", matches = "1")
class ThemeSwatchSheetTest {
    @Test
    void rendersEveryPresetSideBySide() throws Exception {
        Path out = Path.of("build/reports/theme-presets/presets.png");
        ThemeSwatchSheet.render(out, Path.of("src/main/resources/assets/aether/themes"),
                Path.of("src/main/resources/assets/aether/fonts/ui"),
                Arrays.stream(ThemePreset.values()).map(ThemePreset::id).toList());
        assertTrue(Files.size(out) > 0);
    }
}
