package app.anghami.patches.plus

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * Config-driven dialog funnel (Anghami 8.0.28, verified in Anghami 8.0.28).
 *
 * `com.anghami.ui.dialog.x.m(FragmentActivity, DialogConfig)` is the single
 * point where a server-provided DialogConfig becomes a displayable
 * `com.anghami.ui.dialog.g`:
 *
 * - `o.showAsyncDialog(name, ...)` -> `DialogConfig$Builder.buildAsync` ->
 *   `E/D.onDialogConfigReady` -> `x.m` (dialogs loaded from ObjectBox by name)
 * - `j.onDisplayAdsFetched(AdModel, ...)` -> `AdModel.getDialog()` ->
 *   `o.showDialog(DialogConfig, ...)` -> `showGenericDialog` -> `x.m`
 *
 * Hardcoded/local dialogs (`x.b/c/d/...`) build `g` directly and never call
 * `x.m`. All callers of `x.m` null-check its result, so a trampoline that
 * returns null suppresses the dialog safely.
 *
 * Filters verified against the real 8.0.28 smali:
 * `const-string "input"` (line 1253), `"rateplaylistdialog"` (1289),
 * `"Playqueue_dialog"` (1414).
 */
object DialogConfigToViewFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/ui/dialog/x;",
    name = "m",
    returnType = "Lcom/anghami/ui/dialog/g;",
    parameters = listOf(
        "Landroidx/fragment/app/n;",
        "Lcom/anghami/ghost/objectbox/models/DialogConfig;",
    ),
    filters = listOf(
        string("input"),
        string("rateplaylistdialog"),
        string("Playqueue_dialog"),
    ),
)
