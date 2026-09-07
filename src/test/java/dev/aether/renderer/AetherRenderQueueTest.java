package dev.aether.renderer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AetherRenderQueueTest {
    @AfterEach
    void clear() { AetherRenderQueue.clear(); }

    @Test
    void backgroundAndOverlayQueuesBracketMinecraftContent() {
        List<String> rendered = new ArrayList<>();
        AetherRenderQueue.enqueue(() -> rendered.add("counts"));
        AetherRenderQueue.enqueueBeforeGui(() -> rendered.add("themed panel and slots"));
        AetherRenderQueue.flushBeforeGui();
        rendered.add("original items and player model");
        AetherRenderQueue.flush();
        assertEquals(List.of("themed panel and slots", "original items and player model", "counts"), rendered);
        AetherRenderQueue.flushBeforeGui();
        AetherRenderQueue.flush();
        assertEquals(3, rendered.size());
    }

    @Test
    void shutdownDiscardsBothRenderPasses() {
        List<String> rendered = new ArrayList<>();
        AetherRenderQueue.enqueueBeforeGui(() -> rendered.add("background"));
        AetherRenderQueue.enqueue(() -> rendered.add("overlay"));
        AetherRenderQueue.clear();
        AetherRenderQueue.flushBeforeGui();
        AetherRenderQueue.flush();
        assertEquals(List.of(), rendered);
    }
}
