package dev.aether.macro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MacroCatalogTest {
    @Test
    void remembersTheLastStartedEntryForResume() {
        MacroCatalog.recordStarted("strider_fishing");
        assertEquals("Strider Fishing", MacroCatalog.lastStarted().orElseThrow().displayName());
        MacroCatalog.recordStarted("farming");
        assertEquals("farming", MacroCatalog.lastStarted().orElseThrow().id());
        MacroCatalog.recordStarted("no_such_macro");
        assertEquals("farming", MacroCatalog.lastStarted().orElseThrow().id());
    }
}
