package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction11x
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FALLBACK_HELPER =
    "Lapp/anghami/extension/extension/LrclibFallback;"

/**
 * LRCLIB fallback for truncated lyrics (opt-in, default off).
 *
 * Fires only from the GETlyrics.view API callback (`A7/F.onNext`), after the
 * server response has rendered. The helper returns early for full native
 * responses, so the Plus/native path and `saveLyrics` flow are untouched;
 * truncated teasers are never written to StoredLyrics (the helper keeps its
 * own SharedPreferences cache, `lyrics_fallback`).
 *
 * Waterfall (title then artist, sequential): exact `/api/get` with duration,
 * without duration, normalized variants, fielded `/api/search`, `?q=` search.
 *
 * Data-level hasLyrics fix: the server song payload carries hasLyrics=false
 * for lyrics-less tracks (and A7/C re-clears it on empty responses), which
 * kept the button greyed, the mode observer reverting, and the screen
 * empty no matter how many consumer branches were nopped. Instead every
 * trampoline forces Song.hasLyrics=true on the instance (W0 entry, onNext
 * entry, onError entry, mode observer after I0()) via the reflection
 * helper — all native checks then just work, podcasts excepted (their guard
 * is untouched). The fallback itself still only renders on truncated/empty
 * server responses.
 *
 * Truncated suppression: once LRCLIB lyrics are cached for a song, later
 * opens never show the teaser again. The view-load hook paints the cache
 * instantly; the `A7/E.b`-entry hook rewrites the truncated API response
 * in place before native renders it; the onNext hook then re-flags it
 * truncated so native saveLyrics keeps refusing it (prefs cache stays
 * the only source of truth, manual picks keep sticking).
 *
 * Patch-authoring rules honored: all trampolines are branch-free inserts
 * (entry prepends use only dead-at-entry v0, or p1 alone; the a1 insert
 * reuses the I0() result register). No new labels, no register growth.
 */
