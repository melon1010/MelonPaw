package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.provider.ProviderManager;
import com.melon.core.util.JsonUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * QwenPaw console-compatible model endpoints.
 */
@RestController
@RequestMapping("/api/models")
public class ModelsCompatController {

    private final ProviderManager providerManager;
    private final ConfigManager configManager;
    private final MultiAgentManager multiAgentManager;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ModelsCompatController(ProviderManager providerManager, ConfigManager configManager, MultiAgentManager multiAgentManager) {
        this.providerManager = providerManager;
        this.configManager = configManager;
        this.multiAgentManager = multiAgentManager;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> listModels() {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            List<Map<String, Object>> providers = new ArrayList<>();
            for (String providerId : providerManager.listProviders()) {
                providers.add(providerPayload(providerId, providerManager.listModels(providerId)));
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
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                if ("api_key".equals(entry.getKey()) && isMaskedKey(stringValue(entry.getValue()))) {
                    continue;
                }
                config.put(entry.getKey(), entry.getValue());
            }
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
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            Map<String, Object> config = providerConfig(providerId);
            if (body != null) {
                config.putAll(body);
            }
            List<Map<String, Object>> models = fetchProviderModels(providerId, config);
            boolean ok = !models.isEmpty() || ("ollama".equals(providerId) && isProviderConfigured(providerId, config));
            String message = ok ? "Connection successful" : "Connection failed: unable to fetch provider models";
            return ResponseEntity.ok(Map.of(
                    "success", ok,
                    "available", ok,
                    "provider_id", providerId,
                    "message", message,
                    "detail", ok ? "" : message
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{providerId}/models/test")
    public Mono<ResponseEntity<?>> testModel(@PathVariable String providerId, @RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            String modelId = body != null ? stringValue(body.getOrDefault("model_id", body.get("model"))) : "";
            if (modelId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "available", false,
                        "provider_id", providerId,
                        "message", "Empty model ID",
                        "detail", "Empty model ID"
                ));
            }
            ProviderManager.ModelTestResult test = providerManager.testModelConnection(providerId, modelId);
            return ResponseEntity.ok(Map.of(
                    "success", test.success(),
                    "available", test.success(),
                    "provider_id", providerId,
                    "model_id", modelId,
                    "message", test.message(),
                    "detail", test.success() ? "" : test.message()
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{providerId}/discover")
    public Mono<ResponseEntity<?>> discoverModels(@PathVariable String providerId,
                                                  @RequestParam(defaultValue = "true") boolean save,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            Map<String, Object> config = providerConfig(providerId);
            if (body != null) {
                for (Map.Entry<String, Object> entry : body.entrySet()) {
                    if (entry.getValue() != null) config.put(entry.getKey(), entry.getValue());
                }
            }
            List<Map<String, Object>> discovered = fetchProviderModels(providerId, config);
            boolean success = !discovered.isEmpty();
            List<Map<String, Object>> models = success
                    ? discovered
                    : providerManager.listModels(providerId).stream().map(this::modelPayload).toList();
            int addedCount = 0;
            if (save && success) {
                Set<String> existing = new LinkedHashSet<>();
                configuredModelPayloads(providerId).forEach(model -> existing.add(stringValue(model.get("id"))));
                addedCount = (int) discovered.stream()
                        .map(model -> stringValue(model.get("id")))
                        .filter(id -> !id.isBlank() && !existing.contains(id))
                        .count();
                Map<String, Object> saved = providerConfig(providerId);
                saved.put("models", discovered);
                saveProviderConfig(providerId, saved);
            }
            return ResponseEntity.ok(Map.of(
                    "success", success,
                    "message", success ? "" : "Model discovery failed; using configured/default models",
                    "models", models,
                    "added_count", addedCount,
                    "provider_id", providerId,
                    "saved", save && success
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{providerId}/models")
    public Mono<ResponseEntity<?>> addModel(@PathVariable String providerId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = providerConfig(providerId);
        List<Map<String, Object>> models = configuredModelPayloads(providerId);
        String id = body != null ? stringValue(body.getOrDefault("id", body.get("model_id"))) : "";
        if (!id.isBlank()) {
            Map<String, Object> model = new LinkedHashMap<>(body != null ? body : Map.of());
            model.put("id", id);
            model.putIfAbsent("name", stringValue(model.get("name"), id));
            models.removeIf(existing -> id.equals(existing.get("id")));
            models.add(model);
            config.put("models", models);
            saveProviderConfig(providerId, config);
        }
        return Mono.just(ResponseEntity.ok(providerPayload(providerId, providerManager.listModels(providerId))));
    }

    @DeleteMapping("/{providerId}/models/{modelId}")
    public Mono<ResponseEntity<?>> removeModel(@PathVariable String providerId, @PathVariable String modelId) {
        Map<String, Object> config = providerConfig(providerId);
        List<Map<String, Object>> models = configuredModelPayloads(providerId);
        models.removeIf(model -> modelId.equals(model.get("id")));
        config.put("models", models);
        saveProviderConfig(providerId, config);
        return Mono.just(ResponseEntity.ok(providerPayload(providerId, providerManager.listModels(providerId))));
    }

    @PutMapping("/{providerId}/models/{modelId}/config")
    public Mono<ResponseEntity<?>> configureModel(@PathVariable String providerId,
                                                  @PathVariable String modelId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>(providerPayload(providerId, providerManager.listModels(providerId)));
        if (body != null) {
            Map<String, Object> config = providerConfig(providerId);
            List<Map<String, Object>> models = configuredModelPayloads(providerId);
            for (Map<String, Object> model : models) {
                if (modelId.equals(model.get("id"))) {
                    model.putAll(body);
                    break;
                }
            }
            config.put("models", models);
            saveProviderConfig(providerId, config);
            result = new LinkedHashMap<>(providerPayload(providerId, providerManager.listModels(providerId)));
        }
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
            @RequestParam(value = "agent_id", required = false) String queryAgentId,
            @RequestParam(value = "scope", defaultValue = "effective") String scope) {
        String agentId = queryAgentId == null || queryAgentId.isBlank() ? headerAgentId : queryAgentId;
        return Mono.fromCallable(() -> ResponseEntity.ok(activeModels(agentId, scope)));
    }

    @PutMapping("/active")
    public Mono<ResponseEntity<?>> updateActiveModels(
            @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String targetAgentId = stringValue(body.getOrDefault("agent_id", agentId));
            String active = activeModelFromBody(body);
            String scope = stringValue(body.get("scope"), "agent");
            if (!active.isBlank()) {
                String[] parts = splitModel(active);
                if ("global".equalsIgnoreCase(scope)) {
                    providerManager.setActiveModel(parts[0], parts[1]);
                    if (configManager.getConfig().getAgents().containsKey(targetAgentId)) {
                        String agentModel = configManager.getConfig().getAgents().get(targetAgentId).getActiveModel();
                        if (agentModel == null || agentModel.isBlank()) {
                            configManager.getConfig().getAgents().get(targetAgentId).setActiveModel(active);
                            configManager.save();
                            multiAgentManager.reload(targetAgentId);
                        }
                    }
                } else {
                    if (!configManager.getConfig().getAgents().containsKey(targetAgentId)) {
                        throw new IllegalArgumentException("Agent not found: " + targetAgentId);
                    }
                    if (!providerManager.listModels(parts[0]).contains(parts[1])) {
                        throw new IllegalArgumentException("Model '" + parts[1] + "' not found in provider '" + parts[0] + "'.");
                    }
                    configManager.getConfig().getAgents().get(targetAgentId).setActiveModel(active);
                    configManager.save();
                    multiAgentManager.reload(targetAgentId);
                }
            }
            return ResponseEntity.ok(activeModels(targetAgentId, "effective"));
        });
    }

    @PostMapping("/custom-providers")
    public Mono<ResponseEntity<?>> createCustomProvider(@RequestBody Map<String, Object> body) {
        Map<String, Object> config = new LinkedHashMap<>(body != null ? body : Map.of());
        String providerId = stringValue(config.getOrDefault("id", config.getOrDefault("provider_id", "custom")));
        config.put("id", providerId);
        config.put("provider_id", providerId);
        config.put("is_custom", true);
        config.put("enabled", true);
        config.putIfAbsent("base_url", stringValue(config.get("default_base_url")));
        config.putIfAbsent("models", List.of());
        saveProviderConfig(providerId, config);
        return Mono.just(ResponseEntity.ok(providerPayload(providerId, providerManager.listModels(providerId))));
    }

    @GetMapping("/custom-providers")
    public Mono<ResponseEntity<?>> listCustomProviders() {
        return Mono.just(ResponseEntity.ok(providerConfigs().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Map<?, ?> map && isCustomProviderConfig(map))
                .map(entry -> providerPayload(entry.getKey(), providerManager.listModels(entry.getKey())))
                .toList()));
    }

    @DeleteMapping("/custom-providers/{providerId}")
    public Mono<ResponseEntity<?>> deleteCustomProvider(@PathVariable String providerId) {
        Map<String, Object> configs = providerConfigs();
        configs.remove(providerId);
        JsonUtils.save(providerConfigFile(), configs);
        providerManager.refreshProviderConfig();
        return listCustomProviders();
    }

    @GetMapping("/openrouter/series")
    public Mono<ResponseEntity<?>> openRouterSeries() {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(Map.of(
                "series", openRouterModels(openRouterConfig(null)).stream()
                        .map(model -> stringValue(model.get("provider")))
                        .filter(provider -> !provider.isBlank())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                        .stream()
                        .toList()
        ))).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/openrouter/discover-extended")
    public Mono<ResponseEntity<?>> discoverOpenRouterExtended(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            List<Map<String, Object>> models = openRouterModels(openRouterConfig(body));
            Set<String> providers = models.stream()
                    .map(model -> stringValue(model.get("provider")))
                    .filter(provider -> !provider.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "models", models,
                    "providers", new ArrayList<>(providers),
                    "total_count", models.size()
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/openrouter/models/filter")
    public Mono<ResponseEntity<?>> filterOpenRouterModels(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            List<Map<String, Object>> models = filterOpenRouterModels(openRouterModels(openRouterConfig(null)), body);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "models", models,
                    "total_count", models.size()
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> providerPayload(String providerId, List<String> models) {
        Map<String, Object> provider = new LinkedHashMap<>();
        Map<String, Object> config = providerConfig(providerId);
        boolean configured = isProviderConfigured(providerId, config);
        provider.put("provider_id", providerId);
        provider.put("id", providerId);
        boolean custom = isCustomProviderConfig(config);
        String displayName = stringValue(config.get("name"), displayName(providerId));
        provider.put("name", displayName);
        provider.put("display_name", displayName);
        provider.put("provider_group", "default");
        provider.put("enabled", true);
        provider.put("configured", configured);
        List<Map<String, Object>> configuredModels = configuredModelPayloads(providerId);
        Set<String> configuredIds = configuredModels.stream()
                .map(model -> stringValue(model.get("id")))
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> modelPayloads = custom
                ? configuredModels
                : models.stream()
                        .filter(model -> !configuredIds.contains(model))
                        .map(this::modelPayload)
                        .toList();
        provider.put("models", modelPayloads);
        provider.put("extra_models", custom ? List.of() : configuredModels);
        provider.put("available_models", models);
        provider.put("chat_model", stringValue(config.getOrDefault("chat_model", models.isEmpty() ? "" : models.get(0))));
        provider.put("api_key_prefix", "");
        provider.put("is_custom", custom);
        provider.put("is_local", "ollama".equals(providerId));
        provider.put("support_model_discovery", supportsModelDiscovery(providerId, config));
        provider.put("support_connection_check", true);
        provider.put("freeze_url", false);
        provider.put("require_api_key", !"ollama".equals(providerId));
        provider.put("base_url", stringValue(config.getOrDefault("base_url", config.get("default_base_url"))));
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

    private List<Map<String, Object>> fetchProviderModels(String providerId, Map<String, Object> config) {
        String baseUrl = stringValue(config.getOrDefault("base_url", config.get("default_base_url")));
        if (baseUrl.isBlank()) baseUrl = defaultBaseUrl(providerId);
        if (baseUrl.isBlank()) return List.of();
        String apiKey = stringValue(config.get("api_key"));
        if (apiKey.isBlank()) apiKey = envApiKey(providerId);
        List<URI> endpoints;
        try {
            endpoints = modelDiscoveryEndpoints(providerId, baseUrl);
        } catch (Exception e) {
            return List.of();
        }
        for (URI endpoint : endpoints) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(12))
                        .GET()
                        .header("Accept", "application/json");
                applyDiscoveryAuth(builder, providerId, apiKey, config);
                applyCustomHeaders(builder, config.get("custom_headers"));
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    List<Map<String, Object>> models = parseModelList(response.body());
                    if (!models.isEmpty()) return models;
                }
            } catch (Exception ignored) {
                // Try the next compatible endpoint, then fall back to configured defaults.
            }
        }
        return List.of();
    }

    private List<URI> modelDiscoveryEndpoints(String providerId, String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        if ("ollama".equals(providerId)) {
            return List.of(URI.create(base + "/api/tags"));
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        urls.add(base + "/models");
        if (!base.endsWith("/v1")) {
            urls.add(base + "/v1/models");
        }
        return urls.stream().map(URI::create).toList();
    }

    private void applyDiscoveryAuth(HttpRequest.Builder builder, String providerId, String apiKey, Map<String, Object> config) {
        if (apiKey == null || apiKey.isBlank()) return;
        String authMode = stringValue(config.getOrDefault("auth_mode", "api_key"));
        if ("anthropic".equals(providerId) && !"auth_token".equals(authMode)) {
            builder.header("x-api-key", apiKey);
            builder.header("anthropic-version", "2023-06-01");
            return;
        }
        builder.header("Authorization", "Bearer " + apiKey);
    }

    @SuppressWarnings("unchecked")
    private void applyCustomHeaders(HttpRequest.Builder builder, Object raw) {
        if (!(raw instanceof Map<?, ?> headers)) return;
        headers.forEach((key, value) -> {
            if (key != null && value != null && !String.valueOf(key).isBlank()) {
                builder.header(String.valueOf(key), String.valueOf(value));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseModelList(String json) throws Exception {
        Object parsed = JsonUtils.getMapper().readValue(json, Object.class);
        List<?> rawModels = List.of();
        if (parsed instanceof Map<?, ?> map) {
            Object data = map.get("data");
            Object models = map.get("models");
            if (data instanceof List<?> list) rawModels = list;
            else if (models instanceof List<?> list) rawModels = list;
        } else if (parsed instanceof List<?> list) {
            rawModels = list;
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : rawModels) {
            String id = "";
            String name = "";
            if (raw instanceof Map<?, ?> model) {
                id = stringValue(firstPresent(model, "id", "name", "model"));
                name = stringValue(firstPresent(model, "name", "display_name", "displayName", "id"));
            } else if (raw != null) {
                id = String.valueOf(raw);
            }
            id = normalizeDiscoveredModelId(id);
            if (id.isBlank()) continue;
            if (name.isBlank()) name = id;
            result.put(id, modelPayloadWithName(id, name));
        }
        return new ArrayList<>(result.values());
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return "";
    }

    private String normalizeDiscoveredModelId(String id) {
        String value = id == null ? "" : id.trim();
        if (value.startsWith("models/")) value = value.substring("models/".length());
        return value;
    }

    private Map<String, Object> modelPayloadWithName(String id, String name) {
        Map<String, Object> model = new LinkedHashMap<>(modelPayload(id));
        model.put("name", name == null || name.isBlank() ? id : name);
        return model;
    }

    private Map<String, Object> openRouterConfig(Map<String, Object> body) {
        Map<String, Object> config = providerConfig("openrouter");
        if (body != null) {
            body.forEach((key, value) -> {
                if (value != null) config.put(key, value);
            });
        }
        config.putIfAbsent("base_url", defaultBaseUrl("openrouter"));
        return config;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> openRouterModels(Map<String, Object> config) {
        String baseUrl = stringValue(config.getOrDefault("base_url", config.get("default_base_url")));
        if (baseUrl.isBlank()) baseUrl = defaultBaseUrl("openrouter");
        String apiKey = stringValue(config.get("api_key"));
        if (apiKey.isBlank()) apiKey = envApiKey("openrouter");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/models"))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .header("Accept", "application/json");
            applyDiscoveryAuth(builder, "openrouter", apiKey, config);
            applyCustomHeaders(builder, config.get("custom_headers"));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Object parsed = JsonUtils.getMapper().readValue(response.body(), Object.class);
                Object data = parsed instanceof Map<?, ?> map ? map.get("data") : parsed;
                if (data instanceof List<?> list) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Object raw : list) {
                        if (raw instanceof Map<?, ?> model) {
                            result.add(openRouterModelPayload(model));
                        }
                    }
                    if (!result.isEmpty()) return result;
                }
            }
        } catch (Exception ignored) {
            // Local/default fallback below keeps the model manager usable offline.
        }
        return providerManager.listModels("openrouter").stream()
                .map(id -> openRouterFallbackPayload(id, id))
                .toList();
    }

    private Map<String, Object> openRouterModelPayload(Map<?, ?> raw) {
        String id = stringValue(raw.get("id"));
        String name = stringValue(firstPresent(raw, "name", "id"));
        Map<String, Object> architecture = mapValue(raw.get("architecture"));
        List<String> input = stringList(architecture.get("input_modalities"));
        List<String> output = stringList(architecture.get("output_modalities"));
        Map<String, Object> pricing = mapValue(raw.get("pricing"));
        boolean free = id.endsWith(":free")
                || (pricing.containsKey("prompt") && pricing.containsKey("completion")
                && numberValue(pricing.get("prompt")) == 0.0 && numberValue(pricing.get("completion")) == 0.0);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id);
        model.put("name", name.isBlank() ? id : name);
        model.put("provider", openRouterProvider(id, raw));
        model.put("supports_multimodal", input.stream().anyMatch(value -> !"text".equalsIgnoreCase(value)));
        model.put("supports_image", input.contains("image"));
        model.put("supports_video", input.contains("video"));
        model.put("probe_source", "openrouter");
        model.put("is_free", free);
        model.put("input_modalities", input);
        model.put("output_modalities", output);
        model.put("pricing", pricing);
        return model;
    }

    private Map<String, Object> openRouterFallbackPayload(String id, String name) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id);
        model.put("name", name == null || name.isBlank() ? id : name);
        model.put("provider", openRouterProvider(id, Map.of()));
        model.put("supports_multimodal", false);
        model.put("supports_image", false);
        model.put("supports_video", false);
        model.put("probe_source", "configured");
        model.put("is_free", id.endsWith(":free"));
        model.put("input_modalities", List.of("text"));
        model.put("output_modalities", List.of("text"));
        model.put("pricing", Map.of());
        return model;
    }

    private List<Map<String, Object>> filterOpenRouterModels(List<Map<String, Object>> models, Map<String, Object> body) {
        Set<String> providers = stringSet(body != null ? body.get("providers") : null);
        Set<String> inputModalities = stringSet(body != null ? body.get("input_modalities") : null);
        Set<String> outputModalities = stringSet(body != null ? body.get("output_modalities") : null);
        boolean freeOnly = Boolean.TRUE.equals(body != null ? body.get("is_free") : null);
        Double maxPromptPrice = body != null ? nullableNumber(body.get("max_prompt_price")) : null;
        return models.stream()
                .filter(model -> providers.isEmpty() || providers.contains(stringValue(model.get("provider"))))
                .filter(model -> inputModalities.isEmpty() || stringSet(model.get("input_modalities")).containsAll(inputModalities))
                .filter(model -> outputModalities.isEmpty() || stringSet(model.get("output_modalities")).containsAll(outputModalities))
                .filter(model -> !freeOnly || Boolean.TRUE.equals(model.get("is_free")))
                .filter(model -> maxPromptPrice == null || numberValue(mapValue(model.get("pricing")).get("prompt")) <= maxPromptPrice)
                .toList();
    }

    private String openRouterProvider(String id, Map<?, ?> raw) {
        Object topProvider = raw.get("top_provider");
        if (topProvider instanceof Map<?, ?> map) {
            String name = stringValue(firstPresent(map, "name", "provider"));
            if (!name.isBlank()) return name;
        }
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : "openrouter";
    }

    private Map<String, Object> mapValue(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), value);
        });
        return result;
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList();
    }

