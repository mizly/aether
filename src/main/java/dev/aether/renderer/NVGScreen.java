package dev.aether.renderer;

import dev.aether.ui.components.Component;
import dev.aether.util.AetherLang;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for Minecraft {@link Screen}s that render their UI using NanoVG.
 *
 * <p>Subclasses override {@link #renderNVG(NVGRenderer)} to draw their content.
 * Components added via {@link #addComponent(Component)} receive input events
 * automatically.</p>
 */
public abstract class NVGScreen extends Screen {

    private final List<Component> components = new ArrayList<>();

    protected NVGScreen(String title) {
        super(net.minecraft.network.chat.Component.literal(AetherLang.localize(title)));
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    protected void init() {
        components.clear();
        initNVG();
    }

    /** Called after init/resize. Add components via {@link #addComponent(Component)}. */
    protected void initNVG() {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        AetherRenderQueue.enqueue(this::renderQueued);
    }

    private void renderQueued() {
        if (net.minecraft.client.Minecraft.getInstance().screen != this) {
            return;
        }
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            renderNVG(nvg);
            for (Component c : components) {
                if (c.isVisible()) c.render(nvg);
            }
        } finally {
            NanoVGManager.endFrame();
        }
    }

    /** Override to draw custom NanoVG content before components. */
    protected void renderNVG(NVGRenderer nvg) {}

    // -- Input - MC 1.21.11 event-object API ----------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled() && c.contains(mx, my)) {
                if (c.mousePressed(mx, my, btn)) return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled()) {
                if (c.mouseReleased(mx, my, btn)) return true;
            }
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled()) {
                if (c.mouseDragged(mx, my, btn, deltaX, deltaY)) return true;
            }
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled() && c.contains(mouseX, mouseY)) {
                if (c.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.key(), scan = input.scancode(), mods = input.modifiers();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled()) {
                if (c.keyPressed(key, scan, mods)) return true;
            }
        }
        // Handle ESC explicitly; never delegate to super to avoid MC focus-navigation NPEs.
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        char ch = (char) input.codepoint();
        int mods = 0;
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled()) {
                if (c.charTyped(ch, mods)) return true;
            }
        }
        return super.charTyped(input);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** Suppress MC's automatic background blur + menu backdrop - we draw our own. */
    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float delta) {}

    /** Disable MC's focus-navigation system - we manage our own component focus. */
    @Override
    public java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
        return java.util.List.of();
    }


    // -- Component management --------------------------------------------------

    protected <T extends Component> T addComponent(T component) {
        components.add(component);
        return component;
    }

    protected void removeComponent(Component component) { components.remove(component); }
    protected List<Component> getComponents() { return components; }
}
