/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
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

    public McpController(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
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
            return ResponseEntity.ok(Map.of("status", "reloaded"));
        });
    }
}
