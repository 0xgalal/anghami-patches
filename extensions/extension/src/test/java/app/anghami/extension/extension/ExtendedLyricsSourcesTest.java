package app.anghami.extension.extension;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

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
        assertArrayEquals(new String[]{"lrclib", "lyricsplus", "betterlyrics"},
                LrclibFallback.LYRIC_SOURCE_IDS);
        assertEquals("LRCLIB", LrclibFallback.sourceLabel("lrclib"));
        assertEquals("LyricsPlus", LrclibFallback.sourceLabel("lyricsplus"));
        assertEquals("Better Lyrics", LrclibFallback.sourceLabel("betterlyrics"));
        assertEquals("kugou", LrclibFallback.sourceLabel("kugou")); // removed id: no label
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

    @Test
    public void removedSourceIdsAreIgnoredAndNeverRepopulated() {
        assertArrayEquals(new boolean[]{false, false, false},
                LrclibFallback.parseSelectedSources("kugou,paxsenix,unknown"));
        boolean[] mixed = LrclibFallback.parseSelectedSources("kugou,lrclib,paxsenix");
        assertArrayEquals(new boolean[]{true, false, false}, mixed);
        assertEquals("lrclib", LrclibFallback.encodeSelectedSources(mixed));
        // First run shows everything but writes nothing; an explicit empty
        // selection stays empty instead of repopulating defaults.
        assertArrayEquals(new boolean[]{true, true, true},
                LrclibFallback.parseSelectedSources(null));
        assertArrayEquals(new boolean[]{false, false, false},
                LrclibFallback.parseSelectedSources(""));
    }

    private static final class PrefsState {
        String value;
        int writes;
    }

    /** Minimal in-memory SharedPreferences (also acts as its own Editor). */
    private static SharedPreferences prefs(final PrefsState state) {
        return (SharedPreferences) Proxy.newProxyInstance(
                ExtendedLyricsSourcesTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class, SharedPreferences.Editor.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getString".equals(name)) return state.value;
                        if ("putString".equals(name)) {
                            state.value = (String) args[1];
                            state.writes++;
                            return proxy;
                        }
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

    @Test
    public void sourceSelectionIsGlobalAndReadsNeverWrite() {
        boolean[] chosen = {false, true, false};
        PrefsState state = new PrefsState();
        state.value = LrclibFallback.encodeSelectedSources(chosen);
        SharedPreferences p = prefs(state);

        // Two different "songs" read the same single global preference.
        boolean[] firstSong = LrclibFallback.loadSelectedSources(p);
        boolean[] secondSong = LrclibFallback.loadSelectedSources(p);
        assertArrayEquals(chosen, firstSong);
        assertArrayEquals(firstSong, secondSong);
        assertEquals("loading a selection must not write it", 0, state.writes);

        // Only an explicit checkbox change writes, and it writes the one key.
        boolean[] changed = {true, true, false};
        LrclibFallback.saveSelectedSources(p, changed);
        assertEquals(1, state.writes);
        assertArrayEquals(changed, LrclibFallback.loadSelectedSources(p));
        assertEquals("lrclib,lyricsplus", state.value);
    }
}
