/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.plugin.PluginManager;
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
    public Mono<Map<String, Object>> listPlugins() {
        Set<String> pluginIds = pluginManager.getPluginIds();
        List<Map<String, String>> plugins = pluginIds.stream()
            .map(id -> {
                var plugin = pluginManager.getPlugin(id);
                return Map.<String, String>of(
                    "id", plugin.getId(),
                    "name", plugin.getDisplayName(),
                    "version", plugin.getVersion()
                );
            })
            .collect(Collectors.toList());
        return Mono.just(Map.of("plugins", plugins));
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
