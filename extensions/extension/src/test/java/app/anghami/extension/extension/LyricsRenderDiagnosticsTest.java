package app.anghami.extension.extension;

import org.junit.Before;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Regression tests for "next song shows empty lyrics" and "manual pick does
 * not render". All helpers are pure / reflection-only so they run on the JVM
 * without an Android runtime or a network call.
 */
public class LyricsRenderDiagnosticsTest {

    private LrclibFallback.SongInfo song;

    @Before
    public void setUp() throws Exception {
        song = new LrclibFallback.SongInfo();
        song.id = "song-render-1";
        song.title = "Render Test";
        song.artist = "Render Artist";
        LrclibFallback.resetFillStateForTest();
        fieldOf(LrclibFallback.class, "lastPreferSynced").set(null, Boolean.TRUE);
    }

    // ---- 1. dedupe key must carry the current song identity ----

    @Test
    public void renderKeyIsStableForSameSongAndText() {
        String a = LrclibFallback.renderKey(song, "Line one", "Line two");
        String b = LrclibFallback.renderKey(song, "Line one", "Line two");
        assertNotNull(a);
        assertEquals("same song + same text must produce the same key", a, b);
        assertTrue("key must start with the normalized song id", a.startsWith(song.id + "|"));
    }

    @Test
    public void renderKeyDiffersAcrossSongsEvenWithIdenticalText() {
        LrclibFallback.SongInfo other = new LrclibFallback.SongInfo();
        other.id = "song-render-2";
        other.title = song.title;
        other.artist = song.artist;

        String keyA = LrclibFallback.renderKey(song, "Same line", "Same lrc");
        String keyB = LrclibFallback.renderKey(other, "Same line", "Same lrc");
        assertNotNull(keyA);
        assertNotNull(keyB);
        assertNotEquals("different songs must never share a dedupe key", keyA, keyB);
    }

    @Test
    public void renderKeyFallsBackToArtistTitleWhenIdIsMissing() {
        LrclibFallback.SongInfo a = new LrclibFallback.SongInfo();
        a.title = "Alpha";
        a.artist = "Artist One";
        LrclibFallback.SongInfo b = new LrclibFallback.SongInfo();
        b.title = "Beta";
        b.artist = "Artist Two";

        String keyA = LrclibFallback.renderKey(a, "text", null);
        String keyB = LrclibFallback.renderKey(b, "text", null);
        assertNotNull(keyA);
        assertNotNull(keyB);
        assertNotEquals("artist|title must disambiguate songs without an id", keyA, keyB);
    }

    @Test
    public void renderKeyChangesWithText() {
        String keyA = LrclibFallback.renderKey(song, "Line one", null);
        String keyB = LrclibFallback.renderKey(song, "Line two", null);
        assertNotEquals(keyA, keyB);
    }

    // ---- 2. forced render for a manual pick ----

    @Test
    public void manualPickIsDetectedAndForcesPastDedupe() {
        assertTrue(LrclibFallback.isManualPick("picked:lrclib"));
        assertFalse(LrclibFallback.isManualPick("lrclib:test"));
        assertFalse(LrclibFallback.isManualPick(null));

        String key = LrclibFallback.renderKey(song, "Picked text", null);
        // An ordinary identical render is deduped ...
        assertTrue(LrclibFallback.shouldSkipDuplicateRender(false, key, key));
        // ... but a manual pick is forced, both via the explicit flag used by
        // applyPickedLyrics and via auto-detection of the picked: tag.
        boolean forceFlagPassedByPick = true;
        assertFalse(LrclibFallback.shouldSkipDuplicateRender(forceFlagPassedByPick, key, key));
        assertFalse(LrclibFallback.shouldSkipDuplicateRender(
                LrclibFallback.isManualPick("picked:lrclib"), key, key));
        assertFalse(LrclibFallback.shouldSkipDuplicateRender(false, key, "other-key"));
    }

    // ---- 3. callback re-resolution at paint time ----

