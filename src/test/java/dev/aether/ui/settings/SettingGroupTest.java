package dev.aether.ui.settings;

import dev.aether.ui.gui.Icon;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingGroupTest {
    @Test
    void newMetadataIsOptional() {
        SettingGroup group = SettingGroup.alwaysOn("Pest ESP", "Highlights pests");
        assertNull(group.icon());
        assertNull(group.marker());
        assertNull(group.summary());
        assertNull(group.mirrorOf());
        assertTrue(group.headerActions().isEmpty());
    }

    @Test
    void fluentMetadataStaysOnTheSameGroup() {
        int[] pets = {1};
        SettingGroup group = SettingGroup.of("Pet XP Tracker", "Tracks pets", () -> true, v -> { });
        SettingGroup same = group.icon(Icon.item("bone"))
                .marker("pet-tracker")
                .summary(() -> pets[0] + " pets")
                .mirrorOf("profit")
                .headerAction(new SettingGroup.HeaderAction("Add Pet", Icon.svg("/assets/aether/icons/play.svg"),
                        () -> pets[0]++, null, null))
                .headerAction(new SettingGroup.HeaderAction("Remove Pet", null, () -> pets[0]--,
                        () -> pets[0] > 1, null));
        assertSame(group, same);
        assertEquals(Icon.item("bone"), group.icon());
        assertEquals("pet-tracker", group.marker());
        assertEquals("profit", group.mirrorOf());
        assertEquals("1 pets", group.summary());

        SettingGroup.HeaderAction add = group.headerActions().get(0);
        SettingGroup.HeaderAction remove = group.headerActions().get(1);
        assertFalse(remove.isVisible());
        remove.run();
        assertEquals(1, pets[0]);
        add.run();
        assertEquals("2 pets", group.summary());
        assertTrue(remove.isVisible());
        remove.run();
        assertEquals(1, pets[0]);
        assertEquals("Add Pet", add.rawLabel());
        assertThrows(UnsupportedOperationException.class, () -> group.headerActions().clear());
    }

    @Test
    void disabledHeaderActionsDoNothing() {
        boolean[] ran = {false};
        SettingGroup.HeaderAction action = new SettingGroup.HeaderAction("Remove Rewarp", null, () -> ran[0] = true,
                null, () -> false);
        assertTrue(action.isVisible());
        assertFalse(action.isEnabled());
        action.run();
        assertFalse(ran[0]);
    }
}
