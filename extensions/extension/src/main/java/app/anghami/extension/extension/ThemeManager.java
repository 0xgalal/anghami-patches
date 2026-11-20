package app.anghami.extension.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime theme picker for the whole app, including the player.
 *
 * The shipped patch set bakes one player palette into resources/dex at build
 * time. This manager deliberately stays out of that bytecode: it applies a
 * preset at runtime from SharedPreferences, so every choice works from a
 * single install. Five presets have a dark palette (night mode) and a brighter
 * light palette (day mode), selected from the current uiMode. The sixth choice,
 * Default, applies no overlay at all and keeps/restores the app's baseline
 * colors in both modes.
 *
 * Application.onCreate registers lifecycle callbacks so the window, status
 * and navigation bars follow the selected palette on every screen, and the
 * entire Android view hierarchy under {@code android.R.id.content} is walked
 * to recolor view backgrounds, text, buttons and cards. The player fragment's
 * onResume hook calls {@link #applyPlayerTheme(Object)} for the
 * player-specific tint. Before any view/window color is changed, its original
 * value is recorded in a WeakHashMap; picking Default restores those values
 * on the spot. Compose-owned screens cannot be recolored through the view
 * tree; their window colors still follow the theme.
 *
 * Any failure is caught and logged — theming must never break the host app.
 */
@SuppressWarnings("unused")
public final class ThemeManager {

    private static final String TAG = "ThemeManager";
    private static final String PREFS = "theme_settings";
    private static final String KEY_THEME = "theme_id";
    /** LIVE_RADIO is not present in the main Settings list; used as click sentinel. */
    private static final String SENTINEL = "LIVE_RADIO";
    /** dummy_tag_1: empty, unused stock string repurposed to "THEME" by themeTitleResourcePatch. */
    private static final int ROW_TITLE_RES = 0x7f130b07;
    private static final int ROW_ICON_RES = 0x7f080810; // ico_settings_music_settings
    /** Appended last on purpose: stored ids 0..4 from older builds stay valid. */
    static final int DEFAULT_ID = 5;
    /** Placeholder palette for Default; never applied (apply sites skip Default). */
    static final int NO_OVERRIDE = 0;

    private static volatile WeakReference<Activity> settingsActivity =
            new WeakReference<Activity>(null);
    private static volatile WeakReference<Object> playerFragment =
            new WeakReference<Object>(null);
    private static volatile Application registeredApp;

    /** One mode's concrete colors. All colors are opaque ARGB. */
    private static final class Palette {
        final int background;
        final int accent;
        final int secondary;
        final int foreground;
        final int onAccent;

        Palette(int background, int accent, int secondary, int foreground, int onAccent) {
            this.background = background;
            this.accent = accent;
            this.secondary = secondary;
            this.foreground = foreground;
            this.onAccent = onAccent;
        }
    }

    private enum Preset {
        TOKYO_NIGHTS(
                new Palette(0xff0b0e1a, 0xffff2e88, 0xff29e5ff, 0xfff5f5fa, 0xff0b0e1a),
                new Palette(0xfff7f2ff, 0xffc2185b, 0xff00695c, 0xff1a1030, 0xffffffff)),
        NEON_SUNSET(
                new Palette(0xff1a0e14, 0xffff7a45, 0xffffd166, 0xfffdf2ee, 0xff1a0e14),
                new Palette(0xfffff5ef, 0xffd84315, 0xff8d6e00, 0xff2a160f, 0xffffffff)),
        MIDNIGHT_OCEAN(
                new Palette(0xff071426, 0xff3da5ff, 0xff00e0c6, 0xffeaf4ff, 0xff071426),
                new Palette(0xfff0f7ff, 0xff1565c0, 0xff00897b, 0xff10233a, 0xffffffff)),
        FOREST_GLOW(
                new Palette(0xff0a1710, 0xff5be39b, 0xffc8ff7a, 0xfff0fff5, 0xff0a1710),
                new Palette(0xfff3faef, 0xff2e7d32, 0xff558b2f, 0xff11240f, 0xffffffff)),
        ROSE_QUARTZ(
                new Palette(0xff1a0f14, 0xffff7eb6, 0xffffc8dd, 0xfffdf0f6, 0xff1a0f14),
                new Palette(0xfffff4f8, 0xffad1457, 0xff880e4f, 0xff2a101a, 0xffffffff)),
        // No overlay. The all-zero palette is a sentinel and is never applied.
        DEFAULT(
                new Palette(NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE),
                new Palette(NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE));

        final Palette dark;
        final Palette light;

        Preset(Palette dark, Palette light) {
            this.dark = dark;
            this.light = light;
        }

        Palette forMode(boolean night) {
            return night ? dark : light;
        }

        static Preset fromId(int id) {
            Preset[] values = values();
            return values[id >= 0 && id < values.length ? id : 0];
        }
    }

    private static final String[] PRESET_NAMES = {
            "Tokyo Nights",
            "Neon Sunset",
            "Midnight Ocean",
            "Forest Glow",
            "Rose Quartz",
            "Default",
    };

    // ---- original-color snapshots ---------------------------------------
    // Weak keys keep detached views/activities collectable. Values hold only
    // immutable color data (never a Drawable that points back at a view),
    // except the window background Drawable, which is explicitly cleared on
    // activity destruction and on restore.

    /** Original colors of a single themed view. */
    private static final class ViewOriginal {
        Integer backgroundColor;        // captured only for flat ColorDrawables
        boolean backgroundWasNull;       // captured for buttons/toolbars with no background
        boolean hadTextColors;
        ColorStateList textColors;
        boolean hadBackgroundTint;
        ColorStateList backgroundTint;
        boolean hadCardColors;
        ColorStateList cardColors;
    }

    /** Original window colors of a single themed Activity. */
    private static final class WindowOriginal {
        int statusBarColor;
        int navigationBarColor;
        boolean hadBackground;
        Drawable background;
    }

    private static final Map<View, ViewOriginal> viewOriginals =
            Collections.synchronizedMap(new WeakHashMap<View, ViewOriginal>());
    private static final Map<Activity, WindowOriginal> windowOriginals =
            Collections.synchronizedMap(new WeakHashMap<Activity, WindowOriginal>());

    private ThemeManager() {
    }

    // ---- persistence ----------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int loadThemeId(Context ctx) {
        try {
            return prefs(ctx).getInt(KEY_THEME, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    static void saveThemeId(Context ctx, int id) {
        try {
            prefs(ctx).edit().putInt(KEY_THEME, id).apply();
        } catch (Throwable t) {
            Log.d(TAG, "saveThemeId failed: " + t);
        }
    }

    // ---- patch entry points --------------------------------------------

    /** Called from Application.onCreate: keeps app chrome themed on every screen. */
    public static void register(Application app) {
        if (app == null || registeredApp != null) return;
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    applyTheme(activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    forgetWindowOriginals(activity);
                }
            });
            registeredApp = app;
        } catch (Throwable t) {
            Log.d(TAG, "register failed: " + t);
        }
    }

    /** Called from SettingsActivity.onCreate. */
    public static void rememberSettingsActivity(Object activityObj) {
        if (activityObj instanceof Activity) {
            settingsActivity = new WeakReference<Activity>((Activity) activityObj);
        }
    }

    /** Called from MainSettingsViewModel row-list constructor. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void addThemeSettingsRow(Object rowsObj) {
        try {
            if (!(rowsObj instanceof List)) return;
            List rows = (List) rowsObj;
            int insertAt = rows.size();
            for (int i = 0; i < rows.size(); i++) {
                Object row = rows.get(i);
                Object rowId = settingsRowIdOf(row);
                String name = enumName(rowId);
                if (SENTINEL.equals(name)) return; // already injected
                if ("MUSIC".equals(name)) {
                    insertAt = i + 1;
                } else if (insertAt == rows.size() && row != null
                        && row.getClass().getName().endsWith("$Version")) {
                    insertAt = i;
                }
            }
            Class<?> rowIdClass = Class.forName("com.anghami.model.pojo.settings.SettingsRowId");
            Object sentinel = rowIdClass.getField(SENTINEL).get(null);
            Class<?> subtitleClass = Class.forName("com.anghami.model.pojo.settings.SubtitleText");
            Class<?> pageRowClass = Class.forName(
                    "com.anghami.model.pojo.settings.SettingsRow$ItemRow$PageRow");
            Object row = pageRowClass.getConstructor(
                    Integer.TYPE, rowIdClass, subtitleClass, Boolean.TYPE, Integer.TYPE)
                    .newInstance(ROW_TITLE_RES, sentinel, null, true, ROW_ICON_RES);
            rows.add(Math.max(0, Math.min(insertAt, rows.size())), row);
        } catch (Throwable t) {
            Log.d(TAG, "addThemeSettingsRow failed: " + t);
        }
    }

    /** Called from the main Settings PageRow click lambda. */
    public static void handleSettingsPageRowClick(Object clickLambda) {
        try {
            if (clickLambda == null) return;
            Object row = getFieldUp(clickLambda, "b");
            Object rowId = settingsRowIdOf(row);
            if (SENTINEL.equals(enumName(rowId))) {
                Activity activity = settingsActivity.get();
                Context ctx = activity != null ? activity : null;
                if (ctx == null) return;
                showThemePicker(ctx);
                return;
            }
            Object callback = getFieldUp(clickLambda, "a");
            if (callback == null) return;
            Method invoke = callback.getClass().getMethod("invoke", Object.class);
            invoke.setAccessible(true);
            invoke.invoke(callback, rowId);
        } catch (Throwable t) {
            Log.d(TAG, "handleSettingsPageRowClick failed: " + t);
        }
    }

    // ---- applying a preset ---------------------------------------------

    /** Whole-app chrome: window, status/navigation bars and the view hierarchy. */
    public static void applyTheme(Activity activity) {
        try {
            if (activity == null || !shouldOverride(loadThemeId(activity))) return;
            Palette preset = paletteFor(activity);
            Window window = activity.getWindow();
            if (window != null) {
                rememberWindow(window, activity);
                window.setStatusBarColor(preset.background);
                window.setNavigationBarColor(preset.background);
                try {
                    window.setBackgroundDrawable(new ColorDrawable(preset.background));
                } catch (Throwable ignored) {
                }
            }
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                tintViewTree((ViewGroup) content, preset);
            }
        } catch (Throwable t) {
            Log.d(TAG, "applyTheme failed: " + t);
        }
    }

    /** Player surface: root background plus the control-row text accents. */
    public static void applyPlayerTheme(Object fragmentObj) {
        try {
            if (fragmentObj == null) return;
            playerFragment = new WeakReference<Object>(fragmentObj);
            Object rootObj = getFieldUp(fragmentObj, "e"); // player/ui/d.e root view
            if (!(rootObj instanceof View)) return;
            View root = (View) rootObj;
            if (!shouldOverride(loadThemeId(root.getContext()))) return;
            Palette preset = paletteFor(root.getContext());
            ViewOriginal original = originalFor(root);
            rememberBackground(root, root.getBackground(), original);
            root.setBackgroundColor(preset.background);
            Object controlsObj = getFieldUp(fragmentObj, "V"); // fl_lyrics
            if (controlsObj instanceof View) {
                ViewParent parent = ((View) controlsObj).getParent();
                if (parent instanceof ViewGroup) {
                    tintControls((ViewGroup) parent, preset);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "applyPlayerTheme failed: " + t);
        }
    }

    /** Re-apply after the user picks a different preset. */
    static void refreshPlayerTheme() {
        try {
            Object fragment = playerFragment.get();
            if (fragment != null) applyPlayerTheme(fragment);
            Activity activity = settingsActivity.get();
            if (activity != null) applyTheme(activity);
        } catch (Throwable t) {
            Log.d(TAG, "refreshPlayerTheme failed: " + t);
        }
    }

    /** Default (id 5) is the only preset that must not touch any color. */
    static boolean shouldOverride(int id) {
        return id != DEFAULT_ID;
    }

    private static Palette paletteFor(Context ctx) {
        return Preset.fromId(loadThemeId(ctx)).forMode(isNight(ctx));
    }

    /** True for UI_MODE_NIGHT_YES; defaults to the dark palette if unavailable. */
    private static boolean isNight(Context ctx) {
        try {
            int uiMode = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return uiMode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- original-color bookkeeping ------------------------------------

    private static ViewOriginal originalFor(View view) {
        synchronized (viewOriginals) {
            ViewOriginal original = viewOriginals.get(view);
            if (original == null) {
                original = new ViewOriginal();
                viewOriginals.put(view, original);
            }
            return original;
        }
    }

    private static void rememberBackground(View view, Drawable background, ViewOriginal original) {
        if (original.backgroundColor != null || original.backgroundWasNull) return;
        if (background instanceof ColorDrawable) {
            original.backgroundColor = ((ColorDrawable) background).getColor();
        } else if (background == null) {
            original.backgroundWasNull = true;
        }
        // Complex drawables are never replaced, so nothing to remember.
    }

    private static void rememberText(TextView view, ViewOriginal original) {
        if (original.hadTextColors) return;
        original.textColors = view.getTextColors();
        original.hadTextColors = true;
    }

    private static void rememberTint(View view, ViewOriginal original) {
        if (original.hadBackgroundTint) return;
        original.backgroundTint = view.getBackgroundTintList();
        original.hadBackgroundTint = true;
    }

    private static void rememberCard(View view, ViewOriginal original) {
        if (original.hadCardColors) return;
        try {
            Method getter = view.getClass().getMethod("getCardBackgroundColor");
            original.cardColors = (ColorStateList) getter.invoke(view);
            original.hadCardColors = true;
        } catch (Throwable ignored) {
        }
    }

    private static void rememberWindow(Window window, Activity activity) {
        synchronized (windowOriginals) {
            if (windowOriginals.containsKey(activity)) return;
            WindowOriginal original = new WindowOriginal();
            original.statusBarColor = window.getStatusBarColor();
            original.navigationBarColor = window.getNavigationBarColor();
            // Window.getBackground() is hidden API; PhoneWindow installs the
            // window background on the decor view, which is public API.
            View decor = window.getDecorView();
            Drawable background = decor != null ? decor.getBackground() : null;
            original.hadBackground = background != null;
            original.background = background;
            windowOriginals.put(activity, original);
        }
    }

    private static void forgetWindowOriginals(Activity activity) {
        synchronized (windowOriginals) {
            windowOriginals.remove(activity);
        }
    }

    /**
     * Restore the stock look immediately after Default is picked. Snapshots
     * are taken only while an override is actually written, and are cleared
     * after restoring so a later theme choice re-snapshots the stock state.
     */
    static void restoreStockColors() {
        try {
            List<Map.Entry<View, ViewOriginal>> views;
            synchronized (viewOriginals) {
                views = new ArrayList<Map.Entry<View, ViewOriginal>>(viewOriginals.entrySet());
                viewOriginals.clear();
            }
            for (Map.Entry<View, ViewOriginal> entry : views) {
                View view = entry.getKey();
                ViewOriginal original = entry.getValue();
                try {
                    Drawable current = view.getBackground();
                    if (original.backgroundColor != null) {
                        // Only undo our own flat-color override.
                        if (current instanceof ColorDrawable) {
                            view.setBackgroundColor(original.backgroundColor);
                        }
                    } else if (original.backgroundWasNull
                            && (current == null || current instanceof ColorDrawable)) {
                        view.setBackground(null);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (original.hadBackgroundTint) {
                        view.setBackgroundTintList(original.backgroundTint);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (original.hadCardColors && original.cardColors != null) {
                        Method setter = view.getClass().getMethod(
                                "setCardBackgroundColor", ColorStateList.class);
                        setter.invoke(view, original.cardColors);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (original.hadTextColors && view instanceof TextView) {
                        ((TextView) view).setTextColor(original.textColors);
                    }
                } catch (Throwable ignored) {
                }
            }

            List<Map.Entry<Activity, WindowOriginal>> windows;
            synchronized (windowOriginals) {
                windows = new ArrayList<Map.Entry<Activity, WindowOriginal>>(
                        windowOriginals.entrySet());
                windowOriginals.clear();
            }
            for (Map.Entry<Activity, WindowOriginal> entry : windows) {
                try {
                    Window window = entry.getKey().getWindow();
                    WindowOriginal original = entry.getValue();
                    if (window == null) continue;
                    window.setStatusBarColor(original.statusBarColor);
                    window.setNavigationBarColor(original.navigationBarColor);
                    View decor = window.getDecorView();
                    Drawable current = decor != null ? decor.getBackground() : null;
                    if (current == null || current instanceof ColorDrawable) {
                        window.setBackgroundDrawable(original.hadBackground
                                ? original.background : null);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "restoreStockColors failed: " + t);
        }
    }

    // ---- tinting --------------------------------------------------------

    /**
     * Recursively recolor the Android view tree. Deliberately conservative:
     * only flat ColorDrawable backgrounds are replaced (selectors, ripples,
     * gradients and images are left alone), so state lists and custom views
     * keep working. Every step is guarded because theming must be cheap and
     * non-fatal on every resume.
     */
    private static void tintViewTree(View view, Palette preset) {
        if (view == null) return;
        String className = view.getClass().getName();
        boolean keySurface = className.contains("Toolbar") || className.contains("ActionBar");
        try {
            Drawable background = view.getBackground();
            // Flat colors (and key surfaces with no background at all) are
            // safe to replace; selectors, ripples and images are preserved.
            if (background instanceof ColorDrawable
                    || (background == null && (view instanceof Button || keySurface))) {
                ViewOriginal original = originalFor(view);
                rememberBackground(view, background, original);
                view.setBackgroundColor(
                        view instanceof Button ? preset.accent : preset.background);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (className.contains("CardView")) {
                ViewOriginal original = originalFor(view);
                rememberCard(view, original);
                Method method = view.getClass().getMethod("setCardBackgroundColor", Integer.TYPE);
                method.invoke(view, preset.background);
            }
            if (view instanceof Button) {
                ViewOriginal original = originalFor(view);
                rememberTint(view, original);
                view.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(preset.accent));
            }
        } catch (Throwable ignored) {
        }
        try {
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                ViewOriginal original = originalFor(view);
                rememberText(text, original);
                text.setTextColor(view instanceof Button
                        ? preset.onAccent
                        : (view.isClickable() ? preset.accent : preset.foreground));
            }
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                tintViewTree(group.getChildAt(i), preset);
            }
        }
    }

    private static void tintControls(ViewGroup group, Palette preset) {
        for (int i = 0; i < group.getChildCount(); i++) {
            tintControlsRecursive(group.getChildAt(i), preset);
        }
    }

    private static void tintControlsRecursive(View view, Palette preset) {
        try {
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                ViewOriginal original = originalFor(view);
                rememberText(text, original);
                text.setTextColor(preset.accent);
            }
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintControlsRecursive(group.getChildAt(i), preset);
            }
        }
    }

    // ---- picker dialog --------------------------------------------------

    private static void showThemePicker(final Context ctx) {
        try {
            final int[] selected = { loadThemeId(ctx) };
            new AlertDialog.Builder(ctx)
                    .setTitle(label(ctx, "theme_title"))
                    .setSingleChoiceItems(PRESET_NAMES, selected[0],
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    selected[0] = which;
                                    saveThemeId(ctx, which);
                                    if (!shouldOverride(which)) {
                                        // Undo the current overlay on every live
                                        // screen/window before skipping future tints.
                                        restoreStockColors();
                                    }
                                    applyThemeForContext(ctx);
                                    refreshPlayerTheme();
                                    dialog.dismiss();
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Throwable t) {
            Log.d(TAG, "showThemePicker failed: " + t);
        }
    }

    private static void applyThemeForContext(Context ctx) {
        if (ctx instanceof Activity) applyTheme((Activity) ctx);
    }

    static String label(Context ctx, String key) {
        String lang = Locale.getDefault().getLanguage();
        if ("theme_title".equals(key)) {
            if ("ar".equalsIgnoreCase(lang)) return "الثيم";
            if ("fr".equalsIgnoreCase(lang)) return "Thème";
            return "Theme";
        }
        return key;
    }

    // ---- reflection helpers --------------------------------------------

    private static Object settingsRowIdOf(Object row) {
        try {
            if (row == null) return null;
            return row.getClass().getMethod("getSettingsRowId").invoke(row);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String enumName(Object value) {
        try {
            return value instanceof Enum ? ((Enum<?>) value).name() : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Object getFieldUp(Object instance, String name) {
        try {
            Class<?> type = instance.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(instance);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---- test accessors -------------------------------------------------

    /** Exposed for unit tests: resolves a preset for any id, including out-of-range. */
    static String presetName(int id) {
        return PRESET_NAMES[Preset.fromId(id).ordinal()];
    }

    /** Legacy dark-palette accessors retained for compatibility with existing tests. */
    static int presetBackground(int id) {
        return Preset.fromId(id).dark.background;
    }

    static int presetAccent(int id) {
        return Preset.fromId(id).dark.accent;
    }

    /** Mode-aware palette accessors. {@code night == true} selects the dark palette. */
    static int presetBackground(int id, boolean night) {
        return Preset.fromId(id).forMode(night).background;
    }

    static int presetAccent(int id, boolean night) {
        return Preset.fromId(id).forMode(night).accent;
    }

    static int presetSecondary(int id, boolean night) {
        return Preset.fromId(id).forMode(night).secondary;
    }

    static int presetForeground(int id, boolean night) {
        return Preset.fromId(id).forMode(night).foreground;
    }

    static int presetOnAccent(int id, boolean night) {
        return Preset.fromId(id).forMode(night).onAccent;
    }
}
