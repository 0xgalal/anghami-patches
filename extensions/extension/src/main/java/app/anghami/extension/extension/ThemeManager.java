package app.anghami.extension.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Runtime theme picker for the app chrome and the player.
 *
 * The shipped patch set bakes one player palette into resources/dex at build
 * time. This manager deliberately stays out of that bytecode: it applies a
 * preset at runtime from SharedPreferences, so all five choices work from a
 * single install. Application.onCreate registers lifecycle callbacks so the
 * status/navigation bars follow the selected palette on every screen, and the
 * player fragment's onResume hook calls {@link #applyPlayerTheme(Object)} for
 * the player-specific tint.
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
    private static final int ROW_TITLE_RES = 0x7f130fd6; // pick_theme -> "PICK THEME"
    private static final int ROW_ICON_RES = 0x7f080810; // ico_settings_music_settings

    private static volatile WeakReference<Activity> settingsActivity =
            new WeakReference<Activity>(null);
    private static volatile WeakReference<Object> playerFragment =
            new WeakReference<Object>(null);
    private static volatile Application registeredApp;

    private enum Preset {
        TOKYO_NIGHTS(0xff0b0e1a, 0xffff2e88, 0xff29e5ff),
        NEON_SUNSET(0xff1a0e14, 0xffff7a45, 0xffffd166),
        MIDNIGHT_OCEAN(0xff071426, 0xff3da5ff, 0xff00e0c6),
        FOREST_GLOW(0xff0a1710, 0xff5be39b, 0xffc8ff7a),
        ROSE_QUARTZ(0xff1a0f14, 0xffff7eb6, 0xffffc8dd);

        final int background;
        final int accent;
        final int secondary;

        Preset(int background, int accent, int secondary) {
            this.background = background;
            this.accent = accent;
            this.secondary = secondary;
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
    };

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

    /** App chrome: status and navigation bars on every resumed Activity. */
    public static void applyTheme(Activity activity) {
        try {
            if (activity == null) return;
            Preset preset = Preset.fromId(loadThemeId(activity));
            android.view.Window window = activity.getWindow();
            if (window != null) {
                window.setStatusBarColor(preset.background);
                window.setNavigationBarColor(preset.background);
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
            Context ctx = root.getContext();
            Preset preset = Preset.fromId(loadThemeId(ctx));
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

    private static void tintControls(ViewGroup group, Preset preset) {
        for (int i = 0; i < group.getChildCount(); i++) {
            tintControlsRecursive(group.getChildAt(i), preset);
        }
    }

    private static void tintControlsRecursive(View view, Preset preset) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(preset.accent);
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

    /** Exposed for unit tests: resolves a preset for any id, including out-of-range. */
    static String presetName(int id) {
        return PRESET_NAMES[Preset.fromId(id).ordinal()];
    }

    /** Exposed for unit tests: palette accessors. */
    static int presetBackground(int id) {
        return Preset.fromId(id).background;
    }

    static int presetAccent(int id) {
        return Preset.fromId(id).accent;
    }
}
