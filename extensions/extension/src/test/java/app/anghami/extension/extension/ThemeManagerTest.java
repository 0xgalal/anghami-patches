package app.anghami.extension.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThemeManagerTest {

    @Test
    public void offersFiveTrendyPresets() {
        assertEquals("Tokyo Nights", ThemeManager.presetName(0));
        assertEquals("Neon Sunset", ThemeManager.presetName(1));
        assertEquals("Midnight Ocean", ThemeManager.presetName(2));
        assertEquals("Forest Glow", ThemeManager.presetName(3));
        assertEquals("Rose Quartz", ThemeManager.presetName(4));
    }

    @Test
    public void outOfRangeIdsFallBackToFirstPreset() {
        assertEquals("Tokyo Nights", ThemeManager.presetName(-1));
        assertEquals("Tokyo Nights", ThemeManager.presetName(99));
    }

    @Test
    public void everyPresetHasADistinctOpaquePaletteInBothModes() {
        for (boolean night : new boolean[] { true, false }) {
            int previous = 0;
            for (int i = 0; i < 5; i++) {
                int background = ThemeManager.presetBackground(i, night);
                int accent = ThemeManager.presetAccent(i, night);
                int secondary = ThemeManager.presetSecondary(i, night);
                int foreground = ThemeManager.presetForeground(i, night);
                int onAccent = ThemeManager.presetOnAccent(i, night);
                assertEquals("background must be opaque", 0xff000000, background & 0xff000000);
                assertEquals("accent must be opaque", 0xff000000, accent & 0xff000000);
                assertEquals("secondary must be opaque", 0xff000000, secondary & 0xff000000);
                assertEquals("foreground must be opaque", 0xff000000, foreground & 0xff000000);
                assertEquals("onAccent must be opaque", 0xff000000, onAccent & 0xff000000);
                assertNotEquals("presets must differ", previous, background);
                previous = background;
                assertTrue("accent must be a visible color", (accent & 0x00ffffff) != 0);
                assertNotEquals("foreground must differ from background", background, foreground);
            }
        }
    }

    @Test
    public void darkAndLightPalettesAreDistinctForEveryPreset() {
        for (int i = 0; i < 5; i++) {
            assertNotEquals("background must change with mode",
                    ThemeManager.presetBackground(i, true), ThemeManager.presetBackground(i, false));
            assertNotEquals("accent must change with mode",
                    ThemeManager.presetAccent(i, true), ThemeManager.presetAccent(i, false));
            assertNotEquals("foreground must change with mode",
                    ThemeManager.presetForeground(i, true), ThemeManager.presetForeground(i, false));
        }
    }

    @Test
    public void dayBackgroundsAreBrighterThanNightBackgrounds() {
        for (int i = 0; i < 5; i++) {
            assertTrue(ThemeManager.presetName(i) + " day background should be brighter",
                    luminance(ThemeManager.presetBackground(i, false))
                            > luminance(ThemeManager.presetBackground(i, true)));
        }
    }

    @Test
    public void nightTextIsBrighterThanDayText() {
        for (int i = 0; i < 5; i++) {
            assertTrue(ThemeManager.presetName(i) + " night text should be light",
                    luminance(ThemeManager.presetForeground(i, true))
                            > luminance(ThemeManager.presetForeground(i, false)));
        }
    }

    @Test
    public void buttonTextContrastsWithAccentInBothModes() {
        for (int i = 0; i < 5; i++) {
            for (boolean night : new boolean[] { true, false }) {
                int delta = Math.abs(luminance(ThemeManager.presetAccent(i, night))
                        - luminance(ThemeManager.presetOnAccent(i, night)));
                assertTrue(ThemeManager.presetName(i) + " button text needs contrast", delta > 40);
            }
        }
    }

    @Test
    public void legacyAccessorsStillReturnTheDarkPalette() {
        for (int i = 0; i < 5; i++) {
            assertEquals(ThemeManager.presetBackground(i, true), ThemeManager.presetBackground(i));
            assertEquals(ThemeManager.presetAccent(i, true), ThemeManager.presetAccent(i));
        }
    }

    /** Perceived luminance (0..255) of an opaque ARGB color. */
    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
}
