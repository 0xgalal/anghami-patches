package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28

/**
 * Disables client-side playback limits.
 *
     * - PlayQueue.skipLimitReached / queueRestrictionsEnabled -> false
     * - PlayQueueManager.skipLimitReached -> false (also prevents the song API
     *   from being told the client reached its skip limit)
     * - PlayQueue.getDisableSkipLimit / getDisableQueueRestrictions /
     *   getDisablePlayerRestrictions -> true
 * - ProtoAccount.getEnablePlayerRestrictions -> false
 * - MessagesEvent.postSkipLimitReached() -> no-op (blocks the 0x29d event at
 *   its source, so none of its three subscribers run)
 * - Base Activity showSkipLimitsReached() -> no-op (defense in depth for the
 *   skip-limit upsell/dialog)
 *
 * Local gates only; stream authorization stays server-side.
 */
@Suppress("unused")
val unlockPlaybackLimitsPatch = bytecodePatch(
    name = "Unlock playback limits",
    description = "Disables skip and queue limits (skipLimitReached/queueRestrictionsEnabled=false, disable-flags=true). Local gates only.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)

    execute {
        ManagerSkipLimitReachedFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        SkipLimitReachedFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        QueueRestrictionsEnabledFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        GetDisableSkipLimitFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )
        GetDisableQueueRestrictionsFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )
        GetDisablePlayerRestrictionsFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )
        ProtoEnablePlayerRestrictionsFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )
        // Block the skip-limit event at its source so no subscriber reacts.
        PostSkipLimitReachedFingerprint.method.addInstructions(0, "return-void")
        // No-op the skip-limit upsell/dialog method as defense in depth. Void
        // return, no registers used, safe regardless of register count.
        ShowSkipLimitsReachedFingerprint.method.addInstructions(0, "return-void")
    }
}
