package dev.aether.ui.settings;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingDescriptionCatalogTest {
    @Test
    void everyTargetSwitchingSettingHasItsOwnDescription() {
        for (String name : List.of(
                "Human Target Switch",
                "Pest Reaction Time",
                "Pest Overshoot Chance",
                "Pest Overshoot Min Turn",
                "Pest Overshoot Amount",
                "Next Pest Turn Speed")) {
            String description = describe(name);

            assertFalse(description.isBlank(), name);
            assertNotEquals("Turns " + name + " on or off.", description, name);
        }
    }

    @Test
    void theNextPestTurnSpeedIsTheTopOfEachTurn() {
        assertTrue(describe("Next Pest Turn Speed").contains("70%"));
    }

    private static String describe(String name) {
        return SettingDescriptionCatalog.describe(new ToggleSetting(name, () -> true, value -> {
        }));
    }
}
