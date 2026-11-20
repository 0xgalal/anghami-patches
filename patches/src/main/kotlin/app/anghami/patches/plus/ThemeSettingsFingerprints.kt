package app.anghami.patches.plus

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Targets for the runtime theme picker (Anghami 8.0.28, verified against the
 * stock APK). Layout/click hooks mirror the previously shipped Settings-row
 * pattern, which is why the same sentinel/route matches here.
 */

/** Application.onCreate — hook once to register Activity lifecycle theming. */
object ThemeApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/AnghamiApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

/** SettingsActivity.onCreate — remember the themed Activity + apply chrome. */
object ThemeSettingsActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/app/settings/view/SettingsActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(definingClass = "LB5/d0;", name = "setContentView"),
    ),
)

/** MainSettingsViewModel list constructor — receives the mutable row list. */
object ThemeSettingsRowsFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/app/settings/view/ui/mainsettings/MainSettingsViewModel\$a\$b;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(string("settingsRow")),
)

/** PageRow click lambda — intercept the theme sentinel, delegate everything else. */
object ThemeSettingsPageRowClickFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/app/settings/view/ui/compose/m;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/anghami/model/pojo/settings/SettingsRow\$ItemRow\$PageRow;",
            name = "getSettingsRowId",
        ),
    ),
)
