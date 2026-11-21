package app.anghami.extension.extension;

import android.content.Context;
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
 * Decision logic for "Lyrics tab active but nothing on screen": only search
 * when it is safe and useful, render from cache first, and always replace the
 * native spinner with a real status line before any network work starts.
 */
public class ProactiveLyricsFillTest {

    private Map<String, String> values;
    private SharedPreferences prefs;
    private LrclibFallback.SongInfo song;
    private RecordingSink sink;

    @Before
    public void setUp() throws Exception {
        values = new HashMap<String, String>();
        prefs = prefs(values);
        song = new LrclibFallback.SongInfo();
        song.id = "song-fill-1";
        song.title = "Test Song";
        song.artist = "Test Artist";
        song.duration = 200f;
        song.hasDuration = true;
        LrclibFallback.resetWaterfallCountForTest();
        LrclibFallback.resetFillStateForTest();
        LrclibFallback.resetCandidateQueryLogForTest();
        fieldOf(LrclibFallback.class, "lastPreferSynced").set(null, Boolean.TRUE);
        sink = new RecordingSink();
    }

    @Test
    public void lyricsModeActiveNoLyricsPositiveCacheRendersFromCacheWithoutNetwork() throws Exception {
        putPositive("[00:01.00]Hello\n", "Hello");

        LrclibFallback.FillDecision decision = LrclibFallback.decideFill(
                true, false, false, prefs, song, System.currentTimeMillis());
        assertEquals(LrclibFallback.FillAction.FROM_CACHE, decision.action);
        assertNotNull(decision.hit);

        LrclibFallback.commitCachedResult(null, song, new Object(), decision.hit, sink);
        assertEquals(1, sink.renders.size());
        assertEquals("Hello", sink.renders.get(0).plain);
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void lyricsModeInactiveDoesNothing() {
        LrclibFallback.FillDecision decision = LrclibFallback.decideFill(
                false, false, false, prefs, song, System.currentTimeMillis());
        assertEquals(LrclibFallback.FillAction.NONE, decision.action);
        assertEquals("mode", decision.reason);
        assertTrue(sink.renders.isEmpty());
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void lyricsAlreadyRenderedDoesNothing() {
        LrclibFallback.FillDecision decision = LrclibFallback.decideFill(
                true, true, false, prefs, song, System.currentTimeMillis());
        assertEquals(LrclibFallback.FillAction.NONE, decision.action);
        assertEquals("rendered", decision.reason);
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void searchAlreadyInFlightDoesNothing() {
        LrclibFallback.FillDecision decision = LrclibFallback.decideFill(
                true, false, true, prefs, song, System.currentTimeMillis());
        assertEquals(LrclibFallback.FillAction.NONE, decision.action);
        assertEquals("in flight", decision.reason);
    }

    @Test
    public void missWithinCooldownDoesNothing() throws Exception {
        JSONObject miss = new JSONObject();
        miss.put("miss", true);
        miss.put("ts", System.currentTimeMillis());
        values.put(cacheKey(), miss.toString());
        long now = System.currentTimeMillis();
        // Reserve the cooldown slot exactly as a previous search would have.
        assertTrue(LrclibFallback.shouldRetryMiss(cacheKey(), now));

        LrclibFallback.FillDecision decision = LrclibFallback.decideFill(
                true, false, false, prefs, song, now + 1000);
        assertEquals(LrclibFallback.FillAction.NONE, decision.action);
        assertEquals("miss cooldown", decision.reason);
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void searchFoundRendersAndSeedsTranslationSource() throws Exception {
        LrclibFallback.Result result = LrclibFallback.resultForTest(
                "Line A\nLine B", "[00:01.00]Line A\n[00:02.00]Line B",
                "lrclib:test", "lrclib");
        Object callback = new Object();

        LrclibFallback.commitFoundResult(null, song, song, callback, result, sink);

        assertEquals(1, sink.renders.size());
        assertEquals("Line A\nLine B", sink.renders.get(0).plain);
        assertEquals("lrclib:test", sink.renders.get(0).queryUsed);
        assertEquals(song.id, fieldOf(LrclibFallback.class, "translationSongId").get(null));
        @SuppressWarnings("unchecked")
        List<LrclibFallback.Line> lines = (List<LrclibFallback.Line>)
                fieldOf(LrclibFallback.class, "translationSourceLines").get(null);
        assertEquals(2, lines.size());
        assertEquals("Line A", lines.get(0).text);
        assertEquals(1000, lines.get(0).ms);
        assertEquals(0, LrclibFallback.waterfallCountForTest());
    }

    @Test
    public void progressThenSuccessClearsProgress() {
        LrclibFallback.SearchRenderState state = new LrclibFallback.SearchRenderState();
        String first = state.begin(song.id);
        assertNotNull(first);
        assertTrue(state.isProgress(song.id));
        assertNull(state.progress("another-song", "late"));
        assertEquals("Trying source…", state.progress(song.id, "Trying source…"));

        assertEquals("Lyrics now", state.success(song.id, "Lyrics now"));
        assertFalse(state.isProgress(song.id));
        // Nothing may paint a stale progress line after the final result.
        assertNull(state.progress(song.id, "late progress"));
    }

    @Test
    public void progressThenMissClearsProgress() {
        LrclibFallback.SearchRenderState state = new LrclibFallback.SearchRenderState();
        state.begin(song.id);
        assertEquals("No results from the selected sources",
                state.miss(song.id, "No results from the selected sources"));
        assertFalse(state.isProgress(song.id));
        assertNull(state.progress(song.id, "late progress"));
        // A different song's outcome must not finish this search.
        assertNull(state.success("other-song", "other lyrics"));
    }

    // ---- helpers ----

    private void putPositive(String synced, String plain) throws Exception {
        JSONObject j = new JSONObject();
        j.put("ts", System.currentTimeMillis());
        if (plain != null) j.put("plain", plain);
        if (synced != null) j.put("synced", synced);
        j.put("queryUsed", "lrclib:test");
        j.put("source", "lrclib");
        values.put(cacheKey(), j.toString());
    }

    private String cacheKey() throws RuntimeException {
        try {
            String version = (String) fieldOf(LrclibFallback.class, "KEY_VERSION").get(null);
            return song.id + "|" + version;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field fieldOf(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Records render calls instead of touching the Android UI thread. */
    private static final class RecordingSink implements LrclibFallback.LyricsRenderSink {
        final List<Render> renders = new ArrayList<Render>();

        @Override
        public void render(Context ctx, Object songObj, Object callback,
                           String plain, String synced, String queryUsed) {
            renders.add(new Render(plain, synced, queryUsed));
        }
    }

    private static final class Render {
        final String plain;
        final String synced;
        final String queryUsed;

        Render(String plain, String synced, String queryUsed) {
            this.plain = plain;
            this.synced = synced;
            this.queryUsed = queryUsed;
        }
    }

    /** Minimal in-memory SharedPreferences (also acts as its own Editor). */
    private static SharedPreferences prefs(final Map<String, String> state) {
        return (SharedPreferences) Proxy.newProxyInstance(
                ProactiveLyricsFillTest.class.getClassLoader(),
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
