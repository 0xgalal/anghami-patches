package app.anghami.extension.extension;

import org.junit.Test;

import static org.junit.Assert.*;

public class ExtendedLyricsSourcesTest {
    @Test
    public void convertsTtmlClockAndOffsetTimesToLrc() {
        String ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\">"
                + "<body><div>"
                + "<p begin=\"00:00:01.50\"><span>Hello</span> &amp; world</p>"
                + "<p begin=\"2500ms\">Second line</p>"
                + "</div></body></tt>";
        assertEquals("[00:01.50]Hello & world\n[00:02.50]Second line\n",
                LrclibFallback.ttmlToLrc(ttml));
    }

    @Test
    public void ttmlWithoutTimedParagraphsIsEmpty() {
        assertEquals("", LrclibFallback.ttmlToLrc("<tt><body>no lines</body></tt>"));
        assertEquals("", LrclibFallback.ttmlToLrc(null));
    }

    @Test
    public void dialogOffersEverySourceInOrder() {
        assertArrayEquals(new String[]{"lrclib", "kugou", "lyricsplus",
                        "betterlyrics", "paxsenix"},
                LrclibFallback.LYRIC_SOURCE_IDS);
        assertEquals("LRCLIB", LrclibFallback.sourceLabel("lrclib"));
        assertEquals("LyricsPlus", LrclibFallback.sourceLabel("lyricsplus"));
        assertEquals("Better Lyrics", LrclibFallback.sourceLabel("betterlyrics"));
        assertEquals("Paxsenix", LrclibFallback.sourceLabel("paxsenix"));
    }

    @Test
    public void candidateScoringPrefersMatchingMetadata() {
        int exact = LrclibFallback.candidateScore(
                "Bohemian Rhapsody", "Queen", 354, "Bohemian Rhapsody", "Queen", 354);
        int wrongArtist = LrclibFallback.candidateScore(
                "Bohemian Rhapsody", "Queen", 354, "Bohemian Rhapsody", "Someone Else", 354);
        int unrelated = LrclibFallback.candidateScore(
                "Bohemian Rhapsody", "Queen", 354, "Another Song", "Someone Else", 200);
        assertTrue(exact > wrongArtist);
        assertTrue(wrongArtist > 0);
        assertEquals(0, unrelated);
    }
}
