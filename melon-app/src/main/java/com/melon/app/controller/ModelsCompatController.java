package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.provider.ProviderManager;
import com.melon.core.util.JsonUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.*;

/**
 * QwenPaw console-compatible model endpoints.
 */
@RestController
@RequestMapping("/api/models")
public class ModelsCompatController {

    private final ProviderManager providerManager;
    private final ConfigManager configManager;
    private final MultiAgentManager multiAgentManager;

    public ModelsCompatController(ProviderManager providerManager, ConfigManager configManager, MultiAgentManager multiAgentManager) {
        this.providerManager = providerManager;
        this.configManager = configManager;
        this.multiAgentManager = multiAgentManager;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> listModels() {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> providers = new ArrayList<>();
            for (String providerId : providerManager.listProviders()) {
                List<String> models = providerManager.listModels(providerId);
                providers.add(providerPayload(providerId, models));
            }
            return ResponseEntity.ok(providers);
        });
    }

    @GetMapping("/{providerId}/config")
    public Mono<ResponseEntity<?>> getProviderConfig(@PathVariable String providerId) {
        return Mono.just(ResponseEntity.ok(providerPayload(providerId, providerManager.listModels(providerId))));
    }

    @PutMapping("/{providerId}/config")
    public Mono<ResponseEntity<?>> updateProviderConfig(@PathVariable String providerId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = providerConfig(providerId);
        if (body != null) {
            config.putAll(body);
        }
        saveProviderConfig(providerId, config);
        Map<String, Object> result = new LinkedHashMap<>(providerPayload(providerId, providerManager.listModels(providerId)));
        result.putAll(config);
        result.put("configured", isProviderConfigured(providerId, config));
        result.put("api_key_configured", hasText(stringValue(config.get("api_key"))));
        return Mono.just(ResponseEntity.ok(result));
    }

    @PostMapping("/{providerId}/test")
    public Mono<ResponseEntity<?>> testProvider(@PathVariable String providerId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = providerConfig(providerId);
        if (body != null) {
            config.putAll(body);
        }
        boolean ok = isProviderConfigured(providerId, config);
        return Mono.just(ResponseEntity.ok(Map.of(
                "success", ok,
                "available", ok,
                "provider_id", providerId,
                "message", ok ? "Provider is configured" : "Provider API key is not configured",
                "detail", ok ? "" : "Provider API key is not configured"
        )));
    }

    @PostMapping("/{providerId}/models/test")
    public Mono<ResponseEntity<?>> testModel(@PathVariable String providerId, @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "success", false,
                "available", false,
                "provider_id", providerId,
                "detail", "Model test is not implemented"
        )));
    }

    @PostMapping("/{providerId}/discover")
    public Mono<ResponseEntity<?>> discoverModels(@PathVariable String providerId,
                                                  @RequestParam(defaultValue = "true") boolean save,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("models", List.of(), "provider_id", providerId, "saved", false)));
    }

    @PostMapping("/{providerId}/models")
    public Mono<ResponseEntity<?>> addModel(@PathVariable String providerId, @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(providerPayload(providerId, providerManager.listModels(providerId))));
    }

    @DeleteMapping("/{providerId}/models/{modelId}")
    public Mono<ResponseEntity<?>> removeModel(@PathVariable String providerId, @PathVariable String modelId) {
        return Mono.just(ResponseEntity.ok(providerPayload(providerId, providerManager.listModels(providerId))));
    }

    @PutMapping("/{providerId}/models/{modelId}/config")
    public Mono<ResponseEntity<?>> configureModel(@PathVariable String providerId,
                                                  @PathVariable String modelId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>(providerPayload(providerId, providerManager.listModels(providerId)));
        result.put("model_id", modelId);
        result.put("configured", true);
        return Mono.just(ResponseEntity.ok(result));
    }

    @PostMapping("/{providerId}/models/{modelId}/probe-multimodal")
    public Mono<ResponseEntity<?>> probeMultimodal(@PathVariable String providerId, @PathVariable String modelId) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "provider_id", providerId,
                "model_id", modelId,
                "multimodal", false,
                "available", false
        )));
    }

    @GetMapping("/active")
    public Mono<ResponseEntity<?>> getActiveModels(
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String headerAgentId,
            @RequestParam(value = "agent_id", required = false) String queryAgentId) {
        String agentId = queryAgentId == null || queryAgentId.isBlank() ? headerAgentId : queryAgentId;
        return Mono.fromCallable(() -> ResponseEntity.ok(activeModels(agentId)));
    }

    @PutMapping("/active")
    public Mono<ResponseEntity<?>> updateActiveModels(
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String targetAgentId = stringValue(body.getOrDefault("agent_id", agentId));
            Object providerId = body.get("provider_id");
            Object model = body.getOrDefault("active_model", body.getOrDefault("default", body.get("model")));
            if (model != null && configManager.getConfig().getAgents().containsKey(targetAgentId)) {
                String active = providerId != null && !String.valueOf(providerId).isBlank()
                        ? providerId + ":" + model
                        : String.valueOf(model);
                configManager.getConfig().getAgents().get(targetAgentId).setActiveModel(active);
                configManager.save();
                multiAgentManager.reload(targetAgentId);
            }
            return ResponseEntity.ok(activeModels(targetAgentId));
        });
    }

    @PostMapping("/custom-providers")
    public Mono<ResponseEntity<?>> createCustomProvider(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body != null ? body : Map.of());
        response.putIfAbsent("provider_id", "custom");
        response.put("enabled", true);
        return Mono.just(ResponseEntity.ok(response));
    }

    @GetMapping("/custom-providers")
    public Mono<ResponseEntity<?>> listCustomProviders() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @DeleteMapping("/custom-providers/{providerId}")
    public Mono<ResponseEntity<?>> deleteCustomProvider(@PathVariable String providerId) {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/openrouter/series")
    public Mono<ResponseEntity<?>> openRouterSeries() {
        return Mono.just(ResponseEntity.ok(Map.of("series", List.of())));
    }

    @PostMapping("/openrouter/discover-extended")
    public Mono<ResponseEntity<?>> discoverOpenRouterExtended() {
        return Mono.just(ResponseEntity.ok(Map.of("models", List.of())));
    }

    @PostMapping("/openrouter/models/filter")
    public Mono<ResponseEntity<?>> filterOpenRouterModels() {
        return Mono.just(ResponseEntity.ok(Map.of("models", List.of())));
    }

    private Map<String, Object> providerPayload(String providerId, List<String> models) {
        Map<String, Object> provider = new LinkedHashMap<>();
        Map<String, Object> config = providerConfig(providerId);
        boolean configured = isProviderConfigured(providerId, config);
        provider.put("provider_id", providerId);
        provider.put("id", providerId);
        provider.put("name", displayName(providerId));
        provider.put("display_name", displayName(providerId));
        provider.put("provider_group", "default");
        provider.put("enabled", true);
        provider.put("configured", configured);
        provider.put("models", models.stream().map(this::modelPayload).toList());
        provider.put("extra_models", List.of());
        provider.put("available_models", models);
        provider.put("chat_model", stringValue(config.getOrDefault("chat_model", models.isEmpty() ? "" : models.get(0))));
        provider.put("api_key_prefix", "");
        provider.put("is_custom", false);
        provider.put("is_local", "ollama".equals(providerId));
        provider.put("support_model_discovery", false);
        provider.put("support_connection_check", true);
        provider.put("freeze_url", false);
        provider.put("require_api_key", !"ollama".equals(providerId));
        provider.put("base_url", stringValue(config.get("base_url")));
        provider.put("api_key", maskKey(stringValue(config.get("api_key"))));
        provider.put("api_key_configured", configured);
        provider.put("has_api_key", configured);
        provider.put("generate_kwargs", config.getOrDefault("generate_kwargs", Map.of()));
        provider.put("custom_headers", config.getOrDefault("custom_headers", Map.of()));
        provider.put("auth_mode", config.getOrDefault("auth_mode", "api_key"));
        return provider;
    }

    private Map<String, Object> modelPayload(String modelId) {
        return Map.of(
                "id", modelId,
                "name", modelId,
                "supports_multimodal", false,
                "supports_image", false,
                "supports_video", false,
                "is_free", false,
                "max_tokens", 0,
                "max_input_length", 0,
                "generate_kwargs", Map.of()
        );
    }

    private Map<String, Object> activeModels(String agentId) {
        String active = "deepseek:deepseek-v4-flash";
        if (configManager.getConfig().getAgents().containsKey(agentId)) {
            active = configManager.getConfig().getAgents().get(agentId).getActiveModel();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active_model", active);
        String[] parts = splitModel(active);
        result.put("active_llm", Map.of("provider_id", parts[0], "model", parts[1]));
        result.put("default", active);
        result.put("summary", active);
        result.put("coding", active);
        result.put("memory", active);
        return result;
    }

    private String[] splitModel(String active) {
        if (active == null || active.isBlank()) {
            return new String[]{"", ""};
        }
        int idx = active.indexOf(':');
        if (idx > 0 && idx < active.length() - 1) {
            return new String[]{active.substring(0, idx), active.substring(idx + 1)};
        }
        return new String[]{"dashscope", active};
    }

    private String displayName(String providerId) {
        if (providerId == null || providerId.isBlank()) return "Provider";
        return providerId.substring(0, 1).toUpperCase(Locale.ROOT) + providerId.substring(1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> providerConfig(String providerId) {
        Object raw = providerConfigs().get(providerId);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private void saveProviderConfig(String providerId, Map<String, Object> config) {
        Map<String, Object> configs = providerConfigs();
        configs.put(providerId, config);
        JsonUtils.save(providerConfigFile(), configs);
    }

    private Map<String, Object> providerConfigs() {
        return JsonUtils.loadAsMap(providerConfigFile());
    }

    private Path providerConfigFile() {
        return configManager.resolveHomeDir().resolve("providers.json");
    }

    private boolean isProviderConfigured(String providerId, Map<String, Object> config) {
        if ("ollama".equals(providerId)) {
            return true;
        }
        return hasText(stringValue(config.get("api_key"))) || providerManager.isConfigured(providerId);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String maskKey(String apiKey) {
        if (!hasText(apiKey)) return "";
        return apiKey.length() <= 8 ? "********" : apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
