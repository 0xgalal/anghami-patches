package app.anghami.extension.extension;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Translation must read already-cached lyrics instead of asking a lyrics site
 * again. These tests exercise the cache-to-SourceSnapshot conversion and the
 * entry point that builds the snapshot for translation.
 */
public class CachedTranslationSourceTest {

    private PrefsState state;
    private SharedPreferences prefs;
    private Song song;

    @Before
    public void setUp() throws Exception {
        state = new PrefsState();
        prefs = prefs(state);
        song = new Song();
        // Deterministic source selection and no stale in-memory source.
        setStatic("lastPreferSynced", Boolean.TRUE);
        clearLiveSource();
        setStatic("lastSong", song);
        setStatic("lastCallback", new Object());
        LrclibFallback.resetWaterfallCountForTest();
    }

    @Test
    public void positiveCacheYieldsSnapshotFromCache() throws Exception {
        putPositive("[00:01.00]Hello\n[00:02.50]World\n", "Hello\nWorld");

        Object snapshot = LrclibFallback.cachedSourceSnapshot(prefs, song);
        assertNotNull(snapshot);
        assertTrue((Boolean) field(snapshot, "synced"));
        List<?> lines = (List<?>) field(snapshot, "lines");
        assertEquals(2, lines.size());
        assertEquals("Hello", text(lines.get(0)));
        assertEquals(1000, ms(lines.get(0)));
        assertEquals("World", text(lines.get(1)));
        assertEquals(2500, ms(lines.get(1)));
        assertEquals(songKey(song), field(snapshot, "songId"));

        // Building the source only read local preferences — no lyrics fetch.
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void translationFromCacheIssuesNoLyricsSiteRequest() throws Exception {
        putPositive("[00:10.00]First\n[00:11.00]Second\n", "First\nSecond");

        Object snapshot = LrclibFallback.translationSourceFor(prefs, song);
        assertNotNull(snapshot);
        List<?> lines = (List<?>) field(snapshot, "lines");
        assertEquals(2, lines.size());
        assertEquals("First", text(lines.get(0)));
        assertEquals("Second", text(lines.get(1)));
        assertEquals(0, LrclibFallback.waterfallCountForTest());

        // The cache-derived source is adopted, so the consistency checks made
        // by translateAndRender / renderOriginalLyricsIfCurrent see the same
        // lines without re-reading the cache or re-fetching anything.
        Object again = LrclibFallback.translationSourceFor(prefs, song);
        assertNotNull(again);
        List<?> againLines = (List<?>) field(again, "lines");
        assertEquals(lines.size(), againLines.size());
        for (int i = 0; i < lines.size(); i++) {
            assertEquals(text(lines.get(i)), text(againLines.get(i)));
            assertEquals(ms(lines.get(i)), ms(againLines.get(i)));
        }
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void cacheMissOrNegativeEntryFallsBackToNoSource() throws Exception {
        assertNull(LrclibFallback.cachedSourceSnapshot(prefs, song));
        assertNull(LrclibFallback.translationSourceFor(prefs, song));

        JSONObject miss = new JSONObject();
        miss.put("miss", true);
        miss.put("ts", System.currentTimeMillis());
        state.values.put(cacheKey(song), miss.toString());
        assertNull(LrclibFallback.cachedSourceSnapshot(prefs, song));
        assertNull(LrclibFallback.translationSourceFor(prefs, song));

        // A positive entry past its TTL is treated like a miss.
        JSONObject stale = new JSONObject();
        stale.put("ts", 0L);
        stale.put("plain", "Old lyrics");
        stale.put("queryUsed", "lrclib:old");
        state.values.put(cacheKey(song), stale.toString());
        assertNull(LrclibFallback.cachedSourceSnapshot(prefs, song));

        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void cachedLrcKeepsTimestampsAndLineCount() throws Exception {
        String lrc = "[ti:Ignore me]\n"
                + "[00:00.50]One\n"
                + "[00:01.25]Two\n"
                + "[00:01.25]Three\n"
                + "[00:03.00]Four\n";
        putPositive(lrc, "One\nTwo\nThree\nFour");

        Object snapshot = LrclibFallback.cachedSourceSnapshot(prefs, song);
        assertNotNull(snapshot);
        List<?> lines = (List<?>) field(snapshot, "lines");
        assertEquals(4, lines.size());
        assertEquals(500, ms(lines.get(0)));
        assertEquals("One", text(lines.get(0)));
        assertEquals(1250, ms(lines.get(1)));
        assertEquals("Two", text(lines.get(1)));
        assertEquals(1250, ms(lines.get(2)));
        assertEquals("Three", text(lines.get(2)));
        assertEquals(3000, ms(lines.get(3)));
        assertEquals("Four", text(lines.get(3)));
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void plainOnlyCacheUsesPlainLinesWithoutTimestamps() throws Exception {
        setStatic("lastPreferSynced", Boolean.TRUE);
        putPositive(null, "Line A\nLine B\nLine C");

        Object snapshot = LrclibFallback.cachedSourceSnapshot(prefs, song);
        assertNotNull(snapshot);
        assertFalse((Boolean) field(snapshot, "synced"));
        List<?> lines = (List<?>) field(snapshot, "lines");
        assertEquals(3, lines.size());
        assertEquals("Line A", text(lines.get(0)));
        assertEquals(-1, ms(lines.get(0)));
        assertEquals("Line C", text(lines.get(2)));
    }

    @Test
    public void liveSourceWinsOverCacheSoNoDoubleTranslation() throws Exception {
        putPositive("[00:01.00]From cache\n", "From cache");

        // A live snapshot (as captured from the rendered lyrics response) is
        // seeded with different text; it must be returned unchanged.
        List<Object> liveLines = new ArrayList<Object>();
        liveLines.add(newLine("From live view", 999));
        setStatic("translationSongId", songKey(song));
        setStatic("translationSong", song);
        setStatic("translationCallback", new Object());
        setStatic("translationSourceSynced", Boolean.TRUE);
        Field sourceLines = fieldOf(LrclibFallback.class, "translationSourceLines");
        @SuppressWarnings("unchecked")
        List<Object> lines = (List<Object>) sourceLines.get(null);
        lines.clear();
        lines.addAll(liveLines);

        Object snapshot = LrclibFallback.translationSourceFor(prefs, song);
        assertNotNull(snapshot);
        List<?> got = (List<?>) field(snapshot, "lines");
        assertEquals(1, got.size());
        assertEquals("From live view", text(got.get(0)));
        assertEquals(999, ms(got.get(0)));
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void liveSourceForAnotherSongBlocksCacheFallback() throws Exception {
        putPositive("[00:01.00]From cache\n", "From cache");

        Song other = new Song();
        other.id = "song-other";
        setStatic("translationSongId", other.id);
        setStatic("translationSong", other);
        setStatic("translationCallback", new Object());
        @SuppressWarnings("unchecked")
        List<Object> lines = (List<Object>) fieldOf(
                LrclibFallback.class, "translationSourceLines").get(null);
        lines.clear();
        lines.add(newLine("Other song", 100));

        assertNull(LrclibFallback.translationSourceFor(prefs, song));
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    // ---- helpers ----

    private void putPositive(String synced, String plain) throws Exception {
        JSONObject j = new JSONObject();
        j.put("ts", System.currentTimeMillis());
        if (plain != null) j.put("plain", plain);
        if (synced != null) j.put("synced", synced);
        j.put("queryUsed", "lrclib:test");
        j.put("source", "lrclib");
        state.values.put(cacheKey(song), j.toString());
    }

    private static String cacheKey(Song song) throws Exception {
        Field f = fieldOf(LrclibFallback.class, "KEY_VERSION");
        return song.id + "|" + f.get(null);
    }

    private static String songKey(Song song) {
        return song.id;
    }

    private static Object newLine(String text, int ms) throws Exception {
        Class<?> lineClass = Class.forName("app.anghami.extension.extension.LrclibFallback$Line");
        Object line = lineClass.getDeclaredConstructor().newInstance();
        fieldOf(lineClass, "text").set(line, text);
        fieldOf(lineClass, "ms").set(line, ms);
        return line;
    }

    private static String text(Object line) throws Exception {
        return (String) fieldOf(line.getClass(), "text").get(line);
    }

    private static int ms(Object line) throws Exception {
        return (Integer) fieldOf(line.getClass(), "ms").get(line);
    }

    private static Object field(Object target, String name) throws Exception {
        return fieldOf(target.getClass(), name).get(target);
    }

    private static Field fieldOf(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void setStatic(String name, Object value) throws Exception {
        fieldOf(LrclibFallback.class, name).set(null, value);
    }

    private void clearLiveSource() throws Exception {
        setStatic("translationSongId", "");
        setStatic("translationCallback", null);
        @SuppressWarnings("unchecked")
        List<Object> lines = (List<Object>) fieldOf(
                LrclibFallback.class, "translationSourceLines").get(null);
        lines.clear();
    }

    private static final class Song {
        String id = "song-cache-test";
        String title = "Test Song";
        String artistName = "Test Artist";
        float duration = 200f;
    }

    private static final class PrefsState {
        final Map<String, String> values = new HashMap<String, String>();
    }

    /** Minimal in-memory SharedPreferences (also acts as its own Editor). */
    private static SharedPreferences prefs(final PrefsState state) {
        return (SharedPreferences) Proxy.newProxyInstance(
                CachedTranslationSourceTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class, SharedPreferences.Editor.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getString".equals(name)) {
                            String value = state.values.get((String) args[0]);
                            return value != null ? value : (args.length > 1 ? args[1] : null);
                        }
                        if ("putString".equals(name)) {
                            state.values.put((String) args[0], (String) args[1]);
                            return proxy;
                        }
                        if ("getAll".equals(name)) return state.values;
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
