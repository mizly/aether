package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.gui.skin.Surface;

import java.util.List;

// a read-only value box, wrapped when it may run over several lines
public final class InfoBox {
    private static final float PAD_X = 10f;
    private static final float PAD_Y = 7f;

    private InfoBox() {
    }

    public static float height(UiContext ui, String value, boolean multiline, float width) {
        if (!multiline) {
            return ui.metrics().controlHeight();
        }
        int lines = ui.skin().wrap(ui.sc(), FontRole.BODY, value, width - PAD_X * 2f).size();
        return Math.max(ui.metrics().controlHeight(), PAD_Y * 2f + lines * ui.skin().lineHeight(ui.sc(), FontRole.BODY));
    }

    public static void render(UiContext ui, Rect r, String value, boolean multiline) {
        ui.skin().surface(ui.sc(), r, Surface.INSET, 0f);
        if (!multiline) {
            ui.skin().textLeft(ui.sc(), FontRole.BODY, value.replace('\n', ' '), r.inset(PAD_X, 0f, PAD_X, 0f),
                    ui.palette().textValue());
            return;
        }
        List<String> lines = ui.skin().wrap(ui.sc(), FontRole.BODY, value, r.w() - PAD_X * 2f);
        float lh = ui.skin().lineHeight(ui.sc(), FontRole.BODY);
        float y = r.y() + PAD_Y;
        for (String line : lines) {
            ui.skin().text(ui.sc(), FontRole.BODY, line, r.x() + PAD_X, y, ui.palette().textValue());
            y += lh;
        }
    }
}
