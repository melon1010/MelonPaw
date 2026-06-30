/**
 * @author melon
 */
package com.melon.core.provider;

import com.melon.core.config.MelonConfig;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Provider 管理器. 对应 Python model_factory.py 的 ProviderManager.
 * 配置 AgentScope ModelRegistry.
 */
public class ProviderManager {

    private static final Logger log = LoggerFactory.getLogger(ProviderManager.class);

    /** Registered providers: providerId -> list of supported model names */
    private final Map<String, List<String>> providers = new LinkedHashMap<>();

    /** Environment variable names for API keys by provider */
    private static final Map<String, String> API_KEY_ENV_VARS = Map.ofEntries(
        Map.entry("dashscope", "DASHSCOPE_API_KEY"),
        Map.entry("openai", "OPENAI_API_KEY"),
        Map.entry("anthropic", "ANTHROPIC_API_KEY"),
        Map.entry("gemini", "GEMINI_API_KEY"),
        Map.entry("ollama", "OLLAMA_API_KEY"),
        Map.entry("deepseek", "DEEPSEEK_API_KEY")
    );

    /** Default models per provider */
    private static final Map<String, List<String>> DEFAULT_MODELS = Map.ofEntries(
        Map.entry("dashscope", List.of("qwen-plus", "qwen-max", "qwen-turbo", "qwen-long", "qwen-vl-plus", "qwen-vl-max")),
        Map.entry("openai", List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o1", "o1-mini", "o3-mini")),
        Map.entry("anthropic", List.of("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229")),
        Map.entry("gemini", List.of("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash")),
        Map.entry("ollama", List.of("llama3.1", "qwen2.5", "deepseek-r1", "mistral", "phi-3")),
        Map.entry("deepseek", List.of("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner"))
    );

    public void init(MelonConfig config) {
        log.info("Initializing ProviderManager...");

        // Register default providers
        for (Map.Entry<String, List<String>> entry : DEFAULT_MODELS.entrySet()) {
            String providerId = entry.getKey();
            List<String> models = entry.getValue();
            providers.put(providerId, new ArrayList<>(models));
            log.info("Registered provider: {} with {} models", providerId, models.size());
        }

        // AgentScope ModelRegistry auto-resolves "provider:model" format
        // Auto-reads environment variables: DASHSCOPE_API_KEY / OPENAI_API_KEY etc.

        // Register DeepSeek model factory (OpenAI-compatible API)
        // AgentScope doesn't have a built-in deepseek provider, so we register one
        // using OpenAIChatModel with DeepSeek's base URL and DeepSeekFormatter
        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
        if (deepseekKey != null && !deepseekKey.isBlank()) {
            final String apiKey = deepseekKey;
            ModelRegistry.registerFactory("deepseek:(.+)", modelId -> {
                String modelName = modelId.split(":", 2)[1];
                return OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl("https://api.deepseek.com")
                    .modelName(modelName)
                    .formatter(new DeepSeekFormatter())
                    .stream(true)
                    .build();
            });
            log.info("Registered DeepSeek model factory with ModelRegistry (baseUrl=https://api.deepseek.com)");
        } else {
            log.warn("DEEPSEEK_API_KEY not set, DeepSeek models will not be available");
        }

        log.info("ProviderManager initialized with {} providers (ModelRegistry auto-configures from env vars)",
                providers.size());
    }

    /**
     * Lists all registered provider IDs.
     *
     * @return list of provider IDs
     */
    public List<String> listProviders() {
        return new ArrayList<>(providers.keySet());
    }

    /**
     * Lists models for a specific provider.
     *
     * @param providerId the provider ID
     * @return list of model names, or empty list if provider not found
     */
    public List<String> listModels(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Collections.emptyList();
        }
        List<String> models = providers.get(providerId);
        return models != null ? new ArrayList<>(models) : Collections.emptyList();
    }

    /**
     * Tests the connection to a provider by checking if its API key is available.
     *
     * @param providerId the provider ID
     * @return true if the provider is configured and API key is available
     */
    public boolean testConnection(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return false;
        }
        if (!providers.containsKey(providerId)) {
            log.warn("Provider not registered: {}", providerId);
            return false;
        }

        // Ollama runs locally, no API key needed
        if ("ollama".equals(providerId)) {
            log.info("Provider {} (ollama) does not require API key", providerId);
            return true;
        }

        String envVar = API_KEY_ENV_VARS.get(providerId);
        if (envVar == null) {
            log.warn("No API key env var mapping for provider: {}", providerId);
            return false;
        }

        String apiKey = System.getenv(envVar);
        boolean available = apiKey != null && !apiKey.isBlank();
        log.info("Provider {} connection test: {} (env var {} is {})",
                providerId, available ? "PASS" : "FAIL", envVar, available ? "set" : "not set");
        return available;
    }

    /**
     * Lists all models across all providers in "provider:model" format.
     *
     * @return list of fully-qualified model identifiers
     */
    public List<String> listAllModels() {
        List<String> allModels = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : providers.entrySet()) {
            String providerId = entry.getKey();
            for (String model : entry.getValue()) {
                allModels.add(providerId + ":" + model);
            }
        }
        return allModels;
    }

    public String resolveModel(String providerId, String model) {
        return providerId + ":" + model;
    }
}
