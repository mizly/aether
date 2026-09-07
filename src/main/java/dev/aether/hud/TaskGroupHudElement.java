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
    private static final float PAD_V = 8f;
    private static final float LABEL_SZ = 10f;
    private static final float DETAIL_SZ = 9f;
    private static final float ROW_H = 36f;
    private static final float DETAIL_LINE_H = 10f;

    // -- Legacy (pre-Ryn) layout ----------------------------------------------
    private static final float L_PAD_H     = 10f;
    private static final float L_PAD_V     = 8f;
    private static final float L_TITLE_SZ  = 12f;
    private static final float L_LABEL_SZ  = 10f;
    private static final float L_DETAIL_SZ = 9f;
    private static final float L_ROW_H     = 28f;
    private static final float L_CORNER    = 6f;

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
        if (AetherConfig.HUD_PANEL_FROSTED.get()) {
            renderRowsLegacy(nvg, getRows(Minecraft.getInstance()), editMode);
            return;
        }
        renderRows(nvg, getRows(Minecraft.getInstance()));
    }

    @Override
    public float getHeight() {
        if (AetherConfig.HUD_PANEL_FROSTED.get()) {
            return computeHeightLegacy(getRows(Minecraft.getInstance()));
        }
        return computeHeight(getRows(Minecraft.getInstance()));
    }

    // Pre-Ryn rendering: three theme variants, no accent stripe.
    private void renderRowsLegacy(NVGRenderer nvg, List<TaskHudStatusProvider.TaskStatusRow> rows, boolean editMode) {
        float ph = computeHeightLegacy(rows);
        boolean mod = AetherConfig.HUD_THEME.get() == 1;
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        int border = isDragging() ? Theme.HUD_ACCENT : isResizing() ? Theme.HUD_WARNING : Theme.HUD_BORDER;

        if (sleek) {
            nvg.roundedRect(0, 0, W, ph, L_CORNER, Theme.withAlpha(Theme.HUD_BG, 0xCC));
            nvg.rectOutline(0, 0, W, ph, L_CORNER, 1f, Theme.HUD_BORDER);
        } else if (mod) {
            if (editMode) nvg.rect(-1, -1, W + 2f, ph + 2f, border);
            nvg.rect(0, 0, W, ph, Theme.HUD_BG);
            nvg.rect(0, 0, 3f, ph, Theme.HUD_ACCENT);
        } else {
            if (editMode) nvg.roundedRect(-1, -1, W + 2f, ph + 2f, L_CORNER + 1f, border);
            nvg.shadow(0, 0, W, ph, L_CORNER, 12f, Theme.withAlpha(0xFF000000, 0.5f));
            nvg.roundedRect(0, 0, W, ph, L_CORNER, Theme.HUD_BG);
        }

        String title = switch (group) {
            case INTERMEDIARIES -> "Intermediaries";
            case MID_FARMING_TASKS -> "Mid-Farming Tasks";
            case FAILSAFES -> "Failsafes";
        };
        float titleX = (mod || sleek) ? L_PAD_H + 5f : (W - nvg.textWidth(Fonts.BOLD, title, L_TITLE_SZ)) / 2f;
        nvg.text(Fonts.BOLD, title, titleX, L_PAD_V, L_TITLE_SZ, Theme.HUD_TITLE);

        float y = L_PAD_V + L_TITLE_SZ + 4f;
        if (!sleek) {
            nvg.rect(L_PAD_H, y, W - L_PAD_H * 2f, 1f, Theme.HUD_SEP);
            y += 8f;
        } else {
            y += 4f;
        }

        for (int i = 0; i < rows.size(); i++) {
            TaskHudStatusProvider.TaskStatusRow row = rows.get(i);
            int color = row.color();
            float rowTop = y;
            nvg.circle(L_PAD_H + 4f, rowTop + 5f, 3.5f, color);
            nvg.text(Fonts.REGULAR, row.name, L_PAD_H + 13f, rowTop, L_LABEL_SZ, Theme.HUD_VALUE);
            nvg.textRight(Fonts.BOLD, row.badge, L_PAD_H, rowTop, W - L_PAD_H * 2f, L_LABEL_SZ, color);
            String[] detailLines = row.detailLines;
            for (int lineIndex = 0; lineIndex < detailLines.length; lineIndex++) {
                nvg.text(Fonts.REGULAR, detailLines[lineIndex], L_PAD_H + 13f,
                        rowTop + 11f + lineIndex * DETAIL_LINE_H, L_DETAIL_SZ, Theme.HUD_LABEL);
            }

            float rowHeight = computeRowHeightLegacy(row);
            if (i < rows.size() - 1) {
                nvg.rect(L_PAD_H, rowTop + rowHeight - 5f, W - L_PAD_H * 2f, 1f, Theme.withAlpha(Theme.HUD_SEP, 90));
            }
            y += rowHeight;
        }

        if (editMode) {
            String hint = isDragging() ? "moving..."
                    : isResizing() ? "resizing..."
                    : "drag | ctrl+drag to resize";
            nvg.textCentered(Fonts.REGULAR, hint, 0, y + 2f, W, 12f, 9f, Theme.HUD_LABEL);
        }
    }

    private float computeHeightLegacy(List<TaskHudStatusProvider.TaskStatusRow> rows) {
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        float height = L_PAD_V + L_TITLE_SZ + 4f;
        height += sleek ? 4f : 9f;
        for (TaskHudStatusProvider.TaskStatusRow row : rows) {
            height += computeRowHeightLegacy(row);
        }
        return height + L_PAD_V;
    }

    private float computeRowHeightLegacy(TaskHudStatusProvider.TaskStatusRow row) {
        int lineCount = Math.max(1, row.detailLines.length);
        return L_ROW_H + (lineCount - 1) * DETAIL_LINE_H;
    }

    void renderRows(NVGRenderer nvg, List<TaskHudStatusProvider.TaskStatusRow> rows) {
        float ph = computeHeight(rows);
        HudStyle.panel(nvg, W, ph);

        String title = switch (group) {
            case INTERMEDIARIES -> "Intermediaries";
            case MID_FARMING_TASKS -> "Mid-Farming Tasks";
            case FAILSAFES -> "Failsafes";
        };
        long enabled = rows.stream().filter(row -> !row.badge.equals("OFF")).count();
        HudStyle.header(nvg, W, title, enabled + "/" + rows.size());
        float y = HudStyle.CONTENT_Y;
        for (TaskHudStatusProvider.TaskStatusRow row : rows) {
            float rowHeight = computeRowHeight(row);
            int color = row.color();
            float badgeWidth = nvg.textWidth(Fonts.BOLD, row.badge, DETAIL_SZ) + 12f;
            float badgeX = W - HudStyle.PAD - badgeWidth;
            nvg.roundedRect(HudStyle.PAD, y - 2f, W - HudStyle.PAD * 2, rowHeight - 5f,
                    5f, HudStyle.alpha(Theme.HUD_BAR_BG, 0.45f));
            HudStyle.text(nvg, Fonts.BOLD, row.name, HudStyle.PAD + 8f, y + 1f,
                    badgeX - HudStyle.PAD - 14f, LABEL_SZ, Theme.HUD_VALUE);
            nvg.roundedRect(badgeX, y - 1f, badgeWidth, 15f, 4f, HudStyle.alpha(color, 0.14f));
            nvg.textCentered(Fonts.BOLD, row.badge, badgeX, y - 1f, badgeWidth, 15f, DETAIL_SZ, color);
            for (int i = 0; i < row.detailLines.length; i++) {
                HudStyle.text(nvg, Fonts.REGULAR, row.detailLines[i], HudStyle.PAD + 8f,
                        y + 16f + i * DETAIL_LINE_H, W - HudStyle.PAD * 2 - 14f, DETAIL_SZ, Theme.HUD_LABEL);
            }
            y += rowHeight;
        }
    }

    private List<TaskHudStatusProvider.TaskStatusRow> getRows(Minecraft client) {
        return TaskHudStatusProvider.getRows(group, client);
    }

    private float computeHeight(List<TaskHudStatusProvider.TaskStatusRow> rows) {
        float height = HudStyle.CONTENT_Y;
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
