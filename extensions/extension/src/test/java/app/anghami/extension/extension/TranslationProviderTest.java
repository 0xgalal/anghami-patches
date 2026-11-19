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
        assertEquals("shared-model", legacy.cacheModelKey("shared-model"));
        assertNotEquals(legacy.cacheModelKey("shared-model"),
                TranslationProvider.OPENCODE.cacheModelKey("shared-model"));
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

    private static List<String> ids(List<TranslationProvider.Model> models) {
        List<String> ids = new ArrayList<String>();
        for (TranslationProvider.Model model : models) ids.add(model.id);
        return ids;
    }
}
