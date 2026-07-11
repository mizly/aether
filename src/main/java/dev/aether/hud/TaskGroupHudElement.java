package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.List;

public class TaskGroupHudElement extends HudElement {

    public enum Group {
        INTERMEDIARIES,
        MID_FARMING_TASKS,
        FAILSAFES
    }

    private static final float W = 300f;
    private static final float PAD_H = 10f;
    private static final float PAD_V = 8f;
    private static final float TITLE_SZ = 12f;
    private static final float LABEL_SZ = 10f;
    private static final float DETAIL_SZ = 9f;
    private static final float ROW_H = 28f;
    private static final float DETAIL_LINE_H = 10f;
    private static final float CORNER = 6f;

    private final Group group;

    public TaskGroupHudElement(Group group) {
        this.group = group;
    }

    @Override
    public float getX() {
        float configured = switch (group) {
            case INTERMEDIARIES -> AetherConfig.INTERMEDIARIES_HUD_X.get();
            case MID_FARMING_TASKS -> AetherConfig.MID_FARMING_HUD_X.get();
            case FAILSAFES -> AetherConfig.FAILSAFES_HUD_X.get();
        };
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return configured;
        }

        float maxX = Math.max(0f, mc.getWindow().getGuiScaledWidth() - getWidth() * getScale());
        float fallback = group == Group.INTERMEDIARIES ? 10f : Math.max(10f, maxX - 10f);
        if (configured < 0f || configured > maxX) {
            return fallback;
        }
        return Math.max(0f, Math.min(maxX, configured));
    }

    @Override
    public float getY() {
        float configured = switch (group) {
            case INTERMEDIARIES -> AetherConfig.INTERMEDIARIES_HUD_Y.get();
            case MID_FARMING_TASKS -> AetherConfig.MID_FARMING_HUD_Y.get();
            case FAILSAFES -> AetherConfig.FAILSAFES_HUD_Y.get();
        };
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return configured;
        }

        float maxY = Math.max(0f, mc.getWindow().getGuiScaledHeight() - getHeight() * getScale());
        float fallback = Math.min(150f, maxY);
        if (configured < 0f || configured > maxY) {
            return fallback;
        }
        return Math.max(0f, Math.min(maxY, configured));
    }

    @Override
    public void setX(float x) {
        if (group == Group.INTERMEDIARIES) {
            AetherConfig.INTERMEDIARIES_HUD_X.set((int) x);
        } else if (group == Group.MID_FARMING_TASKS) {
            AetherConfig.MID_FARMING_HUD_X.set((int) x);
        } else {
            AetherConfig.FAILSAFES_HUD_X.set((int) x);
        }
    }

    @Override
    public void setY(float y) {
        if (group == Group.INTERMEDIARIES) {
            AetherConfig.INTERMEDIARIES_HUD_Y.set((int) y);
        } else if (group == Group.MID_FARMING_TASKS) {
            AetherConfig.MID_FARMING_HUD_Y.set((int) y);
        } else {
            AetherConfig.FAILSAFES_HUD_Y.set((int) y);
        }
    }

    @Override
    public float getScale() {
        return switch (group) {
            case INTERMEDIARIES -> AetherConfig.INTERMEDIARIES_HUD_SCALE.get();
            case MID_FARMING_TASKS -> AetherConfig.MID_FARMING_HUD_SCALE.get();
            case FAILSAFES -> AetherConfig.FAILSAFES_HUD_SCALE.get();
        };
    }

    @Override
    public void setScale(float s) {
        if (group == Group.INTERMEDIARIES) {
            AetherConfig.INTERMEDIARIES_HUD_SCALE.set(s);
        } else if (group == Group.MID_FARMING_TASKS) {
            AetherConfig.MID_FARMING_HUD_SCALE.set(s);
        } else {
            AetherConfig.FAILSAFES_HUD_SCALE.set(s);
        }
    }

    @Override
    public float getWidth() {
        return W;
    }

    @Override
    public float getHeight() {
        return computeHeight(getRows(Minecraft.getInstance()));
    }

    @Override
    public boolean isEnabled() {
        return switch (group) {
            case INTERMEDIARIES -> AetherConfig.SHOW_INTERMEDIARIES_HUD.get();
            case MID_FARMING_TASKS -> AetherConfig.SHOW_MID_FARMING_HUD.get();
            case FAILSAFES -> AetherConfig.SHOW_FAILSAFES_HUD.get();
        };
    }

    @Override
    public boolean isVisible() {
        boolean allowedArea = ClientUtils.isSupportedHudArea() || AetherConfig.SHOW_HUD_OUTSIDE_GARDEN.get();
        return allowedArea && isEnabled();
    }

    @Override
    public String getName() {
        return switch (group) {
            case INTERMEDIARIES -> "Intermediaries HUD";
            case MID_FARMING_TASKS -> "Mid-Farming HUD";
            case FAILSAFES -> "Failsafes HUD";
        };
    }

    @Override
    public void savePosition() {
        AetherConfig.save();
    }

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        List<TaskHudStatusProvider.TaskStatusRow> rows = getRows(Minecraft.getInstance());
        float ph = computeHeight(rows);
        boolean mod = AetherConfig.HUD_THEME.get() == 1;
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        int border = isDragging() ? BORDER_DRAG : isResizing() ? BORDER_RESIZE : Theme.HUD_BORDER;

        if (sleek) {
            nvg.roundedRect(0, 0, W, ph, CORNER, Theme.withAlpha(Theme.HUD_BG, 0xCC));
            nvg.rectOutline(0, 0, W, ph, CORNER, 1f, Theme.HUD_BORDER);
        } else if (mod) {
            if (editMode) {
                nvg.rect(-1, -1, W + 2f, ph + 2f, border);
            }
            nvg.rect(0, 0, W, ph, Theme.HUD_BG);
            nvg.rect(0, 0, 3f, ph, Theme.HUD_ACCENT);
        } else {
            if (editMode) {
                nvg.roundedRect(-1, -1, W + 2f, ph + 2f, CORNER + 1f, border);
            }
            nvg.shadow(0, 0, W, ph, CORNER, 12f, Theme.withAlpha(0xFF000000, 0.5f));
            nvg.roundedRect(0, 0, W, ph, CORNER, Theme.HUD_BG);
        }

        String title = switch (group) {
            case INTERMEDIARIES -> "Intermediaries";
            case MID_FARMING_TASKS -> "Mid-Farming Tasks";
            case FAILSAFES -> "Failsafes";
        };
        float titleX = (mod || sleek) ? PAD_H + 5f : (W - nvg.textWidth(Fonts.BOLD, title, TITLE_SZ)) / 2f;
        nvg.text(Fonts.BOLD, title, titleX, PAD_V, TITLE_SZ, Theme.HUD_TITLE);

        float y = PAD_V + TITLE_SZ + 4f;
        if (!sleek) {
            nvg.rect(PAD_H, y, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
            y += 8f;
        } else {
            y += 4f;
        }

        for (int i = 0; i < rows.size(); i++) {
            TaskHudStatusProvider.TaskStatusRow row = rows.get(i);
            float rowTop = y;
            float baseline = rowTop;
            nvg.circle(PAD_H + 4f, baseline + 5f, 3.5f, row.color);
            nvg.text(Fonts.REGULAR, row.name, PAD_H + 13f, baseline, LABEL_SZ, Theme.HUD_VALUE);
            nvg.textRight(Fonts.BOLD, row.badge, PAD_H, baseline, W - PAD_H * 2f, LABEL_SZ, row.color);
            String[] detailLines = row.detailLines;
            for (int lineIndex = 0; lineIndex < detailLines.length; lineIndex++) {
                nvg.text(Fonts.REGULAR, detailLines[lineIndex], PAD_H + 13f,
                        baseline + 11f + lineIndex * DETAIL_LINE_H, DETAIL_SZ, Theme.HUD_LABEL);
            }

            float rowHeight = computeRowHeight(row);

            if (i < rows.size() - 1) {
                nvg.rect(PAD_H, rowTop + rowHeight - 5f, W - PAD_H * 2f, 1f, Theme.withAlpha(Theme.HUD_SEP, 90));
            }
            y += rowHeight;
        }

        if (editMode) {
            float hintY = y + 2f;
            String hint = isDragging() ? "moving..."
                    : isResizing() ? "resizing..."
                    : "drag | ctrl+drag to resize";
            nvg.textCentered(Fonts.REGULAR, hint, 0, hintY, W, 12f, 9f, Theme.HUD_LABEL);
        }
    }

    private List<TaskHudStatusProvider.TaskStatusRow> getRows(Minecraft client) {
        return TaskHudStatusProvider.getRows(group, client);
    }

    private float computeHeight(List<TaskHudStatusProvider.TaskStatusRow> rows) {
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        float height = PAD_V + TITLE_SZ + 4f;
        height += sleek ? 4f : 9f;
        for (TaskHudStatusProvider.TaskStatusRow row : rows) {
            height += computeRowHeight(row);
        }
        return height + PAD_V;
    }

    private float computeRowHeight(TaskHudStatusProvider.TaskStatusRow row) {
        int lineCount = Math.max(1, row.detailLines.length);
        return ROW_H + (lineCount - 1) * DETAIL_LINE_H;
    }
}
