package com.melon.app.controller;

import com.melon.core.plugin.PluginManager;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST controller for plugin management.
 * Corresponds to Python /api/plugins endpoints.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginManager pluginManager;

    public PluginController(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * Lists all loaded plugins.
     */
    @GetMapping
    public Mono<List<Map<String, Object>>> listPlugins() {
        Set<String> pluginIds = pluginManager.getPluginIds();
        List<Map<String, Object>> plugins = pluginIds.stream()
            .map(id -> {
                var plugin = pluginManager.getPlugin(id);
                return Map.<String, Object>of(
                    "id", plugin.getId(),
                    "name", plugin.getDisplayName(),
                    "version", plugin.getVersion(),
                    "description", "",
                    "enabled", true,
                    "loaded", true,
                    "plugin_type", "general"
                );
            })
            .collect(Collectors.toList());
        return Mono.just(plugins);
    }

    @GetMapping("/catalog")
    public Mono<ResponseEntity<?>> catalog() {
        Map<String, Object> catalog = new java.util.LinkedHashMap<>();
        catalog.put("updated_at", null);
        catalog.put("plugins", List.of());
        catalog.put("error", null);
        return Mono.just(ResponseEntity.ok(catalog));
    }

    @GetMapping("/market/search")
    public Mono<ResponseEntity<?>> marketSearch() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "success", true,
                "message", "ok",
                "data", Map.of("total", 0, "plugins", List.of())
        )));
    }

    @PostMapping("/install")
    public Mono<ResponseEntity<?>> installPlugin(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.status(501).body(Map.of("detail", "Plugin install is not implemented in Java compatibility mode")));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadPlugin(@RequestPart("file") FilePart filePart) {
        return Mono.just(ResponseEntity.status(501).body(Map.of("detail", "Plugin upload is not implemented in Java compatibility mode")));
    }

    /**
     * Gets details for a specific plugin.
     */
    @GetMapping("/{pluginId}")
    public Mono<Map<String, Object>> getPlugin(@PathVariable String pluginId) {
        var plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            return Mono.just(Map.of("error", "Plugin not found: " + pluginId));
        }
        return Mono.just(Map.of(
            "id", plugin.getId(),
            "name", plugin.getDisplayName(),
            "version", plugin.getVersion()
        ));
    }

    @GetMapping("/{pluginId}/status")
    public Mono<ResponseEntity<?>> pluginStatus(@PathVariable String pluginId) {
        boolean loaded = pluginManager.getPlugin(pluginId) != null;
        return Mono.just(ResponseEntity.ok(Map.of("id", pluginId, "enabled", loaded, "loaded", loaded)));
    }

    /**
     * Reloads all plugins.
     */
    @PostMapping("/reload")
    public Mono<Map<String, Object>> reloadPlugins() {
        pluginManager.unloadAll();
        pluginManager.loadAll();
        return Mono.just(Map.of(
            "status", "reloaded",
            "count", pluginManager.getPluginIds().size()
        ));
    }

    /**
     * Unloads a specific plugin.
     */
    @DeleteMapping("/{pluginId}")
    public Mono<Map<String, Object>> unloadPlugin(@PathVariable String pluginId) {
        pluginManager.unloadPlugin(pluginId);
        return Mono.just(Map.of("status", "unloaded", "id", pluginId));
    }
}
