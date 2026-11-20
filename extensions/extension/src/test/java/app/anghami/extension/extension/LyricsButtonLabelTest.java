package app.anghami.extension.extension;

import org.junit.Test;

import static org.junit.Assert.*;

public class LyricsButtonLabelTest {
    @Test
    public void matchesLocalizedLyricsLabels() {
        assertTrue(LrclibFallback.isLyricsButtonLabel("Lyrics"));
        assertTrue(LrclibFallback.isLyricsButtonLabel("Lyrics + AI"));
        assertTrue(LrclibFallback.isLyricsButtonLabel("Paroles"));
        assertTrue(LrclibFallback.isLyricsButtonLabel("كلمات"));
    }

    @Test
    public void ignoresUnrelatedLabels() {
        assertFalse(LrclibFallback.isLyricsButtonLabel("Play"));
        assertFalse(LrclibFallback.isLyricsButtonLabel(""));
        // The injected one-tap manual-translate control must not inherit the
        // "Lyrics + AI" suffix that is applied to native Lyrics labels.
        assertFalse(LrclibFallback.isLyricsButtonLabel("AI"));
    }
}
