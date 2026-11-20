package app.anghami.patches.plus

import app.anghami.patches.shared.Constants.COMPATIBILITY_ANGHAMI_8_0_28
import app.morphe.patcher.patch.resourcePatch

private const val TITLE_RES_NAME = "dummy_tag_1"

/**
 * Retitles the runtime Theme settings row to exactly "THEME".
 *
 * The stock `pick_theme` string ("PICK THEME", 0x7f130fd6) cannot be
 * repurposed: it is also used by `tvPickTheme` in the two
 * `layout_lyrics_sharing_background_choosing.xml` layouts (portrait and
 * landscape), so changing its value would silently rename a lyrics-sharing
 * label. There is no other "THEME" string in the APK.
 *
 * `dummy_tag_1` (0x7f130b07) is an EMPTY placeholder in the stock
 * `res/values/strings.xml` with no translated variants and no references at
 * all — not in smali (the only hit is its own R$string field), not in any
 * layout/manifest/other resource, and not in assets. Its public.xml id is
 * pinned, so changing only its value keeps the resource id stable across
 * aapt2 re-encoding. The bytecode patch hardcodes that same id for the
 * PageRow title.
 */
@Suppress("unused")
val themeTitleResourcePatch = resourcePatch(
    name = "Theme row title",
    description = "Retitles the runtime Theme settings row to 'THEME' by repurposing the unused dummy_tag_1 string resource.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ANGHAMI_8_0_28)

    execute {
        val path = "res/values/strings.xml"
        val file = get(path)
        val original = file.readText()

        // Morphe/aapt2 may decode the empty entry either self-closing or as
        // an open/close pair; accept both (with or without extra attributes).
        val entry = Regex(
            """<string\b[^>]*\bname="$TITLE_RES_NAME"[^>]*(?:/>|>[^<]*</string>)""",
        )
        val matches = entry.findAll(original).toList()
        check(matches.size == 1) {
            "expected exactly one $TITLE_RES_NAME entry in $path, found ${matches.size}"
        }

        val updated = original.replace(entry, """<string name="$TITLE_RES_NAME">THEME</string>""")
        check(updated != original) { "failed to retitle $TITLE_RES_NAME in $path" }
        file.writeText(updated)
    }
}
