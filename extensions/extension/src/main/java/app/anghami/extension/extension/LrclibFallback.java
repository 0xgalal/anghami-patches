package app.anghami.extension.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.anghami.extension.extension.TranslationProvider.Model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRCLIB fallback for truncated lyrics (opt-in patch runtime).
 *
 * Entry points called from injected trampolines:
 * - {@link #maybeFetch} (from {@code A7/F.onNext}, after the server teaser
 *   renders) renders full LRCLIB lyrics when the server response is
 *   truncated or empty. Full native responses return early, so the
 *   Plus/native path is untouched.
 * - {@link #substituteCached} (from {@code A7/E.b} entry) rewrites a
 *   truncated/empty response in place with cached LRCLIB lyrics before
 *   native renders it — the teaser is never shown, on first open or
 *   revisit. {@link #impl} re-flags such responses truncated afterwards
 *   so native saveLyrics keeps refusing them.
 * - {@link #paintCached} (from the lyrics-view load) paints cached lyrics
 *   instantly without waiting for the DB/API round-trip.
 * - {@link #installLyricsButtonLongPress} opens the auto-translation config
 *   dialog when the player's Lyrics button is long-pressed; its "Lyrics
 *   options" button opens {@link #showOptionsForSong} for manual LRCLIB
 *   search, retry, synced/plain toggle and cache clearing.
 *
 * All app classes are touched via reflection: the extension compiles
 * without the app on the classpath. Any failure is caught and logged;
 * the host app is never crashed by this helper.
 *
 * Server StoredLyrics cache is never written; fallback results live in
 * a separate SharedPreferences file ("lyrics_fallback").
 */
@SuppressWarnings("unused")
public final class LrclibFallback {

    private static final String TAG = "LyricsFallback";
    // Filterable tag for AI-translation diagnostics (raw responses, salvage).
    private static final String TRANSLATION_TAG = "LyricsTranslation";
    private static final String SOURCE_URL = "https://lrclib.net";
    private static final String UA = "AnghamiLyricsFallback/1.0 (+https://lrclib.net/docs)";
    private static final String PREFS = "lyrics_fallback";
    private static final String PREF_LYRIC_SOURCES = "lyric_sources";
    private static final String KEY_VERSION = "v1";
    // Retain the preference file so existing credentials and caches survive upgrades.
    private static final String TRANSLATION_PREFS = "lyrics_openrouter";
    private static final long POS_TTL_MS = 30L * 24 * 3600 * 1000;
    private static final long NEG_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_ENTRIES = 500;
    private static final int HTTP_TIMEOUT_MS = 12000;
    // LRCLIB is checked on a background thread while the user waits for
    // lyrics. Keep each attempt short so a slow/unreachable endpoint fails
    // fast instead of stalling the whole waterfall for 12s per request.
    private static final int LRCLIB_TIMEOUT_MS = 6000;
    // Aggregator/Apple endpoints can be slower than LRCLIB. Give them
    // a little more room, but keep it bounded so a total miss still ends.
    private static final int EXTENDED_TIMEOUT_MS = 9000;
    private static final String[] LYRICSPLUS_BASES = {
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.binimum.org/"
    };
    private static final String BETTERLYRICS_URL = "https://lyrics-api.boidu.dev/getLyrics";
    // A candidate that matches the playing song may be a slightly longer cut
    // (extended outro, live version, different master). Accept equal and
    // longer recordings up to this bound; shorter cuts keep the tighter
    // DURATION_TOLERANCE_MS that the old providers used.
    private static final int DURATION_TOLERANCE_MS = 8000;
    private static final int CANDIDATE_DURATION_TOLERANCE_MS = 10000;
    private static final long LONGER_DURATION_TOLERANCE_MS = 30000L;
    private static final int TRANSLATION_TIMEOUT_MS = 10000;
    // Long lyrics are translated in bounded batches. A single request that
    // returns 80-150 translated lines routinely exceeds the output-token cap
    // of small models — Cloudflare Workers AI in particular truncates early,
    // which is exactly the "only part of the song translates" report: the
    // salvage parser recovers only the first N lines and pads the rest with
    // originals. Keeping each request small makes a complete, aligned answer
    // the normal case. Cloudflare gets noticeably smaller batches than the
    // OpenAI-compatible providers because its models have tighter caps.
    private static final int BATCH_LINES_CLOUDFLARE = 10;
    private static final int BATCH_CHARS_CLOUDFLARE = 1200;
    private static final int BATCH_LINES_CLOUDFLARE_SMALL = 6;
    private static final int BATCH_CHARS_CLOUDFLARE_SMALL = 800;
    private static final int BATCH_LINES_SMALL_MODEL = 16;
    private static final int BATCH_CHARS_SMALL_MODEL = 1800;
    private static final int BATCH_LINES_STANDARD = 22;
    private static final int BATCH_CHARS_STANDARD = 2500;
    // Batching removes the need for a per-song size gate in normal use, but a
    // generous absolute cap stays to bound cost/abuse (worst case ~40 batches,
    // each with its own 10s timeout; never one global timeout for the job).
    private static final int MAX_TRANSLATION_LINES = 800;
    private static final int MAX_TRANSLATION_CHARS = 40000;
    private static final String DEFAULT_TRANSLATION_SYSTEM_PROMPT =
            "You are a precise lyrics translation assistant. Your output must ALWAYS be a valid "
                    + "JSON object of the form {\"lines\": [\"line1\", \"line2\", \"line3\"]}.\n\n"
                    + "CRITICAL RULES:\n"
                    + "1. Output ONLY the JSON object: "
                    + "{\"lines\": [\"line1\", \"line2\", \"line3\"]}\n"
                    + "2. NO explanations, NO questions, NO additional text\n"
                    + "3. Each input line maps to exactly one entry in the \"lines\" array\n"
                    + "4. Preserve empty lines as empty strings \"\"\n"
                    + "5. The \"lines\" array must contain EXACTLY {lineCount} items\n"
                    + "6. If uncertain, provide your best possible translation.";
    private static final String ADDITIONAL_PROMPT_MARKER =
            "\n\nAdditional user instructions:\n";

    private LrclibFallback() {
    }

    /** Timeout applied to connect and read of the AI translation POST. */
    static int translationRequestTimeoutMs() {
        return TRANSLATION_TIMEOUT_MS;
    }

    // ---- last-render state (single visible lyrics view at a time) ----

    private static Object lastSong;
    private static Object lastCallback;
    private static Object lastOriginalResponse;
    private static String lastQueryUsed = "";
    private static String lastSource = "";
    private static String lastPlain;
    private static String lastSynced;
    private static boolean lastPreferSynced = true;
    private static long lastDialogMs;
    private static volatile WeakReference<Object> playerFragment =
            new WeakReference<Object>(null);
    // Original label of the player's Lyrics button, kept per view so the
    // " + AI" suffix can be added/removed without stacking on each onResume.
    private static final Map<Object, String> lyricsButtonLabels =
            new java.util.WeakHashMap<Object, String>();
    private static final java.util.WeakHashMap<Object, String> renderedKeys =
            new java.util.WeakHashMap<Object, String>();
    private static final Map<String, WeakReference<Object>> lyricsCallbacks =
            new LinkedHashMap<String, WeakReference<Object>>();
    private static String translationSongId = "";
    private static Object translationSong;
    private static Object translationCallback;
    private static boolean translationSourceSynced;
    private static final List<Line> translationSourceLines = new ArrayList<Line>();
    private static final Set<String> translationRequestsInFlight =
            new HashSet<String>();
    // The snapshot a translation request started with stays authoritative
    // until its result handler runs, so cache adoption during that window
    // cannot trigger a false "source changed".
    private static final Map<String, SourceSnapshot> inFlightSnapshots =
            new java.util.HashMap<String, SourceSnapshot>();
    // A cached "no lyrics" still gets one background re-check per cooldown
    // window, so a source recovering later updates the view on its own.
    private static final Map<String, Long> missRetryTimes =
            new java.util.HashMap<String, Long>();
    private static final long MISS_RETRY_COOLDOWN_MS = 60L * 1000;

    // ---- proactive fill (Lyrics tab active but nothing on screen) ----
    // One search per song at a time, plus the miss cooldown above, keeps the
    // onResume/mode-observer hooks from hammering a source when a song really
    // has no lyrics.
    private static final Set<String> fillInFlight = new HashSet<String>();
    private static final Map<String, Long> nativeFullRenderTimes =
            new java.util.HashMap<String, Long>();
    private static final long NATIVE_RENDER_FRESH_MS = 10L * 60 * 1000;
    private static String lastRenderedSongKey = "";
    private static boolean lastRenderedRealLyrics;

    // A manual pick is authoritative: it must win over any autofill render that
    // is still queued for the same song. A pick advances a per-song epoch; a
    // queued render/re-assert carries the epoch it was scheduled with and
    // becomes a no-op once the pick changed the epoch.
    private static final Map<String, Long> renderEpochs =
            new java.util.HashMap<String, Long>();
    // Epoch captured when a background fill search starts. If a manual pick
    // advances the epoch before the result lands, that result is stale and is
    // not allowed to overwrite the pick.
    private static final Map<String, Long> fillSearchStartEpochs =
            new java.util.HashMap<String, Long>();

    // Bounded retry for the case where onResume/mode-observer fires before the
    // player's lyrics view is laid out. Keyed by song so a quick song switch
    // cannot leave a stale retry queued for the wrong track.
    private static final Map<String, Long> fillRetryTimes =
            new java.util.HashMap<String, Long>();
    private static final long FILL_RETRY_DELAY_MS = 250;
    private static final int FILL_RETRY_MAX = 3;

    /**
     * State of the live lyrics view, used as the source of truth for
     * "is anything actually on screen". UNKNOWN means the view is not
     * available yet (rotation, pre-layout onResume) — callers then fall back
     * to the in-memory render marks and schedule a bounded retry.
     */
    enum LyricsViewState { UNKNOWN, EMPTY, HAS_LYRICS }

    // ================= entry points =================

    /** Called after {@code A7/E.b} renders the server teaser (or empty). */
    public static void maybeFetch(Object songObj, Object responseObj, Object callbackObj) {
        impl(songObj, responseObj, callbackObj);
    }

    /** Called from the API error path (song has nothing usable on the server). */
    public static void maybeFetchNoResponse(Object songObj, Object callbackObj) {
        impl(songObj, null, callbackObj);
    }

    /**
     * Captures the exact native lyrics payload before the app renders it.
     * The translation action later combines each translated line with its
     * original line and retains the source timestamp for synced lyrics.
     */
    public static void rememberNativeLyrics(Object songObj, Object responseObj,
                                            Object callbackObj) {
        captureNativeLyrics(songObj, responseObj, callbackObj);
    }

    /**
     * Starts automatic translation for full native lyrics without touching
     * the response. Native rendering must complete normally so the lyrics
     * appear as soon as the song loads; the dual-line render replaces them
     * in place when the translation finishes. Holding the response behind a
     * pending message left the screen empty whenever that render was late,
     * which forced the user to leave and re-open the song to see anything.
     * Truncated/empty responses are left to the LRCLIB fallback chain.
     */
    public static void deferLyricsForTranslation(Object songObj, Object responseObj,
                                                 Object callbackObj) {
        try {
            if (songObj == null || responseObj == null || callbackObj == null) return;
            Context ctx = appContextOf(callbackObj);
            if (!hasConfiguredAutomaticTranslation(ctx)) return;
            if (!isFullNativeResponse(responseObj)) return;
            captureNativeLyrics(songObj, responseObj, callbackObj);
        } catch (Throwable t) {
            Log.d(TAG, "deferLyricsForTranslation failed: " + t);
        }
    }

    private static boolean captureNativeLyrics(Object songObj, Object responseObj,
                                               Object callbackObj) {
        try {
            if (callbackObj == null) return false;
            hideUnlockLyricsButton(callbackObj);
            if (songObj == null || responseObj == null) return false;
            if (!isFullNativeResponse(responseObj)) return false;
            SongInfo song = readSong(songObj);
            if (song == null) return false;

            String plain = getString(responseObj, "lyricsUnsynced");
            List<?> nativeLines = getList(responseObj, "lyricsSynced");
            List<Line> timed = new ArrayList<Line>();
            if (nativeLines != null) {
                for (Object nativeLine : nativeLines) {
                    if (nativeLine == null) continue;
                    Object text = getFieldUp(nativeLine, "line");
                    Object ms = getFieldUp(nativeLine, "milliseconds");
                    if (!(text instanceof String)) continue;
                    Line line = new Line();
                    line.text = (String) text;
                    line.ms = ms instanceof Number ? ((Number) ms).intValue() : -1;
                    timed.add(line);
                }
            }
            boolean useSynced = !timed.isEmpty()
                    && (getBoolean(responseObj, "isSynced")
                    || plain == null || plain.trim().isEmpty());
            return setTranslationSource(song, songObj, callbackObj,
                    useSynced ? timed : plainLines(plain), useSynced);
        } catch (Throwable t) {
            Log.d(TAG, "rememberNativeLyrics failed: " + t);
            return false;
        }
    }

    /**
     * Records the song-bound render callback for the Settings options.
     * This runs for native full-lyrics responses too, which the LRCLIB
     * fallback intentionally leaves untouched.
     */
    public static void rememberLyricsTarget(Object songObj, Object callbackObj) {
        try {
            if (songObj == null || callbackObj == null) return;
            SongInfo song = readSong(songObj);
            if (song == null) return;
            synchronized (LrclibFallback.class) {
                lastSong = songObj;
                lastCallback = callbackObj;
                lyricsCallbacks.put(songKey(song),
                        new WeakReference<Object>(callbackObj));
                while (lyricsCallbacks.size() > 16) {
                    String oldest = lyricsCallbacks.keySet().iterator().next();
                    lyricsCallbacks.remove(oldest);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "rememberLyricsTarget failed: " + t);
        }
    }

    /** Installs the separate long-press action on the player's Lyrics toggle. */
    public static void installLyricsButtonLongPress(Object fragmentObj) {
        try {
            if (fragmentObj == null) return;
            playerFragment = new WeakReference<Object>(fragmentObj);
            Object targetObj = getFieldUp(fragmentObj, "V");
            if (!(targetObj instanceof View)) return;
            final WeakReference<Object> fragmentRef =
                    new WeakReference<Object>(fragmentObj);
            ((View) targetObj).setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    showAutoTranslationDialog(view.getContext(), fragmentRef.get());
                    return true;
                }
            });
            applyLyricsButtonLabel((View) targetObj);
        } catch (Throwable t) {
            Log.d(TAG, "installLyricsButtonLongPress failed: " + t);
        }
    }

    /**
     * Shows "Lyrics + AI" on the player's Lyrics button while automatic
     * translation is configured, restoring the native label when it is off.
     * Safe to call repeatedly (onResume, settings toggles).
     */
    private static void applyLyricsButtonLabel(View button) {
        try {
            if (button == null || button.getContext() == null) return;
            boolean active = hasConfiguredAutomaticTranslation(button.getContext());
            List<TextView> labels = new ArrayList<TextView>();
            collectLyricsButtonLabels(button, labels, false);
            if (labels.isEmpty()) {
                // Locale we do not recognize: fall back to the button's
                // first non-empty label rather than leaving it unchanged.
                List<TextView> any = new ArrayList<TextView>();
                collectLyricsButtonLabels(button, any, true);
                if (!any.isEmpty()) labels.add(any.get(0));
            }
            for (TextView label : labels) {
                if (active) {
                    if (!lyricsButtonLabels.containsKey(label)) {
                        lyricsButtonLabels.put(label, String.valueOf(label.getText()));
                    }
                    String original = lyricsButtonLabels.get(label);
                    String desired = original + " + AI";
                    if (!desired.contentEquals(label.getText())) label.setText(desired);
                } else {
                    String original = lyricsButtonLabels.remove(label);
                    if (original != null) label.setText(original);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "applyLyricsButtonLabel failed: " + t);
        }
    }

    private static void refreshLyricsButtonLabel() {
        WeakReference<Object> ref = playerFragment;
        Object fragment = ref == null ? null : ref.get();
        if (fragment == null) return;
        try {
            Object target = getFieldUp(fragment, "V");
            if (target instanceof View) applyLyricsButtonLabel((View) target);
        } catch (Throwable ignored) {
        }
    }

    private static void collectLyricsButtonLabels(View view, List<TextView> out, boolean fallback) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0
                    && (fallback || isLyricsButtonLabel(text.toString()))) {
                out.add((TextView) view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLyricsButtonLabels(group.getChildAt(i), out, fallback);
            }
        }
    }

    static boolean isLyricsButtonLabel(String text) {
        String t = normalize(text, false);
        return t.contains("lyric") || t.contains("parole")
                || t.contains("\u0643\u0644\u0645\u0627\u062a");
    }

    private static void showAutoTranslationDialog(final Context ctx,
                                                  final Object fragmentObj) {
        try {
            showTranslationDialog(ctx, null, fragmentObj, true);
        } catch (Throwable t) {
            Log.d(TAG, "showAutoTranslationDialog failed: " + t);
        }
    }

    private static void setAutoTranslationEnabled(Context ctx, boolean enabled) {
        try {
            translationPrefs(ctx).edit()
                    .putBoolean("auto_translate", enabled)
                    .apply();
        } catch (Throwable t) {
            Log.d(TAG, "setAutoTranslationEnabled failed: " + t);
        }
    }

    private static Object currentSongFromPlayer(Object fragmentObj) {
        try {
            if (fragmentObj != null) {
                Method method = fragmentObj.getClass().getMethod("I0");
                Object song = method.invoke(fragmentObj);
                if (song != null) return song;
            }
        } catch (Throwable ignored) {
        }
        return currentPlayingSong();
    }

    /**
     * True when an enum/state constant name denotes the Lyrics tab. Pure so it
     * is unit-testable without an Android runtime. 8.0.28 uses the constant
     * named "Lyrics"; keeping this name-based makes the detection survive a
     * field rename in a rotated build.
     */
    static boolean isLyricsModeName(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return "lyrics".equals(n) || "lyric".equals(n)
                || n.endsWith("lyrics") || n.contains("lyricsmode");
    }

    /** The native Lyrics mode enum value, or null when reflection cannot read it. */
    private static Object lyricsModeValue() {
        // 8.0.28: com.anghami.player.ui.PlayerFragmentViewModel$c.b == "Lyrics".
        // Try several class/field names, then the enum constant name, so a
        // renamed enum cannot silently disable the proactive fill.
        String[] classNames = {
                "com.anghami.player.ui.PlayerFragmentViewModel$c",
                "com.anghami.player.ui.PlayerFragmentViewModel$LyricsMode",
        };
        String[] fieldNames = {"b", "LYRICS", "Lyrics"};
        for (String cn : classNames) {
            try {
                Class<?> modeClass = Class.forName(cn);
                for (String fn : fieldNames) {
                    try {
                        Object v = modeClass.getField(fn).get(null);
                        if (v != null) return v;
                    } catch (Throwable ignored) {
                    }
                }
                Object[] constants = modeClass.getEnumConstants();
                if (constants != null) {
                    for (Object c : constants) {
                        if (c instanceof Enum
                                && isLyricsModeName(((Enum<?>) c).name())) {
                            return c;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isLyricsModeValue(Object modeObj) {
        if (modeObj == null) return false;
        Object lyricsMode = lyricsModeValue();
        if (modeObj == lyricsMode) return true;
        // Fallback A: 8.0.28 enum exposes b(): boolean ("this == Lyrics").
        try {
            Method m = findMethodUp(modeObj.getClass(), "b");
            if (m == null) m = findMethodUp(modeObj.getClass(), "isLyrics");
            if (m != null && m.getParameterTypes().length == 0) {
                Object r = m.invoke(modeObj);
                if (r instanceof Boolean && (Boolean) r) return true;
            }
        } catch (Throwable ignored) {
        }
        // Fallback B: the constant's own name/toString.
        try {
            if (modeObj instanceof Enum && isLyricsModeName(((Enum<?>) modeObj).name())) {
                return true;
            }
            if (isLyricsModeName(String.valueOf(modeObj))) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Current player mode state: TRUE = Lyrics, FALSE = another mode, null =
     * undetectable. Shared by the mode guard and the proactive fill. The
     * ViewModel LiveData is the primary source (n1 -> _playerMode -> d()),
     * with the fragment's own `p1` field as a fallback (both verified present
     * in the 8.0.28 PlayerFragment smali).
     */
    private static Boolean lyricsModeState(Object fragmentObj) {
        try {
            if (fragmentObj == null) return null;
            Object viewModel = firstNonNullField(fragmentObj, "n1", "viewModel");
            if (viewModel != null) {
                Object modeState = firstNonNullField(viewModel, "_playerMode", "playerMode");
                if (modeState != null) {
                    Object currentMode = invokeNoArgMethod(modeState, "d", "getValue");
                    if (currentMode != null) {
                        return isLyricsModeValue(currentMode) ? Boolean.TRUE : Boolean.FALSE;
                    }
                }
            }
            Object direct = firstNonNullField(fragmentObj, "p1", "playerMode");
            if (direct != null) {
                return isLyricsModeValue(direct) ? Boolean.TRUE : Boolean.FALSE;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns the first non-null field among the given names, or null. */
    private static Object firstNonNullField(Object o, String... names) {
        if (o == null) return null;
        for (String name : names) {
            try {
                Object v = getFieldUp(o, name);
                if (v != null) return v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Invokes the first no-arg method among the given names that returns a value. */
    private static Object invokeNoArgMethod(Object o, String... names) {
        if (o == null) return null;
        for (String name : names) {
            try {
                Method m = findMethodUp(o.getClass(), name);
                if (m != null && m.getParameterTypes().length == 0) {
                    Object v = m.invoke(o);
                    if (v != null) return v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** True only when the player is actually showing the Lyrics tab. */
    static boolean isLyricsModeActive(Object fragmentObj) {
        return Boolean.TRUE.equals(lyricsModeState(fragmentObj));
    }

    /** Enables Lyrics mode without toggling it off when it is already active. */
    private static void ensureLyricsMode(Object fragmentObj) {
        try {
            if (fragmentObj == null) return;
            Boolean active = lyricsModeState(fragmentObj);
            if (active == null) return;
            if (!active) {
                Object viewModel = getFieldUp(fragmentObj, "n1");
                if (viewModel != null) {
                    viewModel.getClass().getMethod("onLyricsClicked").invoke(viewModel);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "ensureLyricsMode failed: " + t);
        }
    }

    // ================= proactive lyrics fill =================

    /**
     * Entry called from PlayerFragment.onResume. If the Lyrics tab is already
     * active but no lyrics are on screen, fill it from the selected sources.
     * One static call at method entry; every decision lives here.
     */
    public static void fillLyricsIfLyricsModeActive(Object fragmentObj) {
        if (!isLyricsModeActive(fragmentObj)) return;
        runLyricsFill(fragmentObj, 0);
    }

    /**
     * Entry for the player-mode observer (`a1`). The observer receives the new
     * mode as an argument before the ViewModel state settles, so pass that value
     * directly. Also covers tapping Lyrics after onResume already ran.
     */
    public static void fillLyricsForMode(Object fragmentObj, Object modeObj) {
        if (!isLyricsModeValue(modeObj)) return;
        runLyricsFill(fragmentObj, 0);
    }

    // ------------------------------------------------------------------
    // Pure decision helpers (unit-testable without Android / a live view)
    // ------------------------------------------------------------------

    /**
     * Decides emptiness from the live view state and the in-memory marks.
     * The view wins when available: static render marks survive view
     * recreation, so a fresh empty view must still be filled even when a
     * previous song (or a previous instance of this one) "rendered" lyrics.
     * Without a view, fall back to the marks.
     */
    static boolean isLyricsActuallyEmpty(boolean marksSayRendered,
                                         LyricsViewState viewState) {
        if (viewState == LyricsViewState.HAS_LYRICS) return false;
        if (viewState == LyricsViewState.EMPTY) return true;
        return !marksSayRendered;
    }

    /** True when a bounded delayed retry is warranted (pre-layout view). */
    static boolean shouldScheduleFillRetry(LyricsViewState viewState,
                                           boolean contextAvailable, int attempt) {
        return attempt < FILL_RETRY_MAX
                && (!contextAvailable || viewState == LyricsViewState.UNKNOWN);
    }

    /**
     * A progress state or an empty/loading view needs the native loading state
     * explicitly cleared before our content is painted. Real lyrics already on
     * screen do not.
     */
    static boolean needsNativeLoadingCleanup(boolean searchInProgress,
                                             LyricsViewState viewState) {
        return searchInProgress || viewState == LyricsViewState.EMPTY;
    }

    /** Delays (ms) used to re-assert our render over the native spinner. */
    static int[] settleRenderDelaysMs() {
        return new int[]{300, 500, 1000};
    }

    /**
     * Runs the three fill-render steps in the order that clears the native
     * spinner: loading cleanup first, our content render second, and the
     * delayed re-assert last. Kept pure (Runnables only) so tests can verify
     * ordering without an Android runtime.
     */
    static void renderFillResult(boolean clearLoading, Runnable clear,
                                 Runnable render, Runnable settle) {
        if (clearLoading && clear != null) clear.run();
        if (render != null) render.run();
        if (settle != null) settle.run();
    }

    // ------------------------------------------------------------------
    // Live-view inspection (reflection only, guarded)
    // ------------------------------------------------------------------

    /** Root view of a Fragment, via getView() or the native `e` field. */
    private static View fragmentRootView(Object fragmentObj) {
        try {
            Method gv = findMethodUp(fragmentObj.getClass(), "getView");
            Object v = gv == null ? null : gv.invoke(fragmentObj);
            if (v instanceof View) return (View) v;
        } catch (Throwable ignored) {
        }
        try {
            Object v = getFieldUp(fragmentObj, "e");
            if (v instanceof View) return (View) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Reads the value of a LiveData-like object via d()/getValue(). */
    private static Object liveDataValue(Object liveData) {
        return invokeNoArgMethod(liveData, "d", "getValue");
    }

    /**
     * State of a known lyrics render callback (com.anghami.ui.view.A): its
     * `B` LiveData holds a sealed state; `com.anghami.ui.view.t` is the
     * success/lyrics-present state, everything else is empty/loading/error.
     */
    private static LyricsViewState lyricsViewStateForCallback(Object callback) {
        try {
            if (callback == null) return LyricsViewState.UNKNOWN;
            Object state = liveDataValue(getFieldUp(callback, "B"));
            if (state == null) return LyricsViewState.UNKNOWN;
            String cn = state.getClass().getName();
            if ("com.anghami.ui.view.t".equals(cn)) {
                // t is the success state; its `a` field holds the rendered
                // lines. A success state with no lines is still effectively
                // empty (e.g. our own loading-clear call).
                Object lines = getFieldOrNull(state, "a");
                if (lines instanceof List && !((List<?>) lines).isEmpty()) {
                    return LyricsViewState.HAS_LYRICS;
                }
                return LyricsViewState.EMPTY;
            }
            return LyricsViewState.EMPTY;
        } catch (Throwable t) {
            return LyricsViewState.UNKNOWN;
        }
    }

    /** Finds the lyrics view (A) in a hierarchy by class name / state field. */
    private static Object findLyricsView(View root) {
        if (root == null) return null;
        String cn = root.getClass().getName();
        if ("com.anghami.ui.view.A".equals(cn)) return root;
        if (cn.startsWith("com.anghami.ui.view.")
                && getFieldOrNull(root, "B") != null
                && getFieldOrNull(root, "i") != null) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                Object found = findLyricsView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object getFieldOrNull(Object o, String name) {
        try {
            return getFieldUp(o, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Finds rv_lyrics / lyrics_container by resource name (ID-safe). */
    private static View findLyricsList(View root) {
        if (root == null) return null;
        try {
            int id = root.getResources().getIdentifier("rv_lyrics", "id",
                    root.getContext().getPackageName());
            if (id != 0) {
                View v = root.findViewById(id);
                if (v != null) return v;
            }
            id = root.getResources().getIdentifier("lyrics_container", "id",
                    root.getContext().getPackageName());
            if (id != 0) {
                View v = root.findViewById(id);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Counts real lyric lines from the RecyclerView adapter or child count. */
    private static int countLyricLines(View list) {
        if (!(list instanceof ViewGroup)) return 0;
        try {
            Method ga = findMethodUp(list.getClass(), "getAdapter");
            Object adapter = ga == null ? null : ga.invoke(list);
            if (adapter != null) {
                Method gi = findMethodUp(adapter.getClass(), "getItemCount");
                Object count = gi == null ? null : gi.invoke(adapter);
                if (count instanceof Integer) return (Integer) count;
            }
        } catch (Throwable ignored) {
        }
        return ((ViewGroup) list).getChildCount();
    }

    /**
     * A lyrics View (com.anghami.ui.view.A) is bound to one Song through its
     * final `c` field (verified in 8.0.28). During song navigation a detached
     * view for the previous song can still be in the player hierarchy; its
     * lines are NOT on screen for the current track. Only trust a view whose
     * song matches — otherwise the raw state is discarded so the fill can
     * proceed instead of being suppressed by a stale "has lyrics" reading.
     * Package-private so the decision is testable without a live view.
     */
    static LyricsViewState viewStateForSong(LyricsViewState raw, boolean songMatches) {
        return songMatches ? raw : LyricsViewState.UNKNOWN;
    }

    /** Best-effort state of the live lyrics view for the proactive fill. */
    private static LyricsViewState lyricsViewState(Object fragmentObj, Object songObj) {
        try {
            View root = fragmentRootView(fragmentObj);
            if (root == null) return LyricsViewState.UNKNOWN;
            Object lyricsView = findLyricsView(root);
            if (lyricsView != null) {
                LyricsViewState raw = lyricsViewStateForCallback(lyricsView);
                if (raw != LyricsViewState.UNKNOWN) {
                    return viewStateForSong(raw, viewMatchesSong(lyricsView, songObj));
                }
            }
            View list = findLyricsList(root);
            if (list == null) return LyricsViewState.UNKNOWN;
            return countLyricLines(list) > 0
                    ? LyricsViewState.HAS_LYRICS : LyricsViewState.EMPTY;
        } catch (Throwable t) {
            return LyricsViewState.UNKNOWN;
        }
    }

    /** True when a lyrics view is bound to the current song (or cannot be checked). */
    private static boolean viewMatchesSong(Object lyricsView, Object songObj) {
        try {
            if (songObj == null) return true; // no identity to compare: keep old behavior
            Object viewSong = getFieldUp(lyricsView, "c");
            if (viewSong == null) return true; // field absent on this build: keep old behavior
            SongInfo fromView = readSong(viewSong);
            SongInfo current = readSong(songObj);
            if (fromView == null || current == null) return true;
            return songKey(fromView).equals(songKey(current));
        } catch (Throwable t) {
            return true;
        }
    }

    /** Bounded main-thread retry when the mode is active but the view is not ready. */
    private static void scheduleFillRetry(final Object fragmentObj, final Object songObj,
                                          final String queueKey, final int attempt) {
        if (!shouldScheduleFillRetry(
                lyricsViewState(fragmentObj, songObj), true, attempt)) return;
        synchronized (LrclibFallback.class) {
            Long last = fillRetryTimes.get(queueKey);
            long now = System.currentTimeMillis();
            if (last != null && now - last < FILL_RETRY_DELAY_MS) return;
            fillRetryTimes.put(queueKey, now);
        }
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!isLyricsModeActive(fragmentObj)) return;
                        runLyricsFill(fragmentObj, attempt + 1);
                    } catch (Throwable ignored) {
                    }
                }
            }, FILL_RETRY_DELAY_MS);
        } catch (Throwable ignored) {
        }
    }

    private static void runLyricsFill(Object fragmentObj, int attempt) {
        try {
            if (fragmentObj == null) return;
            Object songObj = currentSongFromPlayer(fragmentObj);
            if (songObj == null) return;
            SongInfo song = readSong(songObj);
            if (song == null || song.title.isEmpty()) return;
            playerFragment = new WeakReference<Object>(fragmentObj);

            Object callback = callbackForSong(songObj);
            Context ctx = appContextOf(callback);
            if (ctx == null) {
                Object target = getFieldUp(fragmentObj, "V");
                if (target instanceof View) ctx = ((View) target).getContext();
            }
            String queueKey = songKey(song);
            if (ctx == null) {
                scheduleFillRetry(fragmentObj, songObj, queueKey, attempt);
                return;
            }
            // Refresh the song-bound callback so a view recreated since the
            // last render wins over a stale one. evaluate/render always resolve
            // callbackForSong again at paint time.
            if (callback != null) rememberLyricsTarget(songObj, callback);

            LyricsViewState viewState = lyricsViewState(fragmentObj, songObj);
            if (shouldScheduleFillRetry(viewState, true, attempt)
                    && viewState == LyricsViewState.UNKNOWN) {
                scheduleFillRetry(fragmentObj, songObj, queueKey, attempt);
                // Still proceed on the marks so the fill is not lost when the
                // view simply cannot be inspected on this device.
            }
            boolean actuallyEmpty = isLyricsActuallyEmpty(
                    hasRenderedLyricsFor(songObj), viewState);
            FillDecision decision = decideFill(true, !actuallyEmpty,
                    isFillInFlight(queueKey), prefs(ctx), song,
                    System.currentTimeMillis());
            if (decision.action == FillAction.NONE) return;
            executeFillDecision(ctx, songObj, song, callback, prefs(ctx), decision);
        } catch (Throwable t) {
            Log.d(TAG, "fillLyricsIfLyricsModeActive failed: " + t);
        }
    }

    enum FillAction { NONE, FROM_CACHE, SEARCH }

    /**
     * Progress state machine for a lyrics search. A search replaces the native
     * loading spinner with our status line immediately; a final result or a
     * miss must always replace that status line. Kept package-private and
     * side-effect-free so the transitions are unit-testable without a View.
     */
    static final class SearchRenderState {
        enum Phase { IDLE, PROGRESS, SUCCESS, MISS }

        Phase phase = Phase.IDLE;
        String songKey = "";

        /** Starts a search; returns the first line to paint over the spinner. */
        String begin(String key) {
            songKey = key == null ? "" : key;
            phase = Phase.PROGRESS;
            return tr("searching");
        }

        /** Progress update; ignored once a final outcome has been recorded. */
        String progress(String key, String message) {
            if (!belongs(key) || phase != Phase.PROGRESS) return null;
            return message;
        }

        /** Final lyrics; returns the text that must replace the progress line. */
        String success(String key, String text) {
            if (!belongs(key)) return null;
            phase = Phase.SUCCESS;
            return text;
        }

        /** Miss; returns the stable message that replaces the progress line. */
        String miss(String key, String text) {
            if (!belongs(key)) return null;
            phase = Phase.MISS;
            return text;
        }

        boolean isProgress(String key) {
            return belongs(key) && phase == Phase.PROGRESS;
        }

        private boolean belongs(String key) {
            return songKey.equals(key == null ? "" : key);
        }
    }

    /** Immutable result of the pure fill decision; cached hit is read-only. */
    static final class FillDecision {
        final FillAction action;
        final String reason;
        final Cached hit;

        FillDecision(FillAction action, String reason, Cached hit) {
            this.action = action;
            this.reason = reason;
            this.hit = hit;
        }
    }

    /**
     * Pure decision used by both entry points. Package-private so unit tests can
     * cover every branch without an Android runtime or a network call.
     *
     * @param lyricsModeActive   Lyrics tab is showing
     * @param lyricsRendered     real lyrics for this song are already on screen
     * @param inFlight           a fill search for this song is already running
     * @param p                  fallback preferences (may be null in tests)
     * @param song               currently playing song
     * @param nowMs              injected clock for deterministic cooldown tests
     */
    static FillDecision decideFill(boolean lyricsModeActive, boolean lyricsRendered,
                                   boolean inFlight, SharedPreferences p,
                                   SongInfo song, long nowMs) {
        if (!lyricsModeActive) return new FillDecision(FillAction.NONE, "mode", null);
        if (song == null || song.title.isEmpty()) {
            return new FillDecision(FillAction.NONE, "no song", null);
        }
        if (lyricsRendered) return new FillDecision(FillAction.NONE, "rendered", null);
        if (inFlight) return new FillDecision(FillAction.NONE, "in flight", null);
        if (p == null) return new FillDecision(FillAction.NONE, "no prefs", null);

        String key = cacheKey(song);
        Cached hit = lookupCache(p, song, key);
        if (hasUsableLyrics(hit)) {
            return new FillDecision(FillAction.FROM_CACHE, "cache", hit);
        }
        // A negative entry or a cooldown slot means "recently looked, nothing
        // found". Wait out the cooldown instead of re-querying every onResume.
        if (!shouldRetryMiss(key, nowMs)) {
            return new FillDecision(FillAction.NONE, "miss cooldown", null);
        }
        return new FillDecision(FillAction.SEARCH, "search", null);
    }

    private static boolean isFillInFlight(String songKey) {
        synchronized (LrclibFallback.class) {
            return fillInFlight.contains(songKey);
        }
    }

    /**
     * True when real lyrics for this song are still visible. Covers both our
     * own renders and full native lyrics that bypassed the fallback.
     */
    static boolean hasRenderedLyricsFor(Object songObj) {
        try {
            SongInfo song = readSong(songObj);
            if (song == null) return false;
            String wanted = songKey(song);
            synchronized (LrclibFallback.class) {
                if (callbackForSong(songObj) == null) return false;
                if (wanted.equals(lastRenderedSongKey) && lastRenderedRealLyrics) {
                    return true;
                }
                Long nativeMs = nativeFullRenderTimes.get(wanted);
                return nativeMs != null
                        && System.currentTimeMillis() - nativeMs < NATIVE_RENDER_FRESH_MS;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Records a full native render so the proactive fill does not overwrite it. */
    private static void markNativeLyricsRendered(Object songObj) {
        try {
            SongInfo song = readSong(songObj);
            if (song == null) return;
            String wanted = songKey(song);
            synchronized (LrclibFallback.class) {
                nativeFullRenderTimes.put(wanted, System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
        }
    }

    /** Clears the native-render mark when the same song shows a teaser/empty. */
    private static void clearNativeLyricsRendered(Object songObj) {
        try {
            SongInfo song = readSong(songObj);
            if (song == null) return;
            synchronized (LrclibFallback.class) {
                nativeFullRenderTimes.remove(songKey(song));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Render seam: production uses renderOnMain; unit tests record the call. */
    interface LyricsRenderSink {
        void render(Context ctx, Object songObj, Object callback,
                    String plain, String synced, String queryUsed);
    }

    private static final LyricsRenderSink PRODUCTION_RENDER_SINK =
            new LyricsRenderSink() {
                @Override
                public void render(Context ctx, Object songObj, Object callback,
                                   String plain, String synced, String queryUsed) {
                    // Clear any native loading state on the freshest callback
                    // first, then paint our content. The native lyrics load can
                    // post its spinner state right after our render frame, so
                    // settleRenderOnMain re-asserts the same lyrics afterwards.
                    renderFillResult(true,
                            new Runnable() {
                                @Override
                                public void run() {
                                    clearNativeLoading(songObj, callback);
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    renderOnMain(ctx, songObj, callback, plain, synced, queryUsed, false);
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    settleRenderOnMain(songObj, callback, plain, synced);
                                }
                            });
                }
            };

    /**
     * Safe reflective call to the freshest lyrics callback's success method
     * ({@code A7/E$a.V}). Calling V() drives the native view's state LiveData
     * to its success state, which is what actually removes the loading
     * spinner. Guarded so a mismatched/changed callback can never crash the
     * host app.
     */
    private static void clearNativeLoading(final Object songObj, final Object callback) {
        final String songId = songIdOf(songObj);
        final long capturedEpoch = renderEpoch(songId);
        Runnable clear = new Runnable() {
            @Override
            public void run() {
                try {
                    // This paints an empty success state. If a manual pick
                    // committed since it was queued, painting now would blank
                    // the picked lyrics — skip instead.
                    if (!isReassertCurrent(songId, capturedEpoch)) return;
                    Object latest = paintCallback(songObj, callback);
                    if (latest == null) return;
                    Object response = buildResponse(songObj, "", null);
                    if (response == null) return;
                    invokeRender(latest, songObj, new ArrayList<Object>(), response);
                } catch (Throwable t) {
                    Log.d(TAG, "clearNativeLoading failed: " + t);
                }
            }
        };
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                clear.run();
            } else {
                new Handler(Looper.getMainLooper()).post(clear);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Dedupe key for a render. The song identity is normalized (stable id, or
     * artist|title when the build exposes no id) so two objects for the same
     * track share a key while two different tracks can never collide. A
     * manual pick may carry a different Song instance than the autofill path;
     * keying on the normalized identity — not the raw `id` field of whichever
     * object was passed in — keeps the dedupe honest across song navigation.
     * Package-private so tests can verify the song identity is part of it.
     */
    static String renderKey(Object songObj, String plain, String synced) {
        try {
            SongInfo song = readSong(songObj);
            if (song == null) return null;
            return songKey(song) + "|" + (plain == null ? 0 : plain.hashCode())
                    + "|" + (synced == null ? 0 : synced.hashCode())
                    + "|" + lastPreferSynced;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True only when an identical render should be skipped (never when forced). */
    static boolean shouldSkipDuplicateRender(boolean force, String key, String storedKey) {
        return !force && key != null && key.equals(storedKey);
    }

    /** Epoch of the newest committed render for a song id (0 when none). */
    static long renderEpoch(String songId) {
        if (songId == null) return 0L;
        synchronized (LrclibFallback.class) {
            Long epoch = renderEpochs.get(songId);
            return epoch == null ? 0L : epoch.longValue();
        }
    }

    /** Advance a song's render epoch. Package-private for deterministic tests. */
    static long advanceRenderEpoch(Object songObj) {
        String id = songIdOf(songObj);
        if (id == null) return -1L;
        synchronized (LrclibFallback.class) {
            Long epoch = renderEpochs.get(id);
            long next = (epoch == null ? 0L : epoch.longValue()) + 1L;
            renderEpochs.put(id, Long.valueOf(next));
            return next;
        }
    }

    /** Invalidates pending re-asserts so a manual pick cannot be overwritten. */
    static void cancelPendingReasserts(Object songObj) {
        advanceRenderEpoch(songObj);
    }

    /** True when a re-assert scheduled with {@code capturedEpoch} is still valid. */
    static boolean isReassertCurrent(String songId, long capturedEpoch) {
        return renderEpoch(songId) == capturedEpoch;
    }

    /**
     * True when a background fill result is stale: a manual pick committed
     * after this search started. Package-private for deterministic tests.
     */
    static boolean shouldSkipLateFillResult(String songId) {
        if (songId == null) return false;
        synchronized (LrclibFallback.class) {
            Long startedAt = fillSearchStartEpochs.get(songId);
            return startedAt != null && !isReassertCurrent(songId, startedAt.longValue());
        }
    }

    /** Normalized identity of a song object, or null when it cannot be read. */
    private static String songIdOf(Object songObj) {
        try {
            SongInfo song = readSong(songObj);
            return song == null ? null : songKey(song);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Drops the dedupe marks of every live callback bound to this song. */
    static void clearRenderKeysForSong(Object songObj) {
        try {
            final String wanted = songIdOf(songObj);
            if (wanted == null) return;
            final Object resolved = callbackForSong(songObj);
            synchronized (LrclibFallback.class) {
                java.util.Iterator<Map.Entry<Object, String>> it =
                        renderedKeys.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Object, String> entry = it.next();
                    if (entry.getKey() == resolved || belongsToSong(entry.getKey(), wanted)) {
                        it.remove();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** True when a callback (a lyrics View) is bound to the given song id. */
    private static boolean belongsToSong(Object callback, String wantedId) {
        if (!(callback instanceof View)) return false;
        try {
            Object viewSong = getFieldUp(callback, "c");
            if (viewSong == null) return false;
            SongInfo fromView = readSong(viewSong);
            return fromView != null && wantedId.equals(songKey(fromView));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True while our render is still the newest one for this callback. */
    private static boolean isLatestRender(Object callback, String key) {
        if (callback == null || key == null) return false;
        try {
            synchronized (LrclibFallback.class) {
                return key.equals(renderedKeys.get(callback));
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static void settleRenderOnMain(final Object songObj, final Object callback,
                                           final String plain, final String synced) {
        final String key = renderKey(songObj, plain, synced);
        final String songId = songIdOf(songObj);
        if (songId == null) return;
        // Captured now, before any frame is queued. A manual pick advances the
        // epoch, and every pending re-assert below then becomes a no-op instead
        // of painting stale/empty text over the pick.
        final long capturedEpoch = renderEpoch(songId);
        for (final int delay : settleRenderDelaysMs()) {
            try {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!isReassertCurrent(songId, capturedEpoch)) return;
                            Object latest = callbackForSong(songObj);
                            if (latest == null) latest = callback;
                            if (latest == null) return;
                            if (latest instanceof View && songObj != null) {
                                Object viewSong = getFieldUp(latest, "c");
                                Object viewId = viewSong == null ? null : getFieldUp(viewSong, "id");
                                Object songId = getFieldUp(songObj, "id");
                                if (viewId != null && songId != null
                                        && !String.valueOf(viewId).equals(String.valueOf(songId))) {
                                    return;
                                }
                            }
                            // Never overwrite a newer render (e.g. a finished
                            // translation) with our older text.
                            if (!isLatestRender(latest, key)) return;
                            // If real lyrics are already showing, stop.
                            if (lyricsViewStateForCallback(latest)
                                    == LyricsViewState.HAS_LYRICS) return;
                            Object response = buildResponse(songObj, plain, synced);
                            if (response == null) return;
                            invokeRender(latest, songObj,
                                    buildLines(response, plain, synced), response);
                        } catch (Throwable t) {
                            Log.d(TAG, "settle render failed: " + t);
                        }
                    }
                }, delay);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void executeFillDecision(Context ctx, Object songObj, SongInfo song,
                                            Object callback, SharedPreferences p,
                                            FillDecision decision) {
        if (decision.action == FillAction.FROM_CACHE && decision.hit != null) {
            commitCachedResult(ctx, songObj, callback, decision.hit, PRODUCTION_RENDER_SINK);
        } else if (decision.action == FillAction.SEARCH) {
            launchFillSearch(ctx, songObj, song, callback, p);
        }
    }

    /** Renders a positive cache hit without touching the network. */
    static void commitCachedResult(Context ctx, Object songObj, Object callback,
                                   Cached hit, LyricsRenderSink sink) {
        if (hit == null || sink == null) return;
        if (shouldSkipLateFillResult(songIdOf(songObj))) {
            Log.d(TAG, "cached fill result skipped: manual pick took precedence");
            return;
        }
        remember(hit.queryUsed, hit.source, hit.plain, hit.synced);
        try {
            SongInfo song = readSong(songObj);
            seedTranslationForResult(song, songObj, callback, hit.plain, hit.synced);
        } catch (Throwable ignored) {
        }
        sink.render(ctx, songObj, callback, hit.plain, hit.synced, hit.queryUsed);
    }

    /**
     * Commits a successful fill search: cache, translation source and render.
     * Package-private so tests can verify the commit without a network call.
     */
    static void commitFoundResult(Context ctx, Object songObj, SongInfo song,
                                  Object callback, Result result, LyricsRenderSink sink) {
        if (songObj == null || result == null || sink == null) return;
        if (shouldSkipLateFillResult(songIdOf(songObj))) {
            Log.d(TAG, "fill search result skipped: manual pick took precedence");
            return;
        }
        writePositiveCache(ctx, cacheKey(song), result);
        remember(result.queryUsed, result.source, result.plain, result.synced);
        seedTranslationForResult(song, songObj, callback, result.plain, result.synced);
        sink.render(ctx, songObj, callback, result.plain, result.synced, result.queryUsed);
    }

    /**
     * Adopts the fetched lyrics as the translation source without starting an
     * AI request here. renderOnMain starts auto-translation once the lines are
     * actually on screen, with the same text, so there is no double request.
     */
    static boolean seedTranslationForResult(SongInfo song, Object songObj,
                                            Object callbackObj, String plain,
                                            String synced) {
        try {
            if (song == null || songObj == null || callbackObj == null) return false;
            List<Line> parsed = parseLrc(synced);
            boolean useSynced = !parsed.isEmpty()
                    && (lastPreferSynced || plain == null || plain.trim().isEmpty());
            List<Line> lines = useSynced ? parsed : plainLines(plain);
            if (lines.isEmpty()) return false;
            SourceSnapshot snapshot = new SourceSnapshot();
            snapshot.songId = songKey(song);
            snapshot.synced = useSynced;
            snapshot.song = songObj;
            snapshot.callback = callbackObj;
            for (Line source : lines) {
                Line copy = new Line();
                copy.text = source.text == null ? "" : source.text;
                copy.ms = source.ms;
                snapshot.lines.add(copy);
            }
            return adoptSourceSnapshot(snapshot);
        } catch (Throwable t) {
            Log.d(TAG, "seedTranslationForResult failed: " + t);
            return false;
        }
    }

    private static void launchFillSearch(final Context ctx, final Object songObj,
                                         final SongInfo song, final Object callback,
                                         final SharedPreferences p) {
        final String queueKey = songKey(song);
        final String key = cacheKey(song);
        synchronized (LrclibFallback.class) {
            if (!fillInFlight.add(queueKey)) return;
            // Remember the epoch at search start; a manual pick that commits
            // while the network call is in flight must not be overwritten by
            // the result (or by its "no results" status line).
            fillSearchStartEpochs.put(queueKey, Long.valueOf(renderEpoch(queueKey)));
        }
        final Context appCtx = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        final SearchRenderState status = new SearchRenderState();
        // Clear the native loading state on the freshest callback first, then
        // paint a real status line over the spinner before any network work.
        // The state machine guarantees a final line replaces the status.
        if (needsNativeLoadingCleanup(true, lyricsViewStateForCallback(callback))) {
            clearNativeLoading(songObj, callback);
        }
        renderSearchStatus(songObj, callback, status.begin(queueKey));
        new Thread(new Runnable() {
            @Override
            public void run() {
                Result result = null;
                try {
                    result = waterfallForFill(song, p, new SearchProgress() {
                        @Override
                        public void update(String message) {
                            String line = status.progress(queueKey, message);
                            if (line != null) renderSearchStatus(songObj, callback, line);
                        }
                    });
                } catch (Throwable t) {
                    Log.d(TAG, "proactive fill search failed: " + t);
                } finally {
                    synchronized (LrclibFallback.class) {
                        fillInFlight.remove(queueKey);
                    }
                }
                final boolean pickWon = shouldSkipLateFillResult(queueKey);
                Object live = callbackForSong(songObj);
                Object target = live != null ? live : callback;
                if (result == null) {
                    if (!pickWon) {
                        writeNegativeCache(appCtx, key);
                        String message = status.miss(queueKey, tr("no_results_any"));
                        if (message != null) renderSearchStatus(songObj, target, message);
                    }
                    synchronized (LrclibFallback.class) {
                        fillSearchStartEpochs.remove(queueKey);
                    }
                    Log.d(TAG, "proactive fill: no match for " + song.id);
                    return;
                }
                status.success(queueKey, result.plain != null ? result.plain : result.synced);
                if (!pickWon) {
                    commitFoundResult(appCtx, songObj, song, target, result, PRODUCTION_RENDER_SINK);
                }
                synchronized (LrclibFallback.class) {
                    fillSearchStartEpochs.remove(queueKey);
                }
            }
        }).start();
    }

    /**
     * Preference-aware waterfall: only the selected sources, in configured
     * order, first usable result wins. Reuses the same per-source helpers as
     * manual search, so there is no duplicated network code.
     */
    private static Result waterfallForFill(SongInfo song, SharedPreferences p,
                                           SearchProgress progress) {
        waterfallEntriesForTest++;
        boolean[] selected = p == null ? parseSelectedSources(null) : loadSelectedSources(p);
        for (int i = 0; i < LYRIC_SOURCE_IDS.length && i < selected.length; i++) {
            if (!selected[i]) continue;
            String id = LYRIC_SOURCE_IDS[i];
            if (progress != null) {
                progress.update(String.format(Locale.ROOT, tr("trying_source"),
                        sourceLabel(id)));
            }
            Result r = fromSource(song, id);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * Hides the dead "Unlock full lyrics" teaser control from the current
     * lyrics view. Runs after the current render frame so native binding
     * cannot immediately make the button visible again.
     */
    private static void hideUnlockLyricsButton(Object callbackObj) {
        if (!(callbackObj instanceof View)) return;
        final View root = (View) callbackObj;
        final Runnable hide = new Runnable() {
            @Override
            public void run() {
                hideUnlockLyricsInTree(root, root);
            }
        };
        Handler main = new Handler(Looper.getMainLooper());
        main.post(hide);
        main.postDelayed(hide, 250);
    }

    private static void hideUnlockLyricsInTree(View view, View root) {
        if (view.getVisibility() == View.GONE) return;
        boolean matches = isUnlockLyricsLabel(view.getContentDescription());
        if (view instanceof TextView) {
            matches = matches || isUnlockLyricsLabel(((TextView) view).getText());
        }
        if (matches) {
            View target = view;
            ViewParent parent = view.getParent();
            for (int depth = 0; depth < 3 && parent instanceof View; depth++) {
                View candidate = (View) parent;
                if (candidate == root) break;
                if (candidate.isClickable() || candidate.isFocusable()) {
                    target = candidate;
                    break;
                }
                parent = candidate.getParent();
            }
            target.setEnabled(false);
            target.setClickable(false);
            target.setVisibility(View.GONE);
            Log.d(TAG, "hid Unlock full lyrics button");
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                hideUnlockLyricsInTree(group.getChildAt(i), root);
            }
        }
    }

    private static boolean isUnlockLyricsLabel(CharSequence label) {
        if (label == null || label.length() == 0) return false;
        String text = normalize(label.toString(), false);
        return (text.contains("unlock") && text.contains("lyric"))
                || (text.contains("debloquer") && text.contains("parole"))
                || (text.contains("ouvrir") && text.contains("parole"))
                || (text.contains("فتح") && text.contains("كلمات"));
    }

    /**
     * Forces per-song flags at the data level (not a consumer workaround).
     * The server song payload carries {@code hasLyrics=false} for
     * lyrics-less songs, and {@code A7/C} actively re-clears it on empty
     * lyrics responses — so nopping each reader (button gate, mode
     * observer, ...) was whack-a-mole. Setting the flag at every trampoline
     * (W0, onNext, onError, mode observer) makes all native checks see
     * lyrics available; the fallback itself still only renders when the
     * server response is truncated/empty. {@code hasKaraoke} is forced
     * false the same way (song-level kill-switch; GSON sets the field
     * directly so there is no method to hook — same treatment as lyrics).
     * Null-safe, reflection-only, never crashes the host.
     */
    public static void forceSongFlags(Object songObj) {
        try {
            if (songObj == null) return;
            Field f = fieldUp(songObj.getClass(), "hasLyrics");
            if (f != null && f.getType() == Boolean.TYPE && !f.getBoolean(songObj)) {
                f.setBoolean(songObj, true);
                Log.d(TAG, "hasLyrics forced true");
            }
            Field k = fieldUp(songObj.getClass(), "hasKaraoke");
            if (k != null && k.getType() == Boolean.TYPE && k.getBoolean(songObj)) {
                k.setBoolean(songObj, false);
                Log.d(TAG, "hasKaraoke forced false");
            }
        } catch (Throwable t) {
            Log.d(TAG, "forceSongFlags failed: " + t);
        }
    }

    private static boolean shouldRetryMiss(String key) {
        return shouldRetryMiss(key, System.currentTimeMillis());
    }

    /** Time-injected variant used by the pure decision helper and its tests. */
    static boolean shouldRetryMiss(String key, long nowMs) {
        synchronized (LrclibFallback.class) {
            Long last = missRetryTimes.get(key);
            if (last != null && nowMs - last < MISS_RETRY_COOLDOWN_MS) {
                return false;
            }
            missRetryTimes.put(key, nowMs);
            return true;
        }
    }

    private static void retryMissInBackground(final Context ctx, final Object songObj,
                                              final SongInfo song, final Object callback,
                                              final String key) {
        final String songId = songIdOf(songObj);
        final long startEpoch = renderEpoch(songId);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Result r;
                try {
                    r = waterfall(song, new SearchProgress() {
                        @Override
                        public void update(String message) {
                            renderSearchStatus(songObj, callback, message);
                        }
                    });
                } catch (Throwable t) {
                    return;
                }
                if (r == null) return;
                // A manual pick that landed during this background retry owns
                // the screen now; do not paint a late cache-derived result.
                if (songId != null && !isReassertCurrent(songId, startEpoch)) return;
                writePositiveCache(ctx, key, r);
                remember(r.queryUsed, r.source, r.plain, r.synced);
                // renderOnMain resolves the live callback and starts automatic
                // translation when it is enabled.
                renderOnMain(ctx, songObj, callback, r.plain, r.synced, r.queryUsed, false);
            }
        }).start();
    }

    private static void impl(Object songObj, Object responseObj, Object callbackObj) {
        try {
            if (songObj == null || callbackObj == null) return;
            // Already served via substituteCached (E.b entry rewrote this
            // response pre-render): re-flag truncated so the native
            // saveLyrics flow keeps refusing it, then stop. StoredLyrics
            // is never written; our prefs cache stays the only source.
            if (unmarkSubstituted(responseObj)) return;
            boolean serverEmpty = true;
            if (responseObj != null) {
                if (isFullNativeResponse(responseObj)) return; // Plus/native path: untouched.
                serverEmpty = isEmptyResponse(responseObj);
            }

            Context ctx = appContextOf(callbackObj);
            if (ctx == null) return;
            SongInfo song = readSong(songObj);
            if (song == null || song.title.isEmpty()) return;

            synchronized (LrclibFallback.class) {
                lastSong = songObj;
                lastCallback = callbackObj;
                lastOriginalResponse = responseObj;
                lastPlain = null;
                lastSynced = null;
            }

            final String key = cacheKey(song);
            Cached hit = lookupCache(ctx, song, key);
            if (hit != null) {
                if (hit.miss) {
                    Log.d(TAG, "negative cache hit for songId=" + song.id);
                    // "No lyrics found" is not final: keep searching in the
                    // background so a source that came online later updates
                    // the screen without the user re-opening the song.
                    final Context retryCtx = ctx.getApplicationContext() != null
                            ? ctx.getApplicationContext() : ctx;
                    if (shouldRetryMiss(key)) {
                        retryMissInBackground(retryCtx, songObj, song, callbackObj, key);
                    }
                    // Still guide the user: the server view alone (teaser
                    // or empty) never mentions the Settings options.
                    // Full native responses returned above, so this is a
                    // truncated teaser or nothing — safe to own the screen.
                    if (serverEmpty) {
                        renderOnMain(ctx, songObj, callbackObj,
                                tr("no_result"), null, "no-result", false);
                    } else if (responseObj != null) {
                        renderTeaserWithGuide(ctx, songObj, callbackObj, responseObj);
                    }
                    return;
                }
                remember(hit.queryUsed, hit.source, hit.plain, hit.synced);
                renderOnMain(ctx, songObj, callbackObj, hit.plain, hit.synced, hit.queryUsed, true);
                return;
            }

            if (serverEmpty) {
                // Own the screen immediately: empty state becomes a loading
                // placeholder instead of the song-has-no-lyrics path.
                renderOnMain(ctx, songObj, callbackObj,
                        tr("searching"), null, "placeholder", false);
            }

            final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            final Object songRef = songObj;
            final Object cbRef = callbackObj;
            final SongInfo songFinal = song;
            final String keyFinal = key;
            final boolean serverEmptyFinal = serverEmpty;
            final SearchProgress progress = new SearchProgress() {
                @Override
                public void update(String message) {
                    // Refresh the lyrics view as each provider is attempted,
                    // so "no lyrics" is visibly followed by the next try.
                    renderSearchStatus(songRef, cbRef, message);
                }
            };
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Result r;
                    try {
                        r = waterfall(songFinal, progress);
                    } catch (RateLimited rl) {
                        Log.d(TAG, "rate limited, keeping server view for songId=" + songFinal.id);
                        return;
                    } catch (Throwable t) {
                        Log.d(TAG, "waterfall failed: " + t);
                        return;
                    }
                    if (r == null) {
                        writeNegativeCache(appCtx, keyFinal);
                        Log.d(TAG, "no-match for songId=" + songFinal.id);
                        if (serverEmptyFinal) {
                            renderOnMain(appCtx, songRef, cbRef,
                                    tr("no_result"), null, "no-result", false);
                        } else {
                            // Server holds a (truncated) teaser but LRCLIB
                            // has nothing: keep the teaser visible and
                            // append the Settings guide. The unlock
                            // button is a native no-op (A5/t.onClick
                            // returns void), so the guide is the only way
                            // to the manual search.
                            renderTeaserWithGuide(appCtx, songRef, cbRef, responseObj);
                        }
                        return;
                    }
                    writePositiveCache(appCtx, keyFinal, r);
                    remember(r.queryUsed, r.source, r.plain, r.synced);
                    renderOnMain(appCtx, songRef, cbRef, r.plain, r.synced, r.queryUsed, true);
                }
            }).start();
        } catch (Throwable t) {
            Log.d(TAG, "maybeFetch failed: " + t);
        }
    }

    /** Currently playing song, or null. Reflection-only, never throws. */
    private static Object currentPlayingSong() {
        try {
            Class<?> pqm = Class.forName("com.anghami.odin.playqueue.PlayQueueManager");
            Object inst = pqm.getMethod("getSharedInstance").invoke(null);
            if (inst == null) return null;
            return pqm.getMethod("getCurrentSong").invoke(inst);
        } catch (Throwable t) {
            return null;
        }
    }

    // ================= truncated suppression =================

    /**
     * Responses rewritten by {@link #substituteCached} (identity set).
     * {@link #impl} re-flags them truncated so native saveLyrics keeps
     * refusing them.
     */
    private static final java.util.Set<Object> substituted =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Object, Boolean>());

    /** ID-primary lookup with legacy-key fallback (pre-id-key entries). */
    private static Cached lookupCache(Context ctx, SongInfo song, String key) {
        return lookupCache(prefs(ctx), song, key);
    }

    private static Cached lookupCache(SharedPreferences p, SongInfo song, String key) {
        Cached hit = readCache(p, key);
        if (hit == null) {
            String legacy = legacyCacheKey(song);
            if (!legacy.equals(key)) hit = readCache(p, legacy);
        }
        return hit;
    }

    private static boolean hasUsableLyrics(Cached hit) {
        return hit != null && !hit.miss
                && ((hit.plain != null && !hit.plain.isEmpty())
                || (hit.synced != null && !hit.synced.isEmpty()));
    }

    /**
     * {@code A7/E.b} entry: when the server response is truncated/empty
     * and we already hold LRCLIB lyrics for this song, rewrite the
     * response in place BEFORE native renders it — the teaser is never
     * shown in any way, on first open or revisit. Full native/Plus
     * responses and cache misses are untouched. All decisions live here
     * so the trampoline stays a branch-free static call.
     */
    public static void substituteCached(Object songObj, Object responseObj, Object callbackObj) {
        try {
            if (songObj == null || responseObj == null || callbackObj == null) return;
            if (isFullNativeResponse(responseObj)) {
                // Native lyrics are already on screen; the proactive fill must
                // not overwrite them with a source result on the next resume.
                markNativeLyricsRendered(songObj);
                return;
            }
            // This song now shows a truncated teaser or nothing: any earlier
            // full-native render is no longer visible.
            clearNativeLyricsRendered(songObj);
            Context ctx = appContextOf(callbackObj);
            if (ctx == null) return;
            SongInfo song = readSong(songObj);
            if (song == null || song.id.isEmpty()) return;
            Cached hit = lookupCache(ctx, song, cacheKey(song));
            if (!hasUsableLyrics(hit)) return;
            boolean wantSynced = lastPreferSynced && hit.synced != null && !hit.synced.isEmpty();
            if (!wantSynced && (hit.plain == null || hit.plain.isEmpty()) && hit.synced != null) {
                wantSynced = true; // only synced available.
            }
            if (wantSynced) {
                List<Object> lines = buildSyncedLines(hit.synced);
                if (lines.isEmpty()) {
                    if (hit.plain == null || hit.plain.isEmpty()) return;
                    wantSynced = false;
                } else {
                    setLineField(responseObj, "lyricsSynced", lines);
                    setLineField(responseObj, "lyricsUnsynced", null);
                    setLineField(responseObj, "isSynced", true);
                }
            }
            if (!wantSynced) {
                setLineField(responseObj, "lyricsSynced", null);
                setLineField(responseObj, "lyricsUnsynced", hit.plain);
                setLineField(responseObj, "isSynced", false);
            }
            setLineField(responseObj, "truncated", false);
            remember(hit.queryUsed, hit.source, hit.plain, hit.synced);
            synchronized (LrclibFallback.class) {
                lastSong = songObj;
                lastCallback = callbackObj;
                lastOriginalResponse = responseObj;
                substituted.add(responseObj);
            }
            rememberNativeLyrics(songObj, responseObj, callbackObj);
            Log.d(TAG, "substituted cached (" + hit.queryUsed + ")");
        } catch (Throwable t) {
            Log.d(TAG, "substituteCached failed: " + t);
        }
    }

    /**
     * Lyrics-view load entry ({@code com.anghami.ui.view.A.h}): paints
     * cached LRCLIB lyrics instantly without waiting for the DB/API
     * round-trip. Miss = no-op, native proceeds. The later API response
     * is rewritten to the same content by {@link #substituteCached}, so
     * there is no visible change when it lands.
     */
    public static void paintCached(Object viewObj) {
        try {
            if (!(viewObj instanceof View)) return;
            final View view = (View) viewObj;
            final Context ctx = view.getContext();
            if (ctx == null) return;
            final Object songObj;
            try {
                songObj = getFieldUp(viewObj, "c");
            } catch (Exception e) {
                return;
            }
            if (songObj == null) return;
            SongInfo song = readSong(songObj);
            if (song == null || song.id.isEmpty()) return;
            Cached hit = lookupCache(ctx, song, cacheKey(song));
            if (!hasUsableLyrics(hit)) return;
            remember(hit.queryUsed, hit.source, hit.plain, hit.synced);
            synchronized (LrclibFallback.class) {
                lastSong = songObj;
                lastCallback = viewObj;
                lastOriginalResponse = null;
            }
            renderOnMain(ctx, songObj, viewObj, hit.plain, hit.synced,
                    hit.queryUsed + " early", true);
        } catch (Throwable t) {
            Log.d(TAG, "paintCached failed: " + t);
        }
    }

    /**
     * True when this response was rewritten by {@link #substituteCached}.
     * Re-flags it truncated so native saveLyrics refuses it (our prefs
     * cache stays the only source of truth), so the caller can stop.
     */
    private static boolean unmarkSubstituted(Object responseObj) {
        try {
            if (responseObj == null) return false;
            boolean mine;
            synchronized (LrclibFallback.class) {
                mine = substituted.remove(responseObj);
            }
            if (!mine) return false;
            Field f = fieldUp(responseObj.getClass(), "truncated");
            if (f != null && f.getType() == Boolean.TYPE) f.setBoolean(responseObj, true);
            Log.d(TAG, "substituted response unmarked (save skipped)");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * LRCLIB miss while the server holds a (truncated) teaser: re-render
     * the teaser with the Settings guide appended, so the user can
     * still reach the manual search. Plain path only.
     */
    private static void renderTeaserWithGuide(Context ctx, Object songObj,
                                              Object callbackObj, Object responseObj) {
        try {
            if (hasConfiguredAutomaticTranslation(ctx)) {
                renderOnMain(ctx, songObj, callbackObj,
                        tr("no_result"), null, "no-result", false);
                return;
            }
            String teaser = teaserText(responseObj);
            if (teaser == null || teaser.trim().isEmpty()) {
                renderOnMain(ctx, songObj, callbackObj,
                        tr("no_result"), null, "no-result", false);
                return;
            }
            renderOnMain(ctx, songObj, callbackObj,
                    teaser + "\n\n" + tr("guide_teaser"), null, "teaser-guide", false);
        } catch (Throwable t) {
            Log.d(TAG, "renderTeaserWithGuide failed: " + t);
        }
    }

    /** Best-effort teaser text out of a truncated server response. */
    private static String teaserText(Object responseObj) {
        try {
            String unsynced = getString(responseObj, "lyricsUnsynced");
            if (unsynced != null && !unsynced.trim().isEmpty()) return unsynced;
            List<?> synced = getList(responseObj, "lyricsSynced");
            if (synced == null || synced.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Object line : synced) {
                try {
                    Object text = getFieldUp(line, "line");
                    if (text instanceof String && !((String) text).trim().isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append((String) text);
                    }
                } catch (Exception ignored) {
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parsed LRC as native LyricsLine objects (line + milliseconds). */
    private static List<Object> buildSyncedLines(String synced) {
        List<Object> out = new ArrayList<Object>();
        try {
            List<Line> parsed = parseLrc(synced);
            if (parsed.isEmpty()) return out;
            Class<?> lc = Class.forName("com.anghami.ghost.pojo.LyricsLine");
            for (Line l : parsed) {
                Object o = lc.newInstance();
                setLineField(o, "line", l.text);
                setLineField(o, "milliseconds", l.ms);
                out.add(o);
            }
        } catch (Throwable t) {
            Log.d(TAG, "buildSyncedLines failed: " + t);
        }
        return out;
    }

    // ================= response inspection =================

    private static boolean isFullNativeResponse(Object response) throws Exception {
        if (getBoolean(response, "truncated")) return false;
        List<?> synced = getList(response, "lyricsSynced");
        if (synced != null && !synced.isEmpty()) return true;
        String unsynced = getString(response, "lyricsUnsynced");
        return unsynced != null && !unsynced.trim().isEmpty();
    }

    private static boolean isEmptyResponse(Object response) {
        List<?> synced = getList(response, "lyricsSynced");
        if (synced != null && !synced.isEmpty()) return false;
        String unsynced = getString(response, "lyricsUnsynced");
        return unsynced == null || unsynced.trim().isEmpty();
    }

    // ================= song / reflection =================

    static final class SongInfo {
        String id = "";
        String title = "";
        String artist = "";
        float duration;
        boolean hasDuration;
    }

    private static SongInfo readSong(Object songObj) throws Exception {
        SongInfo s = new SongInfo();
        Object id = getFieldUp(songObj, "id");
        Object title = getFieldUp(songObj, "title");
        Object artist = getFieldUp(songObj, "artistName");
        Object dur = getFieldUp(songObj, "duration");
        // Some Anghami builds expose the id as a Number, others as a String.
        // Normalize here so cache keys, songKey and snapshot ids can never
        // disagree about which song is on screen.
        if (id instanceof String) s.id = ((String) id).trim();
        else if (id instanceof Number) s.id = String.valueOf(id);
        if (title instanceof String) s.title = ((String) title).trim();
        if (artist instanceof String) s.artist = ((String) artist).trim();
        if (dur instanceof Number) {
            s.duration = ((Number) dur).floatValue();
            s.hasDuration = s.duration > 0;
        }
        return s;
    }

    private static String songKey(SongInfo song) {
        if (song.id != null && !song.id.isEmpty()) return song.id;
        return normalize(song.artist, true) + "|" + normalize(song.title, true);
    }

    private static List<Line> plainLines(String plain) {
        List<Line> out = new ArrayList<Line>();
        if (plain == null || plain.trim().isEmpty()) return out;
        String[] parts = plain.split("\\r?\\n", -1);
        for (String part : parts) {
            Line line = new Line();
            line.text = part;
            line.ms = -1;
            out.add(line);
        }
        return out;
    }

    private static boolean setTranslationSource(SongInfo song, Object songObj,
                                                Object callbackObj, List<Line> lines,
                                                boolean synced) {
        if (song == null || songObj == null || callbackObj == null) return false;
        synchronized (LrclibFallback.class) {
            translationSongId = songKey(song);
            translationSong = songObj;
            translationCallback = callbackObj;
            translationSourceSynced = synced;
            translationSourceLines.clear();
            if (lines != null) {
                for (Line source : lines) {
                    if (source == null) continue;
                    Line copy = new Line();
                    copy.text = source.text == null ? "" : source.text;
                    copy.ms = source.ms;
                    translationSourceLines.add(copy);
                }
            }
        }
        if (lines != null && !lines.isEmpty()) {
            Context ctx = appContextOf(callbackObj);
            if (ctx != null) return autoTranslateIfEnabled(ctx, songObj);
        }
        return false;
    }

    static final class SourceSnapshot {
        String songId;
        boolean synced;
        Object song;
        Object callback;
        List<Line> lines = new ArrayList<Line>();
    }

    private static SourceSnapshot translationSourceFor(Object songObj) {
        return translationSourceFor(translationContext(songObj), songObj);
    }

    /**
     * Source used by every translation entry point. A live source captured from
     * the rendered lyrics response always wins (so an already-translating song
     * is never translated twice). When no live source exists — for example a
     * "Translate now" request before the lyrics view rendered, or after it was
     * recreated — the already-cached lyrics are used instead. Reading the cache
     * never searches the lyrics websites.
     */
    static SourceSnapshot translationSourceFor(Context ctx, Object songObj) {
        return translationSourceFor(ctx == null ? null : prefs(ctx), songObj);
    }

    static SourceSnapshot translationSourceFor(SharedPreferences p, Object songObj) {
        SourceSnapshot live = liveSourceSnapshot(songObj);
        if (live != null) return live;
        // If a live source for a *different* song is current, never swap in a
        // cached source: the caller's "source changed" check must still fire.
        try {
            SongInfo song = readSong(songObj);
            if (song != null) {
                String wanted = songKey(song);
                synchronized (LrclibFallback.class) {
                    if (!translationSongId.isEmpty() && !translationSongId.equals(wanted)) {
                        return null;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // Keep the request's snapshot authoritative while a translation is in
        // flight: a cache re-read during that window can differ in timestamps
        // or line metadata without changing the lyrics the user sees.
        try {
            SongInfo song = readSong(songObj);
            if (song != null) {
                SourceSnapshot inFlight = inFlightSnapshot(songKey(song));
                if (inFlight != null) {
                    SourceSnapshot stable = copySnapshot(inFlight);
                    Object liveCallback = callbackForSong(songObj);
                    if (liveCallback != null) stable.callback = liveCallback;
                    return stable;
                }
            }
        } catch (Throwable ignored) {
        }
        SourceSnapshot cached = cachedSourceSnapshot(p, songObj);
        if (cached == null || cached.callback == null) return null;
        // Adopt the cached lines as the current source so the consistency
        // checks inside translateAndRender / renderOriginalLyricsIfCurrent see
        // the same text without another cache read or a new lyrics request.
        adoptSourceSnapshot(cached);
        return cached;
    }

    /** Snapshot a translation request started with, while it is in flight. */
    static SourceSnapshot inFlightSnapshot(String songId) {
        synchronized (LrclibFallback.class) {
            return inFlightSnapshots.get(songId);
        }
    }

    private static SourceSnapshot copySnapshot(SourceSnapshot source) {
        SourceSnapshot copy = new SourceSnapshot();
        copy.songId = source.songId;
        copy.synced = source.synced;
        copy.song = source.song;
        copy.callback = source.callback;
        for (Line line : source.lines) {
            Line clone = new Line();
            clone.text = line.text;
            clone.ms = line.ms;
            copy.lines.add(clone);
        }
        return copy;
    }

    /** In-memory source captured from the rendered lyrics response, if current. */
    private static SourceSnapshot liveSourceSnapshot(Object songObj) {
        try {
            if (songObj == null) return null;
            SongInfo song = readSong(songObj);
            if (song == null) return null;
            String wantedId = songKey(song);
            synchronized (LrclibFallback.class) {
                if (!wantedId.equals(translationSongId)
                        || translationCallback == null
                        || translationSourceLines.isEmpty()) return null;
                SourceSnapshot snapshot = new SourceSnapshot();
                snapshot.songId = translationSongId;
                snapshot.synced = translationSourceSynced;
                snapshot.song = translationSong;
                snapshot.callback = translationCallback;
                for (Line source : translationSourceLines) {
                    Line copy = new Line();
                    copy.text = source.text;
                    copy.ms = source.ms;
                    snapshot.lines.add(copy);
                }
                return snapshot;
            }
        } catch (Throwable t) {
            Log.d(TAG, "liveSourceSnapshot failed: " + t);
            return null;
        }
    }

    /** Context of the live lyrics callback, used to read the fallback cache. */
    private static Context translationContext(Object songObj) {
        Context ctx = appContextOf(callbackForSong(songObj));
        if (ctx != null) return ctx;
        synchronized (LrclibFallback.class) {
            ctx = appContextOf(translationCallback);
            if (ctx != null) return ctx;
            return appContextOf(lastCallback);
        }
    }

    /**
     * Builds a translation source from a positive, non-stale cached lyrics
     * entry. No network call is made here — only the local SharedPreferences
     * cache is consulted. Package-private so unit tests can verify the
     * cache-to-snapshot conversion (including LRC timestamps and line count).
     */
    static SourceSnapshot cachedSourceSnapshot(Context ctx, Object songObj) {
        return cachedSourceSnapshot(ctx == null ? null : prefs(ctx), songObj);
    }

    static SourceSnapshot cachedSourceSnapshot(SharedPreferences p, Object songObj) {
        if (p == null || songObj == null) return null;
        try {
            SongInfo song = readSong(songObj);
            if (song == null) return null;
            Cached hit = lookupCache(p, song, cacheKey(song));
            if (!hasUsableLyrics(hit)) return null;

            boolean synced = hit.synced != null && !hit.synced.isEmpty()
                    && (lastPreferSynced || hit.plain == null || hit.plain.isEmpty());
            List<Line> lines;
            if (synced) {
                lines = parseLrc(hit.synced);
                if (lines.isEmpty()) {
                    synced = false;
                    lines = plainLines(hit.plain);
                }
            } else {
                lines = plainLines(hit.plain);
                if (lines.isEmpty() && hit.synced != null && !hit.synced.isEmpty()) {
                    lines = parseLrc(hit.synced);
                    synced = !lines.isEmpty();
                }
            }
            if (lines.isEmpty()) return null;

            SourceSnapshot snapshot = new SourceSnapshot();
            snapshot.songId = songKey(song);
            snapshot.synced = synced;
            snapshot.song = songObj;
            snapshot.callback = callbackForSong(songObj);
            for (Line source : lines) {
                Line copy = new Line();
                copy.text = source.text == null ? "" : source.text;
                copy.ms = source.ms;
                snapshot.lines.add(copy);
            }
            return snapshot;
        } catch (Throwable t) {
            Log.d(TAG, "cachedSourceSnapshot failed: " + t);
            return null;
        }
    }

    /**
     * Adopts a cache-derived source without re-triggering auto-translation.
     * A snapshot belonging to a song with a translation in flight is never
     * replaced: that request's lines are the ones it will render against.
     */
    private static boolean adoptSourceSnapshot(SourceSnapshot snapshot) {
        if (snapshot == null) return false;
        synchronized (LrclibFallback.class) {
            if (inFlightSnapshots.containsKey(snapshot.songId)) return false;
            translationSongId = snapshot.songId;
            translationSong = snapshot.song;
            translationCallback = snapshot.callback;
            translationSourceSynced = snapshot.synced;
            translationSourceLines.clear();
            for (Line source : snapshot.lines) {
                Line copy = new Line();
                copy.text = source.text == null ? "" : source.text;
                copy.ms = source.ms;
                translationSourceLines.add(copy);
            }
            return true;
        }
    }

    /**
     * Song the "Translate now" action must act on. Uses the same precedence as
     * the rendered view: the live player song, then the song the dialog was
     * opened for, then the last song whose lyrics were actually rendered.
     * Package-private so tests can call it without a running player.
     */
    static Object translateNowSong(Object dialogSong, Object fragmentObj) {
        Object activeFragment = fragmentObj != null
                ? fragmentObj
                : playerFragment == null ? null : playerFragment.get();
        Object target = currentSongFromPlayer(activeFragment);
        if (target == null) target = dialogSong;
        if (target == null) {
            synchronized (LrclibFallback.class) {
                target = lastSong;
            }
        }
        return target;
    }

    /**
     * Source for "Translate now", resolved from the same identity and cache
     * state the display path used. Unlike {@link #translationSourceFor} a stale
     * translationSongId or an unresolved weak callback never hides lyrics that
     * are already on screen: a positive cache hit for the target song wins and
     * is adopted as the current source. Package-private for tests.
     */
    static SourceSnapshot translateNowSource(SharedPreferences p, Object target) {
        if (target == null) {
            logTranslation("translateNowSource: no song from player/dialog/lastSong");
            return null;
        }
        SongInfo song;
        String wanted;
        String key;
        try {
            song = readSong(target);
            if (song == null) return null;
            wanted = songKey(song);
            key = cacheKey(song);
        } catch (Throwable t) {
            Log.d(TAG, "translateNowSource failed to read song: " + t);
            return null;
        }
        SourceSnapshot live = liveSourceSnapshot(target);
        if (live != null) return live;

        // This is the exact key paintCached/substituteCached wrote. A hit means
        // these lyrics are (or were) rendered for this song, so translation must
        // proceed even when the in-memory translation state belongs to another
        // song or was cleared by a view recreation.
        Cached hit = p == null ? null : lookupCache(p, song, key);
        if (!hasUsableLyrics(hit)) {
            logTranslation("translateNowSource: no live source, no cached lyrics for "
                    + wanted + " (cacheKey=" + key + ")");
            return null;
        }
        SourceSnapshot cached = cachedSourceSnapshot(p, target);
        if (cached == null || cached.lines.isEmpty()) {
            logTranslation("translateNowSource: cache hit had no usable lines for "
                    + wanted + " (cacheKey=" + key + ")");
            return null;
        }
        if (cached.callback == null) {
            logTranslation("translateNowSource: cached lyrics for " + wanted
                    + " have no live callback; render will re-resolve the visible view");
        }
        // Seed the render state from the displayed/cached source so the
        // consistency checks in translateAndRender see the same text.
        adoptSourceSnapshot(cached);
        logTranslation("translateNowSource: using cached lyrics for " + wanted
                + " (cacheKey=" + key + ", lines=" + cached.lines.size()
                + ", callback=" + (cached.callback != null) + ")");
        return cached;
    }

    /** Convenience overload: derive the cache context from the caller or a live View. */
    static SourceSnapshot translateNowSource(Context ctx, Object target) {
        Context sourceCtx = ctx != null ? ctx : translationContext(target);
        SharedPreferences p = sourceCtx == null ? null : prefs(sourceCtx);
        return translateNowSource(p, target);
    }

    /**
     * Callback to actually paint into at render time: the freshest one bound
     * to this song, or the captured fallback. Both scheduling and painting
     * re-resolve through here so a view recreated in between still receives
     * the lyrics. Package-private for tests.
     */
    static Object paintCallback(Object songObj, Object fallback) {
        Object live = callbackForSong(songObj);
        return live != null ? live : fallback;
    }

    static Object callbackForSong(Object songObj) {
        try {
            if (songObj == null) return null;
            SongInfo song = readSong(songObj);
            if (song == null) return null;
            String wantedId = songKey(song);
            synchronized (LrclibFallback.class) {
                WeakReference<Object> reference = lyricsCallbacks.get(wantedId);
                Object remembered = reference == null ? null : reference.get();
                if (remembered != null) return remembered;
                if (reference != null) lyricsCallbacks.remove(wantedId);
                if (lastCallback != null && lastSong != null) {
                    SongInfo last = readSong(lastSong);
                    if (last != null && wantedId.equals(songKey(last))) return lastCallback;
                }
                // The last rendered callback is often a live lyrics View whose
                // own song field was refreshed with a slightly different song
                // object (id format/feat. suffix). Match on its identity before
                // giving up — otherwise a cache hit is wrongly reported as
                // "no lyrics" even though those lyrics are on screen.
                if (lastCallback instanceof View) {
                    try {
                        Object viewSong = getFieldUp(lastCallback, "c");
                        if (viewSong != null) {
                            SongInfo fromView = readSong(viewSong);
                            if (fromView != null && wantedId.equals(songKey(fromView))) {
                                return lastCallback;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (translationCallback != null && wantedId.equals(translationSongId)) {
                    return translationCallback;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Field fieldUp(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethodUp(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Object getFieldUp(Object o, String name) throws Exception {
        Field f = fieldUp(o.getClass(), name);
        return f == null ? null : f.get(o);
    }

    private static String getString(Object o, String name) {
        try {
            Object v = getFieldUp(o, name);
            return v instanceof String ? (String) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<?> getList(Object o, String name) {
        try {
            Object v = getFieldUp(o, name);
            return v instanceof List ? (List<?>) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean getBoolean(Object o, String name) {
        try {
            Object v = getFieldUp(o, name);
            return v instanceof Boolean && (Boolean) v;
        } catch (Exception e) {
            return false;
        }
    }

    private static Context appContextOf(Object callbackObj) {
        try {
            if (callbackObj instanceof View) return ((View) callbackObj).getContext();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ================= normalize =================

    private static final Pattern FEAT_RX =
            Pattern.compile("(?i)\\s+[\\(\\[]?(feat\\.?|ft\\.?|featuring|x|with|&)\\b.*$");
    private static final Pattern PAREN_RX = Pattern.compile("\\s*[\\(\\[].*?[\\)\\]]");
    private static final Pattern PUNCT_RX = Pattern.compile("[^\\p{L}\\p{N} ]+");
    private static final Pattern SPACE_RX = Pattern.compile("\\s+");

    static String normalize(String s, boolean stripParens) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
        t = FEAT_RX.matcher(t).replaceAll("");
        if (stripParens) t = PAREN_RX.matcher(t).replaceAll("");
        t = PUNCT_RX.matcher(t).replaceAll(" ");
        t = SPACE_RX.matcher(t).replaceAll(" ").trim();
        return t;
    }

    // ================= localization =================

    /**
     * All user-visible strings added by this fallback, in every language
     * the app ships (values=en, values-ar, values-fr). Runtime map —
     * the extension adds no res/ resources, so native strings stay
     * untouched and ours follow the device locale.
     */
    static String tr(String key) {
        String lang = "";
        try {
            lang = Locale.getDefault().getLanguage();
        } catch (Exception ignored) {
        }
        boolean ar = "ar".equalsIgnoreCase(lang);
        boolean fr = "fr".equalsIgnoreCase(lang);

        if ("no_result".equals(key)) {
            if (ar) return "لم يتم العثور على كلمات.\nاضغط مطولاً على زر الكلمات لخيارات البحث.";
            if (fr) return "Paroles introuvables.\nAppuyez longuement sur Paroles pour les options de recherche.";
            return "No lyrics found.\nLong-press the Lyrics button for search options.";
        }
        if ("guide_teaser".equals(key)) {
            if (ar) return "اضغط مطولاً على زر الكلمات لخيارات الكلمات.";
            if (fr) return "Appuyez longuement sur Paroles pour les options des paroles.";
            return "Long-press the Lyrics button for lyrics options.";
        }
        if ("searching".equals(key)) {
            if (ar) return "جارٍ البحث في LRCLIB…";
            if (fr) return "Recherche sur LRCLIB…";
            return "Searching LRCLIB…";
        }
        if ("no_loaded".equals(key)) {
            if (ar) return "لا توجد كلمات بعد";
            if (fr) return "Aucune parole chargée";
            return "No lyrics loaded yet";
        }
        if ("no_fallback".equals(key)) {
            if (ar) return "لا يوجد بديل لهذه الأغنية";
            if (fr) return "Aucune alternative pour ce titre";
            return "No fallback active for this song";
        }
        if ("hint_artist".equals(key)) {
            if (ar) return "الفنان";
            if (fr) return "Artiste";
            return "Artist";
        }
        if ("hint_title".equals(key)) {
            if (ar) return "العنوان";
            if (fr) return "Titre";
            return "Title";
        }
        if ("clear_cache".equals(key)) {
            if (ar) return "مسح الكلمات المحفوظة";
            if (fr) return "Effacer les paroles en cache";
            return "Clear cached lyrics";
        }
        if ("search".equals(key)) {
            if (ar) return "البحث في LRCLIB";
            if (fr) return "Chercher sur LRCLIB";
            return "Search LRCLIB";
        }
        if ("search_all_sources".equals(key)) {
            if (ar) return "البحث في كل المصادر";
            if (fr) return "Rechercher dans toutes les sources";
            return "Search lyrics";
        }
        if ("dialog_title".equals(key)) {
            if (ar) return "مصدر الكلمات";
            if (fr) return "Source des paroles";
            return "Lyrics source";
        }
        if ("retry".equals(key)) {
            if (ar) return "إعادة المحاولة";
            if (fr) return "Réessayer";
            return "Retry";
        }
        if ("show_plain".equals(key)) {
            if (ar) return "عرض بدون توقيت";
            if (fr) return "Afficher sans synchro";
            return "Show plain";
        }
        if ("show_synced".equals(key)) {
            if (ar) return "عرض بالتوقيت";
            if (fr) return "Afficher synchronisé";
            return "Show synced";
        }
        if ("server_version".equals(key)) {
            if (ar) return "نسخة السيرفر";
            if (fr) return "Version du serveur";
            return "Server version";
        }
        if ("pick".equals(key)) {
            if (ar) return "اختر الكلمات";
            if (fr) return "Choisir les paroles";
            return "Pick lyrics";
        }
        if ("no_results".equals(key)) {
            if (ar) return "لا نتائج في LRCLIB";
            if (fr) return "Aucun résultat LRCLIB";
            return "No LRCLIB results";
        }
        if ("no_results_any".equals(key)) {
            if (ar) return "لا نتائج في المصادر المحددة";
            if (fr) return "Aucun résultat dans les sources sélectionnées";
            return "No results from the selected sources";
        }
        if ("empty_record".equals(key)) {
            if (ar) return "تسجيلة فارغة، اختر غيرها";
            if (fr) return "Entrée vide, choisis-en une autre";
            return "Empty record, pick another";
        }
        if ("no_match".equals(key)) {
            if (ar) return "لا تطابق، نبقي الكلمات الحالية";
            if (fr) return "Aucune correspondance, on garde les paroles actuelles";
            return "No match, keeping current lyrics";
        }
        if ("cache_cleared".equals(key)) {
            if (ar) return "تم مسح الكلمات المحفوظة";
            if (fr) return "Paroles en cache effacées";
            return "Cached lyrics cleared";
        }
        if ("instrumental".equals(key)) {
            if (ar) return "موسيقية";
            if (fr) return "Instrumental";
            return "Instrumental";
        }
        if ("ai_translate".equals(key)) {
            if (ar) return "ترجمة الكلمات بالذكاء الاصطناعي";
            if (fr) return "Traduire les paroles par IA";
            return "AI translate lyrics";
        }
        if ("ai_title".equals(key)) {
            if (ar) return "ترجمة الكلمات بالذكاء الاصطناعي";
            if (fr) return "Traduction des paroles par IA";
            return "AI lyrics translation";
        }
        if ("lyrics_options".equals(key)) {
            if (ar) return "خيارات الكلمات";
            if (fr) return "Options des paroles";
            return "Lyrics options";
        }
        if ("lyrics_sources".equals(key)) {
            if (ar) return "مصادر الكلمات";
            if (fr) return "Sources des paroles";
            return "Lyrics sources";
        }
        if ("trying_source".equals(key)) {
            if (ar) return "جارٍ البحث في %s…";
            if (fr) return "Recherche sur %s…";
            return "Trying %s…";
        }
        if ("ai_note".equals(key)) {
            if (ar) return "ستظهر الترجمة أسفل كل سطر أصلي مع الحفاظ على التوقيت.";
            if (fr) return "La traduction apparaît sous chaque ligne originale, synchronisée.";
            return "Translations appear below each original line and keep lyric timing.";
        }
        if ("auto_translate_title".equals(key)) {
            if (ar) return "الترجمة التلقائية للكلمات";
            if (fr) return "Traduction automatique des paroles";
            return "Automatic lyrics translation";
        }
        if ("auto_translate_note".equals(key)) {
            if (ar) return "عند التفعيل، ستظهر الكلمات الأصلية وترجمتها معاً بعد اكتمال الترجمة.";
            if (fr) return "Une fois activée, les paroles originales et leur traduction s’affichent ensemble après la traduction.";
            return "When enabled, original lyrics and their translation appear together after translation completes.";
        }
        if ("auto_translate_toggle".equals(key)) {
            if (ar) return "ترجمة الكلمات تلقائياً";
            if (fr) return "Traduire automatiquement les paroles";
            return "Automatically translate lyrics";
        }
        if ("auto_translate_enabled".equals(key)) {
            if (ar) return "تم تفعيل الترجمة التلقائية للكلمات";
            if (fr) return "Traduction automatique des paroles activée";
            return "Automatic lyrics translation enabled";
        }
        if ("auto_translate_disabled".equals(key)) {
            if (ar) return "تم إيقاف الترجمة التلقائية للكلمات";
            if (fr) return "Traduction automatique des paroles désactivée";
            return "Automatic lyrics translation disabled";
        }
        if ("configure_openrouter".equals(key)) {
            if (ar) return "إعداد OpenRouter";
            if (fr) return "Configurer OpenRouter";
            return "Configure OpenRouter";
        }
        if ("save_settings".equals(key)) {
            if (ar) return "حفظ";
            if (fr) return "Enregistrer";
            return "Save";
        }
        if ("settings_saved".equals(key)) {
            if (ar) return "تم حفظ إعدادات الترجمة";
            if (fr) return "Paramètres de traduction enregistrés";
            return "Translation settings saved";
        }
        if ("provider_section".equals(key)) {
            if (ar) return "موفّر الخدمة";
            if (fr) return "Fournisseur";
            return "Provider";
        }
        if ("provider".equals(key)) {
            if (ar) return "الموفّر";
            if (fr) return "Fournisseur";
            return "Provider";
        }
        if ("opencode_note".equals(key)) {
            if (ar) return "استخدم مفتاح API من OpenCode Console ونموذجاً مدفوعاً. النماذج المجانية متاحة داخل تطبيق OpenCode فقط.";
            if (fr) return "Utilisez une clé API OpenCode Console et un modèle payant. Les modèles gratuits sont réservés à l’application OpenCode.";
            return "Use an OpenCode Console API key and a paid model. Free chat models are available only inside the OpenCode app.";
        }
        if ("xkiro_note".equals(key)) {
            if (ar) return "استخدم مفتاح xKiro API. للنماذج المجانية حد يومي للرموز؛ والمدفوعة تتطلب خطة أو رصيداً مؤهلاً، والمميزة خطة مؤهلة أو إيداعاً فعلياً.";
            if (fr) return "Utilisez une clé API xKiro. Les modèles gratuits ont un quota quotidien de jetons ; les modèles payants nécessitent un forfait ou un solde admissible, et les modèles premium un forfait admissible ou un dépôt réel.";
            return "Use an xKiro API key. Free models have a daily token allowance; paid models need an eligible plan or balance, and premium models need an eligible plan or real deposit.";
        }
        if ("cloudflare_note".equals(key)) {
            if (ar) return "يتطلب مفتاح Cloudflare API مع صلاحية Workers AI ومعرّف الحساب. يُوضع معرّف النموذج في المسار، ويُقرأ نص الرد ككائن JSON.";
            if (fr) return "Nécessite un jeton API Cloudflare avec l’accès Workers AI et l’identifiant du compte. L’ID du modèle est placé dans le chemin et le texte de la réponse est analysé comme du JSON.";
            return "Requires a Cloudflare API token with Workers AI access plus your Account ID. The model ID goes in the URL path and the reply text is parsed as JSON.";
        }
        if ("cloudflare_account_id_label".equals(key)) {
            if (ar) return "معرّف الحساب";
            if (fr) return "Identifiant du compte";
            return "Account ID";
        }
        if ("cloudflare_config_required".equals(key)) {
            if (ar) return "أدخل مفتاح Cloudflare API ومعرّف الحساب والنموذج ولغة الترجمة";
            if (fr) return "Saisissez le jeton Cloudflare, l’identifiant du compte, le modèle et la langue cible";
            return "Enter a Cloudflare API token, Account ID, model, and target language";
        }
        if ("model_label".equals(key)) {
            if (ar) return "النموذج";
            if (fr) return "Modèle";
            return "Model";
        }
        if ("api_credentials".equals(key)) {
            if (ar) return "بيانات API";
            if (fr) return "Identifiants API";
            return "API credentials";
        }
        if ("api_key_label".equals(key)) {
            if (ar) return "مفتاح API";
            if (fr) return "Clé API";
            return "API key";
        }
        if ("translation_mode_section".equals(key)) {
            if (ar) return "وضع الترجمة";
            if (fr) return "Mode de traduction";
            return "Translation mode";
        }
        if ("translation_mode".equals(key)) {
            if (ar) return "وضع الترجمة";
            if (fr) return "Mode de traduction";
            return "Translation mode";
        }
        if ("translation_mode_translation".equals(key)) {
            if (ar) return "ترجمة";
            if (fr) return "Traduction";
            return "Translation";
        }
        if ("system_prompt".equals(key)) {
            if (ar) return "تعليمات النظام";
            if (fr) return "Prompt système";
            return "System prompt";
        }
        if ("system_prompt_default".equals(key)) {
            if (ar) return "افتراضي";
            if (fr) return "Par défaut";
            return "Default";
        }
        if ("system_prompt_custom".equals(key)) {
            if (ar) return "مخصص";
            if (fr) return "Personnalisé";
            return "Custom";
        }
        if ("model_custom".equals(key)) {
            if (ar) return "مخصص";
            if (fr) return "Personnalisé";
            return "Custom";
        }
        if ("model_selector_title".equals(key)) {
            if (ar) return "اختر نموذجاً";
            if (fr) return "Choisir un modèle";
            return "Select model";
        }
        if ("fetching_models".equals(key)) {
            if (ar) return "جارٍ جلب النماذج من %s…";
            if (fr) return "Chargement des modèles depuis %s…";
            return "Fetching models from %s…";
        }
        if ("models_fetch_failed".equals(key)) {
            if (ar) return "تعذر جلب النماذج؛ يتم عرض القائمة الاحتياطية";
            if (fr) return "Impossible de charger les modèles ; liste de secours affichée";
            return "Couldn't fetch models; showing the built-in list";
        }
        if ("edit_model".equals(key)) {
            if (ar) return "معرّف نموذج مخصص";
            if (fr) return "Identifiant de modèle personnalisé";
            return "Custom model ID";
        }
        if ("edit_api_key".equals(key)) {
            if (ar) return "أدخل مفتاح API";
            if (fr) return "Saisissez la clé API";
            return "Enter API key";
        }
        if ("edit_target_language".equals(key)) {
            if (ar) return "أدخل لغة الترجمة";
            if (fr) return "Saisissez la langue cible";
            return "Enter target language";
        }
        if ("edit_system_prompt".equals(key)) {
            if (ar) return "تعليمات إضافية للنموذج";
            if (fr) return "Instructions supplémentaires pour le modèle";
            return "Additional instructions for the model";
        }
        if ("not_configured".equals(key)) {
            if (ar) return "غير مُعدّ";
            if (fr) return "Non configurée";
            return "Not configured";
        }
        if ("arabic_source_skipped".equals(key)) {
            if (ar) return "تم تخطي الكلمات العربية";
            if (fr) return "Paroles en arabe ignorées";
            return "Arabic-script lyrics are skipped";
        }
        if ("openrouter_key".equals(key)) {
            if (ar) return "مفتاح OpenRouter API";
            if (fr) return "Clé API OpenRouter";
            return "OpenRouter API key";
        }
        if ("model".equals(key)) {
            if (ar) return "معرّف النموذج (مثال: openai/gpt-4o-mini)";
            if (fr) return "Identifiant du modèle (ex. openai/gpt-4o-mini)";
            return "Model ID (e.g. openai/gpt-4o-mini)";
        }
        if ("target_language".equals(key)) {
            if (ar) return "لغة الترجمة";
            if (fr) return "Langue cible";
            return "Target language";
        }
        if ("custom_prompt_hint".equals(key)) {
            if (ar) return "اتركه فارغاً لاستخدام التعليمات الافتراضية";
            if (fr) return "Laissez vide pour utiliser les instructions par défaut";
            return "Leave blank to use the default instructions";
        }
        if ("translate_now".equals(key)) {
            if (ar) return "ترجمة الآن";
            if (fr) return "Traduire";
            return "Translate now";
        }
        if ("translate_now_action".equals(key)) {
            if (ar) return "ترجمة الآن";
            if (fr) return "Traduire maintenant";
            return "Translate now";
        }
        if ("translation_partial".equals(key)) {
            if (ar) return "تمت ترجمة جزء من الكلمات؛ تعذّرت ترجمة بعض الأسطر";
            if (fr) return "Traduction partielle : certaines lignes n’ont pas pu être traduites";
            return "Partially translated; some lines could not be translated";
        }
        if ("translation_config_required".equals(key)) {
            if (ar) return "أدخل مفتاح API والنموذج ولغة الترجمة";
            if (fr) return "Saisissez la clé API, le modèle et la langue cible";
            return "Enter an API key, model, and target language";
        }
        if ("no_source_lyrics".equals(key)) {
            if (ar) return "لا توجد كلمات محمّلة لهذه الأغنية";
            if (fr) return "Aucune parole chargée pour ce titre";
            return "No lyrics are loaded for this song";
        }
        if ("translation_too_long".equals(key)) {
            if (ar) return "الكلمات طويلة جداً للترجمة دفعة واحدة";
            if (fr) return "Les paroles sont trop longues pour une seule traduction";
            return "Lyrics are too long to translate in one request";
        }
        if ("translating".equals(key)) {
            if (ar) return "جارٍ ترجمة الكلمات عبر %s…";
            if (fr) return "Traduction des paroles via %s…";
            return "Translating lyrics with %s…";
        }
        if ("translation_pending".equals(key)) {
            if (ar) return "جارٍ ترجمة الكلمات…";
            if (fr) return "Traduction des paroles…";
            return "Translating lyrics…";
        }
        if ("translation_failed".equals(key)) {
            if (ar) return "فشلت ترجمة الكلمات";
            if (fr) return "Échec de la traduction des paroles";
            return "Lyrics translation failed";
        }
        if ("translation_done".equals(key)) {
            if (ar) return "تم عرض الكلمات الأصلية والترجمة معاً";
            if (fr) return "Paroles originales et traduction affichées ensemble";
            return "Original and translated lyrics are now displayed together";
        }
        if ("source_changed".equals(key)) {
            if (ar) return "تغيّرت الكلمات أثناء الترجمة؛ أعد المحاولة";
            if (fr) return "Les paroles ont changé pendant la traduction. Réessayez.";
            return "Lyrics changed during translation; please try again";
        }
        return key;
    }

    // ================= cache =================

    static final class Cached {
        boolean miss;
        long ts;
        String plain;
        String synced;
        String queryUsed = "";
        String source = "";
    }

    private static String cacheKey(SongInfo song) {
        // ID-primary: the server can return slightly different artist/title
        // strings for the same song across sessions (feat. variants,
        // parens). Keying on the stable song id means a cached LRCLIB
        // result keeps winning over future truncated teasers for that song.
        if (song.id != null && !song.id.isEmpty()) return song.id + "|" + KEY_VERSION;
        return song.id + "|" + normalize(song.artist, true)
                + "|" + normalize(song.title, true) + "|" + KEY_VERSION;
    }

    private static String legacyCacheKey(SongInfo song) {
        return song.id + "|" + normalize(song.artist, true)
                + "|" + normalize(song.title, true) + "|" + KEY_VERSION;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The user's provider selection is one stable global preference. It is
     * read from a single key on every song and never derived from per-song
     * state (cache, last source used, current song, defaults) or written by a
     * read path. Only an explicit change in the Lyrics sources checklist
     * writes it.
     */
    private static boolean[] loadSelectedSources(Context ctx) {
        return loadSelectedSources(prefs(ctx));
    }

    static boolean[] loadSelectedSources(SharedPreferences p) {
        return parseSelectedSources(p.getString(PREF_LYRIC_SOURCES, null));
    }

    /**
     * Decode the saved ids. A missing preference is a first run: show all
     * sources as checked without writing anything. An explicitly saved empty
     * selection (the user unchecked everything) stays empty, and ids of
     * removed providers are ignored so they never reappear.
     */
    static boolean[] parseSelectedSources(String raw) {
        boolean[] checked = new boolean[LYRIC_SOURCE_IDS.length];
        if (raw == null) {
            for (int i = 0; i < checked.length; i++) checked[i] = true;
            return checked;
        }
        String[] saved = raw.split(",");
        for (String id : saved) {
            for (int i = 0; i < LYRIC_SOURCE_IDS.length; i++) {
                if (LYRIC_SOURCE_IDS[i].equals(id.trim())) checked[i] = true;
            }
        }
        return checked;
    }

    private static void saveSelectedSources(Context ctx, boolean[] checked) {
        saveSelectedSources(prefs(ctx), checked);
    }

    static void saveSelectedSources(SharedPreferences p, boolean[] checked) {
        p.edit().putString(PREF_LYRIC_SOURCES, encodeSelectedSources(checked)).apply();
    }

    /** Only currently supported ids are written; unknown/removed ids are dropped. */
    static String encodeSelectedSources(boolean[] checked) {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < LYRIC_SOURCE_IDS.length; i++) {
            if (checked == null || i >= checked.length || !checked[i]) continue;
            if (raw.length() > 0) raw.append(',');
            raw.append(LYRIC_SOURCE_IDS[i]);
        }
        return raw.toString();
    }

    private static Cached readCache(Context ctx, String key) {
        return readCache(prefs(ctx), key);
    }

    private static Cached readCache(SharedPreferences p, String key) {
        try {
            String raw = p.getString(key, null);
            if (raw == null) return null;
            JSONObject j = new JSONObject(raw);
            Cached c = new Cached();
            c.ts = j.optLong("ts", 0);
            long age = System.currentTimeMillis() - c.ts;
            if (j.optBoolean("miss", false)) {
                if (age > NEG_TTL_MS) return null;
                c.miss = true;
                return c;
            }
            if (age > POS_TTL_MS) return null;
            c.plain = j.isNull("plain") ? null : j.optString("plain", null);
            c.synced = j.isNull("synced") ? null : j.optString("synced", null);
            c.queryUsed = j.optString("queryUsed", "");
            c.source = j.optString("source", "");
            if (c.plain == null && c.synced == null) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context ctx, String key, JSONObject j) {
        try {
            SharedPreferences.Editor ed = prefs(ctx).edit();
            ed.putString(key, j.toString());
            ed.apply();
            trimCache(ctx);
        } catch (Exception e) {
            Log.d(TAG, "cache write failed: " + e);
        }
    }

    private static void writeNegativeCache(Context ctx, String key) {
        try {
            JSONObject j = new JSONObject();
            j.put("miss", true);
            j.put("ts", System.currentTimeMillis());
            writeCache(ctx, key, j);
        } catch (Exception ignored) {
        }
    }

    private static void writePositiveCache(Context ctx, String key, Result r) {
        try {
            JSONObject j = new JSONObject();
            j.put("ts", System.currentTimeMillis());
            j.put("plain", r.plain);
            j.put("synced", r.synced);
            j.put("queryUsed", r.queryUsed);
            j.put("source", r.source);
            writeCache(ctx, key, j);
        } catch (Exception ignored) {
        }
    }

    private static void trimCache(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            Map<String, ?> all = p.getAll();
            if (all.size() <= MAX_ENTRIES) return;
            List<Map.Entry<String, Long>> ages = new ArrayList<Map.Entry<String, Long>>();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                long ts = 0;
                try {
                    ts = new JSONObject(String.valueOf(e.getValue())).optLong("ts", 0);
                } catch (Exception ignored) {
                }
                ages.add(new java.util.AbstractMap.SimpleEntry<String, Long>(e.getKey(), ts));
            }
            Collections.sort(ages, new Comparator<Map.Entry<String, Long>>() {
                @Override
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return Long.compare(a.getValue(), b.getValue());
                }
            });
            SharedPreferences.Editor ed = p.edit();
            for (int i = 0; i < ages.size() - MAX_ENTRIES; i++) ed.remove(ages.get(i).getKey());
            ed.apply();
        } catch (Exception ignored) {
        }
    }

    // ================= LRCLIB waterfall =================

    static final class Result {
        String plain;
        String synced;
        String queryUsed = "";
        String source = "";
    }

    /** Test-only factory for a per-source result (no network involved). */
    static Result resultForTest(String plain, String synced, String queryUsed,
                                String source) {
        Result r = new Result();
        r.plain = plain;
        r.synced = synced;
        r.queryUsed = queryUsed;
        r.source = source;
        return r;
    }

    /** IDs used by the options dialog, in the order they are offered. */
    static final String[] LYRIC_SOURCE_IDS = {
            "lrclib", "lyricsplus", "betterlyrics"
    };

    static String sourceLabel(String id) {
        if ("lrclib".equals(id)) return "LRCLIB";
        if ("lyricsplus".equals(id)) return "LyricsPlus";
        if ("betterlyrics".equals(id)) return "Better Lyrics";
        return id;
    }

    /** Fetch from exactly one source; used by the manual lyrics options. */
    static Result fromSource(SongInfo song, String source) {
        if (song == null || source == null) return null;
        if ("lrclib".equals(source)) return fromLrclib(song);
        if ("lyricsplus".equals(source)) return fromLyricsPlus(song);
        if ("betterlyrics".equals(source)) return fromBetterLyrics(song);
        return null;
    }

    /** Lets a lookup tell the player which provider it is trying right now. */
    private interface SearchProgress {
        void update(String message);
    }

    private static Result waterfall(SongInfo song) {
        return waterfall(song, null);
    }

    // Test-only instrumentation: counts entries into the lyrics-site search.
    // Unit tests use it to assert that translating from cache triggers no
    // LRCLIB / LyricsPlus / Better Lyrics request.
    static int waterfallEntriesForTest;

    static void resetWaterfallCountForTest() {
        waterfallEntriesForTest = 0;
    }

    /** Clears the in-memory fill/translation state between unit tests. */
    static void resetFillStateForTest() {
        synchronized (LrclibFallback.class) {
            fillInFlight.clear();
            missRetryTimes.clear();
            fillRetryTimes.clear();
            nativeFullRenderTimes.clear();
            lastRenderedSongKey = "";
            lastRenderedRealLyrics = false;
            translationSongId = "";
            translationSong = null;
            translationCallback = null;
            translationSourceLines.clear();
            inFlightSnapshots.clear();
            translationRequestsInFlight.clear();
            renderEpochs.clear();
            fillSearchStartEpochs.clear();
            renderedKeys.clear();
            lastSong = null;
            lastCallback = null;
            lyricsCallbacks.clear();
        }
    }

    static int waterfallCountForTest() {
        return waterfallEntriesForTest;
    }

    private static Result waterfall(SongInfo song, SearchProgress progress) {
        waterfallEntriesForTest++;
        if (progress != null) progress.update(tr("searching"));
        Result r = fromLrclib(song);
        if (r != null) return r;
        return fromExtendedSources(song, progress);
    }

    /** Keep trying source after source until one returns usable lyrics. */
    private static Result fromExtendedSources(SongInfo song, SearchProgress progress) {
        String[] ids = {"lyricsplus", "betterlyrics"};
        for (String id : ids) {
            if (progress != null) {
                progress.update(String.format(Locale.ROOT, tr("trying_source"),
                        sourceLabel(id)));
            }
            Result r = fromSource(song, id);
            if (r != null) return r;
        }
        return null;
    }

    private static Result fromLrclib(SongInfo song) {
        String dur = song.hasDuration ? String.valueOf(Math.round(song.duration)) : null;
        List<String[]> pairs = waterfallPairs(song.title, song.artist);

        // Pass 1: exact /api/get with duration (most accurate). Each pair is
        // unique, so identical normalized variants never repeat a request.
        for (String[] pair : pairs) {
            Result r = apiGet(pair[0], pair[1], dur);
            if (r != null) return r;
        }
        // Pass 2: same exact queries without duration. LRCLIB's duration
        // filter can reject an otherwise correct match when the app's
        // metadata differs; this is only needed when we sent a duration.
        if (dur != null) {
            for (String[] pair : pairs) {
                Result r = apiGet(pair[0], pair[1], null);
                if (r != null) return r;
            }
        }
        // Pass 3: fielded search, then q-style search (only if still empty).
        String title = normalize(song.title, true);
        String artist = normalize(song.artist, true);
        float duration = song.hasDuration ? song.duration : -1;
        Result r = apiSearchFielded(title, artist, duration);
        if (r != null) return r;
        return apiSearchQ(title, artist, duration);
    }

    /**
     * Ordered, deduplicated (track, artist) queries for the exact-match
     * passes. The plain and paren-stripped variants coincide for most songs,
     * so collapsing them halves the request count in the common case.
     */
    static List<String[]> waterfallPairs(String title, String artist) {
        List<String[]> pairs = new ArrayList<String[]>();
        // Always strip parentheticals from the song title: LRCLIB stores the
        // clean title, so "Song (Live)", "Song (feat. X)" and "Song [Remix]"
        // must never be sent as-is. Keep both artist variants (plain and
        // paren-stripped) because the artist sometimes carries a suffix the
        // track does not.
        String track = normalize(title, true);
        addWaterfallPair(pairs, track, normalize(artist, true));
        addWaterfallPair(pairs, track, normalize(artist, false));
        return pairs;
    }

    private static void addWaterfallPair(List<String[]> pairs, String track, String artist) {
        if (track.isEmpty() || artist.isEmpty()) return;
        for (String[] existing : pairs) {
            if (existing[0].equals(track) && existing[1].equals(artist)) return;
        }
        pairs.add(new String[]{track, artist});
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String httpGet(String url) throws Exception {
        return httpGet(url, UA, LRCLIB_TIMEOUT_MS);
    }

    private static String httpGet(String url, String userAgent, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        int code = c.getResponseCode();
        if (code == 404) return null; // clean miss (TrackNotFound).
        if (code == 503 && UA.equals(userAgent)) throw new RateLimited();
        if (code < 200 || code >= 300) return null;
        InputStream in = c.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private static final class RateLimited extends RuntimeException {
    }

    private static Result fromGetJson(String body, String queryUsed) {
        try {
            if (body == null) return null;
            return fromRecord(new JSONObject(body), queryUsed, "get");
        } catch (RateLimited rl) {
            throw rl;
        } catch (Exception e) {
            return null;
        }
    }

    private static Result apiGet(String track, String artist, String duration) {
        try {
            if (track.isEmpty() || artist.isEmpty()) return null;
            StringBuilder u = new StringBuilder(SOURCE_URL)
                    .append("/api/get?track_name=").append(enc(track))
                    .append("&artist_name=").append(enc(artist));
            if (duration != null) u.append("&duration=").append(enc(duration));
            String q = "get track=" + track + " artist=" + artist
                    + (duration != null ? " dur=" + duration : "");
            return fromGetJson(httpGet(u.toString()), q);
        } catch (RateLimited rl) {
            throw rl; // abort the whole waterfall; transient, no negative cache.
        } catch (Exception e) {
            return null;
        }
    }

    private static Result fromRecord(JSONObject o, String queryUsed, String source) {
        boolean instrumental = o.optBoolean("instrumental", false);
        String synced = o.isNull("syncedLyrics") ? null : o.optString("syncedLyrics", null);
        String plain = o.isNull("plainLyrics") ? null : o.optString("plainLyrics", null);
        if (!instrumental) {
            try {
                JSONObject file = o.optJSONObject("lyricsfile");
                if (file == null && o.has("lyricsfile") && !o.isNull("lyricsfile")) {
                    // lyricsfile may arrive as a YAML-ish string; only metadata flag matters.
                    String lf = o.optString("lyricsfile", "");
                    if (lf.contains("instrumental: true")) instrumental = true;
                } else if (file != null) {
                    JSONObject meta = file.optJSONObject("metadata");
                    if (meta != null && meta.optBoolean("instrumental", false)) instrumental = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (instrumental) {
            Result r = new Result();
            r.queryUsed = queryUsed;
            r.source = source;
            r.plain = tr("instrumental");
            r.synced = null;
            return r;
        }
        if ((synced == null || synced.isEmpty()) && (plain == null || plain.isEmpty())) return null;
        Result r = new Result();
        r.queryUsed = queryUsed;
        r.source = source;
        r.plain = plain;
        r.synced = synced;
        return r;
    }

    private static Result apiSearchFielded(String track, String artist, float duration) {
        try {
            if (track.isEmpty()) return null;
            String u = SOURCE_URL + "/api/search?track_name=" + enc(track)
                    + (artist.isEmpty() ? "" : "&artist_name=" + enc(artist));
            String body = httpGet(u);
            if (body == null) return null;
            return pickFirst(new JSONArray(body),
                    "search track=" + track + " artist=" + artist, duration);
        } catch (Exception e) {
            return null;
        }
    }

    private static Result apiSearchQ(String track, String artist, float duration) {
        try {
            if (track.isEmpty()) return null;
            String q = (track + (artist.isEmpty() ? "" : " " + artist)).trim();
            String body = httpGet(SOURCE_URL + "/api/search?q=" + enc(q));
            if (body == null) return null;
            return pickFirst(new JSONArray(body), "search q=" + q, duration);
        } catch (Exception e) {
            return null;
        }
    }

    // ============ extra lyrics sources (LyricsPlus -> Better Lyrics) ============

    private static Result lrcResult(String lrc, String queryUsed, String source) {
        if (lrc == null) return null;
        String clean = lrc.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (clean.isEmpty() || clean.indexOf('[') < 0) return null;
        if (parseLrc(clean).isEmpty()) return null;
        Result r = new Result();
        r.synced = clean;
        r.plain = null;
        r.queryUsed = queryUsed;
        r.source = source;
        return r;
    }

    private static String lrcStamp(long ms) {
        long safe = Math.max(0, ms);
        return String.format(Locale.ROOT, "[%02d:%02d.%02d]",
                safe / 60000, (safe / 1000) % 60, (safe % 1000) / 10);
    }

    private static long longValue(Object value) {
        try {
            if (value instanceof Number) return ((Number) value).longValue();
            if (value != null) return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** LyricsPlus: several deployments of an Apple/Musixmatch/Spotify aggregator. */
    private static Result fromLyricsPlus(SongInfo song) {
        if (song.title.isEmpty() || song.artist.isEmpty()) return null;
        for (String base : LYRICSPLUS_BASES) {
            try {
                String root = base.endsWith("/") ? base : base + "/";
                StringBuilder u = new StringBuilder(root).append("v2/lyrics/get?title=")
                        .append(enc(song.title)).append("&artist=").append(enc(song.artist));
                if (song.hasDuration && song.duration > 0) {
                    u.append("&duration=")
                            .append(enc(String.valueOf(Math.round(song.duration))));
                }
                String body = httpGet(u.toString(), UA, EXTENDED_TIMEOUT_MS);
                if (body == null) continue;
                JSONArray rows = new JSONObject(body).optJSONArray("lyrics");
                if (rows == null || rows.length() == 0) continue;
                StringBuilder lrc = new StringBuilder();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    String text = row.optString("text", "").trim();
                    if (text.isEmpty()) continue;
                    lrc.append(lrcStamp(longValue(row.opt("time")))).append(text).append('\n');
                }
                Result r = lrcResult(lrc.toString(),
                        "lyricsplus " + song.title + " - " + song.artist, "lyricsplus");
                if (r != null) return r;
            } catch (Throwable t) {
                Log.d(TAG, "LyricsPlus lookup failed for " + base + ": " + t);
            }
        }
        return null;
    }

    /** Better Lyrics: Apple TTML with tight line timings. */
    private static Result fromBetterLyrics(SongInfo song) {
        try {
            if (song.title.isEmpty() || song.artist.isEmpty()) return null;
            StringBuilder u = new StringBuilder(BETTERLYRICS_URL)
                    .append("?s=").append(enc(song.title))
                    .append("&a=").append(enc(song.artist));
            if (song.hasDuration && song.duration > 0) {
                u.append("&d=").append(enc(String.valueOf(Math.round(song.duration))));
            }
            String body = httpGet(u.toString(), UA, EXTENDED_TIMEOUT_MS);
            if (body == null) return null;
            String ttml = new JSONObject(body).optString("ttml", "");
            Result r = lrcResult(ttmlToLrc(ttml),
                    "betterlyrics " + song.title + " - " + song.artist, "betterlyrics");
            return r;
        } catch (Throwable t) {
            Log.d(TAG, "Better Lyrics lookup failed: " + t);
            return null;
        }
    }

    static int candidateScore(String title, String artist, int durationSec,
                                      String candidateTitle, String candidateArtist,
                                      int candidateDurationSec) {
        String wantedTitle = normalize(title, true);
        String wantedArtist = normalize(artist, true);
        String gotTitle = normalize(candidateTitle, true);
        String gotArtist = normalize(candidateArtist, true);
        if (wantedTitle.isEmpty() || gotTitle.isEmpty()) return 0;
        int score = 0;
        if (wantedTitle.equals(gotTitle)) score += 80;
        else if (wantedTitle.contains(gotTitle) || gotTitle.contains(wantedTitle)) score += 40;
        else return 0;
        if (!wantedArtist.isEmpty() && (gotArtist.contains(wantedArtist)
                || wantedArtist.contains(gotArtist))) {
            score += 50;
        }
        if (durationSec > 0 && candidateDurationSec > 0) {
            long deltaSec = candidateDurationSec - durationSec;
            if (deltaSec >= 0) {
                // Equal or longer is the normal match (extended outro, live
                // master, rounded metadata). Keep those eligible with a
                // generous upper bound; only absurd results are rejected.
                if (deltaSec <= 2) score += 100;
                else if (deltaSec <= 5) score += 50;
                else if (deltaSec <= LONGER_DURATION_TOLERANCE_MS / 1000) score += 10;
                else return 0;
            } else {
                // Shorter cuts keep the previous, tighter mismatch scoring.
                long diff = -deltaSec;
                if (diff <= 2) score += 100;
                else if (diff <= 5) score += 50;
                else if (diff <= CANDIDATE_DURATION_TOLERANCE_MS / 1000) score += 10;
                else score -= 50;
            }
        }
        return score;
    }

    /**
     * Duration gate for search results. Equal-to-longer candidates are kept
     * (a track can be a little longer than the playing file); shorter ones
     * keep the old tight tolerance. Unknown durations are never filtered.
     */
    static boolean durationMatches(float songDurationSec, long candidateDurationMs) {
        if (songDurationSec <= 0 || candidateDurationMs <= 0) return true;
        long delta = candidateDurationMs - Math.round(songDurationSec * 1000f);
        if (delta >= 0) return delta <= LONGER_DURATION_TOLERANCE_MS;
        return -delta <= DURATION_TOLERANCE_MS;
    }

    /** Convert Apple-style TTML to .lrc using line-level begin timings. */
    static String ttmlToLrc(String ttml) {
        if (ttml == null || ttml.trim().isEmpty()) return "";
        List<long[]> times = new ArrayList<long[]>();
        List<String> texts = new ArrayList<String>();
        try {
            Pattern p = Pattern.compile("<p\\b([^>]*)>(.*?)</p>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(ttml);
            while (m.find()) {
                Long ms = parseTtmlTime(xmlAttr(m.group(1), "begin"));
                if (ms == null) continue;
                String text = decodeEntities(stripTags(m.group(2))).trim();
                if (text.isEmpty()) continue;
                times.add(new long[]{ms});
                texts.add(text);
            }
        } catch (Throwable t) {
            return "";
        }
        if (times.isEmpty()) return "";
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < times.size(); i++) order.add(i);
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Long.compare(times.get(a)[0], times.get(b)[0]);
            }
        });
        StringBuilder lrc = new StringBuilder();
        for (Integer index : order) {
            lrc.append(lrcStamp(times.get(index)[0])).append(texts.get(index)).append('\n');
        }
        return lrc.toString();
    }

    private static String xmlAttr(String attrs, String name) {
        if (attrs == null) return null;
        Pattern single = Pattern.compile("\\b" + name + "\\s*=\\s*['\"]([^'\"]+)['\"]",
                Pattern.CASE_INSENSITIVE);
        Matcher m = single.matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String stripTags(String html) {
        return html == null ? "" : html.replaceAll("<[^>]*>", "");
    }

    private static String decodeEntities(String text) {
        if (text == null) return "";
        String out = text.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'")
                .replace("&nbsp;", " ");
        try {
            Matcher m = Pattern.compile("&#(\\d+);").matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, String.valueOf((char) Integer.parseInt(m.group(1))));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            return out;
        }
    }

    private static Long parseTtmlTime(String value) {
        if (value == null) return null;
        String text = value.trim().replace(",", ".");
        if (text.isEmpty()) return null;
        try {
            Matcher clock = Pattern.compile("^(?:(\\d+):)?(\\d+):(\\d+(?:\\.\\d+)?)$")
                    .matcher(text);
            if (clock.matches()) {
                long hours = longValue(clock.group(1));
                return (long) ((hours * 3600 + Long.parseLong(clock.group(2)) * 60
                        + Double.parseDouble(clock.group(3))) * 1000);
            }
            Matcher offset = Pattern.compile("^(\\d+(?:\\.\\d+)?)(ms|s|m|h)$")
                    .matcher(text);
            if (offset.matches()) {
                double amount = Double.parseDouble(offset.group(1));
                String unit = offset.group(2);
                long scale = "ms".equals(unit) ? 1L : "s".equals(unit) ? 1000L
                        : "m".equals(unit) ? 60000L : 3600000L;
                return (long) (amount * scale);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Result apiGetById(long id) {
        try {
            String body = httpGet(SOURCE_URL + "/api/get/" + id);
            return fromGetJson(body, "get id=" + id);
        } catch (Exception e) {
            return null;
        }
    }

    /** Auto-pick: first duration-compatible result with usable lyrics wins. */
    private static Result pickFirst(JSONArray arr, String queryUsed, float durationSec) {
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long candidateMs = Math.round(o.optDouble("duration", -1) * 1000.0);
                if (!durationMatches(durationSec, candidateMs)) continue;
                Result r = fromRecord(o, queryUsed, "search");
                if (r != null) return r;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Ordered unique candidates for the manual picker dialog. */
    static List<JSONObject> searchCandidates(String track, String artist) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
        String[] bodies = new String[2];
        try {
            if (!track.isEmpty()) {
                bodies[0] = httpGet(SOURCE_URL + "/api/search?track_name=" + enc(track)
                        + (artist.isEmpty() ? "" : "&artist_name=" + enc(artist)));
                String q = (track + (artist.isEmpty() ? "" : " " + artist)).trim();
                bodies[1] = httpGet(SOURCE_URL + "/api/search?q=" + enc(q));
            }
        } catch (Exception e) {
            return out;
        }
        for (String body : bodies) {
            if (body == null) continue;
            try {
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length() && out.size() < 12; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    long id = o.optLong("id", -1);
                    if (id < 0 || !seen.add(id)) continue;
                    out.add(o);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static String candidateLabel(JSONObject o) {
        String t = o.optString("trackName", "?");
        String a = o.optString("artistName", "?");
        double d = o.optDouble("duration", -1);
        String dur = "";
        if (d > 0) {
            int s = (int) d;
            dur = " (" + (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60) + ")";
        }
        return t + " — " + a + dur;
    }

    // ============ source-agnostic candidate picker ============

    /** One picker row. LRCLIB rows re-fetch by id; other sources carry lyrics. */
    static final class Candidate {
        final String label;
        final String source;
        final long lrclibId;
        final Result result;

        Candidate(String label, String source, long lrclibId, Result result) {
            this.label = label;
            this.source = source;
            this.lrclibId = lrclibId;
            this.result = result;
        }
    }

    /**
     * Network seam for candidate collection. Production delegates to the same
     * searchCandidates/fromSource helpers the old LRCLIB-only path used; tests
     * inject canned results to assert ordering and source skipping.
     */
    interface CandidateSearch {
        List<JSONObject> lrclibCandidates(SongInfo song);

        Result sourceResult(SongInfo song, String source);
    }

    private static final CandidateSearch PRODUCTION_CANDIDATE_SEARCH =
            new CandidateSearch() {
                @Override
                public List<JSONObject> lrclibCandidates(SongInfo song) {
                    return searchCandidates(normalize(song.title, true),
                            normalize(song.artist, true));
                }

                @Override
                public Result sourceResult(SongInfo song, String source) {
                    return fromSource(song, source);
                }
            };

    // Test-only log of sources actually queried by collectCandidates.
    static final List<String> candidateQueriesForTest = new ArrayList<String>();

    static void resetCandidateQueryLogForTest() {
        candidateQueriesForTest.clear();
    }

    static List<String> candidateQueriesForTest() {
        return Collections.unmodifiableList(candidateQueriesForTest);
    }

    /**
     * Collects candidates from the saved lyric_sources selection, in the
     * configured order. A source that fails or is unreachable contributes no
     * rows and never aborts the remaining sources.
     */
    static List<Candidate> collectCandidates(SongInfo song, SharedPreferences p,
                                             CandidateSearch search) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (song == null || search == null) return out;
        boolean[] selected = p == null ? parseSelectedSources(null) : loadSelectedSources(p);
        Set<String> seenIds = new HashSet<String>();
        for (int i = 0; i < LYRIC_SOURCE_IDS.length && i < selected.length; i++) {
            if (!selected[i]) continue;
            String id = LYRIC_SOURCE_IDS[i];
            candidateQueriesForTest.add(id);
            if ("lrclib".equals(id)) {
                List<JSONObject> found = search.lrclibCandidates(song);
                if (found == null) continue;
                for (JSONObject o : found) {
                    if (o == null) continue;
                    long recordId = o.optLong("id", -1);
                    String dedupe = recordId >= 0 ? "id:" + recordId
                            : "row:" + sourceLabel(id) + ":" + out.size();
                    if (!seenIds.add(dedupe)) continue;
                    out.add(new Candidate(candidateLabel(o) + " (" + sourceLabel(id) + ")",
                            id, recordId, null));
                }
            } else {
                Result r = search.sourceResult(song, id);
                if (r == null) continue;
                JSONObject meta = new JSONObject();
                try {
                    meta.put("trackName", song.title);
                    meta.put("artistName", song.artist);
                    if (song.hasDuration) meta.put("duration", song.duration);
                } catch (Exception ignored) {
                }
                out.add(new Candidate(candidateLabel(meta) + " (" + sourceLabel(id) + ")",
                        id, -1, r));
            }
        }
        return out;
    }

    // ================= LRC -> LyricsLine =================

    private static final Pattern LRC_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{2,3}))?\\]");
    private static final Pattern LRC_META =
            Pattern.compile("^\\[(ti|ar|al|length|by|offset|re|ve):", Pattern.CASE_INSENSITIVE);

    /** One source lyric line. Package-private so batch helpers are unit-testable. */
    static final class Line {
        int ms;
        String text;
    }

    static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<Line>();
        if (lrc == null) return out;
        for (String raw : lrc.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || LRC_META.matcher(line).find()) continue;
            Matcher m = LRC_TAG.matcher(line);
            List<Integer> times = new ArrayList<Integer>();
            int end = 0;
            while (m.find()) {
                int mm = Integer.parseInt(m.group(1));
                int ss = Integer.parseInt(m.group(2));
                int frac = 0;
                String f = m.group(3);
                if (f != null) frac = f.length() == 2
                        ? Integer.parseInt(f) * 10 : Integer.parseInt(f);
                times.add((mm * 60 + ss) * 1000 + frac);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = line.substring(end).trim();
            if (text.isEmpty()) continue;
            for (int ms : times) {
                Line l = new Line();
                l.ms = ms;
                l.text = text;
                out.add(l);
            }
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Integer.compare(a.ms, b.ms);
            }
        });
        return out;
    }

    // ================= render =================

    private static void remember(String queryUsed, String source, String plain, String synced) {
        synchronized (LrclibFallback.class) {
            lastQueryUsed = queryUsed == null ? "" : queryUsed;
            lastSource = source == null ? "" : source;
            lastPlain = plain;
            lastSynced = synced;
        }
    }

    /**
     * Paint a manually picked result through the same path that renders every
     * other lyric source. Search status lines already replaced the screen, so
     * drop the last-render key first or the result could be skipped when its
     * text matches what was shown before the search.
     */
    private static void applyPickedLyrics(Context ctx, Object song, Object callback,
                                          Result picked) {
        try {
            if (ctx == null || song == null || callback == null || picked == null) return;
            Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            // Make sure the player is actually showing the lyrics tab; a
            // picked result rendered into a hidden view looks like "nothing
            // happened".
            WeakReference<Object> ref = playerFragment;
            ensureLyricsMode(ref == null ? null : ref.get());
            writePositiveCache(appCtx, cacheKey(readSong(song)), picked);
            remember(picked.queryUsed, picked.source, picked.plain, picked.synced);

            // A pick is authoritative. Cancel any autofill re-assert still
            // queued for this song and drop every dedupe mark bound to it,
            // then force the render so an identical earlier text cannot make
            // renderOnMain early-return "duplicate render skipped".
            cancelPendingReasserts(song);
            clearRenderKeysForSong(song);

            Object latest = callbackForSong(song);
            if (latest == null) latest = callback;
            // renderOnMain builds the response the way the native renderer
            // expects, resolves the live callback again (also at paint time)
            // and starts automatic translation for the picked source.
            renderOnMain(appCtx, song, latest, picked.plain, picked.synced,
                    "picked:" + picked.source, true, true);
            Log.d(TAG, "picked lyrics applied from " + picked.source);
        } catch (Throwable t) {
            Log.d(TAG, "could not apply picked lyrics: " + t);
        }
    }

    private static void renderOnMain(final Context ctx, final Object songObj,
                                     Object callbackObj, final String plain,
                                     final String synced, final String queryUsed,
                                     final boolean toast) {
        renderOnMain(ctx, songObj, callbackObj, plain, synced, queryUsed, toast, false);
    }

    /**
     * Paint fetched lyrics on the live view. {@code force} is used by a manual
     * pick: it bypasses dedupe (so picking a result that matches earlier text
     * still paints). The pick also advances the render epoch before this call,
     * so queued autofill re-asserts cannot overwrite it afterwards.
     */
    private static void renderOnMain(final Context ctx, final Object songObj,
                                     Object callbackObj, final String plain,
                                     final String synced, final String queryUsed,
                                     final boolean toast, final boolean force) {
        // A lookup can finish seconds after the lyrics view was recreated
        // (switching songs and coming back). Rendering into the callback that
        // was captured when the request started then updates a dead view:
        // the screen stays empty until the cache is hit on the next open.
        // Resolve the freshest registered callback for this song at render
        // time so the result always lands on the view the user is looking at.
        Object currentCallback = callbackForSong(songObj);
        if (currentCallback != null) callbackObj = currentCallback;
        final Object renderCallback = callbackObj;
        if (renderCallback == null) return;
        // Even if a caller forgets the flag, a manual pick is always forced.
        final boolean mustForce = force || isManualPick(queryUsed);

        // Idempotence: repeated API callbacks for the same song must not
        // re-render (and scroll-jump) an already-shown identical result. A
        // forced render (manual pick) always wins.
        final String key = renderKey(songObj, plain, synced);
        if (key == null) return;
        synchronized (LrclibFallback.class) {
            String stored = renderedKeys.get(renderCallback);
            if (shouldSkipDuplicateRender(mustForce, key, stored)) {
                Log.d(TAG, "duplicate render skipped (" + queryUsed + ")");
                return;
            }
            renderedKeys.put(renderCallback, key);
            // Track what is actually on screen so the proactive fill leaves
            // real lyrics alone but retries when only a placeholder/teaser
            // was rendered (the bug the fill feature fixes).
            try {
                lastRenderedSongKey = songKey(readSong(songObj));
                lastRenderedRealLyrics = isTranslationSourceResult(queryUsed)
                        && ((plain != null && !plain.trim().isEmpty())
                        || (synced != null && !synced.trim().isEmpty()));
            } catch (Throwable ignored) {
            }
        }
        // Captured before the frame is queued. Only a manual pick advances the
        // epoch, so a stale autofill frame that was already queued becomes a
        // no-op once the pick commits — without cancelling our own cleanup.
        final long committedEpoch = renderEpoch(songIdOf(songObj));
        final String songId = songIdOf(songObj);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object response = buildResponse(songObj, plain, synced);
                    if (response == null) return;
                    List<Object> lines = buildLines(response, plain, synced);
                    // Re-resolve at paint time, not just at scheduling time:
                    // the lyrics view can be recreated while this frame waits
                    // on the main looper. Painting the old view looks exactly
                    // like "nothing happened" after a manual pick.
                    Object paintCallback = paintCallback(songObj, renderCallback);
                    if (paintCallback != renderCallback) {
                        synchronized (LrclibFallback.class) {
                            renderedKeys.put(paintCallback, key);
                        }
                    }
                    // Always show fetched LRCLIB lyrics right away. A slow or
                    // failing AI provider must never leave the lyrics screen
                    // hanging on a pending message. Translation still runs in
                    // the background and replaces these with dual lines when
                    // it finishes.
                    try {
                        List<Line> sourceLines = new ArrayList<Line>();
                        boolean sourceSynced = false;
                        if (isTranslationSourceResult(queryUsed)) {
                            sourceLines = parseLrc(synced);
                            sourceSynced = !sourceLines.isEmpty()
                                    && (lastPreferSynced || plain == null || plain.isEmpty());
                            if (!sourceSynced) sourceLines = plainLines(plain);
                            if (!sourceSynced && sourceLines.isEmpty()
                                    && !syncedIsEmpty(synced)) {
                                sourceLines = parseLrc(synced);
                                sourceSynced = !sourceLines.isEmpty();
                            }
                            setTranslationSource(
                                    readSong(songObj), songObj, paintCallback,
                                    sourceLines, sourceSynced);
                        }
                    } catch (Exception ignored) {
                    }
                    // A newer pick may have committed while this frame waited.
                    if (songId != null && !isReassertCurrent(songId, committedEpoch)) return;
                    invokeRender(paintCallback, songObj, lines, response);
                    Log.d(TAG, "fallback applied (" + queryUsed + ")");
                } catch (Throwable t) {
                    Log.d(TAG, "render failed: " + t);
                }
            }
        });
    }

    private static boolean syncedIsEmpty(String synced) {
        return synced == null || synced.trim().isEmpty();
    }

    private static boolean isTranslationSourceResult(String queryUsed) {
        return !"placeholder".equals(queryUsed)
                && !"no-result".equals(queryUsed)
                && !"teaser-guide".equals(queryUsed);
    }

    /** A manual pick is tagged "picked:source" and must always force-render. */
    static boolean isManualPick(String queryUsed) {
        return queryUsed != null && queryUsed.startsWith("picked:");
    }

    private static Object buildResponse(Object songObj, String plain, String synced) {
        try {
            Class<?> rc = Class.forName("com.anghami.ghost.api.response.LyricsResponse");
            Object r = rc.newInstance();
            Field f;
            f = fieldUp(rc, "songId");
            if (f != null) f.set(r, getFieldUp(songObj, "id"));
            boolean wantSynced = lastPreferSynced && synced != null && !synced.isEmpty();
            if (!wantSynced && (plain == null || plain.isEmpty()) && synced != null) {
                wantSynced = true; // only synced available.
            }
            if (wantSynced) {
                f = fieldUp(rc, "lyricsSynced");
                if (f != null) f.set(r, new ArrayList<Object>());
                f = fieldUp(rc, "isSynced");
                if (f != null) f.setBoolean(r, true);
            } else {
                f = fieldUp(rc, "lyricsUnsynced");
                if (f != null) f.set(r, plain);
                f = fieldUp(rc, "isSynced");
                if (f != null) f.setBoolean(r, false);
            }
            f = fieldUp(rc, "truncated");
            if (f != null) f.setBoolean(r, false);
            return r;
        } catch (Throwable t) {
            Log.d(TAG, "buildResponse failed: " + t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> buildLines(Object response, String plain, String synced) {
        List<Object> out = new ArrayList<Object>();
        try {
            List<Line> parsed = parseLrc(synced);
            Class<?> lc = Class.forName("com.anghami.ghost.pojo.LyricsLine");
            if (!parsed.isEmpty() && (lastPreferSynced || plain == null || plain.isEmpty())) {
                int id = 0;
                int prev = -1;
                for (Line l : parsed) {
                    Object o = lc.newInstance();
                    setLineField(o, "line", l.text);
                    setLineField(o, "milliseconds", l.ms);
                    setLineField(o, "id", id++);
                    setLineField(o, "isActive", id <= 1 || prev < l.ms);
                    prev = l.ms;
                    out.add(o);
                }
            } else if (plain != null) {
                for (String s : plain.split("\\r?\\n")) {
                    Object o = lc.newInstance();
                    setLineField(o, "line", s);
                    setLineField(o, "milliseconds", -1);
                    setLineField(o, "id", out.size());
                    setLineField(o, "isActive", true);
                    out.add(o);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "buildLines failed: " + t);
        }
        return out;
    }

    private static void setLineField(Object o, String name, Object value) {
        try {
            Field f = fieldUp(o.getClass(), name);
            if (f == null) return;
            if (value instanceof Integer) f.setInt(o, (Integer) value);
            else if (value instanceof Boolean) f.setBoolean(o, (Boolean) value);
            else f.set(o, value);
        } catch (Exception ignored) {
        }
    }

    private static void invokeRender(Object callbackObj, Object songObj,
                                     List<Object> lines, Object response) {
        try {
            for (Method m : callbackObj.getClass().getMethods()) {
                if (!m.getName().equals("V") || m.getParameterTypes().length != 3) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt[1].isAssignableFrom(List.class)
                        && pt[0].isInstance(songObj) && pt[2].isInstance(response)) {
                    m.invoke(callbackObj, songObj, lines, response);
                    return;
                }
            }
            Log.d(TAG, "render method V not found on callback");
        } catch (Throwable t) {
            Log.d(TAG, "invokeRender failed: " + t);
        }
    }

    // ================= options dialog =================

    /** Options dialog for an already-resolved song (play-button entry). */
    private static void showOptionsForSong(Context ctx, Object song) {
        synchronized (LrclibFallback.class) {
            if (System.currentTimeMillis() - lastDialogMs < 2000) return;
            lastDialogMs = System.currentTimeMillis();
        }
        final Object callbackFromFallback;
        final Object original;
        final String query;
        final String source;
        final String plain;
        final String synced;
        synchronized (LrclibFallback.class) {
            callbackFromFallback = lastCallback;
            original = lastOriginalResponse;
            query = lastQueryUsed;
            source = lastSource;
            plain = lastPlain;
            synced = lastSynced;
        }
        if (song == null) {
            Toast.makeText(ctx, tr("no_fallback"), Toast.LENGTH_SHORT).show();
            return;
        }
        final Object callback = callbackForSong(song);
        if (callback == null) {
            Toast.makeText(ctx, tr("no_fallback"), Toast.LENGTH_SHORT).show();
            return;
        }
        SongInfo info;
        try {
            info = readSong(song);
        } catch (Exception e) {
            return;
        }
        // Freshness: the last-render globals (source/query/plain/synced/
        // original) may belong to a different (pre-cached/next) song than
        // the song currently selected in Settings. The artist/title fields
        // are always filled from the mapped song; the synced/plain toggle
        // and server-version restore are offered only when globals match.
        boolean fresh = false;
        try {
            Object mappedId = getFieldUp(song, "id");
            Object lastId = lastSong == null ? null : getFieldUp(lastSong, "id");
            fresh = mappedId != null && mappedId.equals(lastId)
                    && callback == callbackFromFallback
                    && callback == lastCallback;
        } catch (Exception ignored) {
        }
        final SourceSnapshot aiSource = translationSourceFor(ctx, song);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);
        final EditText artistEdit = new EditText(ctx);
        artistEdit.setHint(tr("hint_artist"));
        artistEdit.setText(info.artist);
        final EditText titleEdit = new EditText(ctx);
        titleEdit.setHint(tr("hint_title"));
        titleEdit.setText(info.title);
        layout.addView(artistEdit);
        layout.addView(titleEdit);
        final android.widget.Button clearBtn = new android.widget.Button(ctx);
        clearBtn.setText(tr("clear_cache"));
        layout.addView(clearBtn);
        final android.widget.Button searchBtn = new android.widget.Button(ctx);
        searchBtn.setText(tr("search_all_sources"));
        layout.addView(searchBtn);
        final android.widget.Button sourcesBtn = new android.widget.Button(ctx);
        sourcesBtn.setText(tr("lyrics_sources"));
        layout.addView(sourcesBtn);
        final android.widget.Button translateBtn;
        if (aiSource != null) {
            translateBtn = new android.widget.Button(ctx);
            translateBtn.setText(tr("ai_translate"));
            layout.addView(translateBtn);
        } else translateBtn = null;

        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(tr("dialog_title"));
        b.setView(layout);
        b.setPositiveButton(tr("retry"), null); // overridden below to avoid auto-dismiss.
        if (fresh && plain != null && synced != null
                && !plain.isEmpty() && !synced.isEmpty()) {
            b.setNeutralButton(lastPreferSynced ? tr("show_plain") : tr("show_synced"), null);
        }
        if (fresh) {
            b.setNegativeButton(tr("server_version"), null);
        }
        final AlertDialog dialog = b.create();
        dialog.show();
        if (translateBtn != null) {
            translateBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    showTranslationDialog(v.getContext(), song);
                }
            });
        }
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Lowercased: LRCLIB matching is case-sensitive enough
                // that mixed-case artist/title variants miss; normalize()
                // lowercases too, this keeps the dialog path explicit.
                final String a = artistEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
                final String t = titleEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
                if (a.isEmpty() && t.isEmpty()) return;
                dialog.dismiss();
                final Context c = v.getContext();
                SongInfo searchSong = new SongInfo();
                searchSong.artist = a;
                searchSong.title = t;
                final SongInfo query = searchSong;
                // Replace the native loading spinner immediately with our own
                // status line; the picker (or a miss message) follows.
                renderSearchStatus(song, callback, tr("searching"));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<Candidate> found = collectCandidates(
                                query, prefs(c), PRODUCTION_CANDIDATE_SEARCH);
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (found.isEmpty()) {
                                    // Never leave the native spinner running.
                                    renderSearchStatus(song, callback, tr("no_results_any"));
                                    Toast.makeText(c, tr("no_results_any"),
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                final String[] labels = new String[found.size()];
                                for (int i = 0; i < found.size(); i++) {
                                    labels[i] = found.get(i).label;
                                }
                                new AlertDialog.Builder(c)
                                        .setTitle(tr("pick"))
                                        .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface d, int which) {
                                                final Candidate picked = found.get(which);
                                                // Non-LRCLIB sources already hold their lyrics;
                                                // apply them without a second network call.
                                                if (picked.result != null) {
                                                    applyPickedLyrics(c, song, callback, picked.result);
                                                    return;
                                                }
                                                final long id = picked.lrclibId;
                                                if (id < 0) return;
                                                new Thread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        Result r = apiGetById(id);
                                                        if (r == null) {
                                                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    renderSearchStatus(song, callback,
                                                                            tr("no_results_any"));
                                                                    Toast.makeText(c, tr("empty_record"),
                                                                            Toast.LENGTH_SHORT).show();
                                                                }
                                                            });
                                                            return;
                                                        }
                                                        // applyPickedLyrics caches the pick, seeds
                                                        // translation state and renders through the
                                                        // same path as every other lyrics source.
                                                        applyPickedLyrics(c, song, callback, r);
                                                    }
                                                }).start();
                                            }
                                        })
                                        .show();
                            }
                        });
                    }
                }).start();
            }
        });
        sourcesBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String a = artistEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
                final String t = titleEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
                final Context c = v.getContext();
                // Close the options dialog first, otherwise it stays on top
                // of the lyrics view and hides the result.
                dialog.dismiss();
                String[] labels = new String[LYRIC_SOURCE_IDS.length];
                // Always display the one global preference, for every song.
                final boolean[] checked = loadSelectedSources(c);
                final boolean[] changed = {false};
                for (int i = 0; i < LYRIC_SOURCE_IDS.length; i++) {
                    labels[i] = sourceLabel(LYRIC_SOURCE_IDS[i]);
                }
                new AlertDialog.Builder(c)
                        .setTitle(tr("lyrics_sources"))
                        .setMultiChoiceItems(labels, checked,
                                new DialogInterface.OnMultiChoiceClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which,
                                                        boolean isChecked) {
                                        // Save on every checkbox change: dismiss,
                                        // cancel or reopening cannot lose it.
                                        checked[which] = isChecked;
                                        changed[0] = true;
                                        saveSelectedSources(c, checked);
                                    }
                                })
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface d) {
                                // Opening the dialog alone is a read, not a
                                // choice: never rewrite the preference then.
                                if (changed[0]) saveSelectedSources(c, checked);
                            }
                        })
                        .setPositiveButton(tr("search"),
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        final List<String> selected = new ArrayList<String>();
                                        for (int i = 0; i < LYRIC_SOURCE_IDS.length; i++) {
                                            if (checked[i]) selected.add(LYRIC_SOURCE_IDS[i]);
                                        }
                                        if (selected.isEmpty()) {
                                            Toast.makeText(c, tr("translation_config_required"),
                                                    Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        // Selection was already persisted on
                                        // each checkbox tap; searching does not
                                        // change it.
                                        Toast.makeText(c, tr("searching"), Toast.LENGTH_SHORT).show();
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                SongInfo s = new SongInfo();
                                                try {
                                                    s.id = String.valueOf(getFieldUp(song, "id"));
                                                } catch (Exception ignored) {
                                                }
                                                s.artist = a;
                                                s.title = t;
                                                s.duration = info.duration;
                                                s.hasDuration = info.hasDuration;
                                                // Try checked providers in the listed order
                                                // until one returns usable lyrics.
                                                Result r = null;
                                                for (String sourceId : selected) {
                                                    renderSearchStatus(song, callback,
                                                            String.format(Locale.ROOT,
                                                                    tr("trying_source"),
                                                                    sourceLabel(sourceId)));
                                                    r = fromSource(s, sourceId);
                                                    if (r != null) break;
                                                }
                                                final Result found = r;
                                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        if (found == null) {
                                                            Toast.makeText(c, tr("no_results"),
                                                                    Toast.LENGTH_SHORT).show();
                                                            return;
                                                        }
                                                        applyPickedLyrics(c, song, callback, found);
                                                    }
                                                });
                                            }
                                        }).start();
                                    }
                                })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Context c = v.getContext();
                    // Remove cached lyrics only. The global source selection
                    // key is never touched, so a cache clear cannot reset it.
                    SharedPreferences p = prefs(c);
                    SharedPreferences.Editor ed = p.edit();
                    for (String key : p.getAll().keySet()) {
                        if (!PREF_LYRIC_SOURCES.equals(key)) ed.remove(key);
                    }
                    ed.apply();
                    Toast.makeText(c, tr("cache_cleared"), Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "fallback cache cleared by user");
                } catch (Throwable t) {
                    Log.d(TAG, "clear cache failed: " + t);
                }
                dialog.dismiss();
            }
        });
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String a = artistEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
                    final String t = titleEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
                    if (a.isEmpty() || t.isEmpty()) return;
                    dialog.dismiss();
                    final Context c = v.getContext();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            SongInfo s = new SongInfo();
                            try {
                                s.id = String.valueOf(getFieldUp(song, "id"));
                            } catch (Exception ignored) {
                            }
                            s.artist = a;
                            s.title = t;
                            Result r;
                            try {
                                r = waterfall(s);
                            } catch (Throwable th) {
                                Log.d(TAG, "retry waterfall failed: " + th);
                                r = null;
                            }
                            if (r == null) {
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(c, tr("no_match"),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                                return;
                            }
                            remember(r.queryUsed, r.source, r.plain, r.synced);
                            // Retry wins and sticks: cache under the song id so
                            // future opens load it instead of the truncated teaser.
                            try {
                                SongInfo orig = readSong(song);
                                writePositiveCache(c.getApplicationContext() != null
                                        ? c.getApplicationContext() : c,
                                        cacheKey(orig), r);
                            } catch (Exception ignored) {
                            }
                            renderOnMain(c.getApplicationContext() != null
                                    ? c.getApplicationContext() : c,
                                    song, callback, r.plain, r.synced, r.queryUsed, false);
                        }
                    }).start();
                }
            });
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    synchronized (LrclibFallback.class) {
                        lastPreferSynced = !lastPreferSynced;
                    }
                    dialog.dismiss();
                    renderOnMain(v.getContext(), song, callback, plain, synced, query, false);
                }
            });
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (original == null) return;
                    // Re-render the server teaser from the kept original response.
                    String oPlain = getString(original, "lyricsUnsynced");
                    renderOnMain(v.getContext(), song, callback, oPlain, null, "server", false);
                }
            });
        }
    }

    // ================= Dual-line AI translation =================

    private static final class TranslationSettings {
        final TranslationProvider provider;
        String apiKey = "";
        String accountId = "";
        String model;
        String language = "English";
        String systemPrompt = "";
        boolean autoTranslate;

        TranslationSettings(TranslationProvider provider) {
            this.provider = provider;
            this.model = provider.defaultModel;
        }
    }

    private static String systemPromptForDialog(String customPrompt) {
        String custom = trimOrEmpty(customPrompt);
        if (custom.isEmpty() || DEFAULT_TRANSLATION_SYSTEM_PROMPT.equals(custom)) {
            return DEFAULT_TRANSLATION_SYSTEM_PROMPT;
        }
        if (custom.startsWith(DEFAULT_TRANSLATION_SYSTEM_PROMPT)) return custom;
        return DEFAULT_TRANSLATION_SYSTEM_PROMPT + ADDITIONAL_PROMPT_MARKER + custom;
    }

    private static String customSystemPromptFromDialog(String dialogPrompt) {
        String prompt = trimOrEmpty(dialogPrompt);
        if (prompt.isEmpty() || DEFAULT_TRANSLATION_SYSTEM_PROMPT.equals(prompt)) return "";
        String markedPrefix = DEFAULT_TRANSLATION_SYSTEM_PROMPT + ADDITIONAL_PROMPT_MARKER;
        if (prompt.startsWith(markedPrefix)) {
            return prompt.substring(markedPrefix.length()).trim();
        }
        if (prompt.startsWith(DEFAULT_TRANSLATION_SYSTEM_PROMPT)) {
            return prompt.substring(DEFAULT_TRANSLATION_SYSTEM_PROMPT.length()).trim();
        }
        return prompt;
    }

    private static String systemPromptSummary(String customPrompt) {
        return trimOrEmpty(customPrompt).isEmpty()
                ? tr("system_prompt_default") : tr("system_prompt_custom");
    }

    private static SharedPreferences translationPrefs(Context ctx) {
        return ctx.getSharedPreferences(TRANSLATION_PREFS, Context.MODE_PRIVATE);
    }

    private static TranslationSettings readTranslationSettings(Context ctx) {
        TranslationProvider provider = TranslationProvider.fromId(
                translationPrefs(ctx).getString("provider", null));
        return readTranslationSettings(ctx, provider);
    }

    private static TranslationSettings readTranslationSettings(Context ctx,
                                                               TranslationProvider provider) {
        TranslationSettings settings = new TranslationSettings(provider);
        try {
            SharedPreferences p = translationPrefs(ctx);
            String apiKey = p.getString(provider.preferenceKey("api_key"), "");
            String accountId = p.getString(provider.preferenceKey("account_id"), "");
            String model = p.getString(provider.preferenceKey("model"), provider.defaultModel);
            String defaultLanguage = "ar".equalsIgnoreCase(Locale.getDefault().getLanguage())
                    ? "Arabic" : "English";
            String language = p.getString("language", defaultLanguage);
            String systemPrompt = p.getString("system_prompt", "");
            settings.apiKey = apiKey == null ? "" : apiKey;
            settings.accountId = accountId == null ? "" : accountId;
            settings.model = model == null ? provider.defaultModel : model;
            settings.language = language == null ? defaultLanguage : language;
            settings.systemPrompt = systemPrompt == null ? "" : systemPrompt;
            settings.autoTranslate = p.getBoolean("auto_translate", false);
        } catch (Throwable t) {
            Log.d(TAG, "Translation settings read failed: " + t);
        }
        return settings;
    }

    private static void saveTranslationSettings(Context ctx, TranslationProvider provider,
                                                TranslationSettings[] drafts, String language,
                                                String systemPrompt) {
        SharedPreferences.Editor editor = translationPrefs(ctx).edit()
                .putString("provider", provider.id)
                .putString("language", language)
                .putString("system_prompt", systemPrompt);
        for (TranslationSettings draft : drafts) {
            editor.putString(draft.provider.preferenceKey("api_key"), trimOrEmpty(draft.apiKey))
                    .putString(draft.provider.preferenceKey("model"), trimOrEmpty(draft.model));
            // Only Cloudflare needs an account id. Other providers keep their
            // original storage keys untouched.
            if (draft.provider == TranslationProvider.CLOUDFLARE) {
                editor.putString(draft.provider.preferenceKey("account_id"),
                        trimOrEmpty(draft.accountId));
            }
        }
        editor.apply();
    }

    private static boolean hasProviderNote(TranslationProvider provider) {
        return provider == TranslationProvider.OPENCODE || provider == TranslationProvider.XKIRO
                || provider == TranslationProvider.CLOUDFLARE;
    }

    private static String translationProviderNote(TranslationProvider provider) {
        if (provider == TranslationProvider.OPENCODE) return tr("opencode_note");
        if (provider == TranslationProvider.XKIRO) return tr("xkiro_note");
        if (provider == TranslationProvider.CLOUDFLARE) return tr("cloudflare_note");
        return "";
    }

    private static String openCodeSessionId(Context ctx) {
        synchronized (LrclibFallback.class) {
            SharedPreferences prefs = translationPrefs(ctx);
            String session = prefs.getString("opencode_session_id", "");
            if (session == null || session.trim().isEmpty()) {
                session = "ses_" + java.util.UUID.randomUUID().toString();
                prefs.edit().putString("opencode_session_id", session).apply();
            }
            return session;
        }
    }

    private static void showTranslationDialog(final Context ctx, final Object song) {
        showTranslationDialog(ctx, song, null, false);
    }

    private static void showTranslationDialog(final Context ctx, final Object song,
                                               final Object fragmentObj,
                                               final boolean includeAutoToggle) {
        final boolean configOnly = song == null;
        // Use the same cache/render-backed resolution as the Translate-now
        // action: a stale live source must not hide lyrics already on screen.
        final SourceSnapshot initialSource =
                configOnly ? null : translateNowSource(ctx, song);
        if (!configOnly && initialSource == null) {
            Toast.makeText(ctx, tr("no_source_lyrics"), Toast.LENGTH_SHORT).show();
            return;
        }
        final TranslationSettings settings = readTranslationSettings(ctx);
        final TranslationProvider[] providers = TranslationProvider.values();
        final TranslationProvider[] provider = {settings.provider};
        final TranslationSettings[] drafts = new TranslationSettings[providers.length];
        final String[] providerNames = new String[providers.length];
        for (TranslationProvider value : providers) {
            drafts[value.ordinal()] = readTranslationSettings(ctx, value);
            providerNames[value.ordinal()] = value.displayName;
        }
        final String[] language = {settings.language};
        final String[] systemPrompt = {systemPromptForDialog(settings.systemPrompt)};
        // 0 = API key/token, 1 = model, 2 = Cloudflare Account ID.
        final TextView[] credentialSummaries = new TextView[3];
        final View[] accountIdRow = new View[1];
        final TextView providerNote = new TextView(ctx);
        providerNote.setText(translationProviderNote(provider[0]));
        providerNote.setTextColor(Color.rgb(166, 172, 181));
        providerNote.setTextSize(13);
        providerNote.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 8));
        providerNote.setVisibility(hasProviderNote(provider[0]) ? View.VISIBLE : View.GONE);

        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        int horizontalPad = dp(ctx, 20);
        rows.setPadding(horizontalPad, dp(ctx, 4), horizontalPad, dp(ctx, 12));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(false);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Pinned footer action, visible in BOTH config-only and manual modes.
        // It translates the current song immediately with the saved settings
        // (the positive button keeps its existing draft-settings behaviour).
        final android.widget.Button translateNowButton = new android.widget.Button(ctx);
        translateNowButton.setText(tr("translate_now_action"));
        LinearLayout dialogView = new LinearLayout(ctx);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        dialogView.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(ctx, 2);
        dialogView.addView(translateNowButton, footerParams);

        final CheckBox autoTranslateToggle;
        if (includeAutoToggle) {
            addSettingsSection(ctx, rows, tr("auto_translate_title"));
            autoTranslateToggle = new CheckBox(ctx);
            autoTranslateToggle.setText(tr("auto_translate_toggle"));
            autoTranslateToggle.setChecked(settings.autoTranslate);
            rows.addView(autoTranslateToggle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView autoNote = new TextView(ctx);
            autoNote.setText(tr("auto_translate_note"));
            autoNote.setTextColor(Color.rgb(166, 172, 181));
            autoNote.setTextSize(13);
            autoNote.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 12));
            rows.addView(autoNote, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            autoTranslateToggle = null;
        }

        addSettingsSection(ctx, rows, tr("provider_section"));
        addSettingsRow(ctx, rows, tr("provider"), provider[0].displayName,
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View view) {
                        new AlertDialog.Builder(ctx)
                                .setTitle(tr("provider"))
                                .setSingleChoiceItems(providerNames, provider[0].ordinal(),
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                provider[0] = providers[which];
                                                TranslationSettings draft = drafts[which];
                                                refreshSettingsSummary(view, tr("provider"),
                                                        provider[0].displayName);
                                                credentialSummaries[0].setText(maskedApiKey(draft.apiKey));
                                                credentialSummaries[1].setText(draft.model);
                                                applyCloudflareAccountRow(provider[0],
                                                        accountIdRow[0], credentialSummaries[2],
                                                        draft.accountId);
                                                providerNote.setText(
                                                        translationProviderNote(provider[0]));
                                                providerNote.setVisibility(
                                                        hasProviderNote(provider[0])
                                                                ? View.VISIBLE : View.GONE);
                                                dialog.dismiss();
                                            }
                                        })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                });
        rows.addView(providerNote, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addSettingsSection(ctx, rows, tr("api_credentials"));
        credentialSummaries[0] = addSettingsRow(ctx, rows, tr("api_key_label"),
                maskedApiKey(settings.apiKey), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final TranslationSettings draft = drafts[provider[0].ordinal()];
                showTextSettingDialog(ctx, draft.provider.displayName + " · " + tr("edit_api_key"),
                        draft.apiKey,
                        android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        false, new TextSettingListener() {
                            @Override
                            public void onValueChanged(String value) {
                                draft.apiKey = value;
                                if (provider[0] == draft.provider) {
                                    credentialSummaries[0].setText(maskedApiKey(value));
                                }
                            }
                        });
            }
        });
        credentialSummaries[2] = addSettingsRow(ctx, rows, tr("cloudflare_account_id_label"),
                maskedAccountId(settings.accountId), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final TranslationSettings draft = drafts[provider[0].ordinal()];
                showTextSettingDialog(ctx, draft.provider.displayName + " · "
                        + tr("cloudflare_account_id_label"), draft.accountId,
                        android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                        false, new TextSettingListener() {
                            @Override
                            public void onValueChanged(String value) {
                                draft.accountId = value;
                                if (provider[0] == draft.provider) {
                                    credentialSummaries[2].setText(maskedAccountId(value));
                                }
                            }
                        });
            }
        });
        // addSettingsRow returns the summary TextView; its parent is the card.
        accountIdRow[0] = (View) credentialSummaries[2].getParent();
        applyCloudflareAccountRow(provider[0], accountIdRow[0], credentialSummaries[2],
                settings.accountId);

        credentialSummaries[1] = addSettingsRow(ctx, rows, tr("model_label"), settings.model,
                new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                final TranslationSettings draft = drafts[provider[0].ordinal()];
                showModelSelector(ctx, draft.provider, draft.accountId, draft.apiKey,
                        draft.model, new ModelSelectionListener() {
                    @Override
                    public void onModelSelected(String value) {
                        draft.model = value;
                        if (provider[0] == draft.provider) {
                            credentialSummaries[1].setText(value);
                        }
                    }
                });
            }
        });

        addSettingsSection(ctx, rows, tr("translation_mode_section"));
        addSettingsRow(ctx, rows, tr("translation_mode"),
                tr("translation_mode_translation"), null);
        addSettingsRow(ctx, rows, tr("system_prompt"),
                systemPromptSummary(settings.systemPrompt),
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View view) {
                        showTextSettingDialog(ctx, tr("edit_system_prompt"), systemPrompt[0],
                                android.text.InputType.TYPE_CLASS_TEXT
                                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                                true, new TextSettingListener() {
                                     @Override
                                     public void onValueChanged(String value) {
                                         systemPrompt[0] = value;
                                         String customPrompt =
                                                 customSystemPromptFromDialog(value);
                                         refreshSettingsSummary(view, tr("system_prompt"),
                                                 systemPromptSummary(customPrompt));
                                     }
                                 });
                    }
                });
        addSettingsRow(ctx, rows, tr("target_language"), language[0],
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View view) {
                        showTextSettingDialog(ctx, tr("edit_target_language"), language[0],
                                android.text.InputType.TYPE_CLASS_TEXT
                                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                                false, new TextSettingListener() {
                                    @Override
                                    public void onValueChanged(String value) {
                                        language[0] = value;
                                        refreshSettingsSummary(view, tr("target_language"), value);
                                    }
                                });
                    }
                });

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(tr("ai_title"))
                .setView(dialogView)
                .setPositiveButton(tr(configOnly ? "save_settings" : "translate_now"), null)
                .setNeutralButton(tr("lyrics_options"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        Object activeFragment = fragmentObj != null
                                ? fragmentObj
                                : playerFragment == null ? null : playerFragment.get();
                        Object song = currentSongFromPlayer(activeFragment);
                        if (song == null) {
                            synchronized (LrclibFallback.class) {
                                song = lastSong;
                            }
                        }
                        if (song == null) {
                            Toast.makeText(ctx, tr("no_loaded"), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showOptionsForSong(ctx, song);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        translateNowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                translateNowWithSavedSettings(ctx, song, fragmentObj, dialog);
            }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final TranslationProvider providerValue = provider[0];
                final TranslationSettings draft = drafts[providerValue.ordinal()];
                final String apiKeyValue = trimOrEmpty(draft.apiKey);
                final String accountIdValue = trimOrEmpty(draft.accountId);
                final String modelValue = trimOrEmpty(draft.model);
                final String languageValue = trimOrEmpty(language[0]);
                final String systemPromptValue =
                        customSystemPromptFromDialog(systemPrompt[0]);
                final boolean autoTranslateEnabled = includeAutoToggle
                        ? autoTranslateToggle.isChecked() : settings.autoTranslate;
                boolean requiresApiKey = !configOnly || autoTranslateEnabled;
                boolean needsAccount = providerValue == TranslationProvider.CLOUDFLARE;
                if ((requiresApiKey && apiKeyValue.isEmpty())
                        || (needsAccount && accountIdValue.isEmpty())
                        || modelValue.isEmpty() || languageValue.isEmpty()) {
                    Toast.makeText(ctx, needsAccount ? tr("cloudflare_config_required")
                                    : tr("translation_config_required"),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                saveTranslationSettings(ctx, providerValue, drafts, languageValue,
                        systemPromptValue);
                if (configOnly) {
                    dialog.dismiss();
                    if (includeAutoToggle) {
                        setAutoTranslationEnabled(ctx, autoTranslateEnabled);
                    }
                    refreshLyricsButtonLabel();
                    if (autoTranslateEnabled) {
                        setAutoTranslationEnabled(ctx, true);
                        Object activeFragment = fragmentObj != null
                                ? fragmentObj
                                : playerFragment == null ? null : playerFragment.get();
                        ensureLyricsMode(activeFragment);
                        Object activeSong = currentSongFromPlayer(activeFragment);
                        if (activeSong != null
                                && autoTranslateIfEnabled(ctx, activeSong)) {
                            SourceSnapshot activeSource = translationSourceFor(ctx, activeSong);
                            if (activeSource != null) {
                                renderTranslationPending(activeSong, activeSource.callback);
                            }
                        }
                        Toast.makeText(ctx, tr("auto_translate_enabled"),
                                Toast.LENGTH_SHORT).show();
                    } else if (includeAutoToggle) {
                        if (settings.autoTranslate) {
                            Object activeFragment = fragmentObj != null
                                    ? fragmentObj
                                    : playerFragment == null ? null : playerFragment.get();
                            Object activeSong = currentSongFromPlayer(activeFragment);
                            if (activeSong != null) {
                                renderOriginalLyricsIfCurrent(activeSong,
                                        translationSourceFor(ctx, activeSong));
                            }
                        }
                        Toast.makeText(ctx, tr("auto_translate_disabled"),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(ctx, tr("settings_saved"),
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                final SourceSnapshot snapshot = translateNowSource(ctx, song);
                if (snapshot == null || snapshot.lines.isEmpty()) {
                    Toast.makeText(ctx, tr("no_source_lyrics"), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!snapshot.songId.equals(initialSource.songId)
                        || snapshot.lines.size() > MAX_TRANSLATION_LINES
                        || sourceCharacterCount(snapshot.lines) > MAX_TRANSLATION_CHARS) {
                    Toast.makeText(ctx, tr("translation_too_long"),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                translateAndRender(ctx, song, snapshot, providerValue, apiKeyValue,
                        accountIdValue, modelValue, languageValue, systemPromptValue, true);
            }
        });
    }

    /**
     * Dialog footer action: translate the current song immediately using the
     * SAVED settings (provider/key/account/model/language/system prompt), via
     * the same translateAndRender path as every other entry point. Incomplete
     * settings leave the dialog open and show the existing validation message;
     * a missing song gets an explicit toast instead of a silent no-op.
     */
    private static void translateNowWithSavedSettings(Context ctx, Object song,
                                                      Object fragmentObj, AlertDialog dialog) {
        try {
            if (ctx == null) return;
            Object target = translateNowSong(song, fragmentObj);
            TranslationSettings settings = readTranslationSettings(ctx);
            String missing = missingTranslationConfig(settings.provider, settings.apiKey,
                    settings.accountId, settings.model, settings.language);
            if (missing != null) {
                Toast.makeText(ctx, tr(missing), Toast.LENGTH_SHORT).show();
                return;
            }
            if (target == null) {
                Log.d(TAG, "Translate now: no song from player, dialog or lastSong");
                Toast.makeText(ctx, tr("no_loaded"), Toast.LENGTH_SHORT).show();
                return;
            }
            SourceSnapshot snapshot = translateNowSource(ctx, target);
            if ((snapshot == null || snapshot.lines.isEmpty())
                    && song != null && song != target) {
                SourceSnapshot fallback = translateNowSource(ctx, song);
                if (fallback != null && !fallback.lines.isEmpty()) {
                    target = song;
                    snapshot = fallback;
                }
            }
            if (snapshot == null || snapshot.lines.isEmpty()) {
                Object last;
                synchronized (LrclibFallback.class) {
                    last = lastSong;
                }
                if (last != null && last != target && last != song) {
                    SourceSnapshot fromLast = translateNowSource(ctx, last);
                    if (fromLast != null && !fromLast.lines.isEmpty()) {
                        target = last;
                        snapshot = fromLast;
                    }
                }
            }
            if (snapshot == null || snapshot.lines.isEmpty()) {
                Toast.makeText(ctx, tr("no_source_lyrics"), Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            translateAndRender(ctx, target, snapshot, settings.provider, settings.apiKey,
                    settings.accountId, settings.model, settings.language,
                    settings.systemPrompt, true);
        } catch (Throwable t) {
            Log.d(TAG, "translateNowWithSavedSettings failed: " + t);
        }
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private interface TextSettingListener {
        void onValueChanged(String value);
    }

    private interface ModelSelectionListener {
        void onModelSelected(String model);
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void addSettingsSection(Context ctx, LinearLayout parent, String title) {
        TextView heading = new TextView(ctx);
        heading.setText(title);
        heading.setTextColor(Color.rgb(177, 203, 232));
        heading.setTextSize(14);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, dp(ctx, 16), 0, dp(ctx, 8));
        parent.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static TextView addSettingsRow(Context ctx, LinearLayout parent,
                                           String title, String summary,
                                           View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(ctx, 20), dp(ctx, 15), dp(ctx, 20), dp(ctx, 15));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(20, 22, 25));
        background.setCornerRadius(dp(ctx, 20));
        card.setBackground(background);

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(235, 238, 243));
        titleView.setTextSize(16);
        titleView.setTypeface(null, Typeface.BOLD);
        card.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView summaryView = new TextView(ctx);
        summaryView.setText(summary);
        summaryView.setTextColor(Color.rgb(166, 172, 181));
        summaryView.setTextSize(14);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(ctx, 4);
        card.addView(summaryView, summaryParams);

        if (listener != null) {
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(listener);
        }
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(ctx, 6);
        parent.addView(card, cardParams);
        return summaryView;
    }

    private static void refreshSettingsSummary(View row, String title, String summary) {
        if (!(row instanceof LinearLayout)) return;
        LinearLayout card = (LinearLayout) row;
        if (card.getChildCount() < 2 || !(card.getChildAt(0) instanceof TextView)
                || !(card.getChildAt(1) instanceof TextView)) return;
        ((TextView) card.getChildAt(0)).setText(title);
        ((TextView) card.getChildAt(1)).setText(summary);
    }

    private static String maskedApiKey(String apiKey) {
        return apiKey == null || apiKey.trim().isEmpty()
                ? tr("not_configured") : "••••••••";
    }

    private static String maskedAccountId(String accountId) {
        return accountId == null || accountId.trim().isEmpty()
                ? tr("not_configured") : accountId.trim();
    }

    private static void applyCloudflareAccountRow(TranslationProvider provider, View row,
                                                  TextView summary, String accountId) {
        boolean visible = provider == TranslationProvider.CLOUDFLARE;
        if (row != null) row.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (summary != null) summary.setText(maskedAccountId(accountId));
    }

    private static void showTextSettingDialog(Context ctx, String title, String initial,
                                              int inputType, boolean multiline,
                                              final TextSettingListener listener) {
        final EditText input = new EditText(ctx);
        input.setInputType(inputType);
        input.setText(initial == null ? "" : initial);
        input.setHint(multiline ? tr("custom_prompt_hint") : title);
        if (multiline) {
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(3);
            input.setMaxLines(6);
        } else {
            input.setSingleLine(true);
        }
        int pad = dp(ctx, 20);
        LinearLayout container = new LinearLayout(ctx);
        container.setPadding(pad, 0, pad, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog editor = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.onValueChanged(input.getText().toString().trim());
                editor.dismiss();
            }
        });
        if (!multiline) {
            input.requestFocus();
        }
    }

    private static void showModelSelector(final Context ctx, final TranslationProvider provider,
                                          final String currentModel,
                                          final ModelSelectionListener listener) {
        showModelSelector(ctx, provider, "", "", currentModel, listener);
    }

    private static void showModelSelector(final Context ctx, final TranslationProvider provider,
                                          final String accountId, final String apiKey,
                                          final String currentModel,
                                          final ModelSelectionListener listener) {
        final ProgressDialog progress = new ProgressDialog(ctx);
        progress.setMessage(String.format(tr("fetching_models"), provider.displayName));
        progress.setCancelable(true);
        progress.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Model> models = null;
                boolean fetchFailed = false;
                try {
                    models = fetchTranslationModels(provider, accountId, apiKey);
                    if (models.isEmpty()) fetchFailed = true;
                } catch (Throwable t) {
                    fetchFailed = true;
                    Log.d(TAG, provider.displayName + " model catalog fetch failed: " + t);
                }
                final List<Model> result = models == null || models.isEmpty()
                        ? provider.fallbackModels() : models;
                final boolean showFallbackMessage = fetchFailed;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!progress.isShowing()) return;
                            progress.dismiss();
                            if (ctx instanceof Activity
                                    && (((Activity) ctx).isFinishing()
                                    || ((Activity) ctx).isDestroyed())) return;
                            if (showFallbackMessage) {
                                Toast.makeText(ctx, tr("models_fetch_failed"),
                                        Toast.LENGTH_SHORT).show();
                            }
                            showModelSelectorDialog(ctx, provider, currentModel, listener, result);
                        } catch (Throwable t) {
                            Log.d(TAG, "Could not show model selector: " + t);
                        }
                    }
                });
            }
        }).start();
    }

    private static List<Model> fetchTranslationModels(TranslationProvider provider) throws Exception {
        return fetchTranslationModels(provider, "", "");
    }

    private static List<Model> fetchTranslationModels(TranslationProvider provider,
                                                     String accountId, String apiKey)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            String catalogUrl = provider == TranslationProvider.CLOUDFLARE
                    ? provider.modelsUrl(accountId) : provider.modelsUrl;
            connection = (HttpURLConnection) new URL(catalogUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AnghamiLyricsFallback/1.0");
            if (provider == TranslationProvider.CLOUDFLARE && apiKey != null
                    && !apiKey.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new Exception(provider.displayName + " returned HTTP " + status);
            }
            String body = readUtf8(connection.getInputStream());
            if (provider == TranslationProvider.CLOUDFLARE) {
                String failure = cloudflareFailureMessage(body);
                if (failure != null) throw new Exception(failure);
            }
            return provider.parseModels(body);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void showModelSelectorDialog(final Context ctx, final TranslationProvider provider,
                                                final String currentModel,
                                                final ModelSelectionListener listener,
                                                final List<Model> models) {
        final String[] choices = new String[models.size() + 1];
        for (int i = 0; i < models.size(); i++) {
            choices[i] = models.get(i).displayName();
        }
        choices[models.size()] = tr("model_custom");
        int selected = modelIndex(currentModel, models);
        final AlertDialog selector = new AlertDialog.Builder(ctx)
                .setTitle(provider.displayName + " · " + tr("model_selector_title"))
                .setSingleChoiceItems(choices, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < models.size()) {
                            listener.onModelSelected(models.get(which).id);
                            dialog.dismiss();
                            return;
                        }
                        dialog.dismiss();
                        String customInitial = modelIndex(currentModel, models) == models.size()
                                ? currentModel : "";
                        showTextSettingDialog(ctx, tr("edit_model"), customInitial,
                                android.text.InputType.TYPE_CLASS_TEXT
                                        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                                false, new TextSettingListener() {
                                    @Override
                                    public void onValueChanged(String value) {
                                        if (value.trim().isEmpty()) {
                                            Toast.makeText(ctx, tr("translation_config_required"),
                                                    Toast.LENGTH_SHORT).show();
                                        } else {
                                            listener.onModelSelected(value.trim());
                                        }
                                    }
                                });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        selector.show();
    }

    private static int modelIndex(String model, List<Model> models) {
        if (model != null) {
            for (int i = 0; i < models.size(); i++) {
                if (models.get(i).id.equals(model)) return i;
            }
        }
        return models.size();
    }

    private static int sourceCharacterCount(List<Line> lines) {
        int count = 0;
        if (lines == null) return 0;
        for (Line line : lines) {
            if (line != null && line.text != null) {
                count += line.text.length();
                if (count > MAX_TRANSLATION_CHARS) return count;
            }
        }
        return count;
    }

    /**
     * True when a single lyric line contains at least one Arabic letter.
     * Digits, punctuation and diacritics are not letters and do not count;
     * UnicodeScript covers the Arabic block (U+0600..U+06FF), its supplement
     * and extended blocks, and the Arabic presentation forms. A mixed line
     * (Arabic plus, say, English words) counts as Arabic so its original text
     * is never rewritten by the translation model.
     */
    static boolean isArabicScriptLine(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetter(codePoint)
                    && Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.ARABIC) {
                return true;
            }
        }
        return false;
    }

    /** True only when there is nothing to translate: every non-blank line is Arabic script. */
    static boolean allArabicScriptLines(List<Line> lines) {
        if (lines == null || lines.isEmpty()) return false;
        for (Line line : lines) {
            String text = line == null ? null : line.text;
            if (text == null || text.trim().isEmpty()) continue;
            if (!isArabicScriptLine(text)) return false;
        }
        return true;
    }

    /**
     * Authoritative per-line passthrough: Arabic-script source lines keep their
     * exact original text regardless of what the model returned, while every
     * other line uses the translated text. Always returns one entry per source
     * line, in source order, so dual-line rendering stays aligned.
     */
    static List<String> preserveArabicLines(List<Line> source, List<String> translated) {
        List<String> out = new ArrayList<String>();
        if (source == null) return out;
        for (int i = 0; i < source.size(); i++) {
            Line line = source.get(i);
            String original = line == null || line.text == null ? "" : line.text;
            if (isArabicScriptLine(original)) {
                out.add(original);
                continue;
            }
            String value = translated != null && i < translated.size()
                    ? translated.get(i) : null;
            out.add(value == null ? "" : value);
        }
        return out;
    }

    /**
     * Render one displayed lyric line. An Arabic-script source always shows its
     * original text alone, so a passthrough entry is never displayed twice as
     * "original + translation"; other lines use the usual dual-line form.
     */
    static String displayLineText(String original, String translation) {
        String text = original == null ? "" : original;
        if (isArabicScriptLine(text)) return text;
        String value = translation == null ? "" : translation;
        return value.trim().isEmpty() ? text : text + "\n" + value;
    }

    private static boolean hasConfiguredAutomaticTranslation(Context ctx) {
        if (ctx == null) return false;
        TranslationSettings settings = readTranslationSettings(ctx);
        return settings.autoTranslate && !settings.apiKey.trim().isEmpty()
                && !settings.model.trim().isEmpty() && !settings.language.trim().isEmpty()
                && hasRequiredAccountId(settings);
    }

    private static boolean hasRequiredAccountId(TranslationSettings settings) {
        return settings.provider != TranslationProvider.CLOUDFLARE
                || !settings.accountId.trim().isEmpty();
    }

    private static boolean canAutoTranslate(TranslationSettings settings,
                                            SourceSnapshot snapshot) {
        return settings != null && settings.autoTranslate
                && !settings.apiKey.trim().isEmpty()
                && !settings.model.trim().isEmpty()
                && !settings.language.trim().isEmpty()
                && hasRequiredAccountId(settings)
                && snapshot != null && !snapshot.lines.isEmpty()
                && !allArabicScriptLines(snapshot.lines)
                && snapshot.lines.size() <= MAX_TRANSLATION_LINES
                && sourceCharacterCount(snapshot.lines) <= MAX_TRANSLATION_CHARS;
    }

    private static boolean sameTranslationSettings(TranslationSettings left,
                                                   TranslationSettings right) {
        return left.provider == right.provider && left.apiKey.equals(right.apiKey)
                && left.accountId.equals(right.accountId)
                && left.model.equals(right.model) && left.language.equals(right.language)
                && left.systemPrompt.equals(right.systemPrompt)
                && left.autoTranslate == right.autoTranslate;
    }

    private static boolean autoTranslateIfEnabled(final Context ctx, final Object song) {
        try {
            if (ctx == null || song == null) return false;
            final TranslationSettings settings = readTranslationSettings(ctx);
            final SourceSnapshot snapshot = translationSourceFor(ctx, song);
            if (!canAutoTranslate(settings, snapshot)) return false;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    TranslationSettings current = readTranslationSettings(ctx);
                    SourceSnapshot latest = translationSourceFor(ctx, song);
                    if (!sourceMatchesForResult(latest, snapshot)) return;
                    if (!sameTranslationSettings(settings, current)) {
                        if (!autoTranslateIfEnabled(ctx, song)) {
                            renderOriginalLyricsIfCurrent(song, snapshot);
                        }
                        return;
                    }
                    if (!canAutoTranslate(current, latest)) {
                        renderOriginalLyricsIfCurrent(song, snapshot);
                        return;
                    }
                    translateAndRender(ctx, song, latest, current.provider, current.apiKey,
                            current.accountId, current.model, current.language,
                            current.systemPrompt, false);
                }
            });
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "autoTranslateIfEnabled failed: " + t);
            return false;
        }
    }

    private static void translateAndRender(final Context ctx, final Object song,
                                           final SourceSnapshot snapshot,
                                           final TranslationProvider provider,
                                           final String apiKey, final String accountId,
                                           final String model,
                                           final String language,
                                           final String systemPrompt,
                                           final boolean interactive) {
        if (snapshot.lines.isEmpty()) {
            if (interactive) {
                Toast.makeText(ctx, tr("no_source_lyrics"), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (allArabicScriptLines(snapshot.lines)) {
            if (interactive) {
                Toast.makeText(ctx, tr("arabic_source_skipped"),
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final String cacheKey;
        try {
            String key = translationCacheKey(snapshot, provider.cacheModelKey(model),
                    language, systemPrompt);
            // Cloudflare-only namespace: whole-song entries written by the old
            // single-request path could be truncated/partial, so never reuse
            // them as a complete batched result. Other providers keep their
            // existing cache key format untouched.
            cacheKey = provider == TranslationProvider.CLOUDFLARE
                    ? key + "|cfbatchv2" : key;
        } catch (Exception e) {
            Toast.makeText(ctx, tr("translation_failed"), Toast.LENGTH_SHORT).show();
            if (!interactive) renderOriginalLyricsIfCurrent(song, snapshot);
            return;
        }
        final String requestKey = cacheKey + "|" + snapshot.songId;
        final SourceSnapshot requestSnapshot = copySnapshot(snapshot);
        synchronized (LrclibFallback.class) {
            if (!translationRequestsInFlight.add(requestKey)) return;
            inFlightSnapshots.put(snapshot.songId, requestSnapshot);
        }
        final ProgressDialog progress;
        try {
            if (interactive) {
                progress = new ProgressDialog(ctx);
                progress.setMessage(String.format(tr("translating"), provider.displayName));
                progress.setCancelable(false);
                progress.show();
            } else {
                progress = null;
            }
        } catch (Throwable t) {
            synchronized (LrclibFallback.class) {
                translationRequestsInFlight.remove(requestKey);
                inFlightSnapshots.remove(snapshot.songId);
            }
            Log.d(TAG, "could not show translation progress: " + t);
            if (!interactive) renderOriginalLyricsIfCurrent(song, snapshot);
            return;
        }
        final boolean batched = provider == TranslationProvider.CLOUDFLARE;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> translated = readTranslationCache(ctx, cacheKey,
                        snapshot.lines.size());
                String failure = null;
                int failedBatches = 0;
                int totalBatches = 0;
                String batchError = null;
                if (translated == null) {
                    if (batched) {
                        // CLOUDFLARE ONLY: many small sequential requests. Each
                        // one has its own 10s read/connect timeout (set inside
                        // requestTranslation), no global job timeout; failures
                        // are isolated to their batch.
                        List<List<Line>> batches = translationBatchesFor(
                                provider, model, snapshot.lines);
                        final BatchTranslator translator = new BatchTranslator() {
                            @Override
                            public List<String> translate(List<Line> batch, String customPrompt)
                                    throws Exception {
                                return requestTranslation(provider, apiKey, accountId, model,
                                        null, language, customPrompt, batch);
                            }
                        };
                        BatchOutcome outcome = translateBatches(translationPrefs(ctx),
                                provider, accountId, model, language, snapshot.synced,
                                systemPrompt, batches, translator);
                        translated = outcome.lines;
                        failedBatches = outcome.failedBatches;
                        totalBatches = outcome.totalBatches;
                        batchError = outcome.firstError;
                        // Cache a whole-song result only if every batch worked;
                        // a partial result must retry its failed batches.
                        if (outcome.failedBatches == 0) {
                            writeTranslationCache(ctx, cacheKey, translated);
                        }
                    } else {
                        // OPENROUTER / OPENCODE / XKIRO: original single request,
                        // original prompt, original cache key — untouched.
                        try {
                            String sessionId = provider == TranslationProvider.OPENCODE
                                    ? openCodeSessionId(ctx) : null;
                            translated = requestTranslation(provider, apiKey, accountId, model,
                                    sessionId, language, systemPrompt, snapshot.lines);
                            writeTranslationCache(ctx, cacheKey, translated);
                        } catch (Throwable t) {
                            failure = safeTranslationError(t);
                            logTranslation(provider.displayName + " translation failed: " + t);
                        }
                    }
                }
                final List<String> result = translated;
                final String error = failure;
                final int failed = failedBatches;
                final int total = totalBatches;
                final String batchFailure = batchError;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (progress != null && progress.isShowing()) progress.dismiss();
                        } catch (Throwable ignored) {
                        }
                        try {
                            TranslationSettings selected = readTranslationSettings(ctx);
                            SourceSnapshot current = translationSourceFor(song);
                            if (!sourceMatchesForResult(current, snapshot)) {
                                if (interactive) {
                                    Toast.makeText(ctx, tr("source_changed"),
                                            Toast.LENGTH_SHORT).show();
                                }
                                return;
                            }
                            boolean settingsMatch = selected.provider == provider
                                    && selected.apiKey.equals(apiKey)
                                    && selected.accountId.equals(accountId)
                                    && selected.model.equals(model)
                                    && selected.language.equals(language)
                                    && selected.systemPrompt.equals(systemPrompt)
                                    && (interactive || selected.autoTranslate);
                            if (!settingsMatch) {
                                if (!interactive
                                        && !autoTranslateIfEnabled(ctx, song)) {
                                    renderOriginalLyricsIfCurrent(song, current);
                                }
                                return;
                            }
                            boolean allFailed = batched
                                    ? (total > 0 && failed == total)
                                    : (error != null || result == null);
                            if (allFailed) {
                                String message = tr("translation_failed");
                                String detail = batched ? batchFailure : error;
                                if (detail != null && !detail.trim().isEmpty()) {
                                    message += ": " + detail;
                                }
                                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                                if (!interactive) renderOriginalLyricsIfCurrent(song, current);
                                return;
                            }
                            // Successful batches render with the originals of
                            // failed batches (blank = original line), so the
                            // full line count/order is always preserved.
                            renderDualLines(song, current.callback, snapshot,
                                    preserveArabicLines(snapshot.lines, result));
                            if (batched && failed > 0) {
                                logTranslation("partial Cloudflare translation: " + failed
                                        + "/" + total + " batches failed for "
                                        + snapshot.songId);
                                Toast.makeText(ctx, tr("translation_partial"),
                                        Toast.LENGTH_SHORT).show();
                            } else if (interactive) {
                                Toast.makeText(ctx, tr("translation_done"),
                                        Toast.LENGTH_SHORT).show();
                            }
                        } finally {
                            synchronized (LrclibFallback.class) {
                                translationRequestsInFlight.remove(requestKey);
                                inFlightSnapshots.remove(snapshot.songId);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    static boolean sameSourceLines(List<Line> a, List<Line> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Line left = a.get(i);
            Line right = b.get(i);
            if (left.ms != right.ms || !String.valueOf(left.text).equals(String.valueOf(right.text))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Semantic equality for the result guard: only the song and the visible
     * text sequence matter. Cache-derived lines and live-rendered lines can
     * carry different timestamps (or provenance) for the same lyrics, and
     * that must not be reported as "source changed".
     */
    static boolean sameSourceText(List<Line> a, List<Line> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String left = String.valueOf(a.get(i).text);
            String right = String.valueOf(b.get(i).text);
            if (!left.equals(right)) return false;
        }
        return true;
    }

    /** Result-handler guard: same song + same visible text is not a change. */
    static boolean sourceMatchesForResult(SourceSnapshot current, SourceSnapshot request) {
        if (current == null || request == null) return false;
        return String.valueOf(current.songId).equals(String.valueOf(request.songId))
                && sameSourceText(current.lines, request.lines);
    }

    private static String translationCacheKey(SourceSnapshot source, String model,
                                              String language, String systemPrompt)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cacheHeader = model + "\n" + language + "\n" + source.synced + "\n";
        cacheHeader += translationSystemPrompt(systemPrompt, source.lines.size()) + "\n";
        digest.update(cacheHeader.getBytes("UTF-8"));
        for (Line line : source.lines) {
            digest.update(String.valueOf(line.ms).getBytes("UTF-8"));
            digest.update((byte) 0);
            digest.update(String.valueOf(line.text).getBytes("UTF-8"));
            digest.update((byte) 10);
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder("translation_");
        for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static String translationSystemPrompt(String customPrompt, int lineCount) {
        String instructions = DEFAULT_TRANSLATION_SYSTEM_PROMPT
                .replace("{lineCount}", String.valueOf(lineCount))
                + "\n\nRead all supplied lines together as one song and use context to resolve "
                + "pronouns, idioms, references, tone, and ambiguity. Make translations natural "
                + "for music listening while staying faithful to meaning, tense, names, "
                + "punctuation, and repeated hooks. Preserve text already in the target language "
                + "verbatim, including target-language parts of mixed lines. Lines written in "
                + "Arabic script are copied back verbatim and their translation ignored; still "
                + "return one entry per input line.";
        String custom = trimOrEmpty(customPrompt);
        if (!custom.isEmpty()) {
            instructions += ADDITIONAL_PROMPT_MARKER + custom;
        }
        return instructions;
    }

    private static List<String> readTranslationCache(Context ctx, String key, int count) {
        return readTranslationCache(translationPrefs(ctx), key, count);
    }

    /** SharedPreferences overload keeps per-batch cache reuse unit-testable. */
    static List<String> readTranslationCache(SharedPreferences p, String key, int count) {
        try {
            String raw = p.getString(key, null);
            if (raw == null) return null;
            JSONArray array = new JSONArray(raw);
            if (array.length() != count) return null;
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (!(value instanceof String)) return null;
                out.add((String) value);
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeTranslationCache(Context ctx, String key,
                                              List<String> translated) {
        writeTranslationCache(translationPrefs(ctx), key, translated);
    }

    static void writeTranslationCache(SharedPreferences p, String key,
                                      List<String> translated) {
        try {
            JSONArray array = new JSONArray();
            for (String line : translated) array.put(line);
            p.edit().putString(key, array.toString()).apply();
        } catch (Throwable t) {
            Log.d(TAG, "translation cache write failed: " + t);
        }
    }

    /**
     * Validation for the one-tap "Translate now" action, which always uses the
     * saved settings. Returns the message key describing what is missing, or
     * null when the configuration is usable. Package-private for unit tests.
     */
    static String missingTranslationConfig(TranslationProvider provider, String apiKey,
                                           String accountId, String model, String language) {
        boolean needsAccount = provider == TranslationProvider.CLOUDFLARE;
        if (needsAccount && trimOrEmpty(accountId).isEmpty()) return "cloudflare_config_required";
        if (trimOrEmpty(apiKey).isEmpty() || trimOrEmpty(model).isEmpty()
                || trimOrEmpty(language).isEmpty()) return "translation_config_required";
        return null;
    }

    /** True when the immediate translate action can run with these saved settings. */
    static boolean hasUsableTranslationConfig(TranslationProvider provider, String apiKey,
                                              String accountId, String model, String language) {
        return missingTranslationConfig(provider, apiKey, accountId, model, language) == null;
    }

    // ================= batched long-song translation =================

    /** Outcome of one batched translation job, sized to the full source lyric. */
    static final class BatchOutcome {
        final List<String> lines;
        final int totalBatches;
        final int failedBatches;
        final int cachedBatches;
        final String firstError;

        BatchOutcome(List<String> lines, int totalBatches, int failedBatches,
                     int cachedBatches, String firstError) {
            this.lines = lines;
            this.totalBatches = totalBatches;
            this.failedBatches = failedBatches;
            this.cachedBatches = cachedBatches;
            this.firstError = firstError;
        }
    }

    /** One request per batch; injected so tests can supply results or failures. */
    interface BatchTranslator {
        List<String> translate(List<Line> batch, String customPrompt) throws Exception;
    }

    /**
     * {maxLines, maxChars} per request. Only CLOUDFLARE uses batching in
     * production; its Workers AI models have much smaller output caps, and a
     * small model id (3b/8b in the name) gets an even tighter batch. The other
     * providers translate the whole song in one request exactly as before —
     * their entries are provided for completeness/tests only.
     */
    static int[] translationBatchConfig(TranslationProvider provider, String model) {
        String id = trimOrEmpty(model).toLowerCase(Locale.ROOT);
        boolean small = id.contains("3b") || id.contains("8b");
        if (provider == TranslationProvider.CLOUDFLARE) {
            return small
                    ? new int[]{BATCH_LINES_CLOUDFLARE_SMALL, BATCH_CHARS_CLOUDFLARE_SMALL}
                    : new int[]{BATCH_LINES_CLOUDFLARE, BATCH_CHARS_CLOUDFLARE};
        }
        return small
                ? new int[]{BATCH_LINES_SMALL_MODEL, BATCH_CHARS_SMALL_MODEL}
                : new int[]{BATCH_LINES_STANDARD, BATCH_CHARS_STANDARD};
    }

    /**
     * The batch plan for a song. Every provider other than CLOUDFLARE gets a
     * single batch containing all lines — its existing one-request path stays
     * untouched. Only Cloudflare is split into small sequential requests.
     */
    static List<List<Line>> translationBatchesFor(TranslationProvider provider, String model,
                                                  List<Line> lines) {
        if (provider != TranslationProvider.CLOUDFLARE) {
            List<List<Line>> single = new ArrayList<List<Line>>();
            if (lines != null && !lines.isEmpty()) single.add(new ArrayList<Line>(lines));
            return single;
        }
        int[] config = translationBatchConfig(provider, model);
        return splitTranslationBatches(lines, config[0], config[1]);
    }

    /**
     * Split source lines into sequential batches bounded by BOTH line count and
     * character count. Empty lines and timestamps are copied verbatim, and a
     * single oversized line still forms a (one-line) batch instead of being
     * dropped. Order and total line count are preserved exactly.
     */
    static List<List<Line>> splitTranslationBatches(List<Line> lines,
                                                    int maxLines, int maxChars) {
        List<List<Line>> batches = new ArrayList<List<Line>>();
        if (lines == null || lines.isEmpty()) return batches;
        if (maxLines < 1) maxLines = 1;
        if (maxChars < 1) maxChars = 1;
        List<Line> current = new ArrayList<Line>();
        int chars = 0;
        for (Line line : lines) {
            int len = line == null || line.text == null ? 0 : line.text.length();
            if (!current.isEmpty()
                    && (current.size() >= maxLines || chars + len > maxChars)) {
                batches.add(current);
                current = new ArrayList<Line>();
                chars = 0;
            }
            current.add(line);
            chars += len;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    /** Force a result list to exactly {@code size} entries (blank for missing). */
    private static List<String> fitToSize(List<String> values, int size) {
        List<String> out = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) {
            String value = values != null && i < values.size() ? values.get(i) : null;
            out.add(value == null ? "" : value);
        }
        return out;
    }

    /** Concatenate batch results in order; a null/failed batch becomes blank lines. */
    static List<String> concatBatchResults(List<List<Line>> batches,
                                           List<List<String>> results) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < batches.size(); i++) {
            List<String> values = results != null && i < results.size()
                    ? results.get(i) : null;
            out.addAll(fitToSize(values, batches.get(i).size()));
        }
        return out;
    }

    /**
     * Cache key for one batch. Built only from the batch's OWN identity —
     * provider, account id (Cloudflare), model, language, synced flag, system
     * prompt and the batch's lines — never the whole song. That way a cached
     * batch stays reusable when another part of the lyric changes, and a
     * failed batch can be retried without re-requesting the successful ones.
     */
    static String translationBatchCacheKey(TranslationProvider provider, String accountId,
                                           String model, String language, boolean synced,
                                           String customPrompt, int batchIndex,
                                           List<Line> batch) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder header = new StringBuilder();
        header.append("cfbatch\n")
                .append(provider == null ? "" : provider.cacheModelKey(model)).append('\n')
                .append(trimOrEmpty(accountId)).append('\n')
                .append(trimOrEmpty(model)).append('\n')
                .append(trimOrEmpty(language)).append('\n')
                .append(synced).append('\n')
                .append(batchIndex).append('\n')
                .append(translationSystemPrompt(customPrompt, batch.size())).append('\n');
        digest.update(header.toString().getBytes("UTF-8"));
        for (Line line : batch) {
            digest.update(String.valueOf(line == null ? -1 : line.ms).getBytes("UTF-8"));
            digest.update((byte) 0);
            digest.update(String.valueOf(line == null ? "" : line.text).getBytes("UTF-8"));
            digest.update((byte) 10);
        }
        StringBuilder hex = new StringBuilder("translation_b" + batchIndex + "_");
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    /**
     * Translate all Cloudflare batches sequentially: check each batch's cache,
     * issue one request on a miss, cache successes, and keep blank lines for
     * failures so no source line is ever dropped. Each request has its own 10s
     * timeout inside {@code requestTranslation}; there is no global job timeout
     * and one slow/failed batch cannot abort the rest of the song.
     */
    static BatchOutcome translateBatches(SharedPreferences prefs, TranslationProvider provider,
                                         String accountId, String model, String language,
                                         boolean synced, String customPrompt,
                                         List<List<Line>> batches, BatchTranslator translator) {
        List<List<String>> results = new ArrayList<List<String>>();
        int failed = 0;
        int cached = 0;
        String firstError = null;
        for (int i = 0; i < batches.size(); i++) {
            List<Line> batch = batches.get(i);
            String key = null;
            List<String> values = null;
            try {
                key = translationBatchCacheKey(provider, accountId, model, language,
                        synced, customPrompt, i, batch);
                values = readTranslationCache(prefs, key, batch.size());
            } catch (Throwable t) {
                logTranslation("batch " + i + " cache key failed: " + t);
            }
            if (values != null) {
                cached++;
                results.add(values);
                continue;
            }
            try {
                values = translator.translate(batch, customPrompt);
                if (values == null) throw new Exception("translation returned no result");
                values = fitToSize(values, batch.size());
                if (key != null) writeTranslationCache(prefs, key, values);
            } catch (Throwable t) {
                failed++;
                String reason = safeTranslationError(t);
                if (firstError == null) firstError = reason;
                // One empty model response is a failed batch, not a failed
                // song: its lines stay original and the other batches still
                // render. Keep the log line concise enough for logcat.
                if (reason.contains("no response text")) {
                    logTranslation("translation batch " + i + " returned an empty model response");
                } else {
                    logTranslation("translation batch " + i + " failed: " + reason);
                }
                values = new ArrayList<String>();
            }
            results.add(values);
        }
        return new BatchOutcome(concatBatchResults(batches, results), batches.size(),
                failed, cached, firstError);
    }

    private static List<String> requestTranslation(TranslationProvider provider, String apiKey,
                                                   String model, String sessionId, String language,
                                                   String systemPrompt, List<Line> source)
            throws Exception {
        return requestTranslation(provider, apiKey, "", model, sessionId, language, systemPrompt,
                source);
    }

    private static List<String> requestTranslation(TranslationProvider provider, String apiKey,
                                                   String accountId, String model,
                                                   String sessionId, String language,
                                                   String systemPrompt, List<Line> source)
            throws Exception {
        boolean cloudflare = provider == TranslationProvider.CLOUDFLARE;
        JSONArray inputLines = new JSONArray();
        for (Line line : source) inputLines.put(line.text == null ? "" : line.text);
        JSONObject request = new JSONObject();
        // Cloudflare identifies the model in the URL path; sending a "model"
        // field in the body is not part of its run endpoint contract.
        if (!cloudflare) request.put("model", model);
        if (provider == TranslationProvider.OPENROUTER) request.put("temperature", 0.1);
        request.put("stream", false);
        String systemInstructions = translationSystemPrompt(systemPrompt, source.size());
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", systemInstructions));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", "Translate the `lines` JSON array into " + language
                        + " for synced song lyrics. Interpret the full lyric as one work "
                        + "before translating, but return one translation per source line. "
                        + "Keep line order, repeated hooks, names, punctuation, and empty "
                        + "lines. Return exactly " + source.size() + " output lines in a "
                        + "JSON object with a string array named `lines`.\n\n"
                        + "{\"lines\":" + inputLines.toString() + "}"));
        request.put("messages", messages);

        Exception lastError = null;
        // Prefer strict schema output, then relax to JSON mode for models or
        // provider routes that do not support structured outputs. Cloudflare's
        // run endpoint has no response_format, so it uses a single plain call.
        int firstOutputMode = cloudflare ? 0
                : provider == TranslationProvider.XKIRO ? 1 : 2;
        for (int outputMode = firstOutputMode; outputMode >= 0; outputMode--) {
            HttpURLConnection connection = null;
            try {
                JSONObject attempt = new JSONObject(request.toString());
                if (outputMode > 0) {
                    attempt.put("response_format",
                            translationResponseFormat(outputMode, source.size()));
                }
                String endpoint = cloudflare
                        ? provider.completionsUrl(accountId, model) : provider.completionsUrl;
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");
                if (provider == TranslationProvider.OPENROUTER) {
                    connection.setRequestProperty("HTTP-Referer",
                            "https://github.com/0xgalal/anghami-patches");
                    connection.setRequestProperty("X-Title", "Anghami AI Lyrics Translation");
                } else if (provider == TranslationProvider.OPENCODE) {
                    connection.setRequestProperty("x-opencode-session", sessionId);
                }
                // The translation endpoint gets its own 10s budget for both
                // connect and read; lyric-source requests keep their own
                // timeouts. Slow models fail fast instead of hanging lyrics.
                int timeout = translationRequestTimeoutMs();
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                java.io.OutputStream out = connection.getOutputStream();
                out.write(attempt.toString().getBytes("UTF-8"));
                out.flush();
                out.close();

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String body = readUtf8(stream);
                if (status >= 200 && status < 300) {
                    String failure = cloudflare ? cloudflareFailureMessage(body) : null;
                    if (failure != null) throw new Exception(failure);
                    return parseTranslationResponse(body, source);
                }
                String detail = cloudflare ? cloudflareFailureMessage(body) : null;
                if (detail == null) {
                    detail = provider.displayName + " HTTP " + status;
                    try {
                        JSONObject error = new JSONObject(body).optJSONObject("error");
                        if (error != null) detail += ": " + error.optString("message", "");
                    } catch (Exception ignored) {
                    }
                }
                lastError = new Exception(detail);
                if (status != 400 && status != 422) throw lastError;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw lastError == null ? new Exception(provider.displayName + " request failed") : lastError;
    }

    private static JSONObject translationResponseFormat(int mode, int lineCount) throws Exception {
        if (mode == 1) return new JSONObject().put("type", "json_object");

        JSONObject stringSchema = new JSONObject()
                .put("type", "array")
                .put("minItems", lineCount)
                .put("maxItems", lineCount)
                .put("items", new JSONObject().put("type", "string"));
        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject().put("lines", stringSchema))
                .put("required", new JSONArray().put("lines"))
                .put("additionalProperties", false);
        return new JSONObject()
                .put("type", "json_schema")
                .put("json_schema", new JSONObject()
                        .put("name", "lyrics_translation")
                        .put("strict", true)
                        .put("schema", schema));
    }

    private static String readUtf8(InputStream input) throws Exception {
        if (input == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        StringBuilder text = new StringBuilder();
        String line;
        try {
            while ((line = reader.readLine()) != null) text.append(line);
        } finally {
            reader.close();
        }
        return text.toString();
    }

    /**
     * Cloudflare reports errors as {"success":false,"errors":[...]}. Returns a
     * readable message, or null when the body is not a Cloudflare envelope.
     */
    private static String cloudflareFailureMessage(String body) {
        try {
            JSONObject root = new JSONObject(body);
            if (!root.has("success") || root.optBoolean("success", true)) return null;
            StringBuilder detail = new StringBuilder("Cloudflare Workers AI");
            JSONArray errors = root.optJSONArray("errors");
            if (errors != null) {
                for (int i = 0; i < errors.length(); i++) {
                    JSONObject error = errors.optJSONObject(i);
                    String code = error == null ? "" : error.optString("code", "");
                    String message = error == null ? "" : error.optString("message", "");
                    if (!code.isEmpty()) detail.append(" error ").append(code);
                    if (!message.isEmpty()) detail.append(": ").append(message);
                }
            }
            if (detail.length() == "Cloudflare Workers AI".length()) {
                String message = root.optString("message", "");
                JSONArray messages = root.optJSONArray("messages");
                if (message.isEmpty() && messages != null && messages.length() > 0) {
                    Object first = messages.opt(0);
                    if (first instanceof String) {
                        message = (String) first;
                    } else if (first instanceof JSONObject) {
                        JSONObject m = (JSONObject) first;
                        message = m.optString("message", m.optString("content", ""));
                    }
                }
                detail.append(message.isEmpty() ? " request failed" : ": " + message);
            }
            return detail.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Pull the assistant's raw text out of a Cloudflare Workers AI envelope or
     * an OpenAI-style envelope. Workers AI models return several shapes in the
     * wild, so every plausible text location is tried before declaring a batch
     * empty. Non-JSON/truncated bodies are left to the lenient salvage path in
     * parseTranslationResponse, which handles them from the raw text.
     */
    private static String extractTranslationContent(String body) throws Exception {
        JSONObject root = new JSONObject(body); // JSON failures are salvaged by the caller.

        // Cloudflare error envelopes are commonly returned with HTTP 200. Give
        // the real errors[].message/code instead of a misleading "no text".
        String failure = cloudflareFailureMessage(body);
        if (failure != null) throw new Exception(failure);

        String text = cloudflareResultText(root);
        if (text != null && !text.trim().isEmpty()) return text.trim();

        JSONArray choices = root.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            String fromChoices = choicesText(choices);
            if (fromChoices != null && !fromChoices.trim().isEmpty()) return fromChoices.trim();
            JSONObject choice = choices.optJSONObject(0);
            String finishReason = choice == null ? "" : choice.optString("finish_reason", "");
            if ("length".equalsIgnoreCase(finishReason)
                    || "max_tokens".equalsIgnoreCase(finishReason)) {
                throw new Exception("Translation response reached the model's output limit");
            }
        }
        // Last resort for non-enveloped provider responses.
        text = firstUsableText(root, "output", "text", "content", "response");
        if (text != null && !text.trim().isEmpty()) return text.trim();
        throw new Exception("Cloudflare Workers AI returned no response text");
    }

    /** All Workers AI result shapes: result.response, alternate fields, or a bare result. */
    private static String cloudflareResultText(JSONObject root) {
        Object result = root.opt("result");
        String text = firstUsableText(result, "response", "text", "content", "output");
        if (text != null && !text.trim().isEmpty()) return text;
        if (result instanceof JSONObject) {
            text = choicesText(((JSONObject) result).optJSONArray("choices"));
            if (text != null && !text.trim().isEmpty()) return text;
        }
        // result itself may be the model string or an array of text parts.
        return messageText(result);
    }

    /** Text from choices[0].message.content (or the choice's own text field). */
    private static String choicesText(JSONArray choices) {
        if (choices == null) return null;
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i);
            if (choice == null) continue;
            JSONObject message = choice.optJSONObject("message");
            String text = message == null ? null : messageText(message.opt("content"));
            if (text != null && !text.trim().isEmpty()) return text;
            text = firstUsableText(choice.opt("text"));
            if (text != null && !text.trim().isEmpty()) return text;
        }
        return null;
    }

    /** OpenAI-style message content: a string, a parts array, or a text object. */
    private static String messageText(Object content) {
        return firstUsableText(content, "text", "content");
    }

    /**
     * Find the first non-empty text inside a JSON value: a bare string, one of
     * {@code keys} of an object, or the joined strings of an array. Empty and
     * whitespace-only fields are skipped so an empty result.response cannot
     * mask a valid alternate field.
     */
    private static String firstUsableText(Object node, String... keys) {
        if (node == null) return null;
        if (node instanceof String) {
            String s = ((String) node).trim();
            return s.isEmpty() ? null : s;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (String key : keys) {
                String found = firstUsableText(object.opt(key));
                if (found != null && !found.trim().isEmpty()) return found;
            }
            return null;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            StringBuilder combined = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String part = firstUsableText(array.opt(i));
                if (part != null && !part.trim().isEmpty()) {
                    if (combined.length() > 0) combined.append('\n');
                    combined.append(part.trim());
                }
            }
            String text = combined.toString().trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private static final Pattern TRANSLATION_LINES_KEY =
            Pattern.compile("\"(?:lines|translations)\"\\s*:\\s*\\[", Pattern.CASE_INSENSITIVE);

    private static boolean isJsonParseError(Throwable error) {
        return error != null && error.getClass().getName().startsWith("org.json.");
    }

    /** Decode the response/content string from a damaged provider envelope. */
    private static String lenientEnvelopeContent(String body) {
        if (body == null) return "";
        String[] keys = {"response", "content"};
        for (String key : keys) {
            Matcher value = Pattern.compile("\"" + key + "\"\\s*:\\s*\"",
                    Pattern.CASE_INSENSITIVE).matcher(body);
            if (value.find()) {
                int[] cursor = new int[1];
                String decoded = parseLenientString(body, value.end() - 1, cursor);
                if (!decoded.trim().isEmpty()) return decoded;
            }
        }
        return body;
    }

    private static List<String> parseTranslationResponse(String body, List<Line> source)
            throws Exception {
        String content;
        try {
            content = extractTranslationContent(body);
        } catch (Exception envelopeError) {
            // A read timeout or output token cap can cut the HTTP body in the
            // middle of the envelope's response string, producing the same
            // "Unterminated string at character N" error before we even get to
            // the model text. Decode what we can from the raw body instead.
            if (!isJsonParseError(envelopeError)) throw envelopeError;
            logTranslation("translation envelope parse failed ("
                    + shortMessage(envelopeError) + "); salvaging raw body: "
                    + truncateForLog(body));
            content = lenientEnvelopeContent(body);
        }
        // Be forgiving about the envelope: fenced markdown, extra keys, a
        // bare array, or the older "translations" name are all acceptable.
        // What matters is getting an array of strings we can align to the
        // source. Models often return a slightly different count even with a
        // strict prompt, so never fail the whole translation for that.
        String json = stripCodeFences(content);
        List<String> values;
        try {
            JSONArray translated = parseStrictTranslationArray(json);
            values = jsonArrayValues(translated);
            // A model that ignored the schema sometimes returns one string with
            // the whole translation separated by newlines. Split it back apart.
            if (source.size() > 1 && values.size() == 1
                    && values.get(0).indexOf('\n') >= 0) {
                String[] parts = values.get(0).split("\\r?\\n");
                values = new ArrayList<String>();
                for (String part : parts) values.add(part.trim());
            }
        } catch (Throwable parseError) {
            // Small Cloudflare Workers AI models frequently emit malformed JSON:
            // raw newlines or quotes inside strings, trailing commas, prose
            // around the object, or a final string cut off by the token limit.
            // org.json's "Unterminated string at character N" is exactly this.
            // Recover what we can instead of failing the whole translation.
            logTranslation("strict translation JSON failed ("
                    + parseError.getClass().getSimpleName() + ": "
                    + shortMessage(parseError) + "); salvaging raw response: "
                    + truncateForLog(content));
            values = salvageTranslationLines(content);
        }
        return alignTranslationLines(values, source);
    }

    /** Remove a leading markdown fence (with optional language) from model text. */
    private static String stripCodeFences(String content) {
        if (content == null) return "";
        String json = content.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "").trim();
        }
        return json;
    }

    /** Unchanged strict path: org.json object/array with "lines" or legacy key. */
    private static JSONArray parseStrictTranslationArray(String json) throws Exception {
        if (json.startsWith("[")) return new JSONArray(json);
        JSONObject result = new JSONObject(json);
        JSONArray translated = result.optJSONArray("lines");
        if (translated == null) translated = result.optJSONArray("translations");
        if (translated == null) {
            throw new Exception("Translation response contained no lines array");
        }
        return translated;
    }

    private static List<String> jsonArrayValues(JSONArray array) {
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            values.add(value instanceof String ? (String) value : "");
        }
        return values;
    }

    /**
     * Best-effort recovery for malformed model output. Only called after
     * strict org.json parsing fails. Looks for a "lines"/"translations" key
     * first, then a bare array, then falls back to plain text lines.
     */
    private static List<String> salvageTranslationLines(String content) {
        if (content == null || content.trim().isEmpty()) return new ArrayList<String>();
        int arrayStart = findSalvageArrayStart(content);
        if (arrayStart >= 0) return parseLenientStringArray(content, arrayStart);
        return collectProseTranslationLines(content);
    }

    /** Locate the array to salvage, ignoring fences and surrounding prose. */
    private static int findSalvageArrayStart(String text) {
        Matcher key = TRANSLATION_LINES_KEY.matcher(text);
        if (key.find()) return key.end() - 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '[') continue;
            int next = i + 1;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
            // Only treat it as a value array when it opens with a quoted
            // string (or is empty), not e.g. a markdown "[link](url)".
            if (next < text.length()
                    && (text.charAt(next) == '"' || text.charAt(next) == ']')) return i;
        }
        return -1;
    }

    /**
     * Scan array elements without requiring well-formed JSON. Tolerates a
     * missing closing quote/bracket/brace, trailing commas, raw newlines and
     * quotes inside strings, and unquoted values.
     */
    private static List<String> parseLenientStringArray(String text, int arrayStart) {
        List<String> values = new ArrayList<String>();
        int i = arrayStart + 1; // skip '['
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c == ']' || c == '}') break;
            if (c == ',' || Character.isWhitespace(c)) { i++; continue; }
            if (c == '"') {
                int[] cursor = new int[1];
                values.add(parseLenientString(text, i, cursor));
                i = cursor[0];
                continue;
            }
            // Unquoted value: take everything up to the next separator.
            int end = i;
            while (end < length && text.charAt(end) != ','
                    && text.charAt(end) != ']' && text.charAt(end) != '}') end++;
            String value = text.substring(i, end).trim();
            if (!value.isEmpty()) values.add(value);
            i = end;
        }
        return values;
    }

    /** Read one string value, returning the index just past it via {@code cursor}. */
    private static String parseLenientString(String text, int openingQuote, int[] cursor) {
        StringBuilder value = new StringBuilder();
        int i = openingQuote + 1;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < length) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'b': case 'f': value.append(' '); break;
                    case 'u': {
                        boolean decoded = false;
                        if (i + 5 < length) {
                            try {
                                value.append((char) Integer.parseInt(
                                        text.substring(i + 2, i + 6), 16));
                                decoded = true;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (!decoded) value.append(next);
                        i += decoded ? 4 : 0;
                        break;
                    }
                    default: value.append(next); break;
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                int after = i + 1;
                while (after < length && Character.isWhitespace(text.charAt(after))) after++;
                // A quote is a real closing quote only at a structural boundary.
                // Otherwise it is an unescaped quote inside the translated text.
                if (after >= length || text.charAt(after) == ','
                        || text.charAt(after) == ']' || text.charAt(after) == '}') {
                    cursor[0] = i + 1;
                    return value.toString();
                }
                value.append('"');
                i++;
                continue;
            }
            // Raw newlines are invalid JSON but common from small models; keep
            // them as content and let alignTranslationLines fold them to spaces.
            value.append(c);
            i++;
        }
        // End of input reached with no closing quote (truncated response).
        cursor[0] = i;
        return value.toString();
    }

    /** Last resort: the model returned no array at all; split its text into lines. */
    private static List<String> collectProseTranslationLines(String content) {
        List<String> values = new ArrayList<String>();
        String[] lines = content.replace("\r\n", "\n").split("\n");
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.equals("```") || text.equalsIgnoreCase("```json")) continue;
            if (text.equals("{") || text.equals("}") || text.equals("[") || text.equals("]")) continue;
            values.add(text);
        }
        return values;
    }

    /** Pad or truncate recovered values to the source line count, as before. */
    private static List<String> alignTranslationLines(List<String> values, List<Line> source) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < source.size(); i++) {
            boolean sourceBlank = source.get(i).text == null
                    || source.get(i).text.trim().isEmpty();
            String text = "";
            if (values != null && i < values.size()) {
                String candidate = values.get(i);
                if (candidate != null) {
                    text = candidate.replace('\r', ' ').replace('\n', ' ').trim();
                }
            }
            // An empty translation renders the original line, which is the
            // safest alignment when the model dropped or merged lines.
            out.add(sourceBlank || text.isEmpty() ? "" : text);
        }
        return out;
    }

    /** Log with the translation tag; android.util.Log is a stub in unit tests. */
    private static void logTranslation(String message) {
        try {
            Log.d(TRANSLATION_TAG, message);
        } catch (Throwable ignored) {
        }
    }

    /** Short, single-line excerpt of a raw response for logcat. */
    private static String truncateForLog(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "...(truncated)";
    }

    private static String shortMessage(Throwable throwable) {
        if (throwable == null) return "";
        return truncateForLog(throwable.getMessage() == null
                ? throwable.getClass().getSimpleName() : throwable.getMessage());
    }

    /** Keep toast errors readable even when org.json embeds the whole payload. */
    private static String safeTranslationError(Throwable throwable) {
        if (throwable == null) return "";
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return truncateForLog(message);
    }

    /** Show a short status line on the live lyrics view (search progress). */
    private static void renderSearchStatus(final Object song, final Object callback,
                                           final String message) {
        if (song == null || callback == null || message == null) return;
        Runnable render = new Runnable() {
            @Override
            public void run() {
                try {
                    Object response = buildResponse(song, message, null);
                    if (response == null) return;
                    Object latest = callbackForSong(song);
                    if (latest == null) latest = callback;
                    // A status line replaces the lyrics on screen. Drop the
                    // last-render key so the following result always paints,
                    // even when it is the same text as before the search.
                    synchronized (LrclibFallback.class) {
                        renderedKeys.remove(latest);
                    }
                    invokeRender(latest, song, buildLines(response, message, null), response);
                } catch (Throwable t) {
                    Log.d(TAG, "could not render search status: " + t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            render.run();
        } else {
            new Handler(Looper.getMainLooper()).post(render);
        }
    }

    private static void renderTranslationPending(final Object song,
                                                final Object callback) {
        renderSearchStatus(song, callback, tr("translation_pending"));
    }

    private static void renderOriginalLyricsIfCurrent(Object song, SourceSnapshot expected) {
        if (song == null || expected == null) return;
        SourceSnapshot current = translationSourceFor(song);
        if (!sourceMatchesForResult(current, expected)) return;
        List<String> noTranslations = new ArrayList<String>();
        for (int i = 0; i < current.lines.size(); i++) noTranslations.add("");
        renderDualLines(song, current.callback, current, noTranslations);
    }

    private static void renderDualLines(Object song, Object callback,
                                        SourceSnapshot source,
                                        List<String> translated) {
        try {
            if (source.lines.size() != translated.size()) return;
            Class<?> lineClass = Class.forName("com.anghami.ghost.pojo.LyricsLine");
            List<Object> displayLines = new ArrayList<Object>();
            StringBuilder unsynced = new StringBuilder();
            int previousMs = -1;
            boolean hasTranslation = false;
            for (int i = 0; i < source.lines.size(); i++) {
                Line original = source.lines.get(i);
                String translation = translated.get(i) == null ? "" : translated.get(i);
                String text = original.text == null ? "" : original.text;
                if (!isArabicScriptLine(text) && !translation.trim().isEmpty()) {
                    hasTranslation = true;
                }
                String dual = displayLineText(original.text, translation);
                Object line = lineClass.newInstance();
                setLineField(line, "line", dual);
                setLineField(line, "milliseconds", original.ms);
                setLineField(line, "id", i);
                setLineField(line, "isActive", !source.synced
                        || i == 0 || previousMs < original.ms);
                previousMs = original.ms;
                displayLines.add(line);
                if (unsynced.length() > 0) unsynced.append('\n');
                unsynced.append(dual);
            }

            Class<?> responseClass =
                    Class.forName("com.anghami.ghost.api.response.LyricsResponse");
            Object response = responseClass.newInstance();
            try {
                setLineField(response, "songId", getFieldUp(song, "id"));
                setLineField(response, "truncated", false);
                setLineField(response, "isSynced", source.synced);
                if (source.synced) {
                    setLineField(response, "lyricsSynced", displayLines);
                    setLineField(response, "lyricsUnsynced", null);
                } else {
                    setLineField(response, "lyricsSynced", null);
                    setLineField(response, "lyricsUnsynced", unsynced.toString());
                }
            } catch (Exception ignored) {
            }
            // The translation can finish long after the lyrics view was
            // recreated. Render into the newest callback registered for this
            // song so the update lands on the visible player instead of a
            // detached view.
            Object latest = callbackForSong(song);
            if (latest == null) latest = callback;
            invokeRender(latest, song, displayLines, response);
            Log.d(TAG, hasTranslation
                    ? "Dual-line translation rendered"
                    : "Original lyrics restored after automatic translation");
        } catch (Throwable t) {
            Log.d(TAG, "dual-line render failed: " + t);
        }
    }
}
