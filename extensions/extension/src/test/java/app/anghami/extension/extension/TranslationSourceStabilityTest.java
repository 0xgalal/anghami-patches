package app.anghami.extension.extension;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * "Translate now" must not report source_changed when the same lyrics are
 * re-read from cache: only the song and the visible text sequence matter, not
 * timestamps or cache-vs-live provenance.
 */
public class TranslationSourceStabilityTest {

    private LrclibFallback.SongInfo song;

    @Before
    public void setUp() {
        song = new LrclibFallback.SongInfo();
        song.id = "song-translate-1";
        song.title = "Test Song";
        song.artist = "Test Artist";
        LrclibFallback.resetFillStateForTest();
        LrclibFallback.resetWaterfallCountForTest();
    }

    @Test
    public void cacheAdoptedSnapshotWithDifferentTimestampsStillMatches() {
        LrclibFallback.SourceSnapshot live = snapshot("song-translate-1",
                Arrays.asList(line("Hello", 1000), line("World", 2500)));
        // Cache-derived lines often carry different/rounded timestamps (-1 or 0)
        // for the exact same text; that was the false source_changed trigger.
        LrclibFallback.SourceSnapshot cached = snapshot("song-translate-1",
                Arrays.asList(line("Hello", -1), line("World", 0)));

        assertFalse(LrclibFallback.sameSourceLines(cached.lines, live.lines));
        assertTrue(LrclibFallback.sameSourceText(cached.lines, live.lines));
        assertTrue(LrclibFallback.sourceMatchesForResult(cached, live));
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void differentLinesOrDifferentSongStillFailTheGuard() {
        LrclibFallback.SourceSnapshot request = snapshot("song-translate-1",
                Arrays.asList(line("Hello", 1000), line("World", 2500)));

        LrclibFallback.SourceSnapshot differentText = snapshot("song-translate-1",
                Arrays.asList(line("Hello", 1000), line("Different", 2500)));
        assertFalse(LrclibFallback.sourceMatchesForResult(differentText, request));

        LrclibFallback.SourceSnapshot differentCount = snapshot("song-translate-1",
                Arrays.asList(line("Hello", 1000)));
        assertFalse(LrclibFallback.sourceMatchesForResult(differentCount, request));

        LrclibFallback.SourceSnapshot differentSong = snapshot("another-song",
                Arrays.asList(line("Hello", 1000), line("World", 2500)));
        assertFalse(LrclibFallback.sourceMatchesForResult(differentSong, request));

        assertFalse(LrclibFallback.sourceMatchesForResult(null, request));
    }

    @Test
    public void adoptDuringInFlightRequestDoesNotAlterItsSnapshot() throws Exception {
        LrclibFallback.SourceSnapshot inFlight = snapshot(song.id,
                Arrays.asList(line("Hello", 1000), line("World", 2500)));
        @SuppressWarnings("unchecked")
        Map<String, LrclibFallback.SourceSnapshot> inFlightMap =
                (Map<String, LrclibFallback.SourceSnapshot>)
                        fieldOf(LrclibFallback.class, "inFlightSnapshots").get(null);
        inFlightMap.put(song.id, inFlight);
        try {
            assertSame(inFlight, LrclibFallback.inFlightSnapshot(song.id));

            Object callback = new Object();
            fieldOf(LrclibFallback.class, "translationSongId").set(null, "sentinel");
            // This is what the auto-fill path calls when it renders lyrics.
            boolean seeded = LrclibFallback.seedTranslationForResult(
                    song, song, callback, "New text", "[00:09.00]New text\n");
            assertFalse("in-flight source must not be replaced", seeded);
            assertEquals("sentinel",
                    fieldOf(LrclibFallback.class, "translationSongId").get(null));
            @SuppressWarnings("unchecked")
            List<LrclibFallback.Line> current = (List<LrclibFallback.Line>)
                    fieldOf(LrclibFallback.class, "translationSourceLines").get(null);
            assertTrue(current.isEmpty());
            // The request still sees its own snapshot, so the result guard passes.
            assertTrue(LrclibFallback.sourceMatchesForResult(
                    LrclibFallback.inFlightSnapshot(song.id), inFlight));
        } finally {
            inFlightMap.remove(song.id);
        }
    }

    // ---- helpers ----

    private static LrclibFallback.SourceSnapshot snapshot(String songId,
                                                           List<LrclibFallback.Line> lines) {
        LrclibFallback.SourceSnapshot s = new LrclibFallback.SourceSnapshot();
        s.songId = songId;
        s.song = new Object();
        s.callback = new Object();
        s.lines = new ArrayList<LrclibFallback.Line>(lines);
        return s;
    }

    private static LrclibFallback.Line line(String text, int ms) {
        LrclibFallback.Line l = new LrclibFallback.Line();
        l.text = text;
        l.ms = ms;
        return l;
    }

    private static Field fieldOf(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
