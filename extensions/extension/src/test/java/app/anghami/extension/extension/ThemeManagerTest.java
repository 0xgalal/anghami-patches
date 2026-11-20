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
    public void everyPresetHasADistinctOpaquePalette() {
        int previous = 0;
        for (int i = 0; i < 5; i++) {
            int background = ThemeManager.presetBackground(i);
            int accent = ThemeManager.presetAccent(i);
            assertEquals("background must be opaque", 0xff000000, background & 0xff000000);
            assertEquals("accent must be opaque", 0xff000000, accent & 0xff000000);
            assertNotEquals("presets must differ", previous, background);
            previous = background;
            assertTrue("accent must be a visible color", (accent & 0x00ffffff) != 0);
        }
    }
}
