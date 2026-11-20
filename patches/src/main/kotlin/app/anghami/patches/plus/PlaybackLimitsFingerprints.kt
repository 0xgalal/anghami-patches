package app.anghami.patches.plus

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Playback-limit targets (Anghami 8.0.28, verified in Anghami 8.0.28).
 *
 * Used by the "Unlock playback limits" patch. Download asserts, ad flags
 * and offline caps live in their own fingerprint files.
 */

object SkipLimitReachedFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/odin/playqueue/PlayQueue;",
    name = "skipLimitReached",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = listOf("Lcom/anghami/ghost/local/Account;"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/lang/System;",
            name = "currentTimeMillis",
        ),
    )
)

object ManagerSkipLimitReachedFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/odin/playqueue/PlayQueueManager;",
    name = "skipLimitReached",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/anghami/odin/playqueue/PlayQueue;",
            name = "skipLimitReached",
        ),
    )
)

object QueueRestrictionsEnabledFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/odin/playqueue/PlayQueue;",
    name = "queueRestrictionsEnabled",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/anghami/ghost/local/Account;",
            name = "isPlusUser",
        ),
        methodCall(
            definingClass = "Lcom/anghami/odin/playqueue/PlayQueue;",
            name = "getDisableQueueRestrictions",
        ),
    )
)

object GetDisableSkipLimitFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/odin/playqueue/PlayQueue;",
    name = "getDisableSkipLimit",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z",
        ),
    )
)

object GetDisableQueueRestrictionsFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/odin/playqueue/PlayQueue;",
    name = "getDisableQueueRestrictions",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z",
        ),
    )
)

object GetDisablePlayerRestrictionsFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/odin/playqueue/PlayQueue;",
    name = "getDisablePlayerRestrictions",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z",
        ),
    )
)

object ProtoEnablePlayerRestrictionsFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/ghost/model/proto/ProtoAccount\$Account;",
    name = "getEnablePlayerRestrictions",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z",
        ),
    )
)

/**
 * Source of the skip-limit event. Builds a `MessagesEvent(0x29d)` and posts it
 * to the GreenRobot bus via `Ve.b.f()`. No-oping this stops every subscriber
 * (the base-Activity dialog helper and the two `update()` handlers) from
 * reacting at all.
 *
 * Public static, void, no parameters, `.locals 3` (registerCount 3), so a
 * plain `return-void` prepend is safe.
 */
object PostSkipLimitReachedFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/ghost/eventbus/events/MessagesEvent;",
    name = "postSkipLimitReached",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        literal(0x29d),
        methodCall(
            definingClass = "LVe/b;",
            name = "f",
            parameters = listOf("Ljava/lang/Object;"),
        ),
    )
)

/**
 * Private helper on the base Activity, called from `handleMessagesEvent` when
 * MessagesEvent 0x29d (EVENT_SKIP_LIMITS_REACHED, posted by
 * `MessagesEvent.postSkipLimitReached`) is received. It posts the
 * "skips reached" analytics event and opens the account's `skipLink` in a
 * browser, which is the skip-limit upsell/dialog the user sees.
 *
 * Void with no parameters and `.locals 3` (registerCount 4), so a plain
 * `return-void` prepend no-ops it without any register-pressure concerns.
 */
object ShowSkipLimitsReachedFingerprint : Fingerprint(
    definingClass = "Lcom/anghami/app/base/j;",
    name = "showSkipLimitsReached",
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/anghami/ghost/analytics/Analytics;",
            name = "postEvent",
        ),
        string("android.intent.action.VIEW"),
    )
)
