package app.anghami.patches.plus

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

/**
 * Targets for the two device-reported startup behaviours (Anghami 8.0.28,
 * verified against the stock APK in /tmp/opencode/anghami-decomp).
 *
 * 1. Stock self-restart: [BaseActivityRestartAppFingerprint] is the only
 *    `restartApp()` in the dex, and its only caller is BaseActivity.onResume.
 * 2. Login gate: [MainActivityOnCreateFingerprint] computes `MainActivity.J`
 *    ("no session -> show login") once, in onCreate, from account/session
 *    state. `J == true` routes to LoginActivity via MainActivity.I0.
 */

/** BaseActivity.restartApp() — `finishAffinity()` + `startActivity(MainActivity)`. */
object BaseActivityRestartAppFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/app/base/o;",
    name = "restartApp",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Landroid/app/Activity;", name = "finishAffinity"),
    ),
)

/** MainActivity.onCreate — computes the login gate `J` from account/session state. */
object MainActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/app/main/MainActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/anghami/ghost/local/Account;",
            name = "getAccountInstance",
        ),
    ),
)
