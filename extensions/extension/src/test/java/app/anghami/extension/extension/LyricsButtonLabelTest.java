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
    }
}
