package app.anghami.extension.extension;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

enum TranslationProvider {
    OPENROUTER("openrouter", "OpenRouter",
            "https://openrouter.ai/api/v1/chat/completions",
            "https://openrouter.ai/api/v1/models",
            "inception/mercury-2.5-preview",
            new String[]{
                    "inception/mercury-2.5-preview", "meta/muse-spark-1.3",
                    "z-ai/glm-5.3-flash", "qwen/qwen3.8-flash",
                    "~deepseek/deepseek-v4-flash-latest", "~openai/gpt-mini-latest",
                    "openai/gpt-oss-120b", "~google/gemini-flash-latest",
            }),
    OPENCODE("opencode", "OpenCode",
            "https://opencode.ai/inference/openai/v1/chat/completions",
            "https://opencode.ai/inference/v1/models",
            "glm-5.3-flash",
            new String[]{
                    "glm-5.3-flash", "deepseek-v4.1-flash", "qwen3.8-flash",
                    "gpt-6-luna", "claude-haiku-5-5",
            }),
    XKIRO("xkiro", "xKiro",
            "https://api.xkiro.com/v1/chat/completions",
            "https://api.xkiro.com/v1/models",
            "mistralai/mistral-small-2603",
            new String[]{
                    "mistralai/mistral-small-2603", "cohere/command-a-translate",
                    "cohere/north-small-translate", "openai/gpt-5.6-luna",
            });

    final String id;
    final String displayName;
    final String completionsUrl;
    final String modelsUrl;
    final String defaultModel;
    private final String[] fallbackModels;

    TranslationProvider(String id, String displayName, String completionsUrl,
                        String modelsUrl, String defaultModel, String[] fallbackModels) {
        this.id = id;
        this.displayName = displayName;
        this.completionsUrl = completionsUrl;
        this.modelsUrl = modelsUrl;
        this.defaultModel = defaultModel;
        this.fallbackModels = fallbackModels;
    }

    static TranslationProvider fromId(String id) {
        for (TranslationProvider provider : values()) {
            if (provider.id.equals(id)) return provider;
        }
        return OPENROUTER;
    }

    String preferenceKey(String key) {
        // Keep existing OpenRouter credentials and model selections on upgrade.
        return this == OPENROUTER ? key : id + "_" + key;
    }

    String cacheModelKey(String model) {
        // Existing OpenRouter translations retain their cache keys.
        return this == OPENROUTER ? model : id + "\n" + model;
    }

    List<Model> parseModels(String body) throws Exception {
        JSONArray data = new JSONObject(body).getJSONArray("data");
        List<Model> models = new ArrayList<Model>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject model = data.optJSONObject(i);
            if (model == null) continue;
            String id = model.optString("id", "").trim();
            if (id.isEmpty() || "null".equals(id)) continue;
            if (this == XKIRO) {
                if (!"chat".equals(model.optString("modality", ""))) continue;
            } else if (!"chat".equals(model.optString("type", "chat"))
                    || !supportsText(model)) continue;

            boolean free;
            if (this == OPENROUTER) {
                JSONObject pricing = model.optJSONObject("pricing");
                free = id.endsWith(":free") || (pricing != null
                        && isZeroPrice(pricing.optString("prompt", ""))
                        && isZeroPrice(pricing.optString("completion", "")));
                if (!free) continue;
            } else if (this == OPENCODE) {
                JSONObject cost = model.optJSONObject("cost");
                JSONObject text = cost == null ? null : cost.optJSONObject("text");
                JSONObject input = text == null ? null : text.optJSONObject("input");
                JSONObject output = text == null ? null : text.optJSONObject("output");
                free = id.endsWith("-free") || "big-pickle".equals(id)
                        || (input != null && output != null
                        && isZeroPrice(input.optString("price", ""))
                        && isZeroPrice(output.optString("price", "")));
                // Console's free chat routes return 403 outside the OpenCode app.
                if (free) continue;
            } else {
                JSONObject pricing = model.optJSONObject("pricing");
                String accessTier = model.optString("access_tier", "").trim();
                free = !accessTier.isEmpty()
                        ? "free".equalsIgnoreCase(accessTier)
                        : (pricing != null
                        && isZeroPrice(pricing.optString("input", ""))
                        && isZeroPrice(pricing.optString("output", "")));
            }
            if (!seen.add(id)) continue;
            String name = model.optString(this == XKIRO ? "display_name" : "name", id).trim();
            models.add(new Model(id, name.isEmpty() ? id : name, free));
        }
        Collections.sort(models, new Comparator<Model>() {
            @Override
            public int compare(Model left, Model right) {
                int byName = left.name.compareToIgnoreCase(right.name);
                return byName != 0 ? byName : left.id.compareToIgnoreCase(right.id);
            }
        });
        return models;
    }

    List<Model> fallbackModels() {
        List<Model> models = new ArrayList<Model>(fallbackModels.length);
        for (String model : fallbackModels) {
            boolean free = this == XKIRO
                    && (model.equals("mistralai/mistral-small-2603")
                    || model.equals("cohere/command-a-translate")
                    || model.equals("cohere/north-small-translate"));
            models.add(new Model(model, model, free));
        }
        return models;
    }

    private static boolean supportsText(JSONObject model) {
        JSONObject modalities = model.optJSONObject("modalities");
        if (modalities == null) modalities = model.optJSONObject("architecture");
        if (modalities == null) return true;
        JSONArray input = modalities.optJSONArray("input");
        JSONArray output = modalities.optJSONArray("output");
        if (input == null) input = modalities.optJSONArray("input_modalities");
        if (output == null) output = modalities.optJSONArray("output_modalities");
        return containsText(input) && containsText(output);
    }

    private static boolean containsText(JSONArray modalities) {
        if (modalities == null) return true;
        for (int i = 0; i < modalities.length(); i++) {
            if ("text".equals(modalities.optString(i))) return true;
        }
        return false;
    }

    private static boolean isZeroPrice(String value) {
        try {
            return new BigDecimal(value.trim()).signum() == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static final class Model {
        final String id;
        final String name;
        final boolean free;

        Model(String id, String name, boolean free) {
            this.id = id;
            this.name = name;
            this.free = free;
        }

        String displayName() {
            String label = name.equals(id) ? id : name + " — " + id;
            return free ? label + " · FREE" : label;
        }
    }
}
