package dev.aether.macro.fishing;

import dev.aether.modules.routes.RouteStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FishingMacroKindTest {

    @Test
    void everyKindIsNamedLikeItsRoutesTab() {
        for (FishingMacroKind kind : FishingMacroKind.values()) {
            assertEquals(kind.folder().displayName(), kind.displayName());
        }
    }

    @Test
    void aRoutesFolderLeadsBackToItsKind() {
        for (FishingMacroKind kind : FishingMacroKind.values()) {
            assertEquals(Optional.of(kind), FishingMacroKind.forFolder(kind.folder()));
        }
        assertEquals(Optional.empty(), FishingMacroKind.forFolder(new RouteStore.Folder("other", "Other", java.util.List.of())));
    }
}