    @Test
    public void callbackForSongResolvesTheLatestRegisteredCallback() throws Exception {
        Object first = new Object();
        Object second = new Object();
        @SuppressWarnings("unchecked")
        Map<String, WeakReference<Object>> callbacks =
                (Map<String, WeakReference<Object>>)
                        fieldOf(LrclibFallback.class, "lyricsCallbacks").get(null);

        callbacks.put(song.id, new WeakReference<Object>(first));
        assertSame(first, LrclibFallback.callbackForSong(song));

        // A view recreated since the first lookup must win on re-resolution.
        callbacks.put(song.id, new WeakReference<Object>(second));
        assertSame("freshest callback must be returned", second, LrclibFallback.callbackForSong(song));
    }

    @Test
    public void paintCallbackPrefersLiveCallbackOverCapturedFallback() throws Exception {
        Object stale = new Object();
        Object live = new Object();
        @SuppressWarnings("unchecked")
        Map<String, WeakReference<Object>> callbacks =
                (Map<String, WeakReference<Object>>)
                        fieldOf(LrclibFallback.class, "lyricsCallbacks").get(null);
        callbacks.put(song.id, new WeakReference<Object>(live));

        assertSame(live, LrclibFallback.paintCallback(song, stale));

        // With no live callback for the song, the captured fallback is used.
        callbacks.remove(song.id);
        assertSame(stale, LrclibFallback.paintCallback(song, stale));
    }

    // ---- 4. pending re-assert cancellation ----

    @Test
    public void manualPickCancelsPendingReasserts() {
        long capturedAtAutofillSchedule = LrclibFallback.advanceRenderEpoch(song);
        assertTrue(LrclibFallback.isReassertCurrent(song.id, capturedAtAutofillSchedule));

        LrclibFallback.cancelPendingReasserts(song);

        assertFalse("a committed pick must invalidate older re-asserts",
                LrclibFallback.isReassertCurrent(song.id, capturedAtAutofillSchedule));
    }

    @Test
    public void cancellingOneSongDoesNotAffectAnotherSong() {
        LrclibFallback.SongInfo other = new LrclibFallback.SongInfo();
        other.id = "song-render-2";
        other.title = "Other";
        other.artist = "Artist";

        long otherEpoch = LrclibFallback.advanceRenderEpoch(other);
        LrclibFallback.cancelPendingReasserts(song);

        assertTrue("re-asserts for other songs stay valid",
                LrclibFallback.isReassertCurrent(other.id, otherEpoch));
    }

    @Test
    public void lateFillResultIsSkippedAfterManualPick() throws Exception {
        long startEpoch = LrclibFallback.advanceRenderEpoch(song);
        @SuppressWarnings("unchecked")
        Map<String, Long> starts = (Map<String, Long>)
                fieldOf(LrclibFallback.class, "fillSearchStartEpochs").get(null);
        starts.put(song.id, Long.valueOf(startEpoch));

        // While the search was in flight nothing changed: result is fresh.
        assertFalse(LrclibFallback.shouldSkipLateFillResult(song.id));

        // A manual pick commits during the search -> stale result is dropped.
        LrclibFallback.cancelPendingReasserts(song);
        assertTrue(LrclibFallback.shouldSkipLateFillResult(song.id));
    }

    // ---- 5. stale view must not suppress the next-song fill ----

    @Test
    public void staleSongViewStateIsDiscardedSoFillCanProceed() {
        assertEquals(LrclibFallback.LyricsViewState.HAS_LYRICS,
                LrclibFallback.viewStateForSong(
                        LrclibFallback.LyricsViewState.HAS_LYRICS, true));
        assertEquals("a view bound to another song must not count as on-screen lyrics",
                LrclibFallback.LyricsViewState.UNKNOWN,
                LrclibFallback.viewStateForSong(
                        LrclibFallback.LyricsViewState.HAS_LYRICS, false));
        assertEquals(LrclibFallback.LyricsViewState.UNKNOWN,
                LrclibFallback.viewStateForSong(
                        LrclibFallback.LyricsViewState.UNKNOWN, false));
    }

    // ---- helpers ----

    private static Field fieldOf(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
