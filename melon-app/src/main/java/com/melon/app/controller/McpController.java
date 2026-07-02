package com.melon.app.controller;

import com.melon.app.mcp.McpClientManager;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * MCP REST 控制器. 对应 Python /api/mcp/* 端点.
 * <p>
 * 端点:
 * <ul>
 *   <li>GET /api/mcp/servers - 列出所有 MCP 服务器</li>
 *   <li>POST /api/mcp/servers - 注册 MCP 服务器</li>
 *   <li>DELETE /api/mcp/servers/{id} - 移除 MCP 服务器</li>
 *   <li>GET /api/mcp/servers/{id}/tools - 列出服务器工具</li>
 *   <li>POST /api/mcp/servers/{id}/tools - 调用服务器工具</li>
 *   <li>POST /api/mcp/reload - 热重载所有服务器</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpClientManager mcpClientManager;
    private final ConfigManager configManager;
    private final MultiAgentManager multiAgentManager;

    public McpController(McpClientManager mcpClientManager, ConfigManager configManager, MultiAgentManager multiAgentManager) {
        this.mcpClientManager = mcpClientManager;
        this.configManager = configManager;
        this.multiAgentManager = multiAgentManager;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> listClients() {
        return Mono.fromCallable(() -> ResponseEntity.ok(mcpClientManager.listServers().stream()
                .map(this::clientInfo)
                .collect(Collectors.toList())));
    }

    @GetMapping("/{clientKey}")
    public Mono<ResponseEntity<?>> getClient(@PathVariable String clientKey) {
        return Mono.fromCallable(() -> {
            var config = mcpClientManager.getServer(clientKey);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(clientInfo(config));
        });
    }

    @PostMapping
    public Mono<ResponseEntity<?>> createClient(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            var config = McpClientManager.McpServerConfig.fromMap(normalizeClientBody(body));
            mcpClientManager.register(config);
            multiAgentManager.reloadAll();
            return ResponseEntity.status(201).body(clientInfo(config));
        });
    }

    @PutMapping("/{clientKey}")
    public Mono<ResponseEntity<?>> updateClient(@PathVariable String clientKey, @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            if (mcpClientManager.getServer(clientKey) == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> normalized = normalizeClientBody(body);
            normalized.put("id", clientKey);
            mcpClientManager.unregister(clientKey);
            var config = McpClientManager.McpServerConfig.fromMap(normalized);
            mcpClientManager.register(config);
            multiAgentManager.reloadAll();
            return ResponseEntity.ok(clientInfo(config));
        });
    }

    @PatchMapping("/toggle/{clientKey}")
    public Mono<ResponseEntity<?>> toggleClient(@PathVariable String clientKey) {
        return Mono.fromCallable(() -> {
            var config = mcpClientManager.getServer(clientKey);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            config.setEnabled(!config.isEnabled());
            if (config.isEnabled()) {
                mcpClientManager.connect(clientKey);
            } else {
                mcpClientManager.disconnect(clientKey);
            }
            multiAgentManager.reloadAll();
            return ResponseEntity.ok(clientInfo(config));
        });
    }

    @DeleteMapping("/{clientKey}")
    public Mono<ResponseEntity<?>> deleteClient(@PathVariable String clientKey) {
        return Mono.fromCallable(() -> {
            boolean removed = mcpClientManager.unregister(clientKey);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            multiAgentManager.reloadAll();
            return ResponseEntity.ok(Map.of("message", "deleted"));
        });
    }

    @GetMapping("/tools/{clientKey}")
    public Mono<ResponseEntity<?>> listClientTools(@PathVariable String clientKey) {
        return Mono.fromCallable(() -> ResponseEntity.ok(mcpClientManager.listTools(clientKey).stream()
                .map(tool -> {
                    Map<String, Object> info = tool.toMap();
                    info.put("enabled", true);
                    info.put("client_key", clientKey);
                    return info;
                })
                .collect(Collectors.toList())));
    }

    @PutMapping("/tools/{clientKey}")
    public Mono<ResponseEntity<?>> updateToolWhitelist(@PathVariable String clientKey, @RequestBody Map<String, Object> body) {
        return listClientTools(clientKey);
    }

    @GetMapping("/access-principals")
    public Mono<ResponseEntity<?>> accessPrincipals() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/policy/{clientKey}")
    public Mono<ResponseEntity<?>> getPolicy(@PathVariable String clientKey) {
        return Mono.fromCallable(() -> ResponseEntity.ok(policy(clientKey)));
    }

    @PutMapping("/policy/{clientKey}")
    public Mono<ResponseEntity<?>> updatePolicy(@PathVariable String clientKey, @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = defaultPolicy();
            if (body != null) result.putAll(body);
            JsonUtils.save(policyFile(clientKey), result);
            return ResponseEntity.ok(result);
        });
    }

    @PostMapping("/oauth/start/{clientKey}")
    public Mono<ResponseEntity<?>> startOAuth(@PathVariable String clientKey) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "auth_url", "",
                "session_id", "",
                "authorization_url", "",
                "detail", "OAuth MCP clients are not implemented in Java compatibility mode"
        )));
    }

    @GetMapping("/oauth/status/{clientKey}")
    public Mono<ResponseEntity<?>> oauthStatus(@PathVariable String clientKey) {
        return Mono.just(ResponseEntity.ok(Map.of("authorized", false, "expires_at", 0, "scope", "")));
    }

    @DeleteMapping("/oauth/{clientKey}")
    public Mono<ResponseEntity<?>> revokeOAuth(@PathVariable String clientKey) {
        return Mono.just(ResponseEntity.ok(Map.of("message", "oauth disabled")));
    }

    /**
     * 列出所有 MCP 服务器.
     */
    @GetMapping("/servers")
    public Mono<ResponseEntity<?>> listServers() {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> servers = mcpClientManager.listServers().stream()
                    .map(config -> {
                        var map = config.toMap();
                        var state = mcpClientManager.getConnectionState(config.getId());
                        map.put("connected", state.connected());
                        if (state.error() != null) {
                            map.put("error", state.error());
                        }
                        return map;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("servers", servers));
        });
    }

    /**
     * 注册 MCP 服务器.
     * <p>
     * 请求体示例 (stdio):
     * <pre>{@code
     * {
     *   "name": "filesystem",
     *   "transport": "stdio",
     *   "command": "npx",
     *   "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
     *   "enabled": true
     * }
     * }</pre>
     * <p>
     * 请求体示例 (SSE):
     * <pre>{@code
     * {
     *   "name": "remote-tools",
     *   "transport": "sse",
     *   "url": "http://localhost:3001/sse",
     *   "enabled": true
     * }
     * }</pre>
     */
    @PostMapping("/servers")
    public Mono<ResponseEntity<?>> registerServer(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            try {
                var config = McpClientManager.McpServerConfig.fromMap(body);
                mcpClientManager.register(config);
                multiAgentManager.reloadAll();
                log.info("MCP server registered: id={}, name={}", config.getId(), config.getName());
                return ResponseEntity.ok(config.toMap());
            } catch (Exception e) {
                log.error("Failed to register MCP server", e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 移除 MCP 服务器.
     */
    @DeleteMapping("/servers/{id}")
    public Mono<ResponseEntity<?>> removeServer(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            boolean removed = mcpClientManager.unregister(id);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            multiAgentManager.reloadAll();
            return ResponseEntity.ok(Map.of("status", "removed", "id", id));
        });
    }

    /**
     * 列出服务器的可用工具.
     */
    @GetMapping("/servers/{id}/tools")
    public Mono<ResponseEntity<?>> listTools(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            var config = mcpClientManager.getServer(id);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            var tools = mcpClientManager.listTools(id).stream()
                    .map(McpClientManager.McpTool::toMap)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("server_id", id, "tools", tools));
        });
    }

    /**
     * 调用服务器上的工具.
     * <p>
     * 请求体:
     * <pre>{@code
     * {
     *   "tool": "read_file",
     *   "args": {"path": "/tmp/test.txt"}
     * }
     * }</pre>
     */
    @PostMapping("/servers/{id}/tools")
    public Mono<ResponseEntity<?>> callTool(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String toolName = (String) body.get("tool");
            if (toolName == null || toolName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "tool name is required"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) body.get("args");
            var result = mcpClientManager.callTool(id, toolName, args);
            if (result.containsKey("error")) {
                return ResponseEntity.status(400).body(result);
            }
            return ResponseEntity.ok(result);
        });
    }

    /**
     * 列出所有已连接服务器的全部工具.
     */
    @GetMapping("/tools")
    public Mono<ResponseEntity<?>> listAllTools() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(Map.of("tools", mcpClientManager.listAllTools())));
    }

    /**
     * 热重载所有 MCP 服务器.
     */
    @PostMapping("/reload")
    public Mono<ResponseEntity<?>> reload() {
        return Mono.fromCallable(() -> {
            mcpClientManager.reload();
            multiAgentManager.reloadAll();
            return ResponseEntity.ok(Map.of("status", "reloaded"));
        });
    }

    private Map<String, Object> normalizeClientBody(Map<String, Object> body) {
        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        if (body == null) body = Map.of();
        if (body.get("client") instanceof Map<?, ?> client) {
            client.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            Object key = firstPresent(body, "client_key", "key", "id");
            if (key != null) normalized.put("id", String.valueOf(key));
        }
        Object id = firstPresent(body, "id", "key", "client_key");
        Object name = firstPresent(body, "name", "display_name", "client_key", "key");
        normalized.putIfAbsent("id", id != null ? String.valueOf(id) : null);
        normalized.putIfAbsent("name", name != null ? String.valueOf(name) : null);
        normalized.putIfAbsent("transport", String.valueOf(firstPresent(normalized, "transport", "transport_type") != null ? firstPresent(normalized, "transport", "transport_type") : "stdio"));
        normalized.putIfAbsent("command", firstPresent(body, "command", "cmd"));
        normalized.putIfAbsent("args", body.getOrDefault("args", List.of()));
        normalized.putIfAbsent("env", body.getOrDefault("env", Map.of()));
        normalized.putIfAbsent("headers", body.getOrDefault("headers", Map.of()));
        normalized.putIfAbsent("cwd", body.getOrDefault("cwd", ""));
        normalized.putIfAbsent("url", firstPresent(body, "url", "server_url"));
        normalized.putIfAbsent("enabled", body.getOrDefault("enabled", true));
        return normalized;
    }

    private Object firstPresent(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return body.get(key);
            }
        }
        return null;
    }

    private Map<String, Object> clientInfo(McpClientManager.McpServerConfig config) {
        Map<String, Object> map = new LinkedHashMap<>(config.toMap());
        var state = mcpClientManager.getConnectionState(config.getId());
        map.put("key", config.getId());
        map.put("client_key", config.getId());
        map.put("display_name", config.getName());
        map.put("description", "");
        map.putIfAbsent("headers", Map.of());
        map.putIfAbsent("cwd", "");
        map.put("tools", null);
        map.put("oauth_status", null);
        map.put("access_summary", Map.of("default_effect", policy(config.getId()).get("default_effect"), "overrides_count", 0));
        map.put("connected", state.connected());
        map.put("status", state.connected() ? "connected" : "disconnected");
        map.put("available", config.isEnabled());
        if (state.error() != null) {
            map.put("error", state.error());
        }
        return map;
    }

    private Map<String, Object> policy(String clientKey) {
        Map<String, Object> saved = JsonUtils.loadAsMap(policyFile(clientKey));
        if (saved.isEmpty()) return defaultPolicy();
        Map<String, Object> result = defaultPolicy();
        result.putAll(saved);
        return result;
    }

    private Map<String, Object> defaultPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("default_effect", "allow");
        policy.put("client_overrides", List.of());
        policy.put("tool_defaults", List.of());
        policy.put("tool_overrides", List.of());
        policy.put("unmanaged_rules_count", 0);
        return policy;
    }

    private java.nio.file.Path policyFile(String clientKey) {
        return configManager.resolveStateDir().resolve("mcp-policies").resolve(clientKey + ".json");
    }
}
