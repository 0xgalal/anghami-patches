package app.anghami.patches.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28

private const val THEME_HELPER = "Lapp/anghami/extension/extension/ThemeManager;"

/**
 * Adds a "Theme" section to Anghami's native Settings page and applies the
 * selected preset to the app chrome (status/navigation bars on every screen)
 * and to the player surface (root background + control-row accents).
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
    dependsOn(lyricsFallbackPatch)

    execute {
        // Register lifecycle callbacks once, at app startup. p0 is the
        // Application; the static call uses no local registers.
        ThemeApplicationOnCreateFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $THEME_HELPER->register(Landroid/app/Application;)V
            """
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