@Suppress("unused")
val lyricsFallbackPatch = bytecodePatch(
    name = "LRCLIB lyrics fallback",
    description = "When the server returns truncated/empty lyrics, fetches the full text from LRCLIB (opt-in free source) into a separate cache. Native full lyrics and the Plus path are untouched.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    extendWith("extensions/extension.mpe")

    execute {
        val method = LyricsApiOnNextFingerprint.method
        // Data-level first: the callback's song gets hasLyrics=true before
        // anything downstream reads it. Branch-free prepend (v0 dead at entry).
        method.addInstructions(
            0,
            """
                iget-object v0, p0, LA7/F;->a:Lcom/anghami/ghost/pojo/Song;
                invoke-static {v0}, $FALLBACK_HELPER->forceSongFlags(Ljava/lang/Object;)V
            """
        )
        val insns = method.implementation!!.instructions
        val anchor = insns.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.INVOKE_STATIC && ins is Instruction35c &&
                (ins.reference as? MethodReference)?.let {
                    it.definingClass == "LA7/E;" && it.name == "b"
                } == true
            ) {
                index
            } else {
                null
            }
        }
        check(anchor.size == 1) {
            "expected exactly 1 A7/E.b call in onNext, found ${anchor.size}"
        }
        method.addInstructions(
            anchor[0] + 1,
            """
                iget-object v0, p0, LA7/F;->a:Lcom/anghami/ghost/pojo/Song;
                iget-object v1, p0, LA7/F;->b:Ljava/lang/Object;
                invoke-static {v0, p1, v1}, $FALLBACK_HELPER->maybeFetch(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )
        // Truncated suppression at the render choke point (`A7/E.b`
        // entry): a cached LRCLIB result rewrites the truncated/empty
        // response in place before native renders it, so the teaser is
        // never shown on first open or revisit. Also retain the render
        // callback for the Settings options entry even when native full
        // lyrics make the LRCLIB helper return early. Branch-free prepend;
        // v0-v2 are dead at entry, and decisions live in the helpers.
        LyricsSuccessFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0, p3}, $FALLBACK_HELPER->rememberLyricsTarget(Ljava/lang/Object;Ljava/lang/Object;)V
                move-object v0, p0
                move-object v1, p1
                move-object v2, p3
                invoke-static {v0, v1, v2}, $FALLBACK_HELPER->substituteCached(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )
        // Instant cached paint at lyrics-view load (`A.h` entry):
        // known songs render immediately without waiting for the
        // DB/API round-trip. Branch-free (p0 alone, no v-reg touched).
        LyricsViewLoadFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $FALLBACK_HELPER->paintCached(Ljava/lang/Object;)V
            """
        )
        // Player lyrics button: the Song arrives with server hasLyrics=false
        // for lyrics-less tracks — force it true at W0 entry so the native
        // enable branch (and every other reader of this instance) just
        // works. Data-level fix; the old IF_EQZ nop is gone. Branch-free
        // prepend using p1 alone (no v-reg touched).
        LyricsButtonGateFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1}, $FALLBACK_HELPER->forceSongFlags(Ljava/lang/Object;)V
            """
        )
        // Mode observer: it reads the current song via I0() and reverts
        // lyrics mode when !hasLyrics. Force the flags on the gate's song
        // instance right after its I0() move-result. a1 calls I0() twice,
        // so the insert anchors on the object register of the single
        // Song.hasLyrics iget (not on call order). Podcast guard untouched;
        // no nops.
        run {
            val a1 = LyricsModeRevertFingerprint.method
            val a1ins = a1.implementation!!.instructions
            val objRegs = a1ins.mapNotNull { ins ->
                if (ins.opcode == Opcode.IGET_BOOLEAN && ins is Instruction22c &&
                    (ins.reference as? FieldReference)?.let {
                        it.definingClass == "Lcom/anghami/ghost/pojo/Song;" &&
                            it.name == "hasLyrics"
                    } == true
                ) {
                    ins.registerB
                } else {
                    null
                }
            }
            check(objRegs.size == 1) {
                "expected exactly 1 hasLyrics iget in a1, found ${objRegs.size}"
            }
            val moves = a1ins.mapIndexedNotNull { index, ins ->
                if (ins.opcode == Opcode.MOVE_RESULT_OBJECT && index > 0 &&
                    ins is Instruction11x && ins.registerA == objRegs[0] &&
                    a1ins[index - 1].opcode == Opcode.INVOKE_VIRTUAL &&
                    ((a1ins[index - 1] as? Instruction35c)?.reference as? MethodReference)?.let {
                        it.name == "I0" &&
                            it.returnType == "Lcom/anghami/ghost/pojo/Song;"
                    } == true
                ) {
                    index
                } else {
                    null
                }
            }
            check(moves.size == 1) {
                "expected exactly 1 gate I0() move-result in a1, found ${moves.size}"
            }
            val dest = (a1ins[moves[0]] as Instruction11x).registerA
            a1.addInstructions(
                moves[0] + 1,
                """
                    invoke-static {v$dest}, $FALLBACK_HELPER->forceSongFlags(Ljava/lang/Object;)V
                """
            )
        }
        // API error path (no usable server lyrics): force the flag first,
        // then the same fallback entry with a null response. Inserted
        // before the single return-void; v0/v1 are dead there (consumed by
        // the E.a call above). Branch-free.
        run {
            val onError = LyricsApiOnErrorFingerprint.method
            onError.addInstructions(
                0,
                """
                    iget-object v0, p0, LA7/F;->a:Lcom/anghami/ghost/pojo/Song;
                    invoke-static {v0}, $FALLBACK_HELPER->forceSongFlags(Ljava/lang/Object;)V
                """
            )
            val eins = onError.implementation!!.instructions
            val rets = eins.mapIndexedNotNull { index, ins ->
                if (ins.opcode == Opcode.RETURN_VOID) index else null
            }
            check(rets.size == 1) {
                "expected exactly 1 return in onError, found ${rets.size}"
            }
            onError.addInstructions(
                rets[0],
                """
                    iget-object v0, p0, LA7/F;->a:Lcom/anghami/ghost/pojo/Song;
                    iget-object v1, p0, LA7/F;->b:Ljava/lang/Object;
                    invoke-static {v0, v1}, $FALLBACK_HELPER->maybeFetchNoResponse(Ljava/lang/Object;Ljava/lang/Object;)V
                """
            )
        }
    }
}

/**
 * Adds a first-party PageRow to Anghami's main Settings list and routes it
 * to the lyrics options dialog. The play-button long-press is left native.
 */
@Suppress("unused")
val lyricsSettingsPatch = bytecodePatch(
    name = "Lyrics options in Settings",
    description = "Adds a Lyrics entry to Anghami Settings for LRCLIB search and AI translation options. Pulls in 'LRCLIB lyrics fallback'.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    dependsOn(lyricsFallbackPatch)

    execute {
        // Add the standard Settings PageRow after the Music category.
        MainSettingsRowsFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1}, $FALLBACK_HELPER->addLyricsSettingsRow(Ljava/lang/Object;)V
            """
        )

        // Preserve the native click handler for every stock PageRow; the
        // extension recognizes its sentinel row and opens the options dialog.
        MainSettingsPageRowClickFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $FALLBACK_HELPER->handleSettingsPageRowClick(Ljava/lang/Object;)V
                sget-object v0, LVd/E;->a:LVd/E;
                return-object v0
            """
        )

        // Keep the themed Settings Activity available for AlertDialog.
        SettingsActivityOnCreateFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $FALLBACK_HELPER->rememberSettingsActivity(Ljava/lang/Object;)V
            """
        )
    }
}

