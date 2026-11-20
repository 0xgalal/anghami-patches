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
    private static final String SOURCE_URL = "https://lrclib.net";
    private static final String UA = "AnghamiLyricsFallback/1.0 (+https://lrclib.net/docs)";
    private static final String PREFS = "lyrics_fallback";
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
    // Aggregator/Apple/KuGou endpoints can be slower than LRCLIB. Give them
    // a little more room, but keep it bounded so a total miss still ends.
    private static final int EXTENDED_TIMEOUT_MS = 9000;
    private static final String KUGOU_SEARCH_URL = "https://lyrics.kugou.com/search";
    private static final String KUGOU_DOWNLOAD_URL = "https://lyrics.kugou.com/download";
    private static final String[] LYRICSPLUS_BASES = {
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.binimum.org/"
    };
    private static final String BETTERLYRICS_URL = "https://lyrics-api.boidu.dev/getLyrics";
    private static final String PAXSENIX_SEARCH_URL =
            "https://lyrics.paxsenix.org/apple-music/search";
    private static final String PAXSENIX_LYRICS_URL =
            "https://lyrics.paxsenix.org/apple-music/lyrics";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final int MAX_EXTENDED_CANDIDATES = 3;
    private static final int DURATION_TOLERANCE_MS = 8000;
    private static final int CANDIDATE_DURATION_TOLERANCE_MS = 10000;
    private static final int TRANSLATION_TIMEOUT_MS = 90000;
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
     * Holds native lyrics for automatic translation without aborting the
     * native render. Returning early from {@code A7/E.b} used to skip its
     * loading-state cleanup, leaving the spinner up forever. Instead the
     * source lines are captured and the response is rewritten in place with
     * a short pending message, so the native method renders that message and
     * completes normally; the dual-line render replaces it once translation
     * finishes. Truncated/empty responses are left untouched so the LRCLIB
     * fallback can still fetch full lyrics for them.
     */
    public static void deferLyricsForTranslation(Object songObj, Object responseObj,
                                                 Object callbackObj) {
        try {
            if (songObj == null || responseObj == null || callbackObj == null) return;
            Context ctx = appContextOf(callbackObj);
            if (!hasConfiguredAutomaticTranslation(ctx)) return;
            if (!isFullNativeResponse(responseObj)) return;
            if (!captureNativeLyrics(songObj, responseObj, callbackObj)) return;

            setLineField(responseObj, "lyricsUnsynced", tr("translation_pending"));
            setLineField(responseObj, "lyricsSynced", null);
            setLineField(responseObj, "isSynced", false);
            setLineField(responseObj, "truncated", true);
            synchronized (LrclibFallback.class) {
                substituted.add(responseObj); // impl must not re-own this response.
            }
            Log.d(TAG, "native lyrics held for automatic translation");
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

    /** Enables Lyrics mode without toggling it off when it is already active. */
    private static void ensureLyricsMode(Object fragmentObj) {
        try {
            if (fragmentObj == null) return;
            Object viewModel = getFieldUp(fragmentObj, "n1");
            if (viewModel == null) return;
            Object modeState = getFieldUp(viewModel, "_playerMode");
            if (modeState == null) return;
            Object currentMode = modeState.getClass().getMethod("d").invoke(modeState);
            Class<?> modeClass =
                    Class.forName("com.anghami.player.ui.PlayerFragmentViewModel$c");
            Object lyricsMode = modeClass.getField("b").get(null);
            if (currentMode != lyricsMode) {
                viewModel.getClass().getMethod("onLyricsClicked").invoke(viewModel);
            }
        } catch (Throwable t) {
            Log.d(TAG, "ensureLyricsMode failed: " + t);
        }
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
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Result r;
                    try {
                        r = waterfall(songFinal);
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
        Cached hit = readCache(ctx, key);
        if (hit == null) {
            String legacy = legacyCacheKey(song);
            if (!legacy.equals(key)) hit = readCache(ctx, legacy);
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
            if (isFullNativeResponse(responseObj)) return;
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

    private static final class SongInfo {
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
        if (id instanceof String) s.id = (String) id;
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

    private static final class SourceSnapshot {
        String songId;
        boolean synced;
        Object song;
        Object callback;
        List<Line> lines = new ArrayList<Line>();
    }

    private static SourceSnapshot translationSourceFor(Object songObj) {
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
            Log.d(TAG, "translationSourceFor failed: " + t);
            return null;
        }
    }

    private static Object callbackForSong(Object songObj) {
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
            if (ar) return "لم يتم العثور على كلمات.\nافتح الإعدادات > الكلمات للبحث يدويًا.";
            if (fr) return "Paroles introuvables.\nOuvrez Paramètres > Paroles pour les rechercher manuellement.";
            return "No lyrics found.\nOpen Settings > Lyrics to search manually.";
        }
        if ("guide_teaser".equals(key)) {
            if (ar) return "افتح الإعدادات > الكلمات لخيارات الكلمات.";
            if (fr) return "Ouvrez Paramètres > Paroles pour les options des paroles.";
            return "Open Settings > Lyrics for lyrics options.";
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

    private static final class Cached {
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

    private static Cached readCache(Context ctx, String key) {
        try {
            String raw = prefs(ctx).getString(key, null);
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

    private static final class Result {
        String plain;
        String synced;
        String queryUsed = "";
        String source = "";
    }

    private static Result waterfall(SongInfo song) {
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
        r = apiSearchQ(title, artist, duration);
        if (r != null) return r;

        // LRCLIB came back empty. Walk the extra sources in priority order;
        // the first one with usable synced lyrics wins. Each source is
        // self-contained and swallows its own failures so one dead endpoint
        // cannot stop the rest of the chain.
        r = fromKugou(song);
        if (r != null) return r;
        r = fromLyricsPlus(song);
        if (r != null) return r;
        r = fromBetterLyrics(song);
        if (r != null) return r;
        return fromPaxsenix(song);
    }

    /**
     * Ordered, deduplicated (track, artist) queries for the exact-match
     * passes. The plain and paren-stripped variants coincide for most songs,
     * so collapsing them halves the request count in the common case.
     */
    static List<String[]> waterfallPairs(String title, String artist) {
        List<String[]> pairs = new ArrayList<String[]>();
        addWaterfallPair(pairs, normalize(title, true), normalize(artist, true));
        addWaterfallPair(pairs, normalize(title, false), normalize(artist, false));
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
                    "search track=" + track + " artist=" + artist);
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
            return pickFirst(new JSONArray(body), "search q=" + q);
        } catch (Exception e) {
            return null;
        }
    }

    // ============ extra lyrics sources (KuGou -> LyricsPlus -> Better Lyrics -> Paxsenix) ============

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

    /** KuGou: search candidates, then download a base64-encoded .lrc file. */
    private static Result fromKugou(SongInfo song) {
        try {
            String keyword = (normalize(song.title, true) + " - "
                    + normalize(song.artist, true)).trim();
            if ("-".equals(keyword) || keyword.trim().isEmpty()) return null;
            StringBuilder u = new StringBuilder(KUGOU_SEARCH_URL)
                    .append("?ver=1&man=yes&client=pc&keyword=").append(enc(keyword));
            if (song.hasDuration && song.duration > 0) {
                u.append("&duration=")
                        .append(enc(String.valueOf(Math.round(song.duration * 1000f))));
            }
            String body = httpGet(u.toString(), BROWSER_UA, EXTENDED_TIMEOUT_MS);
            if (body == null) return null;
            JSONArray candidates = new JSONObject(body).optJSONArray("candidates");
            if (candidates == null) return null;
            int limit = Math.min(candidates.length(), MAX_EXTENDED_CANDIDATES);
            for (int i = 0; i < limit; i++) {
                JSONObject cand = candidates.optJSONObject(i);
                if (cand == null) continue;
                long candMs = longValue(cand.opt("duration"));
                if (song.hasDuration && candMs > 0
                        && Math.abs(candMs - song.duration * 1000f) > DURATION_TOLERANCE_MS) {
                    continue;
                }
                String id = cand.optString("id", "");
                String accessKey = cand.optString("accesskey", "");
                if (id.isEmpty() || accessKey.isEmpty()) continue;
                String download = httpGet(KUGOU_DOWNLOAD_URL
                        + "?fmt=lrc&charset=utf8&client=pc&ver=1&id=" + enc(id)
                        + "&accesskey=" + enc(accessKey), BROWSER_UA, EXTENDED_TIMEOUT_MS);
                if (download == null) continue;
                String content = new JSONObject(download).optString("content", "");
                if (content.isEmpty()) continue;
                byte[] decoded = android.util.Base64.decode(
                        content.getBytes("UTF-8"), android.util.Base64.DEFAULT);
                String lrc = new String(decoded, "UTF-8");
                Result r = lrcResult(lrc, "kugou " + keyword, "kugou");
                if (r != null) return r;
            }
        } catch (Throwable t) {
            Log.d(TAG, "KuGou lookup failed: " + t);
        }
        return null;
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

    /** Paxsenix: search the Apple Music catalogue, then fetch the best match. */
    private static Result fromPaxsenix(SongInfo song) {
        try {
            String q = (normalize(song.title, true) + " "
                    + normalize(song.artist, true)).trim();
            if (q.isEmpty()) return null;
            String body = httpGet(PAXSENIX_SEARCH_URL + "?q=" + enc(q),
                    BROWSER_UA, EXTENDED_TIMEOUT_MS);
            if (body == null) return null;
            JSONArray hits = new JSONArray(body);
            List<String[]> scored = new ArrayList<String[]>();
            for (int i = 0; i < Math.min(hits.length(), 20); i++) {
                JSONObject hit = hits.optJSONObject(i);
                if (hit == null) continue;
                String track = hit.optString("trackName", hit.optString("songName", ""));
                String artist = hit.optString("artistName", "");
                long durationMs = longValue(hit.opt("duration"));
                int durationSec = durationMs > 0 ? (int) (durationMs / 1000) : -1;
                int score = candidateScore(song.title, song.artist,
                        song.hasDuration ? (int) song.duration : -1,
                        track, artist, durationSec);
                String id = hit.optString("id", "");
                if (score > 0 && !id.isEmpty()) {
                    scored.add(new String[]{String.valueOf(score), id, artist, track});
                }
            }
            Collections.sort(scored, new Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    return Integer.compare(Integer.parseInt(b[0]), Integer.parseInt(a[0]));
                }
            });
            for (int i = 0; i < Math.min(scored.size(), MAX_EXTENDED_CANDIDATES); i++) {
                String[] pick = scored.get(i);
                String lyricsBody = httpGet(PAXSENIX_LYRICS_URL + "?id=" + enc(pick[1]),
                        BROWSER_UA, EXTENDED_TIMEOUT_MS);
                if (lyricsBody == null) continue;
                JSONObject data = new JSONObject(lyricsBody);
                String lrc = ttmlToLrc(data.optString("ttmlContent", ""));
                if (lrc.isEmpty()) {
                    lrc = stripWordTags(data.optString("elrcMultiPerson",
                            data.optString("elrc", "")));
                }
                if (lrc.isEmpty()) lrc = data.optString("plain", "");
                Result r = lrcResult(lrc,
                        "paxsenix " + pick[3] + " - " + pick[2], "paxsenix");
                if (r != null) return r;
            }
        } catch (Throwable t) {
            Log.d(TAG, "Paxsenix lookup failed: " + t);
        }
        return null;
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
            long diff = Math.abs(candidateDurationSec - durationSec);
            if (diff <= 2) score += 100;
            else if (diff <= 5) score += 50;
            else if (diff <= CANDIDATE_DURATION_TOLERANCE_MS / 1000) score += 10;
            else score -= 50;
        }
        return score;
    }

    private static String stripWordTags(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("<[^>]*>", "").replace("\r", "").trim();
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

    /** Auto-pick: first result with usable lyrics wins, no score threshold. */
    private static Result pickFirst(JSONArray arr, String queryUsed) {
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
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

    // ================= LRC -> LyricsLine =================

    private static final Pattern LRC_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{2,3}))?\\]");
    private static final Pattern LRC_META =
            Pattern.compile("^\\[(ti|ar|al|length|by|offset|re|ve):", Pattern.CASE_INSENSITIVE);

    private static final class Line {
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

    private static void renderOnMain(final Context ctx, final Object songObj,
                                     Object callbackObj, final String plain,
                                     final String synced, final String queryUsed,
                                     final boolean toast) {
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

        // Idempotence: repeated API callbacks for the same song must not
        // re-render (and scroll-jump) an already-shown identical result.
        final String key;
        try {
            Object id = getFieldUp(songObj, "id");
            key = String.valueOf(id) + "|" + (plain == null ? 0 : plain.hashCode())
                    + "|" + (synced == null ? 0 : synced.hashCode())
                    + "|" + lastPreferSynced;
        } catch (Exception e) {
            return;
        }
        synchronized (LrclibFallback.class) {
            if (key.equals(renderedKeys.get(renderCallback))) {
                Log.d(TAG, "duplicate render skipped (" + queryUsed + ")");
                return;
            }
            renderedKeys.put(renderCallback, key);
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object response = buildResponse(songObj, plain, synced);
                    if (response == null) return;
                    List<Object> lines = buildLines(response, plain, synced);
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
                                    readSong(songObj), songObj, renderCallback,
                                    sourceLines, sourceSynced);
                        }
                    } catch (Exception ignored) {
                    }
                    invokeRender(renderCallback, songObj, lines, response);
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
        final SourceSnapshot aiSource = translationSourceFor(song);

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
        searchBtn.setText(tr("search"));
        layout.addView(searchBtn);
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
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<JSONObject> found =
                                searchCandidates(normalize(t, true), normalize(a, true));
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (found.isEmpty()) {
                                    Toast.makeText(c, tr("no_results"),
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                final String[] labels = new String[found.size()];
                                for (int i = 0; i < found.size(); i++) {
                                    labels[i] = candidateLabel(found.get(i));
                                }
                                new AlertDialog.Builder(c)
                                        .setTitle(tr("pick"))
                                        .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface d, int which) {
                                                final long id = found.get(which).optLong("id", -1);
                                                if (id < 0) return;
                                                new Thread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        Result r = apiGetById(id);
                                                        if (r == null) {
                                                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    Toast.makeText(c, tr("empty_record"),
                                                                            Toast.LENGTH_SHORT).show();
                                                                }
                                                            });
                                                            return;
                                                        }
                                                        // The user's pick wins and sticks:
                                                        // cached, so future opens load it directly.
                                                        try {
                                                            writePositiveCache(c, cacheKey(readSong(song)), r);
                                                        } catch (Exception ignored) {
                                                        }
                                                        remember(r.queryUsed, r.source, r.plain, r.synced);
                                                        renderOnMain(c.getApplicationContext() != null
                                                                ? c.getApplicationContext() : c,
                                                                song, callback, r.plain, r.synced,
                                                                r.queryUsed, true);
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
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Context c = v.getContext();
                    prefs(c).edit().clear().apply();
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
            String model = p.getString(provider.preferenceKey("model"), provider.defaultModel);
            String defaultLanguage = "ar".equalsIgnoreCase(Locale.getDefault().getLanguage())
                    ? "Arabic" : "English";
            String language = p.getString("language", defaultLanguage);
            String systemPrompt = p.getString("system_prompt", "");
            settings.apiKey = apiKey == null ? "" : apiKey;
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
        }
        editor.apply();
    }

    private static boolean hasProviderNote(TranslationProvider provider) {
        return provider == TranslationProvider.OPENCODE || provider == TranslationProvider.XKIRO;
    }

    private static String translationProviderNote(TranslationProvider provider) {
        if (provider == TranslationProvider.OPENCODE) return tr("opencode_note");
        if (provider == TranslationProvider.XKIRO) return tr("xkiro_note");
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
        final SourceSnapshot initialSource =
                configOnly ? null : translationSourceFor(song);
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
        final TextView[] credentialSummaries = new TextView[2];
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
        credentialSummaries[1] = addSettingsRow(ctx, rows, tr("model_label"), settings.model,
                new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                final TranslationSettings draft = drafts[provider[0].ordinal()];
                showModelSelector(ctx, draft.provider, draft.model, new ModelSelectionListener() {
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
                .setView(scroll)
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
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final TranslationProvider providerValue = provider[0];
                final TranslationSettings draft = drafts[providerValue.ordinal()];
                final String apiKeyValue = trimOrEmpty(draft.apiKey);
                final String modelValue = trimOrEmpty(draft.model);
                final String languageValue = trimOrEmpty(language[0]);
                final String systemPromptValue =
                        customSystemPromptFromDialog(systemPrompt[0]);
                final boolean autoTranslateEnabled = includeAutoToggle
                        ? autoTranslateToggle.isChecked() : settings.autoTranslate;
                boolean requiresApiKey = !configOnly || autoTranslateEnabled;
                if ((requiresApiKey && apiKeyValue.isEmpty())
                        || modelValue.isEmpty() || languageValue.isEmpty()) {
                    Toast.makeText(ctx, tr("translation_config_required"),
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
                            SourceSnapshot activeSource = translationSourceFor(activeSong);
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
                                        translationSourceFor(activeSong));
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
                final SourceSnapshot snapshot = translationSourceFor(song);
                if (snapshot == null) {
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
                translateAndRender(ctx, song, snapshot, providerValue, apiKeyValue, modelValue,
                        languageValue, systemPromptValue, true);
            }
        });
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
                    models = fetchTranslationModels(provider);
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
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(provider.modelsUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AnghamiLyricsFallback/1.0");
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new Exception(provider.displayName + " returned HTTP " + status);
            }
            return provider.parseModels(readUtf8(connection.getInputStream()));
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

    private static boolean isArabicScriptSource(List<Line> lines) {
        int arabicLetters = 0;
        int allLetters = 0;
        if (lines == null) return false;
        for (Line line : lines) {
            if (line == null || line.text == null) continue;
            for (int offset = 0; offset < line.text.length();) {
                int codePoint = line.text.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (!Character.isLetter(codePoint)) continue;
                allLetters++;
                if (Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.ARABIC) {
                    arabicLetters++;
                }
            }
        }
        return arabicLetters >= 3 && arabicLetters * 2 >= allLetters;
    }

    private static boolean hasConfiguredAutomaticTranslation(Context ctx) {
        if (ctx == null) return false;
        TranslationSettings settings = readTranslationSettings(ctx);
        return settings.autoTranslate && !settings.apiKey.trim().isEmpty()
                && !settings.model.trim().isEmpty() && !settings.language.trim().isEmpty();
    }

    private static boolean canAutoTranslate(TranslationSettings settings,
                                            SourceSnapshot snapshot) {
        return settings != null && settings.autoTranslate
                && !settings.apiKey.trim().isEmpty()
                && !settings.model.trim().isEmpty()
                && !settings.language.trim().isEmpty()
                && snapshot != null && !snapshot.lines.isEmpty()
                && !isArabicScriptSource(snapshot.lines)
                && snapshot.lines.size() <= MAX_TRANSLATION_LINES
                && sourceCharacterCount(snapshot.lines) <= MAX_TRANSLATION_CHARS;
    }

    private static boolean sameTranslationSettings(TranslationSettings left,
                                                   TranslationSettings right) {
        return left.provider == right.provider && left.apiKey.equals(right.apiKey)
                && left.model.equals(right.model) && left.language.equals(right.language)
                && left.systemPrompt.equals(right.systemPrompt)
                && left.autoTranslate == right.autoTranslate;
    }

    private static boolean autoTranslateIfEnabled(final Context ctx, final Object song) {
        try {
            if (ctx == null || song == null) return false;
            final TranslationSettings settings = readTranslationSettings(ctx);
            final SourceSnapshot snapshot = translationSourceFor(song);
            if (!canAutoTranslate(settings, snapshot)) return false;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    TranslationSettings current = readTranslationSettings(ctx);
                    SourceSnapshot latest = translationSourceFor(song);
                    if (latest == null || !latest.songId.equals(snapshot.songId)
                            || !sameSourceLines(latest.lines, snapshot.lines)) return;
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
                            current.model, current.language, current.systemPrompt, false);
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
                                           final String apiKey, final String model,
                                           final String language,
                                           final String systemPrompt,
                                           final boolean interactive) {
        if (snapshot.lines.isEmpty()) {
            if (interactive) {
                Toast.makeText(ctx, tr("no_source_lyrics"), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (isArabicScriptSource(snapshot.lines)) {
            if (interactive) {
                Toast.makeText(ctx, tr("arabic_source_skipped"),
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final String cacheKey;
        try {
            cacheKey = translationCacheKey(snapshot, provider.cacheModelKey(model),
                    language, systemPrompt);
        } catch (Exception e) {
            Toast.makeText(ctx, tr("translation_failed"), Toast.LENGTH_SHORT).show();
            if (!interactive) renderOriginalLyricsIfCurrent(song, snapshot);
            return;
        }
        final String requestKey = cacheKey + "|" + snapshot.songId;
        synchronized (LrclibFallback.class) {
            if (!translationRequestsInFlight.add(requestKey)) return;
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
            }
            Log.d(TAG, "could not show translation progress: " + t);
            if (!interactive) renderOriginalLyricsIfCurrent(song, snapshot);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> translated = readTranslationCache(ctx, cacheKey,
                        snapshot.lines.size());
                String failure = null;
                if (translated == null) {
                    try {
                        String sessionId = provider == TranslationProvider.OPENCODE
                                ? openCodeSessionId(ctx) : null;
                        translated = requestTranslation(provider, apiKey, model, sessionId,
                                language, systemPrompt, snapshot.lines);
                        writeTranslationCache(ctx, cacheKey, translated);
                    } catch (Throwable t) {
                        failure = t.getMessage();
                        Log.d(TAG, provider.displayName + " translation failed: " + t);
                    }
                }
                final List<String> result = translated;
                final String error = failure;
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
                            if (current == null || !current.songId.equals(snapshot.songId)
                                    || !sameSourceLines(current.lines, snapshot.lines)) {
                                if (interactive) {
                                    Toast.makeText(ctx, tr("source_changed"),
                                            Toast.LENGTH_SHORT).show();
                                }
                                return;
                            }
                            boolean settingsMatch = selected.provider == provider
                                    && selected.apiKey.equals(apiKey)
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
                            if (error != null || result == null) {
                                String message = tr("translation_failed");
                                if (error != null && !error.trim().isEmpty()) {
                                    message += ": " + error;
                                }
                                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                                if (!interactive) renderOriginalLyricsIfCurrent(song, current);
                                return;
                            }
                            renderDualLines(song, current.callback, snapshot, result);
                            if (interactive) {
                                Toast.makeText(ctx, tr("translation_done"),
                                        Toast.LENGTH_SHORT).show();
                            }
                        } finally {
                            synchronized (LrclibFallback.class) {
                                translationRequestsInFlight.remove(requestKey);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    private static boolean sameSourceLines(List<Line> a, List<Line> b) {
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
                + "verbatim, including target-language parts of mixed lines.";
        String custom = trimOrEmpty(customPrompt);
        if (!custom.isEmpty()) {
            instructions += ADDITIONAL_PROMPT_MARKER + custom;
        }
        return instructions;
    }

    private static List<String> readTranslationCache(Context ctx, String key, int count) {
        try {
            String raw = translationPrefs(ctx).getString(key, null);
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
        try {
            JSONArray array = new JSONArray();
            for (String line : translated) array.put(line);
            translationPrefs(ctx).edit().putString(key, array.toString()).apply();
        } catch (Throwable t) {
            Log.d(TAG, "translation cache write failed: " + t);
        }
    }

    private static List<String> requestTranslation(TranslationProvider provider, String apiKey,
                                                   String model, String sessionId, String language,
                                                   String systemPrompt, List<Line> source)
            throws Exception {
        JSONArray inputLines = new JSONArray();
        for (Line line : source) inputLines.put(line.text == null ? "" : line.text);
        JSONObject request = new JSONObject();
        request.put("model", model);
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
        // provider routes that do not support structured outputs.
        int firstOutputMode = provider == TranslationProvider.XKIRO ? 1 : 2;
        for (int outputMode = firstOutputMode; outputMode >= 0; outputMode--) {
            HttpURLConnection connection = null;
            try {
                JSONObject attempt = new JSONObject(request.toString());
                if (outputMode > 0) {
                    attempt.put("response_format",
                            translationResponseFormat(outputMode, source.size()));
                }
                connection = (HttpURLConnection) new URL(provider.completionsUrl).openConnection();
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
                connection.setConnectTimeout(HTTP_TIMEOUT_MS);
                connection.setReadTimeout(TRANSLATION_TIMEOUT_MS);
                java.io.OutputStream out = connection.getOutputStream();
                out.write(attempt.toString().getBytes("UTF-8"));
                out.flush();
                out.close();

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String body = readUtf8(stream);
                if (status >= 200 && status < 300) {
                    return parseTranslationResponse(body, source);
                }
                String detail = provider.displayName + " HTTP " + status;
                try {
                    JSONObject error = new JSONObject(body).optJSONObject("error");
                    if (error != null) detail += ": " + error.optString("message", "");
                } catch (Exception ignored) {
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

    private static List<String> parseTranslationResponse(String body, List<Line> source)
            throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("Translation provider returned no choices");
        }
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        if (message == null) throw new Exception("Translation provider returned an empty response");
        String finishReason = choice.optString("finish_reason", "");
        if ("length".equalsIgnoreCase(finishReason)
                || "max_tokens".equalsIgnoreCase(finishReason)) {
            throw new Exception("Translation response reached the model's output limit");
        }
        Object contentValue = message.opt("content");
        String content;
        if (contentValue instanceof String) {
            content = ((String) contentValue).trim();
        } else if (contentValue instanceof JSONArray) {
            StringBuilder combined = new StringBuilder();
            JSONArray parts = (JSONArray) contentValue;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null) combined.append(part.optString("text", ""));
            }
            content = combined.toString().trim();
        } else {
            throw new Exception("Translation provider returned unsupported content");
        }
        // Be forgiving about the envelope: fenced markdown, extra keys, a
        // bare array, or the older "translations" name are all acceptable.
        // What matters is getting an array of strings we can align to the
        // source. Models often return a slightly different count even with a
        // strict prompt, so never fail the whole translation for that.
        String json = content;
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "").trim();
        }
        JSONArray translated;
        if (json.startsWith("[")) {
            translated = new JSONArray(json);
        } else {
            JSONObject result = new JSONObject(json);
            translated = result.optJSONArray("lines");
            if (translated == null) translated = result.optJSONArray("translations");
            if (translated == null) {
                throw new Exception("Translation response contained no lines array");
            }
        }
        // A model that ignored the schema sometimes returns one string with
        // the whole translation separated by newlines. Split it back apart.
        if (source.size() > 1 && translated.length() == 1) {
            Object only = translated.opt(0);
            if (only instanceof String && ((String) only).indexOf('\n') >= 0) {
                String[] parts = ((String) only).split("\\r?\\n");
                JSONArray split = new JSONArray();
                for (String part : parts) split.put(part.trim());
                translated = split;
            }
        }
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < source.size(); i++) {
            boolean sourceBlank = source.get(i).text == null
                    || source.get(i).text.trim().isEmpty();
            String text = "";
            if (i < translated.length()) {
                Object value = translated.opt(i);
                if (value instanceof String) {
                    text = ((String) value).replace('\r', ' ')
                            .replace('\n', ' ').trim();
                }
            }
            // An empty translation renders the original line, which is the
            // safest alignment when the model dropped or merged lines.
            out.add(sourceBlank || text.isEmpty() ? "" : text);
        }
        return out;
    }

    private static void renderTranslationPending(final Object song,
                                                final Object callback) {
        if (song == null || callback == null) return;
        Runnable render = new Runnable() {
            @Override
            public void run() {
                try {
                    String message = tr("translation_pending");
                    Object response = buildResponse(song, message, null);
                    if (response == null) return;
                    invokeRender(callback, song, buildLines(response, message, null), response);
                } catch (Throwable t) {
                    Log.d(TAG, "could not render translation-pending state: " + t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            render.run();
        } else {
            new Handler(Looper.getMainLooper()).post(render);
        }
    }

    private static void renderOriginalLyricsIfCurrent(Object song, SourceSnapshot expected) {
        if (song == null || expected == null) return;
        SourceSnapshot current = translationSourceFor(song);
        if (current == null || !current.songId.equals(expected.songId)
                || !sameSourceLines(current.lines, expected.lines)) return;
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
                if (!translation.trim().isEmpty()) hasTranslation = true;
                String dual = translation.trim().isEmpty()
                        ? text : text + "\n" + translation;
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
            invokeRender(callback, song, displayLines, response);
            Log.d(TAG, hasTranslation
                    ? "Dual-line translation rendered"
                    : "Original lyrics restored after automatic translation");
        } catch (Throwable t) {
            Log.d(TAG, "dual-line render failed: " + t);
        }
    }
}
