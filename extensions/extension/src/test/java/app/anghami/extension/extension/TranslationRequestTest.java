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
    private static Method requestWithAccount;
    private static Method fetchWithCredentials;
    private static Method parseLyrics;
    private static Method parsePlainLyrics;
    private static Method isTranslationSourceResult;

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
        requestWithAccount = LrclibFallback.class.getDeclaredMethod("requestTranslation",
                TranslationProvider.class, String.class, String.class, String.class,
                String.class, String.class, String.class, List.class);
        requestWithAccount.setAccessible(true);
        fetchWithCredentials = LrclibFallback.class.getDeclaredMethod(
                "fetchTranslationModels", TranslationProvider.class, String.class, String.class);
        fetchWithCredentials.setAccessible(true);
        parseLyrics = LrclibFallback.class.getDeclaredMethod("parseLrc", String.class);
        parseLyrics.setAccessible(true);
        parsePlainLyrics = LrclibFallback.class.getDeclaredMethod("plainLines", String.class);
        parsePlainLyrics.setAccessible(true);
        isTranslationSourceResult = LrclibFallback.class.getDeclaredMethod(
                "isTranslationSourceResult", String.class);
        isTranslationSourceResult.setAccessible(true);
    }

    @Before
    public void resetResponses() {
        responses.clear();
        connections.clear();
    }

    @Test
    public void fetchesEachCatalogWithOnlyCloudflareRequiringCredentials() throws Exception {
        Method fetch = LrclibFallback.class.getDeclaredMethod(
                "fetchTranslationModels", TranslationProvider.class);
        fetch.setAccessible(true);
        for (TranslationProvider provider : TranslationProvider.values()) {
            String catalog;
            if (provider == TranslationProvider.CLOUDFLARE) {
                catalog = "{\"success\":true,\"result\":["
                        + "{\"id\":\"@cf/meta/llama-3.1-8b-instruct\",\"name\":\"Llama\"}]}";
            } else if (provider == TranslationProvider.XKIRO) {
                catalog = "{\"object\":\"list\",\"data\":[{\"id\":\"mistralai/mistral-small-2603\","
                        + "\"display_name\":\"Mistral Small 4\",\"modality\":\"chat\","
                        + "\"access_tier\":\"free\"}]}";
            } else {
                catalog = "{\"data\":[{\"id\":\"provider/model:free\",\"name\":\"Free\"},"
                        + "{\"id\":\"glm-5.3-flash\",\"name\":\"GLM\",\"type\":\"chat\"}]}";
            }
            responses.add(new Response(200, catalog));
            List<?> models = provider == TranslationProvider.CLOUDFLARE
                    ? (List<?>) fetchWithCredentials.invoke(null, provider, "acct123", "cf-token")
                    : (List<?>) fetch.invoke(null, provider);
            assertFalse(models.isEmpty());
            Connection connection = connections.get(connections.size() - 1);
            String expectedUrl;
            if (provider == TranslationProvider.OPENROUTER) {
                expectedUrl = "https://openrouter.ai/api/v1/models";
            } else if (provider == TranslationProvider.OPENCODE) {
                expectedUrl = "https://opencode.ai/inference/v1/models";
            } else if (provider == TranslationProvider.XKIRO) {
                expectedUrl = "https://api.xkiro.com/v1/models";
            } else {
                expectedUrl = "https://api.cloudflare.com/client/v4/accounts/acct123/ai/models";
            }
            assertEquals(expectedUrl, connection.getURL().toString());
            assertEquals("GET", connection.getRequestMethod());
            if (provider == TranslationProvider.CLOUDFLARE) {
                assertEquals("Bearer cf-token", connection.getRequestProperty("Authorization"));
            } else {
                assertNull(connection.getRequestProperty("Authorization"));
            }
            assertTrue(connection.disconnected);
        }
    }

    @Test
    public void routesEachProviderWithItsSelectedKeyAndModel() throws Exception {
        for (TranslationProvider provider : TranslationProvider.values()) {
            if (provider == TranslationProvider.CLOUDFLARE) continue; // covered separately
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
    public void extraTranslationLinesAreIgnored() throws Exception {
        responses.add(new Response(200, success("Bonjour", "Extra line")));
        assertEquals(Arrays.asList("Bonjour"),
                translate(TranslationProvider.OPENCODE, "test-key"));
        assertEquals(1, connections.size());
    }

    @Test
    public void missingTranslationLinesFallBackToOriginals() throws Exception {
        List<?> source = (List<?>) parsePlainLyrics.invoke(null, "Hello\nWorld\nAgain");
        responses.add(new Response(200, success("Bonjour")));
        @SuppressWarnings("unchecked")
        List<String> translated = (List<String>) request.invoke(null,
                TranslationProvider.OPENROUTER, "test-key",
                TranslationProvider.OPENROUTER.defaultModel, null, "French", "", source);
        assertEquals(Arrays.asList("Bonjour", "", ""), translated);
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
    public void statusAndTeaserRowsAreNotTreatedAsLyricsToTranslate() throws Exception {
        assertFalse((Boolean) isTranslationSourceResult.invoke(null, "placeholder"));
        assertFalse((Boolean) isTranslationSourceResult.invoke(null, "no-result"));
        assertFalse((Boolean) isTranslationSourceResult.invoke(null, "teaser-guide"));
        assertTrue((Boolean) isTranslationSourceResult.invoke(null, "lrclib-title"));
    }

    @Test
    public void parserAcceptsLegacyTranslationsKey() throws Exception {
        String legacyContent = new JSONObject()
                .put("translations", new JSONArray().put("Bonjour")).toString();
        responses.add(new Response(200, completionWithContent(legacyContent)));
        assertEquals(Arrays.asList("Bonjour"),
                translate(TranslationProvider.XKIRO, "test-key"));
    }

    @Test
    public void parserAcceptsExtraKeysAndMarkdownWrappedJson() throws Exception {
        String extraKey = new JSONObject().put("lines", new JSONArray().put("Bonjour"))
                .put("note", "Here is the translation").toString();
        responses.add(new Response(200, completionWithContent(extraKey)));
        assertEquals(Arrays.asList("Bonjour"),
                translate(TranslationProvider.XKIRO, "test-key"));

        responses.add(new Response(200, completionWithContent(
                "```json\n{\"lines\":[\"Bonjour\"]}\n```")));
        assertEquals(Arrays.asList("Bonjour"),
                translate(TranslationProvider.XKIRO, "test-key"));
    }

    @Test
    public void cloudflareBuildsAccountScopedUrlOmitsModelAndParsesResultResponse() throws Exception {
        responses.add(new Response(200, cloudflareSuccess("Bonjour")));
        assertEquals(Arrays.asList("Bonjour"), translateCloudflare("cf-token", "acct123"));
        assertEquals(1, connections.size()); // no response_format retries
        Connection connection = connections.get(0);
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acct123/ai/run/"
                + "@cf/meta/llama-3.3-70b-instruct-fp8-fast", connection.getURL().toString());
        assertEquals("POST", connection.getRequestMethod());
        assertEquals("Bearer cf-token", connection.getRequestProperty("Authorization"));
        assertNull(connection.getRequestProperty("HTTP-Referer"));
        assertNull(connection.getRequestProperty("x-opencode-session"));
        JSONObject body = connection.requestBody();
        assertFalse(body.has("model"));
        assertFalse(body.has("response_format"));
        assertFalse(body.getBoolean("stream"));
        assertNotNull(body.getJSONArray("messages"));
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content")
                .contains("{\"lines\":[\"Hello\"]}"));
        assertTrue(connection.disconnected);
    }

    @Test
    public void cloudflareAcceptsMarkdownFencedJsonInsideResultResponse() throws Exception {
        responses.add(new Response(200, cloudflareResponse(
                "```json\n{\"lines\":[\"Bonjour\"]}\n```")));
        assertEquals(Arrays.asList("Bonjour"), translateCloudflare("cf-token", "acct123"));
        assertEquals(1, connections.size());
    }

    @Test
    public void cloudflareRecoversUnterminatedFinalString() throws Exception {
        // Reproduces the device report: org.json throws
        // "Unterminated string at character 660 of ..." when the model's
        // output is cut off in the middle of the last array element.
        StringBuilder filler = new StringBuilder();
        while (filler.length() < 620) filler.append('x');
        String content = "{\"lines\":[\"Bonjour\",\"Monde\",\"Au revoir\",\"" + filler;
        assertTrue("expected ~660 chars, got " + content.length(),
                content.length() >= 650 && content.length() < 700);
        assertEquals(Arrays.asList("Bonjour", "Monde", "Au revoir", filler.toString()),
                translateCloudflareWithSource(content, sourceWithLines(4)));
        assertEquals(1, connections.size());
    }

    @Test
    public void cloudflareRecoversRawNewlineInsideJsonString() throws Exception {
        String content = "{\"lines\":[\"Bonjour\nle monde\",\"Au revoir\"]}";
        assertEquals(Arrays.asList("Bonjour le monde", "Au revoir", ""),
                translateCloudflareWithSource(content, sourceWithLines(3)));
    }

    @Test
    public void cloudflareSalvageAcceptsLegacyTranslationsKeyWithRawNewline() throws Exception {
        String content = "{\"translations\":[\"Bonjour\nmonde\",\"Au revoir\"]}";
        assertEquals(Arrays.asList("Bonjour monde", "Au revoir"),
                translateCloudflareWithSource(content, sourceWithLines(2)));
    }

    @Test
    public void cloudflareRecoversTrailingCommaInLinesArray() throws Exception {
        assertEquals(Arrays.asList("Bonjour", "Monde"),
                translateCloudflareWithSource("{\"lines\":[\"Bonjour\",\"Monde\",]}",
                        sourceWithLines(2)));
    }

    @Test
    public void cloudflareRecoversUnescapedQuoteInsideString() throws Exception {
        String content = "{\"lines\":[\"It's a \"quote\" line\",\"Deuxième\"]}";
        assertEquals(Arrays.asList("It's a \"quote\" line", "Deuxième"),
                translateCloudflareWithSource(content, sourceWithLines(2)));
    }

    @Test
    public void cloudflareRecoversFencedJsonSurroundedByProse() throws Exception {
        String content = "Sure, here it is:\n```json\n{\"lines\":[\"Bonjour\",\"Monde\"]}\n```\n"
                + "Let me know if you need anything else!";
        assertEquals(Arrays.asList("Bonjour", "Monde"),
                translateCloudflareWithSource(content, sourceWithLines(2)));
    }

    @Test
    public void cloudflareAcceptsBareArrayResponse() throws Exception {
        assertEquals(Arrays.asList("Bonjour", "Monde"),
                translateCloudflareWithSource("[\"Bonjour\",\"Monde\"]", sourceWithLines(2)));
    }

    @Test
    public void cloudflareRecoversTruncatedResponseEnvelope() throws Exception {
        String valid = cloudflareResponse("{\"lines\":[\"Bonjour\",\"Monde\",\"Au revoir\"]}");
        int cut = valid.indexOf("Au revoir") + "Au revoir".length();
        String truncated = valid.substring(0, cut); // cuts inside the envelope's string
        responses.add(new Response(200, truncated));
        assertEquals(Arrays.asList("Bonjour", "Monde", "Au revoir", ""),
                invokeCloudflareRequest("cf-token", "acct123", sourceWithLines(4)));
    }

    @Test
    public void cloudflareUnrecoverableTextFallsBackToPlainLines() throws Exception {
        String content = "Bonjour le monde\n\nAu revoir\nThis is not JSON at all";
        assertEquals(Arrays.asList("Bonjour le monde", "Au revoir"),
                translateCloudflareWithSource(content, sourceWithLines(2)));
    }

    @Test
    public void cloudflareReportsFailureEnvelopeWithUsefulMessage() {
        responses.add(new Response(403, "{\"success\":false,\"errors\":[{\"code\":10000,"
                + "\"message\":\"Invalid API token\"}],\"messages\":[]}"));
        Exception error = assertThrows(Exception.class,
                () -> translateCloudflare("bad-token", "acct123"));
        assertTrue(error.getMessage().contains("Cloudflare Workers AI"));
        assertTrue(error.getMessage().contains("Invalid API token"));
        assertEquals(1, connections.size());
        assertTrue(connections.get(0).disconnected);
    }

    @Test
    public void cloudflareReportsSuccessFalseEvenOnOkStatus() {
        responses.add(new Response(200, "{\"success\":false,\"errors\":[{\"code\":10001,"
                + "\"message\":\"Model unavailable\"}],\"messages\":[]}"));
        Exception error = assertThrows(Exception.class,
                () -> translateCloudflare("cf-token", "acct123"));
        assertTrue(error.getMessage().contains("Model unavailable"));
        assertEquals(1, connections.size());
    }

    @Test
    public void cloudflareCatalogErrorIsNotSwallowedIntoAnEmptyModelList() {
        responses.add(new Response(401, "{\"success\":false,\"errors\":[{\"code\":10000,"
                + "\"message\":\"denied\"}]}"));
        Exception error = assertThrows(Exception.class, () -> {
            try {
                fetchWithCredentials.invoke(null, TranslationProvider.CLOUDFLARE,
                        "acct123", "bad-token");
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
                throw e;
            }
        });
        assertTrue(error.getMessage().contains("Cloudflare"));
        assertFalse(TranslationProvider.CLOUDFLARE.fallbackModels().isEmpty());
    }

    private static List<?> translateCloudflare(String key, String accountId) throws Exception {
        return invokeCloudflareRequest(key, accountId,
                (List<?>) parseLyrics.invoke(null, "[00:01.00]Hello"));
    }

    private static List<?> translateCloudflareWithSource(String content, List<?> source)
            throws Exception {
        responses.add(new Response(200, cloudflareResponse(content)));
        return invokeCloudflareRequest("cf-token", "acct123", source);
    }

    private static List<?> invokeCloudflareRequest(String key, String accountId, List<?> source)
            throws Exception {
        try {
            return (List<?>) requestWithAccount.invoke(null, TranslationProvider.CLOUDFLARE,
                    key, accountId, TranslationProvider.CLOUDFLARE.defaultModel,
                    null, "French", "", source);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    private static List<?> sourceWithLines(int count) throws Exception {
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) plain.append('\n');
            plain.append("Source").append(i);
        }
        return (List<?>) parsePlainLyrics.invoke(null, plain.toString());
    }

    private static String cloudflareSuccess(String... lines) throws Exception {
        return cloudflareResponse(new JSONObject()
                .put("lines", new JSONArray(Arrays.asList(lines))).toString());
    }

    private static String cloudflareResponse(String content) throws Exception {
        return new JSONObject().put("success", true)
                .put("result", new JSONObject().put("response", content))
                .put("errors", new JSONArray()).put("messages", new JSONArray()).toString();
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
        return completionWithContent(content);
    }

    private static String completionWithContent(String content) throws Exception {
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
