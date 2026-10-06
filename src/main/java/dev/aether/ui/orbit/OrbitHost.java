package dev.aether.ui.orbit;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.config.ConfigProfileManager;
import dev.aether.macro.MacroCatalog;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.renderer.SkinFaceProvider;
import dev.aether.telemetry.AetherAuthService;
import dev.aether.telemetry.AetherAuthState;
import dev.aether.ui.MacroStartScreen;
import dev.aether.ui.gui.Clipboard;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.nav.ProfileActions;
import dev.aether.ui.orbit.panel.PanelHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

// the live game behind the orbit panels; everything here reads client state or opens client screens
final class OrbitHost implements PanelHost {
    private final OrbitScreen screen;
    private final boolean stoppedByMenu;

    OrbitHost(OrbitScreen screen, boolean stoppedByMenu) {
        this.screen = screen;
        this.stoppedByMenu = stoppedByMenu;
    }

    @Override
    public Account account() {
        AetherAuthState state = AetherAuthService.getState();
        AuthState auth = switch (state) {
            case AUTHENTICATED -> AuthState.SIGNED_IN;
            case AUTHENTICATING -> AuthState.SIGNING_IN;
            default -> AuthState.SIGNED_OUT;
        };
        Minecraft client = Minecraft.getInstance();
        String name = client.getUser() == null ? "" : client.getUser().getName();
        return new Account(name, auth, AetherAuthService.getTotalSeconds());
    }

    @Override
    public void sound(Sound sound, float pitch) {
        var manager = Minecraft.getInstance().getSoundManager();
        switch (sound) {
            case CLICK -> manager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, pitch));
        }
    }

    @Override
    public boolean streamerMode() {
        return StreamerModeManager.isEnabled();
    }

    @Override
    public Session session() {
        var last = MacroCatalog.lastStarted().orElse(null);
        long now = System.currentTimeMillis();
        long rest = DynamicRestManager.getNextRestTriggerMs();
        String item = last == null ? null : last.id().equals("strider_fishing") ? "fishing_rod" : "diamond_hoe";
        return new Session(last == null ? null : last.displayName(), item, stoppedByMenu,
                MacroStateManager.getSessionRunningTime(), rest > now ? rest - now : -1L, null, -1L);
    }

    @Override
    public List<String> profiles() {
        return ConfigProfileManager.list();
    }

    @Override
    public String activeProfile() {
        String name = ConfigProfileManager.activeName();
        return name == null ? "main" : name;
    }

    @Override
    public void loadProfile(String name) {
        ProfileActions.load(ProfileActions.Kind.CONFIG, name);
    }

    @Override
    public void beginLogin() {
        AetherAuthService.beginLogin();
    }

    @Override
    public void resume() {
        MacroCatalog.lastStarted().ifPresent(MacroCatalog::start);
    }

    @Override
    public void openMacroMenu() {
        Minecraft.getInstance().setScreen(new MacroStartScreen());
    }

    @Override
    public void openHudEditor() {
        Screen editor = AetherBootstrapHooks.maybeCreateHudEditScreen();
        if (editor != null) Minecraft.getInstance().setScreen(editor);
    }

    @Override
    public void close() {
        screen.beginClose();
    }

    @Override
    public void paintHead(GuiCanvas canvas, float x, float y, float size) {
        canvas.legacy(nvg -> SkinFaceProvider.render(nvg, x, y, size, 1f));
    }

    @Override
    public Clipboard clipboard() {
        return new Clipboard() {
            @Override
            public String read() {
                return Minecraft.getInstance().keyboardHandler.getClipboard();
            }

            @Override
            public void write(String text) {
                Minecraft.getInstance().keyboardHandler.setClipboard(text);
            }
        };
    }
}
