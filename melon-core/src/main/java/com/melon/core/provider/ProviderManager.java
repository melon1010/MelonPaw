package com.melon.core.provider;

import com.melon.core.config.MelonConfig;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    private Path activeModelFile;
    private Map<String, Object> providerConfigCache = Map.of();
    private long providerConfigCacheMtime = Long.MIN_VALUE;
    private Map<String, String> activeModel = Map.of();

    /** Environment variable names for API keys by provider */
    private static final Map<String, String> API_KEY_ENV_VARS = Map.ofEntries(
        Map.entry("dashscope", "DASHSCOPE_API_KEY"),
        Map.entry("openai", "OPENAI_API_KEY"),
        Map.entry("anthropic", "ANTHROPIC_API_KEY"),
        Map.entry("gemini", "GEMINI_API_KEY"),
        Map.entry("ollama", "OLLAMA_API_KEY"),
        Map.entry("deepseek", "DEEPSEEK_API_KEY"),
        Map.entry("openrouter", "OPENROUTER_API_KEY")
    );

    /** Default models per provider */
    private static final Map<String, List<String>> DEFAULT_MODELS = Map.ofEntries(
        Map.entry("dashscope", List.of("qwen-plus", "qwen-max", "qwen-turbo", "qwen-long", "qwen-vl-plus", "qwen-vl-max")),
        Map.entry("openai", List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o1", "o1-mini", "o3-mini")),
        Map.entry("anthropic", List.of("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229")),
        Map.entry("gemini", List.of("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash")),
        Map.entry("ollama", List.of("llama3.1", "qwen2.5", "deepseek-r1", "mistral", "phi-3")),
        Map.entry("deepseek", List.of("deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash", "deepseek-v4-pro")),
        Map.entry("openrouter", List.of("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "google/gemini-flash-1.5"))
    );

    public void init(MelonConfig config) {
        log.info("Initializing ProviderManager...");
        Path homeDir = resolveHomeDir(config);
        this.providerConfigFile = homeDir.resolve("providers.json");
        this.activeModelFile = homeDir.resolve("active_model.json");

        // Register default providers
        for (Map.Entry<String, List<String>> entry : DEFAULT_MODELS.entrySet()) {
            String providerId = entry.getKey();
            List<String> models = entry.getValue();
            providers.put(providerId, new ArrayList<>(models));
            log.info("Registered provider: {} with {} models", providerId, models.size());
        }

        registerConfiguredModelFactories();
        loadActiveModel();

        log.info("ProviderManager initialized with {} providers (ModelRegistry auto-configures from env vars)",
                providers.size());
    }

    /**
     * Lists all registered provider IDs.
     *
     * @return list of provider IDs
     */
    public List<String> listProviders() {
        LinkedHashSet<String> result = new LinkedHashSet<>(providers.keySet());
        for (Map.Entry<String, Object> entry : providerConfigs().entrySet()) {
            if ("active_llm".equals(entry.getKey())) {
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> map && isCustomProviderConfig(map)) {
                result.add(entry.getKey());
            }
        }
        return new ArrayList<>(result);
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
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> models = providers.get(providerId);
        if (models != null) result.addAll(models);
        result.addAll(configuredModels(providerId));
        return new ArrayList<>(result);
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
        if (!providers.containsKey(providerId) && !isCustomProvider(providerId)) {
            log.warn("Provider not registered: {}", providerId);
            return false;
        }

        // Ollama runs locally, no API key needed
        if ("ollama".equals(providerId)) {
            log.info("Provider {} (ollama) does not require API key", providerId);
            return true;
        }

        if (isCustomProvider(providerId)) {
            return !configuredValue(providerId, "base_url").isBlank();
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
        if (isCustomProvider(providerId)) {
            return !configuredValue(providerId, "base_url").isBlank();
        }
        String envVar = API_KEY_ENV_VARS.get(providerId);
        String apiKey = configuredApiKey(providerId);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = envVar != null ? configuredEnvValue(envVar) : "";
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

    public Model createModel(String modelId) {
        int idx = modelId == null ? -1 : modelId.indexOf(':');
        if (idx <= 0 || idx >= modelId.length() - 1) {
            throw new IllegalArgumentException("Model id must be provider:model, got: " + modelId);
        }
        return createModel(modelId.substring(0, idx), modelId.substring(idx + 1));
    }

    public Model createModel(String providerId, String modelName) {
        String provider = providerId == null ? "" : providerId.trim();
        String model = modelName == null ? "" : modelName.trim();
        if (provider.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("Provider and model are required");
        }
        return switch (provider) {
            case "dashscope" -> DashScopeChatModel.builder()
                    .apiKey(requireApiKey(provider, "DASHSCOPE_API_KEY", resolveModel(provider, model)))
                    .baseUrl(blankToNull(configuredValue(provider, "base_url")))
                    .modelName(model)
                    .stream(true)
                    .defaultOptions(generateOptions(provider, model, true, Duration.ofMinutes(5)))
                    .build();
            case "openai" -> OpenAIChatModel.builder()
                    .apiKey(requireApiKey(provider, "OPENAI_API_KEY", resolveModel(provider, model)))
                    .baseUrl(valueOr(configuredValue(provider, "base_url"), "https://api.openai.com/v1"))
                    .modelName(model)
                    .stream(true)
                    .generateOptions(generateOptions(provider, model, true, Duration.ofMinutes(5)))
                    .build();
            case "deepseek" -> OpenAIChatModel.builder()
                    .apiKey(requireApiKey(provider, "DEEPSEEK_API_KEY", resolveModel(provider, model)))
                    .baseUrl(valueOr(configuredValue(provider, "base_url"), "https://api.deepseek.com/v1"))
                    .modelName(model)
                    .formatter(new DeepSeekFormatter())
                    .stream(true)
                    .generateOptions(generateOptions(provider, model, true, Duration.ofMinutes(5)))
                    .build();
            case "openrouter" -> OpenAIChatModel.builder()
                    .apiKey(requireApiKey(provider, "OPENROUTER_API_KEY", resolveModel(provider, model)))
                    .baseUrl(valueOr(configuredValue(provider, "base_url"), "https://openrouter.ai/api/v1"))
                    .modelName(model)
                    .stream(true)
                    .generateOptions(generateOptions(provider, model, true, Duration.ofMinutes(5)))
                    .build();
            case "anthropic" -> AnthropicChatModel.builder()
                    .apiKey(requireApiKey(provider, "ANTHROPIC_API_KEY", resolveModel(provider, model)))
                    .baseUrl(blankToNull(configuredValue(provider, "base_url")))
                    .modelName(model)
                    .stream(true)
                    .defaultOptions(generateOptions(provider, model, true, Duration.ofMinutes(5)))
                    .build();
            case "gemini" -> GeminiChatModel.builder()
                    .apiKey(requireApiKey(provider, "GEMINI_API_KEY", resolveModel(provider, model)))
                    .baseUrl(blankToNull(configuredValue(provider, "base_url")))
                    .modelName(model)
                    .streamEnabled(true)
                    .defaultOptions(generateOptions(provider, model, true, Duration.ofMinutes(5)))
                    .build();
            case "ollama" -> OllamaChatModel.builder()
                    .baseUrl(valueOr(configuredValue(provider, "base_url"), "http://127.0.0.1:11434"))
                    .modelName(model)
                    .build();
            default -> createCustomModel(provider, model);
        };
    }

    public ModelTestResult testModelConnection(String providerId, String modelName) {
        String modelId = resolveModel(providerId, modelName);
        try {
            Model model = createModel(providerId, modelName);
            GenerateOptions options = GenerateOptions.builder()
                    .modelName(modelName)
                    .stream(false)
                    .maxTokens(20)
                    .executionConfig(ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(30))
                            .maxAttempts(1)
                            .build())
                    .build();
            model.stream(List.of(new UserMessage("ping")), List.of(), options).blockLast(Duration.ofSeconds(35));
            return new ModelTestResult(true, "Model connection successful");
        } catch (Exception e) {
            String message = userFacingError(e);
            log.info("Model connection test failed: {} - {}", modelId, message);
            log.debug("Model connection test failure detail: {}", modelId, e);
            return new ModelTestResult(false, message);
        }
    }

    public Map<String, String> getActiveModel() {
        return activeModel;
    }

    public synchronized void setActiveModel(String providerId, String modelName) {
        String provider = providerId == null ? "" : providerId.trim();
        String model = modelName == null ? "" : modelName.trim();
        if (!listProviders().contains(provider)) {
            throw new IllegalArgumentException("Provider '" + provider + "' not found.");
        }
        if (!listModels(provider).contains(model)) {
            throw new IllegalArgumentException("Model '" + model + "' not found in provider '" + provider + "'.");
        }
        activeModel = Map.of("provider_id", provider, "model", model);
        JsonUtils.save(activeModelFile, activeModel);
    }

    public record ModelTestResult(boolean success, String message) {
    }

    private void registerConfiguredModelFactories() {
        ModelRegistry.registerFactory("dashscope:(.+)", modelId -> {
            String modelName = modelId.substring("dashscope:".length());
            return createModel("dashscope", modelName);
        });
        ModelRegistry.registerFactory("qwen.+", modelId -> createModel("dashscope", modelId));
        ModelRegistry.registerFactory("openai:(.+)", modelId -> createModel("openai", modelId.substring("openai:".length())));
        ModelRegistry.registerFactory("deepseek:(.+)", modelId -> createModel("deepseek", modelId.substring("deepseek:".length())));
        ModelRegistry.registerFactory("openrouter:(.+)", modelId -> createModel("openrouter", modelId.substring("openrouter:".length())));
        ModelRegistry.registerFactory("anthropic:(.+)", modelId -> createModel("anthropic", modelId.substring("anthropic:".length())));
        ModelRegistry.registerFactory("gemini:(.+)", modelId -> createModel("gemini", modelId.substring("gemini:".length())));
        ModelRegistry.registerFactory("([A-Za-z0-9._-]+):(.+)", this::createModel);
    }

    private Model createCustomModel(String providerId, String modelName) {
        if (!isCustomProvider(providerId)) {
            throw new IllegalArgumentException("Provider is not configured: " + providerId);
        }
        String baseUrl = configuredValue(providerId, "base_url");
        if (baseUrl.isBlank()) {
            baseUrl = configuredValue(providerId, "default_base_url");
        }
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("Provider base_url is not configured for model: " + resolveModel(providerId, modelName));
        }
        String apiKey = configuredApiKey(providerId);
        String chatModel = valueOr(configuredValue(providerId, "chat_model"), "OpenAIChatModel");
        GenerateOptions options = generateOptions(providerId, modelName, true, Duration.ofMinutes(5));
        return switch (chatModel) {
            case "AnthropicChatModel" -> AnthropicChatModel.builder()
                    .apiKey(apiKey == null || apiKey.isBlank() ? "EMPTY" : apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .stream(true)
                    .defaultOptions(options)
                    .build();
            case "GeminiChatModel" -> GeminiChatModel.builder()
                    .apiKey(apiKey == null || apiKey.isBlank() ? "EMPTY" : apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .streamEnabled(true)
                    .defaultOptions(options)
                    .build();
            case "DashScopeChatModel" -> DashScopeChatModel.builder()
                    .apiKey(apiKey == null || apiKey.isBlank() ? "EMPTY" : apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .stream(true)
                    .defaultOptions(options)
                    .build();
            default -> OpenAIChatModel.builder()
                    .apiKey(apiKey == null || apiKey.isBlank() ? "EMPTY" : apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .stream(true)
                    .generateOptions(options)
                    .build();
        };
    }

    private GenerateOptions generateOptions(String providerId, String modelName, boolean stream, Duration timeout) {
        GenerateOptions.Builder builder = GenerateOptions.builder()
                .modelName(modelName)
                .stream(stream)
                .executionConfig(ExecutionConfig.builder()
                        .timeout(timeout)
                        .maxAttempts(1)
                        .build());
        Map<String, Object> config = providerConfig(providerId);
        applyGenerateKwargs(builder, effectiveGenerateKwargs(providerId, modelName, config));
        applyStringHeaders(builder, config.get("custom_headers"));
        return builder.build();
    }

    private Map<String, Object> effectiveGenerateKwargs(String providerId, String modelName, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        mergeMap(result, config.get("generate_kwargs"));
        Map<String, Object> modelConfig = modelConfig(providerId, modelName);
        mergeMap(result, modelConfig.get("generate_kwargs"));
        if (!result.containsKey("max_tokens")) {
            Integer maxTokens = asPositiveInteger(modelConfig.get("max_tokens"));
            if (maxTokens != null) {
                result.put("max_tokens", maxTokens);
            }
        }
        result.entrySet().removeIf(entry -> isEmptyGenerateValue(entry.getKey(), entry.getValue()));
        return result;
    }

    private void mergeMap(Map<String, Object> target, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((key, value) -> {
            if (key != null && !String.valueOf(key).isBlank()) {
                target.put(String.valueOf(key), value);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void applyGenerateKwargs(GenerateOptions.Builder builder, Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case "temperature" -> builder.temperature(asDouble(value));
                case "top_p", "topP" -> builder.topP(asDouble(value));
                case "max_tokens", "maxTokens" -> builder.maxTokens(asInteger(value));
                case "max_completion_tokens", "maxCompletionTokens" -> builder.maxCompletionTokens(asInteger(value));
                case "extra_body" -> mergeMap(extra, value);
                case "extra_headers" -> applyStringHeaders(builder, value);
                case "extra_query" -> applyStringQueryParams(builder, value);
                default -> extra.put(key, value);
            }
        }
        if (!extra.isEmpty()) {
            builder.additionalBodyParams(extra);
        }
    }

    private void applyStringHeaders(GenerateOptions.Builder builder, Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null && !String.valueOf(key).isBlank()) {
                headers.put(String.valueOf(key), String.valueOf(value));
            }
        });
        if (!headers.isEmpty()) {
            builder.additionalHeaders(headers);
        }
    }

    private void applyStringQueryParams(GenerateOptions.Builder builder, Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null && !String.valueOf(key).isBlank()) {
                params.put(String.valueOf(key), String.valueOf(value));
            }
        });
        if (!params.isEmpty()) {
            builder.additionalQueryParams(params);
        }
    }

    private String requireApiKey(String providerId, String envKey, String modelId) {
        String apiKey = configuredApiKey(providerId);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = configuredEnvValue(envKey);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Provider API key is not configured for model: " + modelId);
        }
        return apiKey;
    }

    private String configuredApiKey(String providerId) {
        return configuredValue(providerId, "api_key");
    }

    private String configuredEnvValue(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return value;
    }

    private boolean isCustomProvider(String providerId) {
        return isCustomProviderConfig(providerConfig(providerId));
    }

    private boolean isCustomProviderConfig(Map<?, ?> map) {
        Object custom = map.get("is_custom");
        return Boolean.TRUE.equals(custom) || "true".equalsIgnoreCase(String.valueOf(custom));
    }

    private List<String> configuredModels(String providerId) {
        Object raw = providerConfig(providerId).get("models");
        if (!(raw instanceof List<?> list)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object id = map.get("id");
                if (id != null && !String.valueOf(id).isBlank()) result.add(String.valueOf(id));
            } else if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private Map<String, Object> modelConfig(String providerId, String modelName) {
        Object raw = providerConfig(providerId).get("models");
        if (!(raw instanceof List<?> list)) {
            return Map.of();
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && modelName.equals(String.valueOf(map.get("id")))) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        }
        return Map.of();
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

    private void loadActiveModel() {
        activeModel = readActiveModelFile();
        if (!activeModel.isEmpty()) {
            return;
        }
        Object legacy = providerConfigs().get("active_llm");
        if (legacy instanceof Map<?, ?> map) {
            String provider = stringValue(map.get("provider_id"));
            String model = stringValue(map.get("model"));
            if (!provider.isBlank() && !model.isBlank()) {
                activeModel = Map.of("provider_id", provider, "model", model);
                JsonUtils.save(activeModelFile, activeModel);
            }
        }
    }

    private Map<String, String> readActiveModelFile() {
        if (activeModelFile == null || !Files.exists(activeModelFile)) {
            return Map.of();
        }
        Map<String, Object> raw = JsonUtils.loadAsMap(activeModelFile);
        String provider = stringValue(raw.get("provider_id"));
        String model = stringValue(raw.get("model"));
        return !provider.isBlank() && !model.isBlank()
                ? Map.of("provider_id", provider, "model", model)
                : Map.of();
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null || String.valueOf(value).isBlank() ? null : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null || String.valueOf(value).isBlank() ? null : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer asPositiveInteger(Object value) {
        Integer parsed = asInteger(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private boolean isEmptyGenerateValue(String key, Object value) {
        if (value == null) return true;
        if (("max_tokens".equals(key) || "maxTokens".equals(key)
                || "max_completion_tokens".equals(key) || "maxCompletionTokens".equals(key))
                && value instanceof Number number) {
            return number.intValue() <= 0;
        }
        return false;
    }

    private String userFacingError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getMessage();
        }
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 600 ? message.substring(0, 600) + "..." : message;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
