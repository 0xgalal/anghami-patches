package app.anghami.extension.extension;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class LrclibWaterfallTest {
    @Test
    public void identicalVariantsCollapseToOneQuery() {
        List<String[]> pairs = LrclibFallback.waterfallPairs("Bohemian Rhapsody", "Queen");
        assertEquals(1, pairs.size());
        assertArrayEquals(new String[]{"bohemian rhapsody", "queen"}, pairs.get(0));
    }

    @Test
    public void titleParenthesesAreAlwaysStripped() {
        List<String[]> pairs = LrclibFallback.waterfallPairs("Song (Remix)", "Artist");
        assertEquals(1, pairs.size());
        assertArrayEquals(new String[]{"song", "artist"}, pairs.get(0));
    }

    @Test
    public void allTitleParenthesesAreStripped() {
        List<String[]> pairs = LrclibFallback.waterfallPairs(
                "Song (feat. Guest) (Live at Tokyo) [Official Video]", "Artist");
        assertEquals(1, pairs.size());
        assertArrayEquals(new String[]{"song", "artist"}, pairs.get(0));
    }

    @Test
    public void artistVariantsAddASecondQuery() {
        List<String[]> pairs = LrclibFallback.waterfallPairs("Song", "Artist [Live]");
        assertEquals(2, pairs.size());
        assertArrayEquals(new String[]{"song", "artist"}, pairs.get(0));
        assertArrayEquals(new String[]{"song", "artist live"}, pairs.get(1));
    }

    @Test
    public void missingMetadataProducesNoExactQueries() {
        assertTrue(LrclibFallback.waterfallPairs("Song", "").isEmpty());
        assertTrue(LrclibFallback.waterfallPairs("", "Artist").isEmpty());
    }

    @Test
    public void restoredOptionsDialogLabelsCandidates() throws Exception {
        JSONObject candidate = new JSONObject()
                .put("trackName", "Song").put("artistName", "Artist").put("duration", 185.0);
        assertEquals("Song — Artist (3:05)", LrclibFallback.candidateLabel(candidate));
    }
}
