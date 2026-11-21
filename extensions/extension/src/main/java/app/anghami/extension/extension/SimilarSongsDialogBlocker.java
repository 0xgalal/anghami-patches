package app.anghami.extension.extension;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Runtime blocker for the server-provided
 * "We'll play you similar songs. This helps us keep music free for you"
 * dialog (an OK-only dialog shown when navigating to the next song).
 *
 * <p>The text of this dialog is not in the APK resources: it arrives from the
 * server as a {@code DialogConfig} (stored in ObjectBox by dialog name, or
 * embedded in a server AdModel) and is rendered through the config-driven
 * dialog funnel {@code com.anghami.ui.dialog.x.m(FragmentActivity, DialogConfig)}.
 * Hardcoded/local dialogs never go through that funnel.
 *
 * <p>This class is compiled with no app classes on the classpath, so every
 * access to the app's DialogConfig is reflective and every call is wrapped in
 * a try/catch that fails open (returns {@code false}), meaning an unexpected
 * app layout can never suppress a legitimate dialog.
 */
public final class SimilarSongsDialogBlocker {

    private SimilarSongsDialogBlocker() {
    }

    /**
     * Called from a trampoline prepended to {@code x.m}.
     *
     * @param dialogConfig the app's {@code DialogConfig} instance (passed as
     *                     Object so no compile-time app dependency is needed)
     * @return true only when the config is a server-provided similar-songs
     *         dialog; the caller then aborts building/showing the dialog
     */
    public static boolean shouldSuppress(Object dialogConfig) {
        try {
            if (dialogConfig == null) {
                return false;
            }

            String name = stringField(dialogConfig, "dialogName");
            String id = stringField(dialogConfig, "id");

            // DialogConfigs that reach the config-driven funnel are
            // server-provided when they carry a server id or a dialog name.
            // Purely local, hardcoded dialogs have neither and are ignored.
            boolean serverOrigin = notEmpty(id) || notEmpty(name);
            if (!serverOrigin) {
                return false;
            }

            StringBuilder allText = new StringBuilder();
            for (String field : TEXT_FIELDS) {
                String value = stringField(dialogConfig, field);
                if (notEmpty(value)) {
                    allText.append(value).append('\n');
                }
            }
            String text = allText.toString().toLowerCase(Locale.US);

            // Primary signature: the distinctive server copy. Both halves
            // must be present, so an unrelated "similar songs" promo that
            // does not mention keeping music free is left alone.
            boolean similar = text.contains("similar song");
            boolean freeMessage = text.contains("keep music free")
                    || text.contains("music free")
                    || text.contains("free for you");
            if (similar && freeMessage) {
                return true;
            }

            // Name-based fallback: the server config name stays stable even
            // when the message itself is localized. Require both tokens to
            // avoid matching an unrelated "similar artists" dialog.
            String lowerName = name == null ? "" : name.toLowerCase(Locale.US);
            if (lowerName.contains("similar") && lowerName.contains("song")) {
                return true;
            }

            return false;
        } catch (Throwable ignored) {
            // Never break dialog display because of a reflection problem.
            return false;
        }
    }

    private static final String[] TEXT_FIELDS = {
            "title", "subtitle", "description",
            "buttonText", "buttonSubtitle",
            "cancelButtonText", "neutralButtonText",
            "customText1", "customText2", "customText3",
            "inputHint", "inputSubtext",
            "dialogName",
    };

    private static String stringField(Object object, String fieldName) {
        try {
            Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(object);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }
}
