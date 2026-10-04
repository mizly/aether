package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Animator;
import dev.aether.ui.gui.FocusManager;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.GuiClock;
import dev.aether.ui.gui.HitRegions;
import dev.aether.ui.gui.InputCapture;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.overlay.HoverHelp;
import dev.aether.ui.gui.overlay.Overlay;
import dev.aether.ui.gui.overlay.OverlayFactory;
import dev.aether.ui.gui.overlay.OverlayStack;
import dev.aether.ui.gui.skin.GuiSkin;
import dev.aether.ui.gui.skin.ReferenceSkin;
import dev.aether.ui.gui.skin.SkinContext;
import dev.aether.ui.gui.skin.SkinMetrics;

// everything controls, widgets and overlays use in one frame: the skin context, the active skin and
// overlay factory, and the per-view services (overlay stack, editor focus, keybind capture, host, hover
// help, control memory). the view builds one per frame; services live as long as the view
public final class UiContext {
    private final SkinContext sc;
    private final GuiSkin skin;
    private final OverlayFactory factory;
    private final OverlayStack overlays;
    private final EditorFocus editors;
    private final InputCapture capture;
    private final ControlHost host;
    private final HoverHelp help;
    private final ControlMemory memory;
    private final Object layer;

    private UiContext(Builder b, SkinContext sc, Object layer) {
        this.sc = sc;
        this.skin = b.skin;
        this.factory = b.factory;
        this.overlays = b.overlays;
        this.editors = b.editors;
        this.capture = b.capture;
        this.host = b.host;
        this.help = b.help;
        this.memory = b.memory;
        this.layer = layer;
    }

    public static Builder builder(SkinContext sc) {
        return new Builder(sc);
    }

    // the same services with another skin context, e.g. after SkinContext.withPalette for a themed preview
    public UiContext with(SkinContext next) {
        return new UiContext(toBuilder(), next, layer);
    }

    // controls drawn while this context is current belong to the given overlay: their editors commit when it closes
    public UiContext inLayer(Object overlay) {
        return new UiContext(toBuilder(), sc, overlay);
    }

    // draws but registers, animates and opens nothing, for thumbnails
    public UiContext inert() {
        return new UiContext(toBuilder(), sc.inert(), layer);
    }

    public SkinContext sc() {
        return sc;
    }

    public GuiCanvas canvas() {
        return sc.canvas();
    }

    public Palette palette() {
        return sc.palette();
    }

    public Animator anim() {
        return sc.anim();
    }

    public GuiClock clock() {
        return sc.clock();
    }

    public HitRegions hits() {
        return sc.hits();
    }

    public FocusManager focus() {
        return sc.focus();
    }

    public GuiSkin skin() {
        return skin;
    }

    public SkinMetrics metrics() {
        return skin.metrics();
    }

    public OverlayFactory overlayFactory() {
        return factory;
    }

    public OverlayStack overlays() {
        return overlays;
    }

    public EditorFocus editors() {
        return editors;
    }

    public InputCapture capture() {
        return capture;
    }

    public ControlHost host() {
        return host;
    }

    public HoverHelp help() {
        return help;
    }

    public ControlMemory memory() {
        return memory;
    }

    // the overlay this context draws in, or null for the page
    public Object layer() {
        return layer;
    }

    public long nowMs() {
        return sc.nowMs();
    }

    // false for inert contexts: nothing can be pressed, so controls skip work only input needs
    public boolean interactive() {
        return sc.hits() != sc.hits().inert();
    }

    // commits the page's editor (an overlay owns the keyboard from now on) and pushes the layer; null is ignored
    public void open(Overlay overlay) {
        if (overlay == null) {
            return;
        }
        editors.commit();
        capture.cancel();
        overlays.open(overlay);
    }

    // records where an overlay anchor is drawn this frame, in root space; overlays follow it and close when it goes
    public void anchor(Object id, Rect local) {
        overlays.publishAnchor(id, sc.canvas().toRoot(local));
    }

    public Rect rootRect(Rect local) {
        return sc.canvas().toRoot(local);
    }

    // call after the page and overlays drew: overlay layers, hover help, editor bookkeeping
    public void finishFrame() {
        overlays.render(this);
        help.render(this);
        editors.endFrame();
    }

    private Builder toBuilder() {
        Builder b = new Builder(sc);
        b.skin = skin;
        b.factory = factory;
        b.overlays = overlays;
        b.editors = editors;
        b.capture = capture;
        b.host = host;
        b.help = help;
        b.memory = memory;
        return b;
    }

    public static final class Builder {
        private final SkinContext sc;
        private GuiSkin skin = ReferenceSkin.INSTANCE;
        private OverlayFactory factory = OverlayFactory.DEFAULT;
        private OverlayStack overlays;
        private EditorFocus editors;
        private InputCapture capture;
        private ControlHost host;
        private HoverHelp help;
        private ControlMemory memory;

        private Builder(SkinContext sc) {
            this.sc = sc;
        }

        public Builder skin(GuiSkin value) {
            skin = value;
            return this;
        }

        public Builder overlayFactory(OverlayFactory value) {
            factory = value;
            return this;
        }

        public Builder overlays(OverlayStack value) {
            overlays = value;
            return this;
        }

        public Builder editors(EditorFocus value) {
            editors = value;
            return this;
        }

        public Builder capture(InputCapture value) {
            capture = value;
            return this;
        }

        public Builder host(ControlHost value) {
            host = value;
            return this;
        }

        public Builder help(HoverHelp value) {
            help = value;
            return this;
        }

        public Builder memory(ControlMemory value) {
            memory = value;
            return this;
        }

        // services left unset get fresh private instances, which is what thumbnails and tests want
        public UiContext build() {
            if (overlays == null) {
                overlays = new OverlayStack();
            }
            if (editors == null) {
                editors = new EditorFocus();
            }
            if (capture == null) {
                capture = new InputCapture();
            }
            if (host == null) {
                host = ControlHost.detached();
            }
            if (help == null) {
                help = new HoverHelp();
            }
            if (memory == null) {
                memory = new ControlMemory();
            }
            return new UiContext(this, sc, null);
        }
    }
}
