package dev.aether.ui.gui.nav;

import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.GuiFrame;
import dev.aether.ui.gui.GuiStyle;
import dev.aether.ui.gui.GuiView;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.LaunchRequest;
import dev.aether.ui.gui.MonospaceTextMetrics;
import dev.aether.ui.gui.StyleRegistry;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.gui.preview.PreviewGuiHost;
import dev.aether.ui.theme.Theme;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuiViewNavigationTest {
    private static final KeyInput ESCAPE = new KeyInput(GLFW.GLFW_KEY_ESCAPE, 0, 0, false, false, false);

    @BeforeAll
    static void registry() {
        TestConfigDir.ensure();
        MainGUIRegistry.refresh();
    }

    @Test
    void theViewNavigatesThroughGuiActionsAndEscapeClimbsBeforeClosing() {
        GuiFrame[] last = new GuiFrame[1];
        GuiStyle style = new GuiStyle() {
            @Override
            public String id() {
                return "aurora";
            }

            @Override
            public String displayName() {
                return "Aurora";
            }

            @Override
            public void render(GuiFrame frame) {
                last[0] = frame;
            }
        };
        String savedStyle = Theme.GUI_STYLE;
        Theme.GUI_STYLE = "aurora";
        PreviewGuiHost host = new PreviewGuiHost();
        GuiView view = new GuiView(host, () -> 0L, new StyleRegistry(List.of(style)), MainGUIRegistry::snapshot,
                new MonospaceTextMetrics());
        try {
            view.open(new LaunchRequest(null, "Farming Macro", null, null));
            view.render(null, 800f, 480f, 0f, 0f);
            GuiActions actions = last[0].actions();
            assertSame(view.actions(), actions);
            assertSame(actions, last[0].inert().actions());
            assertEquals(NavLocation.page("farming", "farming-macro"), actions.location());

            assertTrue(view.keyPressed(ESCAPE));
            assertEquals(NavLocation.category("farming"), actions.location());
            assertTrue(view.keyPressed(ESCAPE));
            assertEquals(NavLocation.HOME, actions.location());
            assertEquals(0, host.closeRequests());
            assertTrue(view.keyPressed(ESCAPE));
            assertEquals(1, host.closeRequests());

            assertTrue(view.keyPressed(new KeyInput(GLFW.GLFW_KEY_BACKSPACE, 0, 0, false, false, false)));
            assertEquals(NavLocation.category("farming"), actions.location(), "back retraces history");
            view.close();
            view.open(null);
            assertEquals(NavLocation.category("farming"), actions.location(), "reopening restores the place");
        } finally {
            Theme.GUI_STYLE = savedStyle;
        }
    }
}