    private Set<String> stringSet(Object raw) {
        return new LinkedHashSet<>(stringList(raw));
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null || String.valueOf(value).isBlank() ? 0.0 : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private Double nullableNumber(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null || String.valueOf(value).isBlank() ? null : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String defaultBaseUrl(String providerId) {
        return switch (providerId) {
            case "openai" -> "https://api.openai.com/v1";
            case "deepseek" -> "https://api.deepseek.com";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            case "ollama" -> "http://127.0.0.1:11434";
            default -> "";
        };
    }

    private boolean supportsModelDiscovery(String providerId, Map<String, Object> config) {
        return !defaultBaseUrl(providerId).isBlank()
                || hasText(stringValue(config.getOrDefault("base_url", config.get("default_base_url"))));
    }

    private String envApiKey(String providerId) {
        String env = switch (providerId) {
            case "dashscope" -> "DASHSCOPE_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "gemini" -> "GEMINI_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "openrouter" -> "OPENROUTER_API_KEY";
            default -> "";
        };
        if (env.isBlank()) {
            return "";
        }
        String value = stringValue(System.getProperty(env));
        return value.isBlank() ? stringValue(System.getenv(env)) : value;
    }

    private List<Map<String, Object>> configuredModelPayloads(String providerId) {
        Object raw = providerConfig(providerId).get("models");
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> model = new LinkedHashMap<>();
                map.forEach((key, value) -> model.put(String.valueOf(key), value));
                String id = stringValue(model.get("id"));
                if (id.isBlank()) continue;
                model.putAll(new LinkedHashMap<>(modelPayload(id)));
                map.forEach((key, value) -> model.put(String.valueOf(key), value));
                result.add(model);
            } else {
                String id = stringValue(item);
                if (!id.isBlank()) result.add(new LinkedHashMap<>(modelPayload(id)));
            }
        }
        return result;
    }

