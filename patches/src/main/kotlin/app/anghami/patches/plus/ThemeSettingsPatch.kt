package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val THEME_HELPER = "Lapp/anghami/extension/extension/ThemeManager;"

/**
 * Adds a "Theme" section to Anghami's native Settings page and applies the
 * selected preset to the whole app: window + status/navigation bars, the
 * Android view hierarchy (backgrounds, text, buttons, cards, toolbars) and
 * the player surface (root background + control-row accents). Each preset
 * has a dark and a brighter light palette selected from the current uiMode.
 *
 * The picker offers five presets — Tokyo Nights, Neon Sunset, Midnight Ocean,
 * Forest Glow and Rose Quartz — stored in SharedPreferences so a single
 * install supports all of them. The existing build-time player palette stays
 * untouched; this patch only adds runtime calls, each of which is a single
 * static invoke at method entry (no branches, no labels, no new registers),
 * which is the label-safe insertion form used throughout this project.
 */
@Suppress("unused")
val themeSettingsPatch = bytecodePatch(
    name = "Theme section in Settings",
    description = "Adds a Theme section to Settings with five presets (Tokyo Nights, Neon Sunset, Midnight Ocean, Forest Glow, Rose Quartz) for the app chrome and player.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)
    // A single Patch cannot touch both dex and resources; the row title comes
    // from a repurposed unused string applied by this companion resource patch.
    dependsOn(lyricsFallbackPatch, themeTitleResourcePatch)

    execute {
        // Register lifecycle callbacks once, at app startup. AnghamiApplication.onCreate
        // declares .locals 54 (registerCount 55), so `this` lives in v54 — beyond
        // the 16-register limit of inline `invoke-static` (35c). Morphe's inline
        // smali compiler fails there with "classDef is null", so build an
        // `invoke-static/range` (3rc) instruction directly instead.
        val appOnCreate = ThemeApplicationOnCreateFingerprint.method
        val thisRegister = appOnCreate.implementation!!.registerCount -
            appOnCreate.parameters.size - 1
        check(thisRegister >= 0) {
            "could not resolve the this register in AnghamiApplication.onCreate"
        }
        appOnCreate.addInstructions(
            0,
            listOf(
                BuilderInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE,
                    thisRegister,
                    1,
                    ImmutableMethodReference(
                        THEME_HELPER,
                        "register",
                        listOf("Landroid/app/Application;"),
                        "V",
                    ),
                ),
            ),
        )

        // Remember the Settings Activity and theme its chrome.
        ThemeSettingsActivityOnCreateFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $THEME_HELPER->rememberSettingsActivity(Ljava/lang/Object;)V
                invoke-static {p0}, $THEME_HELPER->applyTheme(Landroid/app/Activity;)V
            """
        )

        // Re-apply the player preset on every player resume (rotation, revisit).
        PlayerLyricsButtonResumeFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $THEME_HELPER->applyPlayerTheme(Ljava/lang/Object;)V
            """
        )

        // Inject the Theme row right after MUSIC. p1 is the mutable row list.
        ThemeSettingsRowsFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1}, $THEME_HELPER->addThemeSettingsRow(Ljava/lang/Object;)V
            """
        )

        // Replace the PageRow click lambda body: intercept our sentinel row,
        // delegate all other rows to the original callback. The method has
        // .locals 2, so v0 is safe for the Unit return.
        ThemeSettingsPageRowClickFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $THEME_HELPER->handleSettingsPageRowClick(Ljava/lang/Object;)V
                sget-object v0, LVd/E;->a:LVd/E;
                return-object v0
            """
        )
    }
}
