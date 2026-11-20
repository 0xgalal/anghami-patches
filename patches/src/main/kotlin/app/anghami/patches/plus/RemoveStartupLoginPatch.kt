package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val MAIN_ACTIVITY = "Lcom/anghami/app/main/MainActivity;"
private const val LOGIN_GATE_FIELD = "$MAIN_ACTIVITY->J:Z"

/**
 * Removes the two device-reported startup behaviours:
 *
 * 1. "app restart after I close it" — stock Anghami, not caused by any patch
 *    here. BaseActivity.onResume() calls BaseActivity.restartApp() when the
 *    cached Google/HMS availability codes differ from the current ones;
 *    restartApp() is `finishAffinity()` + `startActivity(MainActivity)` and
 *    has no other caller in the dex. Making it `return-void` at index 0 kills
 *    the self-restart without touching anything else.
 *
 * 2. "login screen after restart" — MainActivity.onCreate computes
 *
 *        J = (account == null) || account.isSignedOut
 *            || f6/b.k() == f6/b$g.EMPTY
 *
 *    and stores it once via `iput-boolean v0, v1, MainActivity;->J:Z`.
 *    J == true means "no session", and every login redirect (I0, the
 *    "Got to main activity without a session. Will get out" path) checks J.
 *    Overwriting the stored value with false right after the store makes the
 *    normal logged-in setup path run and no LoginActivity is shown.
 *
 * Both hooks are label-free. The restartApp hook uses no registers. The
 * onCreate hook uses only v0/v1 (both < 16), so Morphe's inline smali
 * compiler is safe here and no BuilderInstruction3rc is needed even though
 * onCreate itself declares a large register frame.
 *
 * Client-side only: no session/token is created. A repacked APK still has a
 * different signing certificate, so server-side authentication and data may
 * keep failing; this patch removes the prompt, not the underlying cause.
 */
@Suppress("unused")
val removeStartupLoginPatch = bytecodePatch(
    name = "Remove startup login + self-restart",
    description = "Skips the stock self-restart in BaseActivity.onResume and forces MainActivity's login gate false on startup. Client-side only; no session is created, so server-backed data may still fail.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)

    execute {
        // (1) Neutralize the stock self-restart. Single-instruction early
        //     return; the original finishAffinity/startActivity body is dead.
        BaseActivityRestartAppFingerprint.method.addInstructions(0, "return-void")

        // (2) Force the login gate false. Insert AFTER the original store so
        //     the computed value cannot overwrite ours. v1 is `this` here
        //     (`move-object/from16 v1, p0` at method entry) and v0 is the
        //     scratch register the original store just used.
        val onCreate = MainActivityOnCreateFingerprint.method
        val insns = onCreate.implementation!!.instructions
        val storeIndex = insns.indexOfFirst { ins ->
            ins.opcode == Opcode.IPUT_BOOLEAN &&
                (ins as? Instruction22c)?.reference.let { ref ->
                    (ref as? FieldReference)?.toString() == LOGIN_GATE_FIELD
                } == true
        }
        check(storeIndex >= 0) {
            "could not find the MainActivity.J store in MainActivity.onCreate"
        }
        check(storeIndex + 1 < insns.size) {
            "MainActivity.J store is the last instruction of onCreate"
        }
        onCreate.addInstructions(
            storeIndex + 1,
            """
                const/4 v0, 0x0
                iput-boolean v0, v1, $LOGIN_GATE_FIELD
            """
        )
    }
}