/**
 * Adds native-lyrics capture and automatic translation for OpenRouter and OpenCode.
 * Capture at the renderer covers API and StoredLyrics/cache responses;
 * truncated/empty responses are ignored so teasers are never translated.
 * Long-pressing the player's Lyrics toggle opens the auto-translation dialog.
 */
@Suppress("unused")
val lyricsOpenRouterTranslationPatch = bytecodePatch(
    name = "AI lyrics translation · OpenRouter / OpenCode",
    description = "Adds optional automatic lyric translation via OpenRouter or OpenCode, shown beneath each original line. Long-press the player Lyrics button to choose a provider and configure it.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    dependsOn(lyricsSettingsPatch)

    execute {
        // Capture at the render choke point so lyrics loaded from Anghami's
        // StoredLyrics cache also have a song-bound callback for the dialog.
        // The helper ignores truncated/empty payloads; substituteCached
        // captures its own replacement after rewriting a cached fallback.
        LyricsSuccessFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0, p1, p3}, $FALLBACK_HELPER->rememberNativeLyrics(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        // Also capture the original API callback after maybeFetch returns.
        // This preserves native lines even when the fallback path returns
        // early for a complete server response.
        val method = LyricsApiOnNextFingerprint.method
        val captureAnchor = method.implementation!!.instructions.mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.INVOKE_STATIC && ins is Instruction35c &&
                (ins.reference as? MethodReference)?.let {
                    it.definingClass == FALLBACK_HELPER && it.name == "maybeFetch"
                } == true
            ) {
                index
            } else {
                null
            }
        }
        check(captureAnchor.size == 1) {
            "expected exactly 1 LRCLIB maybeFetch call in onNext, found ${captureAnchor.size}"
        }
        method.addInstructions(
            captureAnchor[0] + 1,
            """
                iget-object v0, p0, LA7/F;->a:Lcom/anghami/ghost/pojo/Song;
                iget-object v1, p0, LA7/F;->b:Ljava/lang/Object;
                invoke-static {v0, p1, v1}, $FALLBACK_HELPER->rememberNativeLyrics(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        // The Lyrics button uses a native View click listener. Add a
        // separate long-click listener without changing its ordinary click.
        PlayerLyricsButtonResumeFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $FALLBACK_HELPER->installLyricsButtonLongPress(Ljava/lang/Object;)V
            """
        )
    }
}
