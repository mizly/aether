package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.Button;
import dev.aether.ui.gui.control.Part;
import dev.aether.ui.gui.control.UiContext;
import dev.aether.ui.gui.skin.ButtonKind;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.gui.skin.SkinContext;
import dev.aether.ui.gui.skin.Surface;
import org.lwjgl.glfw.GLFW;

import java.util.List;

// a centred yes/no dialog over a dimmed page; enter confirms, escape or a press outside cancels
final class ConfirmOverlay extends Popup {
    private static final float WIDTH = 380f;
    private static final float PAD = 20f;

    private final ConfirmRequest request;
    private UiContext last;
    private boolean decided;

    ConfirmOverlay(ConfirmRequest request) {
        this.request = request;
    }

    @Override
    public boolean modal() {
        return true;
    }

    @Override
    public void render(UiContext ui) {
        last = ui;
        SkinContext sc = ui.sc();
        Rect screen = ui.canvas().bounds();
        float t = openProgress(ui);
        ui.canvas().rect(screen, Argb.multiplyAlpha(ui.palette().scrim(), t));
        float w = Math.min(WIDTH, screen.w() - EDGE * 4f);
        float inner = w - PAD * 2f;
        List<String> title = ui.skin().wrap(sc, FontRole.TITLE, request.title(), inner);
        List<String> message = request.message() == null || request.message().isBlank() ? List.of()
                : ui.skin().wrap(sc, FontRole.BODY, request.message(), inner);
        float titleLh = ui.skin().lineHeight(sc, FontRole.TITLE);
        float bodyLh = ui.skin().lineHeight(sc, FontRole.BODY) + 2f;
        float buttonH = ui.metrics().controlHeight() + 2f;
        float h = PAD + title.size() * titleLh + (message.isEmpty() ? 0f : 8f + message.size() * bodyLh) + 22f
                + buttonH + PAD;
        Rect panel = new Rect(screen.centerX() - w / 2f, screen.centerY() - h / 2f, w, h);
        bounds = panel;
        ui.canvas().save();
        ui.canvas().alpha(t);
        ui.canvas().translate(0f, (1f - t) * 6f);
        ui.skin().surface(sc, panel, Surface.POPOVER, 0f);
        ui.hits().block(panel);
        float y = panel.y() + PAD;
        for (String line : title) {
            ui.skin().text(sc, FontRole.TITLE, line, panel.x() + PAD, y, ui.palette().text());
            y += titleLh;
        }
        if (!message.isEmpty()) {
            y += 8f;
            for (String line : message) {
                ui.skin().text(sc, FontRole.BODY, line, panel.x() + PAD, y, ui.palette().textSecondary());
                y += bodyLh;
            }
        }
        float by = panel.bottom() - PAD - buttonH;
        float confirmW = Math.max(88f, Button.width(ui, request.confirmLabel(), null));
        float cancelW = Math.max(80f, Button.width(ui, request.cancelLabel(), null));
        Rect confirm = new Rect(panel.right() - PAD - confirmW, by, confirmW, buttonH);
        Rect cancel = new Rect(confirm.x() - 8f - cancelW, by, cancelW, buttonH);
        Button.render(ui, Part.of(this, "cancel"), cancel, request.cancelLabel(), ButtonKind.GHOST, null, true,
                this::cancel);
        Button.render(ui, Part.of(this, "confirm"), confirm, request.confirmLabel(),
                request.destructive() ? ButtonKind.DANGER : ButtonKind.PRIMARY, null, true, this::confirm);
        ui.canvas().restore();
    }

    private void confirm() {
        decided = true;
        if (last != null) {
            last.overlays().close(this);
        }
        if (request.onConfirm() != null) {
            request.onConfirm().run();
        }
    }

    private void cancel() {
        if (last != null) {
            last.overlays().close(this);
        }
    }

    @Override
    public boolean key(KeyInput k) {
        if (k.is(GLFW.GLFW_KEY_ENTER) || k.is(GLFW.GLFW_KEY_KP_ENTER)) {
            confirm();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        if (!decided) {
            decided = true;
            if (request.onCancel() != null) {
                request.onCancel().run();
            }
        }
    }
}
