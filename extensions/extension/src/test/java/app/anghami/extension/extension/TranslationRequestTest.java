package app.anghami.extension.extension;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TranslationRequestTest {
    private static final ArrayDeque<Response> responses = new ArrayDeque<Response>();
    private static final List<Connection> connections = new ArrayList<Connection>();
    private static Method request;
    private static Method parseLyrics;
    private static Method parsePlainLyrics;

    @BeforeClass
    public static void interceptHttpRequests() throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol)
                ? new URLStreamHandler() {
                    @Override
                    protected java.net.URLConnection openConnection(URL url) {
                        Connection connection = new Connection(url, responses.remove());
                        connections.add(connection);
                        return connection;
                    }
                } : null);
        request = LrclibFallback.class.getDeclaredMethod("requestTranslation",
                TranslationProvider.class, String.class, String.class, String.class,
                String.class, String.class, List.class);
        request.setAccessible(true);
        parseLyrics = LrclibFallback.class.getDeclaredMethod("parseLrc", String.class);
        parseLyrics.setAccessible(true);
        parsePlainLyrics = LrclibFallback.class.getDeclaredMethod("plainLines", String.class);
        parsePlainLyrics.setAccessible(true);
    }

    @Before
    public void resetResponses() {
        responses.clear();
        connections.clear();
    }

    @Test
    public void fetchesEachPublicCatalogWithoutSendingTranslationCredentials() throws Exception {
        Method fetch = LrclibFallback.class.getDeclaredMethod(
                "fetchTranslationModels", TranslationProvider.class);
        fetch.setAccessible(true);
        for (TranslationProvider provider : TranslationProvider.values()) {
            String catalog = provider == TranslationProvider.XKIRO
                    ? "{\"object\":\"list\",\"data\":[{\"id\":\"mistralai/mistral-small-2603\","
                    + "\"display_name\":\"Mistral Small 4\",\"modality\":\"chat\","
                    + "\"access_tier\":\"free\"}]}"
                    : "{\"data\":[{\"id\":\"provider/model:free\",\"name\":\"Free\"},"
                    + "{\"id\":\"glm-5.3-flash\",\"name\":\"GLM\",\"type\":\"chat\"}]}";
            responses.add(new Response(200, catalog));
            List<?> models = (List<?>) fetch.invoke(null, provider);
            assertFalse(models.isEmpty());
            Connection connection = connections.get(connections.size() - 1);
            String expectedUrl;
            if (provider == TranslationProvider.OPENROUTER) {
                expectedUrl = "https://openrouter.ai/api/v1/models";
            } else if (provider == TranslationProvider.OPENCODE) {
                expectedUrl = "https://opencode.ai/inference/v1/models";
            } else {
                expectedUrl = "https://api.xkiro.com/v1/models";
            }
            assertEquals(expectedUrl, connection.getURL().toString());
            assertEquals("GET", connection.getRequestMethod());
            assertNull(connection.getRequestProperty("Authorization"));
            assertTrue(connection.disconnected);
        }
    }

    @Test
    public void routesEachProviderWithItsSelectedKeyAndModel() throws Exception {
        for (TranslationProvider provider : TranslationProvider.values()) {
            responses.add(new Response(200, success("Bonjour")));
            assertEquals(Arrays.asList("Bonjour"), translate(provider, provider.id + "-test-key"));
            Connection connection = connections.get(connections.size() - 1);
            String expectedUrl;
            if (provider == TranslationProvider.OPENROUTER) {
                expectedUrl = "https://openrouter.ai/api/v1/chat/completions";
            } else if (provider == TranslationProvider.OPENCODE) {
                expectedUrl = "https://opencode.ai/inference/openai/v1/chat/completions";
            } else {
                expectedUrl = "https://api.xkiro.com/v1/chat/completions";
            }
            assertEquals(expectedUrl, connection.getURL().toString());
            assertEquals("POST", connection.getRequestMethod());
            assertEquals("Bearer " + provider.id + "-test-key",
                    connection.getRequestProperty("Authorization"));
            JSONObject body = connection.requestBody();
            assertEquals(provider.defaultModel, body.getString("model"));
            assertFalse(body.getBoolean("stream"));
            JSONArray messages = body.getJSONArray("messages");
            String systemPrompt = messages.getJSONObject(0).getString("content");
            String userPrompt = messages.getJSONObject(1).getString("content");
            assertTrue(systemPrompt.startsWith("You are a precise lyrics translation assistant."));
            assertTrue(systemPrompt.contains("Output ONLY the JSON object: "
                    + "{\"lines\": [\"line1\", \"line2\", \"line3\"]}"));
            assertTrue(systemPrompt.contains("EXACTLY 1 items"));
            assertTrue(systemPrompt.contains("If uncertain, provide your best possible translation."));
            assertFalse(systemPrompt.contains("{lineCount}"));
            assertTrue(userPrompt.contains("Return exactly 1 output lines"));
            assertTrue(userPrompt.contains("string array named `lines`"));
            assertTrue(userPrompt.contains("{\"lines\":[\"Hello\"]}"));
            if (provider != TranslationProvider.XKIRO) {
                JSONObject schema = body.getJSONObject("response_format")
                        .getJSONObject("json_schema").getJSONObject("schema");
                JSONObject lineSchema = schema.getJSONObject("properties")
                        .getJSONObject("lines");
                assertEquals(1, lineSchema.getInt("minItems"));
                assertEquals(1, lineSchema.getInt("maxItems"));
                assertEquals("lines", schema.getJSONArray("required").getString(0));
            }
            if (provider == TranslationProvider.OPENCODE) {
                assertNotNull(connection.getRequestProperty("x-opencode-session"));
                assertNull(connection.getRequestProperty("HTTP-Referer"));
                assertFalse(body.has("temperature"));
            } else if (provider == TranslationProvider.XKIRO) {
                assertNull(connection.getRequestProperty("x-opencode-session"));
                assertNull(connection.getRequestProperty("HTTP-Referer"));
                assertFalse(body.has("temperature"));
            } else {
                assertNotNull(connection.getRequestProperty("HTTP-Referer"));
                assertNull(connection.getRequestProperty("x-opencode-session"));
            }
            assertTrue(connection.disconnected);
        }
    }

    @Test
    public void unsupportedStructuredOutputRetriesWithSameSessionAndCredentials() throws Exception {
        responses.add(new Response(400, "{\"error\":{\"message\":\"json_schema unsupported\"}}"));
        responses.add(new Response(422, "{\"error\":{\"message\":\"json_object unsupported\"}}"));
        responses.add(new Response(200, success("Bonjour")));
        assertEquals(Arrays.asList("Bonjour"), translate(TranslationProvider.OPENCODE, "test-key"));
        assertEquals(3, connections.size());
        assertEquals("json_schema", connections.get(0).requestBody()
                .getJSONObject("response_format").getString("type"));
        assertEquals("json_object", connections.get(1).requestBody()
                .getJSONObject("response_format").getString("type"));
        assertFalse(connections.get(2).requestBody().has("response_format"));
        String session = connections.get(0).getRequestProperty("x-opencode-session");
        for (Connection connection : connections) {
            assertEquals(session, connection.getRequestProperty("x-opencode-session"));
            assertEquals("Bearer test-key", connection.getRequestProperty("Authorization"));
            assertTrue(connection.disconnected);
        }
    }

    @Test
    public void deniedRequestDoesNotRetryOrSwitchProviders() {
        responses.add(new Response(403, "{\"error\":{\"message\":\"Access denied\"}}"));
        Exception error = assertThrows(Exception.class,
                () -> translate(TranslationProvider.OPENCODE, "test-key"));
        assertTrue(error.getMessage().contains("OpenCode HTTP 403"));
        assertEquals(1, connections.size());
        assertTrue(connections.get(0).disconnected);
    }

    @Test
    public void xKiroUsesDocumentedJsonObjectAndDoesNotSendOpenCodeSessionHeader() throws Exception {
        responses.add(new Response(200, success("Bonjour")));
        assertEquals(Arrays.asList("Bonjour"), translate(TranslationProvider.XKIRO, "test-key"));
        assertEquals(1, connections.size());
        Connection connection = connections.get(0);
        assertEquals("Bearer test-key", connection.getRequestProperty("Authorization"));
        assertNull(connection.getRequestProperty("x-opencode-session"));
        assertEquals("json_object", connection.requestBody()
                .getJSONObject("response_format").getString("type"));
    }

    @Test
    public void xKiroFallsBackToPlainCompletionIfJsonModeIsRejected() throws Exception {
        responses.add(new Response(400, "{\"error\":{\"message\":\"response_format unsupported\"}}"));
        responses.add(new Response(200, success("Bonjour")));
        assertEquals(Arrays.asList("Bonjour"), translate(TranslationProvider.XKIRO, "test-key"));
        assertEquals(2, connections.size());
        assertEquals("json_object", connections.get(0).requestBody()
                .getJSONObject("response_format").getString("type"));
        assertFalse(connections.get(1).requestBody().has("response_format"));
    }

    @Test
    public void misalignedTranslationIsRejected() throws Exception {
        responses.add(new Response(200, success("Bonjour", "Extra line")));
        Exception error = assertThrows(Exception.class,
                () -> translate(TranslationProvider.OPENCODE, "test-key"));
        assertEquals("Model returned a different number of lines", error.getMessage());
        assertEquals(1, connections.size());
    }

    @Test
    public void requestKeepsEmptyLinesAndRequiresExactSourceCount() throws Exception {
        List<?> source = (List<?>) parsePlainLyrics.invoke(null, "Hello\n\nWorld");
        responses.add(new Response(200, success("Bonjour", "", "Monde")));
        @SuppressWarnings("unchecked")
        List<String> translated = (List<String>) request.invoke(null,
                TranslationProvider.OPENROUTER, "test-key",
                TranslationProvider.OPENROUTER.defaultModel, null, "French", "", source);

        assertEquals(Arrays.asList("Bonjour", "", "Monde"), translated);
        JSONObject body = connections.get(0).requestBody();
        String systemPrompt = body.getJSONArray("messages").getJSONObject(0)
                .getString("content");
        String userPrompt = body.getJSONArray("messages").getJSONObject(1)
                .getString("content");
        assertTrue(systemPrompt.contains("EXACTLY 3 items"));
        assertFalse(systemPrompt.contains("{lineCount}"));
        assertTrue(userPrompt.contains("{\"lines\":[\"Hello\",\"\",\"World\"]}"));
        JSONObject lineSchema = body.getJSONObject("response_format")
                .getJSONObject("json_schema").getJSONObject("schema")
                .getJSONObject("properties").getJSONObject("lines");
        assertEquals(3, lineSchema.getInt("minItems"));
        assertEquals(3, lineSchema.getInt("maxItems"));
    }

    @Test
    public void dialogShowsStrictDefaultPromptAndKeepsAdditionalInstructions() throws Exception {
        Method forDialog = LrclibFallback.class.getDeclaredMethod(
                "systemPromptForDialog", String.class);
        Method fromDialog = LrclibFallback.class.getDeclaredMethod(
                "customSystemPromptFromDialog", String.class);
        forDialog.setAccessible(true);
        fromDialog.setAccessible(true);

        String defaultPrompt = (String) forDialog.invoke(null, "");
        assertTrue(defaultPrompt.contains("EXACTLY {lineCount} items"));
        assertEquals("", fromDialog.invoke(null, defaultPrompt));

        String custom = "Keep the translation conversational.";
        String editable = (String) forDialog.invoke(null, custom);
        assertTrue(editable.contains("Additional user instructions:\n" + custom));
        assertEquals(custom, fromDialog.invoke(null, editable));
    }

    @Test
    public void parserRejectsResponsesThatDoNotUseLinesObject() throws Exception {
        String legacyContent = new JSONObject()
                .put("translations", new JSONArray().put("Bonjour")).toString();
        String body = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", "stop")
                .put("message", new JSONObject().put("content", legacyContent)))).toString();
        responses.add(new Response(200, body));
        Exception error = assertThrows(Exception.class,
                () -> translate(TranslationProvider.XKIRO, "test-key"));
        assertEquals("Model returned a different number of lines", error.getMessage());
    }

    private static List<?> translate(TranslationProvider provider, String key) throws Exception {
        try {
            return (List<?>) request.invoke(null, provider, key, provider.defaultModel,
                    provider == TranslationProvider.OPENCODE ? "ses_test-session" : null,
                    "French", "", parseLyrics.invoke(null, "[00:01.00]Hello"));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    private static String success(String... lines) throws Exception {
        String content = new JSONObject().put("lines", new JSONArray(Arrays.asList(lines)))
                .toString();
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", "stop")
                .put("message", new JSONObject().put("content", content)))).toString();
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class Connection extends HttpURLConnection {
        final Response response;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean disconnected;

        Connection(URL url, Response response) {
            super(url);
            this.response = response;
        }

        JSONObject requestBody() throws Exception {
            return new JSONObject(output.toString("UTF-8"));
        }

        @Override public void connect() {}
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return response.status; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(response.body.getBytes(StandardCharsets.UTF_8));
        }
        @Override public InputStream getErrorStream() { return getInputStream(); }
    }
}
