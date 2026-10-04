package dev.aether.ui.gui.preview;

import org.lwjgl.glfw.GLFW;

import java.util.List;

import static dev.aether.ui.gui.Inputs.key;

// the scenes every style renders; coordinates are layout units
final class PreviewScenarios {
    private PreviewScenarios() {
    }

    static List<Scenario> all() {
        return List.of(
                Scenario.of("home", 1280, 800)
                        .hover(220f, 217f)
                        .advance(400L),
                Scenario.of("narrow", 800, 480)
                        .advance(400L),
                Scenario.of("interaction", 1280, 800)
                        .click(200f, 257f)
                        .click(420f, 184f)
                        .key(key(GLFW.GLFW_KEY_TAB))
                        .key(key(GLFW.GLFW_KEY_DOWN))
                        .key(key(GLFW.GLFW_KEY_DOWN))
                        .scroll(700f, 450f, -2.0)
                        .hover(700f, 520f)
                        .advance(400L));
    }
}
