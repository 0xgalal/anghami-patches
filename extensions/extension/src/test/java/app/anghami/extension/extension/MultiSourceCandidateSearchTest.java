package app.anghami.extension.extension;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The picker's "Search lyrics" button must query every selected source in the
 * saved order, skip failures, and never touch deselected sources.
 */
public class MultiSourceCandidateSearchTest {

    private Map<String, String> values;
    private SharedPreferences prefs;
    private LrclibFallback.SongInfo song;
    private FakeSearch search;

    @Before
    public void setUp() {
        values = new HashMap<String, String>();
        prefs = prefs(values);
        song = new LrclibFallback.SongInfo();
        song.id = "song-picker-1";
        song.title = "Test Song";
        song.artist = "Test Artist";
        song.duration = 200f;
        song.hasDuration = true;
        search = new FakeSearch();
        LrclibFallback.resetCandidateQueryLogForTest();
    }

    @Test
    public void onlyLrclibSelectedKeepsPreviousBehaviour() throws Exception {
        LrclibFallback.saveSelectedSources(prefs, new boolean[]{true, false, false});
        JSONObject first = candidate(11, "Test Song", "Test Artist", 200);
        JSONObject second = candidate(12, "Other Song", "Other Artist", 180);
        search.lrclibRows.add(first);
        search.lrclibRows.add(second);

        List<LrclibFallback.Candidate> found =
                LrclibFallback.collectCandidates(song, prefs, search);

        assertEquals(Arrays.asList("lrclib"), LrclibFallback.candidateQueriesForTest());
        assertEquals(2, found.size());
        assertEquals(11L, found.get(0).lrclibId);
        assertEquals(12L, found.get(1).lrclibId);
        assertTrue(found.get(0).label.contains(LrclibFallback.candidateLabel(first)));
        assertTrue(found.get(0).label.contains("LRCLIB"));
        assertNull(found.get(0).result); // LRCLIB rows still re-fetch by id.
    }

    @Test
    public void multipleSourcesAreCollectedInPreferenceOrder() throws Exception {
        LrclibFallback.saveSelectedSources(prefs, new boolean[]{true, true, true});
        search.lrclibRows.add(candidate(21, "Test Song", "Test Artist", 200));
        search.bySource.put("lyricsplus", result("LyricsPlus lyrics", "lyricsplus"));
        search.bySource.put("betterlyrics", result("Better lyrics", "betterlyrics"));

        List<LrclibFallback.Candidate> found =
                LrclibFallback.collectCandidates(song, prefs, search);

        assertEquals(Arrays.asList("lrclib", "lyricsplus", "betterlyrics"),
                LrclibFallback.candidateQueriesForTest());
        assertEquals(3, found.size());
        assertEquals("lrclib", found.get(0).source);
        assertEquals("lyricsplus", found.get(1).source);
        assertEquals("betterlyrics", found.get(2).source);
        assertTrue(found.get(1).label.contains("LyricsPlus"));
        assertTrue(found.get(2).label.contains("Better Lyrics"));
        assertNotNull(found.get(1).result); // already-fetched lyrics, no second call.
        assertEquals("LyricsPlus lyrics", found.get(1).result.plain);
    }

    @Test
    public void failingSourceIsSkippedWithoutAbortingTheRest() throws Exception {
        LrclibFallback.saveSelectedSources(prefs, new boolean[]{true, true, true});
        search.lrclibRows.add(candidate(31, "Test Song", "Test Artist", 200));
        // lyricsplus is unreachable/null; betterlyrics still succeeds.
        search.bySource.put("betterlyrics", result("Better lyrics", "betterlyrics"));

        List<LrclibFallback.Candidate> found =
                LrclibFallback.collectCandidates(song, prefs, search);

        assertEquals(Arrays.asList("lrclib", "lyricsplus", "betterlyrics"),
                LrclibFallback.candidateQueriesForTest());
        assertEquals(2, found.size());
        assertEquals("lrclib", found.get(0).source);
        assertEquals("betterlyrics", found.get(1).source);
    }

    @Test
    public void deselectedSourcesAreNeverQueried() {
        LrclibFallback.saveSelectedSources(prefs, new boolean[]{false, false, true});
        search.bySource.put("betterlyrics", result("Better lyrics", "betterlyrics"));

        List<LrclibFallback.Candidate> found =
                LrclibFallback.collectCandidates(song, prefs, search);

        assertEquals(Arrays.asList("betterlyrics"), LrclibFallback.candidateQueriesForTest());
        assertEquals(1, found.size());
        assertEquals("betterlyrics", found.get(0).source);
        assertEquals(0, search.lrclibCalls);
    }

    @Test
    public void newButtonKeyDoesNotBreakTheOldOne() {
        assertEquals("Search lyrics", LrclibFallback.tr("search_all_sources"));
        assertEquals("Search LRCLIB", LrclibFallback.tr("search"));
    }

    // ---- helpers ----

    private static JSONObject candidate(long id, String track, String artist, double duration)
            throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("trackName", track)
                .put("artistName", artist)
                .put("duration", duration);
    }

    private static LrclibFallback.Result result(String plain, String source) {
        return LrclibFallback.resultForTest(plain, null, source + ":test", source);
    }

    private static final class FakeSearch implements LrclibFallback.CandidateSearch {
        final List<JSONObject> lrclibRows = new ArrayList<JSONObject>();
        final Map<String, LrclibFallback.Result> bySource =
                new HashMap<String, LrclibFallback.Result>();
        int lrclibCalls;

        @Override
        public List<JSONObject> lrclibCandidates(LrclibFallback.SongInfo song) {
            lrclibCalls++;
            return lrclibRows;
        }

        @Override
        public LrclibFallback.Result sourceResult(LrclibFallback.SongInfo song,
                                                  String source) {
            return bySource.get(source);
        }
    }

    private static SharedPreferences prefs(final Map<String, String> state) {
        return (SharedPreferences) Proxy.newProxyInstance(
                MultiSourceCandidateSearchTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class, SharedPreferences.Editor.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getString".equals(name)) {
                            String value = state.get((String) args[0]);
                            return value != null ? value : (args.length > 1 ? args[1] : null);
                        }
                        if ("putString".equals(name)) {
                            state.put((String) args[0], (String) args[1]);
                            return proxy;
                        }
                        if ("getAll".equals(name)) return state;
                        if ("edit".equals(name) || "remove".equals(name)
                                || "clear".equals(name)) return proxy;
                        if ("apply".equals(name)) return null;
                        if ("commit".equals(name)) return Boolean.TRUE;
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("toString".equals(name)) return "fake lyrics prefs";
                        return null;
                    }
                });
    }
}
