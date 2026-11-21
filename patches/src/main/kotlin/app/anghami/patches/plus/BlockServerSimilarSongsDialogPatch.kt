package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28

private const val BLOCKER = "Lapp/anghami/extension/extension/SimilarSongsDialogBlocker;"

/**
 * Blocks the server-provided "We'll play you similar songs. This helps us
 * keep music free for you" dialog (OK button) shown on next-song navigation.
 *
 * The message is not in the APK resources: it is delivered as a server
 * DialogConfig and rendered through the config-driven dialog funnel
 * `com.anghami.ui.dialog.x.m(FragmentActivity, DialogConfig)`. The injected
 * trampoline asks the reflection helper whether the incoming config is that
 * specific server dialog; if so, `x.m` returns null (every caller null-checks
 * it), so the dialog is never built or shown. Local/hardcoded dialogs do not
 * pass through this funnel and are untouched.
 *
 * Hook is a branch-free entry prepend using only v0 (dead at entry;
 * `.locals 3`, 5 registers total, well below Morphe's 16-register p-reg
 * limit) and p1 (the DialogConfig argument).
 */
@Suppress("unused")
val blockServerSimilarSongsDialogPatch = bytecodePatch(
    name = "Block server similar-songs dialog",
    description = "Suppresses the server-provided 'We'll play you similar songs...' dialog shown on next-song navigation. Local dialogs untouched.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    extendWith("extensions/extension.mpe")

    execute {
        DialogConfigToViewFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1}, $BLOCKER->shouldSuppress(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :not_similar_server_dialog
                const/4 v0, 0x0
                return-object v0
                :not_similar_server_dialog
            """
        )
    }
}
