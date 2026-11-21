package app.anghami.extension.extension;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Batch-planning / cache-key / assembly tests for the Cloudflare-only small
 * batch path. The non-Cloudflare providers keep the original single-request
 * path, which is asserted here at the plan level and at the HTTP level in
 * TranslationRequestTest.
 */
public class TranslationBatchTest {

    // ---------- small in-memory SharedPreferences ----------

    private static SharedPreferences prefs() {
        final Map<String, String> store = new HashMap<String, String>();
        return (SharedPreferences) Proxy.newProxyInstance(
                TranslationBatchTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class, SharedPreferences.Editor.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getString".equals(name)) return store.get((String) args[0]);
                        if ("putString".equals(name)) {
                            store.put((String) args[0], (String) args[1]);
                            return proxy;
                        }
                        if ("edit".equals(name) || "remove".equals(name)
                                || "clear".equals(name)) return proxy;
                        if ("apply".equals(name)) return null;
                        if ("commit".equals(name)) return Boolean.TRUE;
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("toString".equals(name)) return "fake translation prefs";
                        return null;
                    }
                });
    }

    // ---------- line builders (LrclibFallback.Line is package-private) ----------

    private static LrclibFallback.Line line(String text, int ms) {
        LrclibFallback.Line l = new LrclibFallback.Line();
        l.text = text;
        l.ms = ms;
        return l;
    }

    private static List<LrclibFallback.Line> lines(int count) {
        List<LrclibFallback.Line> out = new ArrayList<LrclibFallback.Line>();
        for (int i = 0; i < count; i++) out.add(line("Source" + i, i * 1000));
        return out;
    }

    private static List<LrclibFallback.Line> linesWithTexts(String... texts) {
        List<LrclibFallback.Line> out = new ArrayList<LrclibFallback.Line>();
        for (int i = 0; i < texts.length; i++) out.add(line(texts[i], i * 1000));
        return out;
    }

    // ---------- tests ----------

    @Test
    public void onlyCloudflareIsSplitIntoBatches() {
        List<LrclibFallback.Line> src = lines(60);
        List<List<LrclibFallback.Line>> cf = LrclibFallback.translationBatchesFor(
                TranslationProvider.CLOUDFLARE, TranslationProvider.CLOUDFLARE.defaultModel, src);
        assertTrue("Cloudflare must issue multiple requests", cf.size() > 1);

        for (TranslationProvider provider : TranslationProvider.values()) {
            if (provider == TranslationProvider.CLOUDFLARE) continue;
            List<List<LrclibFallback.Line>> plan = LrclibFallback.translationBatchesFor(
                    provider, provider.defaultModel, src);
            assertEquals(provider + " must stay a single request", 1, plan.size());
            assertEquals(60, plan.get(0).size());
        }
    }

    @Test
    public void cloudflareBatchSizesAreSmallAndSmallModelsSmaller() {
        int[] normal = LrclibFallback.translationBatchConfig(
                TranslationProvider.CLOUDFLARE, "@cf/meta/llama-3.3-70b-instruct-fp8-fast");
        assertEquals(10, normal[0]);
        assertEquals(1200, normal[1]);

        int[] small = LrclibFallback.translationBatchConfig(
                TranslationProvider.CLOUDFLARE, "@cf/meta/llama-3.1-8b-instruct");
        assertEquals(6, small[0]);
        assertEquals(800, small[1]);
        assertTrue(small[0] < normal[0]);
        assertTrue(small[1] < normal[1]);

        int[] standard = LrclibFallback.translationBatchConfig(
                TranslationProvider.OPENROUTER, TranslationProvider.OPENROUTER.defaultModel);
        assertTrue(standard[0] > normal[0]);
    }

    @Test
    public void longLyricsSplitIntoExpectedBatchesKeepingOrderAndEmptyLines() {
        List<LrclibFallback.Line> src = lines(60);
        // Replace a few lines with blanks: they must survive the split.
        src.set(4, line("", 4000));
        src.set(25, line("", 25000));
        List<List<LrclibFallback.Line>> batches =
                LrclibFallback.splitTranslationBatches(src, 10, 1200);

        assertEquals(6, batches.size());
        int seen = 0;
        for (List<LrclibFallback.Line> batch : batches) {
            assertTrue("batch <= 10 lines", batch.size() <= 10);
            for (LrclibFallback.Line l : batch) {
                String expectedText = (seen == 4 || seen == 25) ? "" : "Source" + seen;
                assertEquals("line " + seen, expectedText, l.text);
                assertEquals("timestamp " + seen, seen * 1000, l.ms);
                seen++;
            }
        }
        assertEquals(60, seen);
    }

    @Test
    public void characterLimitAlsoSplitsAndKeepsEveryLine() {
        List<LrclibFallback.Line> src = new ArrayList<LrclibFallback.Line>();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 600; i++) longText.append('x');
        for (int i = 0; i < 5; i++) src.add(line(longText.toString(), i));

        List<List<LrclibFallback.Line>> batches =
                LrclibFallback.splitTranslationBatches(src, 10, 1000);
        assertEquals("one 600-char line per 1000-char batch", 5, batches.size());
        for (List<LrclibFallback.Line> batch : batches) assertEquals(1, batch.size());

        // A line larger than the cap still forms a batch instead of vanishing.
        List<LrclibFallback.Line> huge = linesWithTexts("a", repeat('z', 5000), "b");
        List<List<LrclibFallback.Line>> hugeBatches =
                LrclibFallback.splitTranslationBatches(huge, 10, 1000);
        assertEquals(3, hugeBatches.size());
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    public void reassemblyPreservesCountOrderAndEmptyLines() {
        List<LrclibFallback.Line> src = linesWithTexts("A", "", "C", "D", "", "F");
        List<List<LrclibFallback.Line>> batches =
                LrclibFallback.splitTranslationBatches(src, 2, 1000);
        assertEquals(3, batches.size());
        List<List<String>> results = new ArrayList<List<String>>();
        results.add(Arrays.asList("A'", ""));
        results.add(null); // a failed batch: its lines become originals (blank)
        // Third batch is ["", "F"]: keep the empty line, translate "F", and
        // drop the extra element the model returned.
        results.add(Arrays.asList("", "F'", "extra is dropped"));

        List<String> joined = LrclibFallback.concatBatchResults(batches, results);
        assertEquals(6, joined.size());
        assertEquals(Arrays.asList("A'", "", "", "", "", "F'"), joined);
    }

    @Test
    public void partialFailureLeavesOriginalsAndReusesCachedBatches() {
        SharedPreferences p = prefs();
        List<LrclibFallback.Line> src = lines(30);
        List<List<LrclibFallback.Line>> batches = LrclibFallback.translationBatchesFor(
                TranslationProvider.CLOUDFLARE, TranslationProvider.CLOUDFLARE.defaultModel, src);
        assertEquals(3, batches.size());

        final int[] firstCalls = {0};
        LrclibFallback.BatchTranslator failMiddle = (batch, prompt) -> {
            firstCalls[0]++;
            if (firstCalls[0] == 2) throw new Exception("simulated timeout");
            List<String> out = new ArrayList<String>();
            for (LrclibFallback.Line l : batch) out.add("T" + l.text);
            return out;
        };
        LrclibFallback.BatchOutcome first = LrclibFallback.translateBatches(p,
                TranslationProvider.CLOUDFLARE, "acct", "model", "French", false, "",
                batches, failMiddle);
        assertEquals(3, firstCalls[0]);
        assertEquals(1, first.failedBatches);
        assertEquals(30, first.lines.size());
        for (int i = 0; i < 30; i++) {
            String expected = (i >= 10 && i < 20) ? "" : "TSource" + i;
            assertEquals("line " + i, expected, first.lines.get(i));
        }

        // Retry: only the failed batch is requested again; the two successful
        // batches come straight from their own cache entries.
        final int[] secondCalls = {0};
        LrclibFallback.BatchTranslator throwsAll = (batch, prompt) -> {
            secondCalls[0]++;
            throw new Exception("should not be called for cached batches");
        };
        LrclibFallback.BatchOutcome second = LrclibFallback.translateBatches(p,
                TranslationProvider.CLOUDFLARE, "acct", "model", "French", false, "",
                batches, throwsAll);
        assertEquals("only the previously failed batch retried", 1, secondCalls[0]);
        assertEquals(2, second.cachedBatches);
        assertEquals(1, second.failedBatches);
        assertEquals(30, second.lines.size());

        // Now the retried batch succeeds: everything is cached and complete.
        final int[] thirdCalls = {0};
        LrclibFallback.BatchTranslator succeed = (batch, prompt) -> {
            thirdCalls[0]++;
            List<String> out = new ArrayList<String>();
            for (LrclibFallback.Line l : batch) out.add("T" + l.text);
            return out;
        };
        LrclibFallback.BatchOutcome third = LrclibFallback.translateBatches(p,
                TranslationProvider.CLOUDFLARE, "acct", "model", "French", false, "",
                batches, succeed);
        assertEquals(1, thirdCalls[0]);
        assertEquals(0, third.failedBatches);
        assertEquals("two batches already cached before this retry", 2, third.cachedBatches);
        for (int i = 0; i < 30; i++) assertEquals("TSource" + i, third.lines.get(i));

        // Fourth run: every batch is cached, so zero requests are issued.
        final int[] fourthCalls = {0};
        LrclibFallback.BatchTranslator never = (batch, prompt) -> {
            fourthCalls[0]++;
            throw new Exception("all batches should be cached");
        };
        LrclibFallback.BatchOutcome fourth = LrclibFallback.translateBatches(p,
                TranslationProvider.CLOUDFLARE, "acct", "model", "French", false, "",
                batches, never);
        assertEquals(0, fourthCalls[0]);
        assertEquals(3, fourth.cachedBatches);
        assertEquals(0, fourth.failedBatches);
        for (int i = 0; i < 30; i++) assertEquals("TSource" + i, fourth.lines.get(i));
    }

    @Test
    public void oneEmptyCloudflareBatchLeavesOnlyItsLinesOriginal() {
        SharedPreferences p = prefs();
        List<LrclibFallback.Line> src = lines(60);
        List<List<LrclibFallback.Line>> batches = LrclibFallback.translationBatchesFor(
                TranslationProvider.CLOUDFLARE, TranslationProvider.CLOUDFLARE.defaultModel, src);
        assertTrue(batches.size() > 1);

        final int[] calls = {0};
        LrclibFallback.BatchTranslator emptySecondBatch = (batch, prompt) -> {
            int index = calls[0]++;
            if (index == 1) {
                throw new Exception("Cloudflare Workers AI returned no response text");
            }
            List<String> out = new ArrayList<String>();
            for (LrclibFallback.Line l : batch) out.add("T" + l.text);
            return out;
        };
        LrclibFallback.BatchOutcome outcome = LrclibFallback.translateBatches(p,
                TranslationProvider.CLOUDFLARE, "acct", "model", "French", false, "",
                batches, emptySecondBatch);

        assertEquals(1, outcome.failedBatches);
        assertTrue(outcome.firstError.contains("no response text"));
        assertEquals(60, outcome.lines.size());
        int offset = 0;
        for (int b = 0; b < batches.size(); b++) {
            for (int j = 0; j < batches.get(b).size(); j++) {
                int line = offset + j;
                if (b == 1) {
                    assertEquals("empty batch keeps the original line", "", outcome.lines.get(line));
                } else {
                    assertEquals("TSource" + line, outcome.lines.get(line));
                }
            }
            offset += batches.get(b).size();
        }
        assertEquals(60, offset);
    }

    @Test
    public void batchCacheKeyDependsOnConfigAndItsOwnLinesOnly() throws Exception {
        String model = "model-id";
        List<LrclibFallback.Line> batch = new ArrayList<LrclibFallback.Line>();
        for (int i = 0; i < 10; i++) batch.add(line("Source" + i, i * 1000));

        String base = LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "acct", model, "French", false, "", 0, batch);
        assertEquals(base, LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "acct", model, "French", false, "", 0, batch));
        assertNotEquals(base, LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "other-acct", model, "French", false, "", 0, batch));
        assertNotEquals(base, LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "acct", "other-model", "French", false, "", 0, batch));
        assertNotEquals(base, LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "acct", model, "Arabic", false, "", 0, batch));
        assertNotEquals(base, LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "acct", model, "French", false, "keep slang", 0, batch));
        assertNotEquals(base, LrclibFallback.translationBatchCacheKey(
                TranslationProvider.OPENROUTER, "acct", model, "French", false, "", 0, batch));
        assertNotEquals(base, LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "acct", model, "French", false, "", 1, batch));
        assertNotEquals(base, LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "acct", model, "French", false, "", 0,
                linesWithTexts("Different", "lines", "here")));

        // The key must NOT depend on the rest of the song: the first batch of
        // a 60-line song has the same key as that batch translated on its own.
        List<List<LrclibFallback.Line>> plan = LrclibFallback.translationBatchesFor(
                TranslationProvider.CLOUDFLARE, model, lines(60));
        String fromWholeSong = LrclibFallback.translationBatchCacheKey(
                TranslationProvider.CLOUDFLARE, "acct", model, "French", false, "", 0, plan.get(0));
        assertEquals(base, fromWholeSong);
    }

    @Test
    public void immediateActionRequiresCompleteSavedSettings() {
        assertTrue(LrclibFallback.hasUsableTranslationConfig(
                TranslationProvider.OPENROUTER, "key", "", "model", "French"));
        assertTrue(LrclibFallback.hasUsableTranslationConfig(
                TranslationProvider.CLOUDFLARE, "key", "acct", "model", "French"));
        assertFalse(LrclibFallback.hasUsableTranslationConfig(
                TranslationProvider.OPENROUTER, "", "", "model", "French"));
        assertFalse(LrclibFallback.hasUsableTranslationConfig(
                TranslationProvider.OPENROUTER, "key", "", "", "French"));
        assertFalse(LrclibFallback.hasUsableTranslationConfig(
                TranslationProvider.OPENROUTER, "key", "", "model", ""));
        assertFalse(LrclibFallback.hasUsableTranslationConfig(
                TranslationProvider.CLOUDFLARE, "key", "", "model", "French"));

        assertEquals("cloudflare_config_required", LrclibFallback.missingTranslationConfig(
                TranslationProvider.CLOUDFLARE, "key", "", "model", "French"));
        assertEquals("translation_config_required", LrclibFallback.missingTranslationConfig(
                TranslationProvider.OPENROUTER, "", "", "model", "French"));
        assertNull(LrclibFallback.missingTranslationConfig(
                TranslationProvider.OPENROUTER, "key", "", "model", "French"));
    }

    @Test
    public void translateNowActionIsLocalized() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            assertEquals("Translate now", LrclibFallback.tr("translate_now_action"));
            Locale.setDefault(Locale.FRENCH);
            assertEquals("Traduire maintenant", LrclibFallback.tr("translate_now_action"));
            Locale.setDefault(new Locale("ar"));
            assertEquals("ترجمة الآن", LrclibFallback.tr("translate_now_action"));
        } finally {
            Locale.setDefault(before);
        }
    }
}
