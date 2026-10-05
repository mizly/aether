package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.BoundKey;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.KeyTarget;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.ButtonKind;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.gui.skin.Glyph;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.util.AetherLang;

// a key binding: pressing it starts InputCapture (whose rules decide what binds, clears or cancels), the
// result goes through the host so options are saved there. capture gives up after ten quiet seconds
public final class KeybindButton {
    public static final long TIMEOUT_MS = 10_000L;

    private KeybindButton() {
    }

    public static KeyTarget target(ControlHost host, KeybindSetting setting) {
        return new KeyTarget() {
            @Override
            public void bind(BoundKey key) {
                host.bindKey(setting, key);
            }

            @Override
            public void clear() {
                host.clearKey(setting);
            }
        };
    }

    public static void begin(UiContext ui, Object id, KeybindSetting setting) {
        ui.editors().commit();
        ui.capture().begin(id, target(ui.host(), setting));
        ui.memory().put(Part.of(id, "since"), ui.nowMs());
    }

    // cancels a capture that has waited too long; true while this binding is still capturing
    public static boolean capturing(UiContext ui, Object id) {
        if (!ui.capture().capturing(id)) {
            return false;
        }
        Long since = ui.memory().peek(Part.of(id, "since"));
        if (since != null && ui.nowMs() - since > TIMEOUT_MS) {
            ui.capture().cancel();
            return false;
        }
        return true;
    }

    public static float resetWidth(UiContext ui) {
        return Button.width(ui, AetherLang.localize("Reset"), null);
    }

    // reset may be null to leave the reset button out
    public static void render(UiContext ui, Object id, Rect bind, Rect reset, KeybindSetting setting, boolean enabled) {
        Palette p = ui.palette();
        boolean listening = enabled && capturing(ui, id);
        float hoverT = enabled ? ControlSupport.hover(ui, id) : 0f;
        ui.skin().field(ui.sc(), bind, listening, hoverT, false);
        if (listening) {
            float pulse = 0.65f + 0.35f * (float) Math.abs(Math.sin(ui.nowMs() / 420.0));
            ui.skin().textCentered(ui.sc(), FontRole.BODY, AetherLang.localize("Press a key…"), bind.inset(8f, 0f, 8f, 0f),
                    Argb.multiplyAlpha(p.accent(), pulse));
        } else {
            boolean conflict = ui.host().keyConflicts(setting);
            String name = ui.host().keyName(setting);
            Rect text = bind.inset(8f, 0f, conflict ? 22f : 8f, 0f);
            ui.skin().textCentered(ui.sc(), FontRole.BODY, name, text, conflict ? p.warning() : p.textValue());
            if (conflict) {
                ui.skin().glyph(ui.sc(), Glyph.WARNING, bind.right() - 13f, bind.centerY(), 13f, p.warning());
                ui.help().offer(ui.sc(), Part.of(id, "conflict"), bind, name,
                        AetherLang.localize("Another binding uses this key too."));
            }
        }
        if (enabled) {
            ControlSupport.region(ui, id, bind, HitHandler.click(() -> begin(ui, id, setting)), Cursor.HAND);
            ControlSupport.focusable(ui, id, bind, () -> begin(ui, id, setting));
            ControlSupport.ring(ui, id, bind, ui.metrics().fieldRadius());
        }
        if (reset != null) {
            Button.render(ui, Part.of(id, "reset"), reset, AetherLang.localize("Reset"), ButtonKind.GHOST, null,
                    enabled && !ui.host().keyIsDefault(setting), () -> ui.host().resetKey(setting));
        }
    }
}
