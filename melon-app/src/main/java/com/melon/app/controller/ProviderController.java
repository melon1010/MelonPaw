/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.provider.ProviderManager;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for model provider management.
 * Corresponds to Python /api/providers endpoints.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderManager providerManager;

    public ProviderController(ProviderManager providerManager) {
        this.providerManager = providerManager;
    }

    /**
     * Lists all registered model providers.
     */
    @GetMapping
    public Mono<Map<String, Object>> listProviders() {
        return Mono.just(Map.of(
            "providers", providerManager.listProviders()
        ));
    }

    /**
     * Gets details for a specific provider.
     */
    @GetMapping("/{providerId}")
    public Mono<Map<String, Object>> getProvider(@PathVariable String providerId) {
        return Mono.just(Map.of(
            "provider_id", providerId,
            "models", providerManager.listModels(providerId)
        ));
    }

    /**
     * Tests a provider connection.
     */
    @PostMapping("/{providerId}/test")
    public Mono<Map<String, Object>> testProvider(@PathVariable String providerId) {
        boolean ok = providerManager.testConnection(providerId);
        return Mono.just(Map.of(
            "provider_id", providerId,
            "status", ok ? "ok" : "failed"
        ));
    }

    /**
     * Lists all available models across providers.
     */
    @GetMapping("/models")
    public Mono<Map<String, Object>> listAllModels() {
        return Mono.just(Map.of(
            "models", providerManager.listAllModels()
        ));
    }
}
