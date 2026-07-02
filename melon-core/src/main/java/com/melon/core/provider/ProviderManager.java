package com.melon.core.provider;

import com.melon.core.config.MelonConfig;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Provider 管理器. 对应 Python model_factory.py 的 ProviderManager.
 * 配置 AgentScope ModelRegistry.
 */
public class ProviderManager {

    private static final Logger log = LoggerFactory.getLogger(ProviderManager.class);

    /** Registered providers: providerId -> list of supported model names */
    private final Map<String, List<String>> providers = new LinkedHashMap<>();
    private Path providerConfigFile;
    private Map<String, Object> providerConfigCache = Map.of();
    private long providerConfigCacheMtime = Long.MIN_VALUE;

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
        Map.entry("deepseek", List.of("deepseek-chat", "deepseek-reasoner"))
    );

    public void init(MelonConfig config) {
        log.info("Initializing ProviderManager...");
        this.providerConfigFile = resolveHomeDir(config).resolve("providers.json");

        // Register default providers
        for (Map.Entry<String, List<String>> entry : DEFAULT_MODELS.entrySet()) {
            String providerId = entry.getKey();
            List<String> models = entry.getValue();
            providers.put(providerId, new ArrayList<>(models));
            log.info("Registered provider: {} with {} models", providerId, models.size());
        }

        registerConfiguredModelFactories();

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

        boolean available = isConfigured(providerId);
        if (available) {
            log.info("Provider {} connection test: PASS ({} is configured)", providerId, envVar);
        } else {
            log.debug("Provider {} connection test: FAIL ({} is not configured)", providerId, envVar);
        }
        return available;
    }

    public boolean isConfigured(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return false;
        }
        if ("ollama".equals(providerId)) {
            return true;
        }
        String envVar = API_KEY_ENV_VARS.get(providerId);
        String apiKey = configuredApiKey(providerId);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = envVar != null ? System.getenv(envVar) : "";
        }
        return apiKey != null && !apiKey.isBlank();
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

    private void registerConfiguredModelFactories() {
        ModelRegistry.registerFactory("dashscope:(.+)", modelId -> {
            String modelName = modelId.substring("dashscope:".length());
            return DashScopeChatModel.builder()
                    .apiKey(requireApiKey("dashscope", "DASHSCOPE_API_KEY", modelId))
                    .modelName(modelName)
                    .stream(true)
                    .build();
        });
        ModelRegistry.registerFactory("qwen.+", modelId -> DashScopeChatModel.builder()
                .apiKey(requireApiKey("dashscope", "DASHSCOPE_API_KEY", modelId))
                .modelName(modelId)
                .stream(true)
                .build());
        ModelRegistry.registerFactory("openai:(.+)", modelId -> OpenAIChatModel.builder()
                .apiKey(requireApiKey("openai", "OPENAI_API_KEY", modelId))
                .baseUrl(configuredValue("openai", "base_url"))
                .modelName(modelId.substring("openai:".length()))
                .stream(true)
                .build());
        ModelRegistry.registerFactory("deepseek:(.+)", modelId -> OpenAIChatModel.builder()
                .apiKey(requireApiKey("deepseek", "DEEPSEEK_API_KEY", modelId))
                .baseUrl(valueOr(configuredValue("deepseek", "base_url"), "https://api.deepseek.com"))
                .modelName(modelId.substring("deepseek:".length()))
                .formatter(new DeepSeekFormatter())
                .stream(true)
                .build());
        ModelRegistry.registerFactory("anthropic:(.+)", modelId -> AnthropicChatModel.builder()
                .apiKey(requireApiKey("anthropic", "ANTHROPIC_API_KEY", modelId))
                .modelName(modelId.substring("anthropic:".length()))
                .stream(true)
                .build());
        ModelRegistry.registerFactory("gemini:(.+)", modelId -> GeminiChatModel.builder()
                .apiKey(requireApiKey("gemini", "GEMINI_API_KEY", modelId))
                .modelName(modelId.substring("gemini:".length()))
                .streamEnabled(true)
                .build());
    }

    private String requireApiKey(String providerId, String envKey, String modelId) {
        String apiKey = configuredApiKey(providerId);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv(envKey);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Provider API key is not configured for model: " + modelId);
        }
        return apiKey;
    }

    private String configuredApiKey(String providerId) {
        return configuredValue(providerId, "api_key");
    }

    private String configuredValue(String providerId, String key) {
        Object raw = providerConfig(providerId).get(key);
        return raw == null ? "" : String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> providerConfig(String providerId) {
        Object raw = providerConfigs().get(providerId);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    public synchronized void refreshProviderConfig() {
        providerConfigCache = Map.of();
        providerConfigCacheMtime = Long.MIN_VALUE;
    }

    private synchronized Map<String, Object> providerConfigs() {
        if (providerConfigFile == null) {
            return Map.of();
        }
        try {
            long mtime = Files.exists(providerConfigFile)
                    ? Files.getLastModifiedTime(providerConfigFile).toMillis()
                    : -1L;
            if (mtime != providerConfigCacheMtime) {
                providerConfigCache = new LinkedHashMap<>(JsonUtils.loadAsMap(providerConfigFile));
                providerConfigCacheMtime = mtime;
            }
            return providerConfigCache;
        } catch (Exception e) {
            log.warn("Failed to load provider config from {}", providerConfigFile, e);
            return providerConfigCache;
        }
    }

    private Path resolveHomeDir(MelonConfig config) {
        String homeDir = config != null && config.getHomeDir() != null && !config.getHomeDir().isBlank()
                ? config.getHomeDir()
                : "~/.melon";
        if (homeDir.startsWith("~")) {
            homeDir = System.getProperty("user.home") + homeDir.substring(1);
        }
        return Path.of(homeDir).toAbsolutePath().normalize();
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
