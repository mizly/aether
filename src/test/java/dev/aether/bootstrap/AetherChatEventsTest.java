package dev.aether.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AetherChatEventsTest {
    @Test
    void recognizesTypedAndGenericShardCatchMessages() {
        assertTrue(AetherChatEvents.isPestCatchMessage("you caught 3x fly shards!"));
        assertTrue(AetherChatEvents.isPestCatchMessage("you caught a fly shard!"));
        assertTrue(AetherChatEvents.isPestCatchMessage(
                "you charmed a pest and captured its shard!"));
    }

    @Test
    void ignoresUnrelatedShardMessages() {
        assertFalse(AetherChatEvents.isPestCatchMessage("you purchased 3x fly shards!"));
        assertFalse(AetherChatEvents.isPestCatchMessage("you caught a case of crop fever!"));
    }
}
