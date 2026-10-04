package dev.aether.ui.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuiCanvasTest {
    private static final String FONT = "test";

    private final MonospaceTextMetrics metrics = new MonospaceTextMetrics();
    private GuiCanvas canvas;

    @BeforeEach
    void beginFrame() {
        canvas = new GuiCanvas(metrics);
        canvas.begin(null, 800f, 480f);
    }

    @Test
    void rootStateCoversTheViewport() {
        assertEquals(new Rect(0f, 0f, 800f, 480f), canvas.bounds());
        assertEquals(canvas.bounds(), canvas.rootClip());
        assertEquals(1f, canvas.alpha());
        assertFalse(canvas.drawing());
    }

    @Test
    void translateAndScaleMapLocalRectsToRoot() {
        canvas.translate(100f, 50f);
        canvas.scale(2f);
        canvas.translate(10f, 5f);
        assertEquals(new Rect(120f, 60f, 20f, 10f), canvas.toRoot(new Rect(0f, 0f, 10f, 5f)));
        assertEquals(new Rect(124f, 68f, 6f, 2f), canvas.toRoot(new Rect(2f, 4f, 3f, 1f)));
        assertEquals(2f, canvas.scaleX());
        assertEquals(2f, canvas.toLocalX(124f));
        assertEquals(4f, canvas.toLocalY(68f));
    }

    @Test
    void clipsIntersectInRootSpaceAcrossTransforms() {
        canvas.clip(new Rect(100f, 100f, 200f, 200f));
        canvas.save();
        canvas.translate(150f, 150f);
        canvas.clip(new Rect(0f, 0f, 400f, 20f));
        assertEquals(new Rect(150f, 150f, 150f, 20f), canvas.rootClip());
        assertTrue(canvas.isVisible(new Rect(0f, 0f, 10f, 10f)));
        assertFalse(canvas.isVisible(new Rect(0f, 30f, 10f, 10f)));
        canvas.restore();
        assertEquals(new Rect(100f, 100f, 200f, 200f), canvas.rootClip());
    }

    @Test
    void disjointClipsLeaveNothingVisible() {
        canvas.clip(new Rect(0f, 0f, 10f, 10f));
        canvas.clip(new Rect(20f, 20f, 10f, 10f));
        assertTrue(canvas.rootClip().isEmpty());
        assertFalse(canvas.isVisible(new Rect(0f, 0f, 100f, 100f)));
    }

    @Test
    void alphaMultipliesAndRestores() {
        canvas.alpha(0.5f);
        canvas.save();
        canvas.alpha(0.5f);
        assertEquals(0.25f, canvas.alpha(), 1e-6f);
        canvas.alpha(4f);
        assertEquals(0.25f, canvas.alpha(), 1e-6f);
        canvas.restore();
        assertEquals(0.5f, canvas.alpha(), 1e-6f);
    }

    @Test
    void stateDepthIsCappedBelowNanovgsLimit() {
        for (int i = 0; i < GuiCanvas.MAX_DEPTH; i++) {
            canvas.save();
        }
        assertThrows(IllegalStateException.class, canvas::save);
        for (int i = 0; i < GuiCanvas.MAX_DEPTH; i++) {
            canvas.restore();
        }
        assertThrows(IllegalStateException.class, canvas::restore);
    }

    @Test
    void endingWithOpenSavesFailsLoudly() {
        canvas.save();
        assertThrows(IllegalStateException.class, canvas::end);
        canvas.begin(null, 10f, 10f);
        assertEquals(0, canvas.depth());
        canvas.end();
    }

    @Test
    void scaleMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> canvas.scale(0f));
        assertThrows(IllegalArgumentException.class, () -> canvas.scale(1f, -1f));
    }

    @Test
    void headlessFramesNeedMetrics() {
        assertThrows(IllegalStateException.class, () -> new GuiCanvas().begin(null, 10f, 10f));
    }

    @Test
    void textWidthsAreCachedByFontSizeAndText() {
        assertEquals(30f, canvas.textWidth(FONT, 12f, "hello"));
        assertEquals(30f, canvas.textWidth(FONT, 12f, "hello"));
        assertEquals(1, metrics.widthCalls());
        assertEquals(25f, canvas.textWidth(FONT, 10f, "hello"));
        assertEquals(30f, canvas.textWidth("other", 12f, "hello"));
        assertEquals(3, metrics.widthCalls());
        assertEquals(0f, canvas.textWidth(FONT, 12f, ""));
        assertEquals(15f, canvas.lineHeight(FONT, 12f));
    }

    @Test
    void wrapBreaksOnWordsKeepsNewlinesAndSplitsLongWords() {
        // 10 px text advances 5 px per char, so 50 px holds 10 chars
        assertEquals(List.of("alpha beta", "gamma"), canvas.wrap(FONT, 10f, "alpha beta gamma", 50f));
        assertEquals(List.of("one", "", "two"), canvas.wrap(FONT, 10f, "one\n\ntwo", 50f));
        assertEquals(List.of("abcdefghij", "klmno"), canvas.wrap(FONT, 10f, "abcdefghijklmno", 50f));
        assertEquals(List.of(""), canvas.wrap(FONT, 10f, "", 50f));
        assertEquals(List.of("a", "b"), canvas.wrap(FONT, 10f, "  a  \r\n b ", 50f));
    }

    @Test
    void ellipsizeKeepsWhatFitsWithTheEllipsis() {
        assertEquals("short", canvas.ellipsize(FONT, 10f, "short", 50f));
        assertEquals("a much lo…", canvas.ellipsize(FONT, 10f, "a much longer label", 50f));
        // the space before the cut is dropped rather than left dangling before the ellipsis
        assertEquals("a much…", canvas.ellipsize(FONT, 10f, "a much longer label", 40f));
        assertEquals("…", canvas.ellipsize(FONT, 10f, "anything", 5f));
        assertEquals("", canvas.ellipsize(FONT, 10f, "anything", 2f));
    }
}
