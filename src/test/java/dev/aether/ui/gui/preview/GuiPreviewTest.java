package dev.aether.ui.gui.preview;

import dev.aether.ui.gui.GuiStyle;
import dev.aether.ui.gui.GuiView;
import dev.aether.ui.gui.ManualClock;
import dev.aether.ui.gui.StyleRegistry;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.theme.ThemePreset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

// renders every style x theme x scenario to build/reports/gui-preview/<style>/<theme>/<scenario>.png.
// opt-in and run by the guiPreview task only; see README.md next to this file
@Tag("gui-preview")
@EnabledIfEnvironmentVariable(named = "AETHER_GUI_PREVIEW", matches = "1")
class GuiPreviewTest {
    private static final List<String> DEFAULT_THEMES = List.of("default", "sage");

    @Test
    void rendersEveryScenario() throws IOException {
        TestConfigDir.ensure();
        Path output = Path.of(System.getProperty("preview.outputDir", "build/reports/gui-preview"));
        String savedTheme = Theme.exportJson();
        String savedStyle = Theme.GUI_STYLE;
        List<Path> written = new ArrayList<>();
        try (PreviewHarness harness = PreviewHarness.start()) {
            for (String styleId : styleIds()) {
                for (String theme : filter("preview.theme", DEFAULT_THEMES)) {
                    applyTheme(theme);
                    Theme.GUI_STYLE = styleId;
                    for (Scenario scenario : scenarios()) {
                        ManualClock clock = new ManualClock();
                        GuiView view = new GuiView(new PreviewGuiHost(), clock);
                        BufferedImage image = harness.capture(scenario, view, clock);
                        view.close();
                        Path file = output.resolve(view.style().id()).resolve(theme).resolve(scenario.name() + ".png");
                        Files.createDirectories(file.getParent());
                        ImageIO.write(image, "png", file.toFile());
                        written.add(file);
                        assertTrue(distinctColours(image) > 16, file + " looks blank");
                    }
                }
            }
        } finally {
            Theme.importJson(savedTheme);
            Theme.GUI_STYLE = savedStyle;
        }
        assertFalse(written.isEmpty(), "the filters matched nothing");
        written.forEach(file -> System.out.println("[gui-preview] " + file.toAbsolutePath()));
    }

    // registered styles, or the default id (which resolves to the debug scene) while none are registered
    private static List<String> styleIds() {
        StyleRegistry styles = StyleRegistry.defaults();
        List<String> ids = styles.all().stream().map(GuiStyle::id).toList();
        List<String> candidates = ids.isEmpty() ? List.of(StyleRegistry.DEFAULT_ID) : ids;
        List<String> wanted = filter("preview.style", null);
        return wanted == null ? candidates : candidates.stream()
                .filter(id -> wanted.contains(id) || wanted.contains(styles.resolve(id).id())).toList();
    }

    private static List<Scenario> scenarios() {
        List<String> wanted = filter("preview.scenario", null);
        return PreviewScenarios.all().stream().filter(s -> wanted == null || wanted.contains(s.name())).toList();
    }

    private static List<String> filter(String property, List<String> fallback) {
        String value = System.getProperty(property, "").trim();
        return value.isEmpty() ? fallback : Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    private static void applyTheme(String theme) {
        if (theme.equals("default")) {
            Theme.resetColorsToDefaults();
        } else {
            ThemePreset.valueOf(theme.toUpperCase(Locale.ROOT)).apply();
        }
    }

    private static long distinctColours(BufferedImage image) {
        return Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
                .distinct().count();
    }
}
