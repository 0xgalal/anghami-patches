package app.anghami.extension.extension;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ArabicTranslationTest {

    @Test
    public void arabicLineDetectorCountsOnlyArabicLetters() {
        assertTrue(LrclibFallback.isArabicScriptLine("مرحبا بالعالم"));
        assertTrue(LrclibFallback.isArabicScriptLine("كلمات الأغنية"));
        // Arabic presentation forms and the Arabic block are both Arabic script.
        assertTrue(LrclibFallback.isArabicScriptLine("\uFB50"));
        // Per-line rule: one Arabic letter is enough, so a mixed line keeps
        // its original text verbatim instead of being rewritten by the model.
        assertTrue(LrclibFallback.isArabicScriptLine("hello مرحبا"));
        assertFalse(LrclibFallback.isArabicScriptLine("Hello, world!"));
        assertFalse(LrclibFallback.isArabicScriptLine("123 456"));
        // Arabic-Indic digits and Arabic punctuation are not letters.
        assertFalse(LrclibFallback.isArabicScriptLine("١٢٣ !؟"));
        assertFalse(LrclibFallback.isArabicScriptLine(""));
        assertFalse(LrclibFallback.isArabicScriptLine(null));
    }

    @Test
    public void preserveArabicLinesCopiesArabicVerbatimAndReplacesOtherLines() throws Exception {
        List<?> source = lines("  مرحبا  ", "Hello world", "mixed مرحبا words", "");
        List<String> translated = Arrays.asList("ignored", "Bonjour", "ignored too", "vide");

        assertEquals(Arrays.asList("  مرحبا  ", "Bonjour", "mixed مرحبا words", "vide"),
                LrclibFallback.preserveArabicLines(raw(source), translated));
    }

    @Test
    public void preserveArabicLinesPreservesSourceCountAndOrder() throws Exception {
        List<?> source = lines("مرحبا", "Hello", "世界");

        // Short model output: missing entries stay empty, Arabic still wins.
        assertEquals(Arrays.asList("مرحبا", "Bonjour", ""),
                LrclibFallback.preserveArabicLines(raw(source),
                        Arrays.asList("ignored", "Bonjour")));
        // Extra model output is dropped; source order is authoritative.
        assertEquals(Arrays.asList("مرحبا", "Bonjour", "Monde"),
                LrclibFallback.preserveArabicLines(raw(source),
                        Arrays.asList("ignored", "Bonjour", "Monde", "extra")));
        assertEquals(3, LrclibFallback.preserveArabicLines(raw(source), null).size());
    }

    @Test
    public void renderingNeverDuplicatesAnArabicLine() {
        assertEquals("مرحبا", LrclibFallback.displayLineText("مرحبا", "مرحبا"));
        assertEquals("  مرحبا  ", LrclibFallback.displayLineText("  مرحبا  ", "ignored"));
        assertEquals("mixed مرحبا", LrclibFallback.displayLineText("mixed مرحبا", "translated"));
        assertEquals("Hello\nBonjour", LrclibFallback.displayLineText("Hello", "Bonjour"));
        assertEquals("Hello", LrclibFallback.displayLineText("Hello", ""));
    }

    @Test
    public void allArabicScriptLinesOnlySkipsWhenNothingIsTranslatable() throws Exception {
        assertTrue(LrclibFallback.allArabicScriptLines(raw(lines("مرحبا", "العالم"))));
        // Blank lines carry nothing to translate either.
        assertTrue(LrclibFallback.allArabicScriptLines(raw(lines("مرحبا", ""))));
        assertFalse(LrclibFallback.allArabicScriptLines(raw(lines("مرحبا", "Hello"))));
        assertFalse(LrclibFallback.allArabicScriptLines(raw(lines("Hello"))));
        assertFalse(LrclibFallback.allArabicScriptLines(raw(lines())));
        assertFalse(LrclibFallback.allArabicScriptLines(null));
    }

    @Test
    public void autoTranslateRunsForMixedSongsButNotFullyArabicOnes() throws Exception {
        Class<?> settingsClass =
                Class.forName("app.anghami.extension.extension.LrclibFallback$TranslationSettings");
        Class<?> snapshotClass =
                Class.forName("app.anghami.extension.extension.LrclibFallback$SourceSnapshot");
        Method canAutoTranslate = LrclibFallback.class.getDeclaredMethod(
                "canAutoTranslate", settingsClass, snapshotClass);
        canAutoTranslate.setAccessible(true);

        Constructor<?> settingsCtor =
                settingsClass.getDeclaredConstructor(TranslationProvider.class);
        settingsCtor.setAccessible(true);
        Object settings = settingsCtor.newInstance(TranslationProvider.OPENCODE);
        set(settingsClass, settings, "apiKey", "test-key");
        set(settingsClass, settings, "model", "test-model");
        set(settingsClass, settings, "language", "French");
        set(settingsClass, settings, "autoTranslate", true);

        Constructor<?> snapshotCtor = snapshotClass.getDeclaredConstructor();
        snapshotCtor.setAccessible(true);
        Object snapshot = snapshotCtor.newInstance();

        set(snapshotClass, snapshot, "lines", lines("مرحبا", "العالم"));
        assertFalse((Boolean) canAutoTranslate.invoke(null, settings, snapshot));

        set(snapshotClass, snapshot, "lines", lines("مرحبا", "Hello"));
        assertTrue((Boolean) canAutoTranslate.invoke(null, settings, snapshot));
    }

    private static void set(Class<?> type, Object target, String name, Object value)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** LrclibFallback.Line is private, so build instances reflectively. */
    private static List<Object> lines(String... texts) throws Exception {
        Class<?> lineClass =
                Class.forName("app.anghami.extension.extension.LrclibFallback$Line");
        Constructor<?> ctor = lineClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Field textField = lineClass.getDeclaredField("text");
        textField.setAccessible(true);
        List<Object> out = new ArrayList<Object>();
        for (String text : texts) {
            Object line = ctor.newInstance();
            textField.set(line, text);
            out.add(line);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List raw(List<?> lines) {
        return (List) lines;
    }
}
