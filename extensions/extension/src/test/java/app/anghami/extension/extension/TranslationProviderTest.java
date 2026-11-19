package app.anghami.extension.extension;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TranslationProviderTest {
    @Test
    public void existingSettingsDefaultToOpenRouterWithSeparateOpenCodeCredentials() {
        TranslationProvider legacy = TranslationProvider.fromId(null);
        assertEquals(TranslationProvider.OPENROUTER, legacy);
        assertEquals(legacy, TranslationProvider.fromId("unknown"));
        assertEquals("api_key", legacy.preferenceKey("api_key"));
        assertEquals("model", legacy.preferenceKey("model"));
        assertEquals("opencode_api_key",
                TranslationProvider.fromId("opencode").preferenceKey("api_key"));
        assertEquals("opencode_model", TranslationProvider.OPENCODE.preferenceKey("model"));
        assertEquals("xkiro_api_key", TranslationProvider.XKIRO.preferenceKey("api_key"));
        assertEquals("xkiro_model", TranslationProvider.XKIRO.preferenceKey("model"));
        assertEquals("shared-model", legacy.cacheModelKey("shared-model"));
        assertNotEquals(legacy.cacheModelKey("shared-model"),
                TranslationProvider.OPENCODE.cacheModelKey("shared-model"));
        assertNotEquals(legacy.cacheModelKey("shared-model"),
                TranslationProvider.XKIRO.cacheModelKey("shared-model"));
    }

    @Test
    public void openCodeCatalogExcludesAppOnlyFreeAndNonChatModels() throws Exception {
        JSONArray data = new JSONArray()
                .put(new JSONObject().put("id", "big-pickle"))
                .put(new JSONObject().put("id", "deepseek-v4.1-flash-free"))
                .put(openCodeModel("new-free-route", "Free", "0", "0"))
                .put(openCodeModel("jev-1.13", "Decision", "0.042", "0")
                        .put("type", "decision"))
                .put(openCodeModel("image-model", "Image", "1", "1")
                        .put("modalities", new JSONObject().put("output",
                                new JSONArray().put("image"))))
                .put(openCodeModel("glm-5.3-flash", "GLM Flash", "0.15", "0.5"))
                .put(openCodeModel("claude-haiku-5-5", "Claude Haiku", "0.1", "0.5"))
                .put(openCodeModel("glm-5.3-flash", "Duplicate", "0.15", "0.5"))
                .put(new JSONObject().put("id", "metadata-unavailable"))
                .put(JSONObject.NULL);
        List<TranslationProvider.Model> models = TranslationProvider.OPENCODE.parseModels(
                new JSONObject().put("data", data).toString());
        assertEquals(Arrays.asList("claude-haiku-5-5", "glm-5.3-flash", "metadata-unavailable"),
                ids(models));
        for (TranslationProvider.Model model : models) assertFalse(model.free);
    }

    @Test
    public void openRouterFreeCatalogHandlesPricingWithoutMislabelingUnknownOrPaid() throws Exception {
        JSONArray data = new JSONArray()
                .put(new JSONObject().put("id", "provider/variant:free").put("name", "Alpha"))
                .put(openRouterModel("provider/zero", "Bravo", "0.0000", "0e-6"))
                .put(openRouterModel("provider/paid", "Paid", "0", "0.000001"))
                .put(openRouterModel("provider/tiny-price", "Tiny price", "1e-500", "0"))
                .put(openRouterModel("provider/malformed", "Invalid", "", "0"))
                .put(new JSONObject().put("id", "provider/unknown"))
                .put(openRouterModel("provider/image:free", "Image", "0", "0")
                        .put("architecture", new JSONObject().put("output_modalities",
                                new JSONArray().put("image"))))
                .put(openRouterModel("provider/zero", "Duplicate", "0", "0"));
        List<TranslationProvider.Model> models = TranslationProvider.OPENROUTER.parseModels(
                new JSONObject().put("data", data).toString());
        assertEquals(Arrays.asList("provider/variant:free", "provider/zero"), ids(models));
        for (TranslationProvider.Model model : models) {
            assertTrue(model.free);
            assertTrue(model.displayName().contains(model.id));
        }
    }

    @Test
    public void xKiroCatalogUsesDisplayNamesAccessTiersAndChatModality() throws Exception {
        JSONArray data = new JSONArray()
                .put(xKiroModel("mistralai/mistral-small-2603", "Mistral Small 4",
                        "free", 0, 0))
                .put(xKiroModel("cohere/command-a-translate", "Command A Translate",
                        "free", 0, 0))
                .put(xKiroModel("openai/gpt-5.6-luna", "GPT-5.6 Luna",
                        "paid", 0.1, 0.6))
                .put(xKiroModel("google/gemini-3.6-flash", "Gemini 3.6 Flash",
                        "premium", 0.4875, 2.4375))
                .put(xKiroModel("vendor/comped-paid-tier", "Comped Paid Tier",
                        "paid", 0, 0))
                .put(xKiroModel("openai/voice", "Voice", "free", 0, 0)
                        .put("modality", "tts"))
                .put(xKiroModel("openai/gpt-5.6-luna", "Duplicate", "paid", 0.1, 0.6));
        List<TranslationProvider.Model> models = TranslationProvider.XKIRO.parseModels(
                new JSONObject().put("object", "list").put("data", data).toString());
        assertEquals(Arrays.asList(
                "cohere/command-a-translate", "google/gemini-3.6-flash",
                "openai/gpt-5.6-luna", "vendor/comped-paid-tier",
                "mistralai/mistral-small-2603"), ids(models));
        assertTrue(models.get(0).displayName().contains("Command A Translate"));
        assertTrue(models.get(0).displayName().endsWith("FREE"));
        assertFalse(models.get(1).free);
        assertFalse(models.get(2).free);
        assertFalse(models.get(3).free);
        assertTrue(models.get(4).free);
    }

    @Test
    public void invalidCatalogIsAnErrorInsteadOfAUsableEmptyList() {
        assertThrows(Exception.class, () ->
                TranslationProvider.OPENCODE.parseModels("{\"error\":{\"message\":\"denied\"}}"));
    }

    private static JSONObject openCodeModel(String id, String name, String input, String output)
            throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("type", "chat")
                .put("cost", new JSONObject().put("text", new JSONObject()
                        .put("input", new JSONObject().put("price", input))
                        .put("output", new JSONObject().put("price", output))));
    }

    private static JSONObject openRouterModel(String id, String name, String input, String output)
            throws Exception {
        return new JSONObject().put("id", id).put("name", name)
                .put("pricing", new JSONObject().put("prompt", input).put("completion", output));
    }

    private static JSONObject xKiroModel(String id, String displayName, String tier,
                                         double input, double output) throws Exception {
        return new JSONObject().put("id", id).put("type", "model").put("modality", "chat")
                .put("display_name", displayName).put("access_tier", tier)
                .put("pricing", new JSONObject().put("input", input).put("output", output));
    }

    private static List<String> ids(List<TranslationProvider.Model> models) {
        List<String> ids = new ArrayList<String>();
        for (TranslationProvider.Model model : models) ids.add(model.id);
        return ids;
    }
}