    private Map<String, Object> activeModels(String agentId, String scope) {
        String active = "";
        if ("global".equalsIgnoreCase(scope)) {
            Map<String, String> global = providerManager.getActiveModel();
            active = activeModelId(global.get("provider_id"), global.get("model"));
        } else if ("agent".equalsIgnoreCase(scope)) {
            if (configManager.getConfig().getAgents().containsKey(agentId)) {
                active = configManager.getConfig().getAgents().get(agentId).getActiveModel();
            }
        } else if (configManager.getConfig().getAgents().containsKey(agentId)) {
            active = configManager.getConfig().getAgents().get(agentId).getActiveModel();
            if (active == null || active.isBlank()) {
                Map<String, String> global = providerManager.getActiveModel();
                active = activeModelId(global.get("provider_id"), global.get("model"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active_model", active != null ? active : "");
        String[] parts = splitModel(active);
        result.put("active_llm", Map.of("provider_id", parts[0], "model", parts[1]));
        result.put("default", active != null ? active : "");
        result.put("summary", active != null ? active : "");
        result.put("coding", active != null ? active : "");
        result.put("memory", active != null ? active : "");
        return result;
    }

    private String activeModelId(String providerId, String model) {
        return hasText(providerId) && hasText(model) ? providerId + ":" + model : "";
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
        providerManager.refreshProviderConfig();
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
        if (isCustomProviderConfig(config)) {
            return hasText(stringValue(config.getOrDefault("base_url", config.get("default_base_url"))));
        }
        return hasText(stringValue(config.get("api_key"))) || providerManager.isConfigured(providerId);
    }

    private boolean isCustomProviderConfig(Map<?, ?> config) {
        Object custom = config.get("is_custom");
        return Boolean.TRUE.equals(custom) || "true".equalsIgnoreCase(String.valueOf(custom));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String activeModelFromBody(Map<String, Object> body) {
        if (body.get("active_llm") instanceof Map<?, ?> slot) {
            String providerId = stringValue(slot.get("provider_id"));
            String model = stringValue(slot.get("model"));
            if (hasText(providerId) && hasText(model)) {
                return providerId + ":" + model;
            }
        }
        Object active = body.getOrDefault("active_model", body.getOrDefault("default", null));
        if (active != null && hasText(String.valueOf(active))) {
            return String.valueOf(active);
        }
        String model = stringValue(body.get("model"));
        if (!hasText(model)) {
            return "";
        }
        String providerId = stringValue(body.get("provider_id"));
        if (!hasText(providerId) || model.contains(":")) {
            return model;
        }
        return providerId + ":" + model;
    }

    private boolean isMaskedKey(String apiKey) {
        return apiKey != null && apiKey.contains("*");
    }

    private String maskKey(String apiKey) {
        if (!hasText(apiKey)) return "";
        return apiKey.length() <= 8 ? "********" : apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
