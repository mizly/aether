package dev.aether.hud;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScoreboardDrawListTest {
    @Test
    void largerHeadingsReserveHeightWithoutSeparatingBodyNamesFromScores() {
        var heading = ScoreboardText.prepare(null, Component.literal("Heading").getVisualOrderText(), -1, false, true);
        var list = new ScoreboardDrawList();
        list.fill(0, 0, 100, 37, 0);
        list.text(heading, 40, 1, 20);
        list.text(null, 2, 10, 40);
        list.text(null, 94, 10, 6);
        list.text(null, 2, 19, 40);
        list.text(heading, 2, 28, 40);
        assertEquals(1, list.lineY(1));
        assertEquals(12, list.lineY(10));
        assertEquals(22, list.lineY(19));
        assertEquals(32, list.lineY(28));
        assertEquals(62, list.panelHeight());
    }

    @Test
    void addsOnePixelBetweenRowsWithoutSeparatingNamesFromScores() {
        var list = new ScoreboardDrawList();
        list.fill(0, 0, 100, 37, 0);
        list.text(null, 40, 1, 20);
        for (int y : new int[]{10, 19, 28}) {
            list.text(null, 2, y, 40);
            list.text(null, 94, y, 6);
        }
        assertEquals(1, list.lineY(1));
        assertEquals(11, list.lineY(10));
        assertEquals(21, list.lineY(19));
        assertEquals(31, list.lineY(28));
        assertEquals(60, list.panelHeight());
    }

    @Test
    void longCustomTextExpandsThePanelBounds() {
        var list = new ScoreboardDrawList();
        list.fill(0, 0, 100, 19, 0);
        list.text(null, 40, 1, 20, 200);
        assertEquals(224, list.panelWidth());
    }

    @Test
    void editorBoundsIncludeVanillaHeaderPaddingAndAllFifteenRows() {
        var list = new ScoreboardDrawList();
        list.fill(235, 130, 399, 139, 0x66000000);
        list.fill(235, 139, 399, 275, 0x4C000000);
        assertFalse(list.isEmpty());
        assertEquals(235, list.left());
        assertEquals(130, list.top());
        assertEquals(164, list.width());
        assertEquals(145, list.height());
        assertEquals(184, list.panelWidth());
        assertEquals(165, list.panelHeight());
    }

    @Test
    void transparentAndReversedFillsStillContributeToEditorBounds() {
        var list = new ScoreboardDrawList();
        list.fill(399, 139, 235, 130, 0);
        list.fill(399, 140, 235, 139, 0);
        assertFalse(list.isEmpty());
        assertEquals(164, list.width());
        assertEquals(10, list.height());
    }

    @Test
    void absentSidebarHasNoStaleBounds() {
        var list = new ScoreboardDrawList();
        assertTrue(list.isEmpty());
        assertEquals(0, list.left());
        assertEquals(0, list.top());
        assertEquals(0, list.width());
        assertEquals(0, list.height());
    }
}
