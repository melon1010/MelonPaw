package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.config.ToolsConfig;
import com.melon.core.config.BuiltinToolConfig;
import com.melon.core.config.AgentConfig;
import com.melon.app.runner.FrontendToolCompat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 工具管理 API. 对应 Python /api/tools.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    @Autowired
    private ConfigManager configManager;

    /**
     * 列出所有工具及其启用状态.
     */
    @GetMapping
    public Mono<ResponseEntity<?>> listTools(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            ToolsConfig toolsConfig = toolsConfig(agentId);
            return ResponseEntity.ok(toolsConfig.getBuiltinTools().entrySet().stream()
                    .filter(entry -> entry.getValue().isDisplayToUser())
                    .map(entry -> toolInfo(FrontendToolCompat.displayToolName(entry.getKey()), entry.getValue()))
                    .toList());
        });
    }

    /**
     * 更新工具的启用状态.
     */
    @PutMapping("/{name}/enabled")
    public Mono<ResponseEntity<?>> toggleTool(
            @PathVariable String name,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            try {
                Boolean enabled = (Boolean) body.get("enabled");
                if (enabled == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "'enabled' field is required"));
                }

                ToolsConfig toolsConfig = toolsConfig(agentId);
                String toolName = resolveToolName(name);
                BuiltinToolConfig tool = toolsConfig.getBuiltinTools().get(toolName);

                if (tool == null) {
                    return ResponseEntity.notFound().build();
                }

                tool.setEnabled(enabled);
                configManager.save();
                log.info("Tool '{}' enabled={}", toolName, enabled);

                return ResponseEntity.ok(Map.of("name", toolName, "enabled", enabled));
            } catch (Exception e) {
                log.error("Failed to toggle tool: {}", name, e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    @PatchMapping("/{name}/toggle")
    public Mono<ResponseEntity<?>> toggleToolCompat(
            @PathVariable String name,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            ToolsConfig toolsConfig = toolsConfig(agentId);
            String toolName = resolveToolName(name);
            BuiltinToolConfig tool = toolsConfig.getBuiltinTools().get(toolName);
            if (tool == null) {
                return ResponseEntity.notFound().build();
            }
            Object rawEnabled = body != null ? body.get("enabled") : null;
            boolean enabled = rawEnabled instanceof Boolean b ? b : !tool.isEnabled();
            tool.setEnabled(enabled);
            configManager.save();
            return ResponseEntity.ok(toolInfo(toolName, tool));
        });
    }

    @PatchMapping("/{name}/async-execution")
    public Mono<ResponseEntity<?>> updateAsyncExecution(
            @PathVariable String name,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            ToolsConfig toolsConfig = toolsConfig(agentId);
            String toolName = resolveToolName(name);
            BuiltinToolConfig tool = toolsConfig.getBuiltinTools().get(toolName);
            if (tool == null) {
                return ResponseEntity.notFound().build();
            }
            Object rawEnabled = body != null ? body.get("async_execution") : null;
            if (rawEnabled == null && body != null) rawEnabled = body.get("enabled");
            if (rawEnabled instanceof Boolean b) {
                tool.setAsyncExecution(b);
                configManager.save();
            }
            return ResponseEntity.ok(toolInfo(toolName, tool));
        });
    }

    @GetMapping("/{name}/config")
    public Mono<ResponseEntity<?>> getToolConfig(@PathVariable String name,
                                                 @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            ToolsConfig toolsConfig = toolsConfig(agentId);
            String toolName = resolveToolName(name);
            BuiltinToolConfig tool = toolsConfig.getBuiltinTools().get(toolName);
            if (tool == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(maskedConfig(tool));
        });
    }

    @PostMapping("/{name}/config")
    public Mono<ResponseEntity<?>> updateToolConfig(@PathVariable String name,
                                                    @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                    @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            ToolsConfig toolsConfig = toolsConfig(agentId);
            String toolName = resolveToolName(name);
            BuiltinToolConfig tool = toolsConfig.getBuiltinTools().get(toolName);
            if (tool == null) {
                return ResponseEntity.notFound().build();
            }
            if (body != null) {
                if (body.get("enabled") instanceof Boolean b) tool.setEnabled(b);
                if (body.get("async_execution") instanceof Boolean b) tool.setAsyncExecution(b);
                if (body.get("description") != null) tool.setDescription(String.valueOf(body.get("description")));
                if (body.get("config") instanceof Map<?, ?> config) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    config.forEach((k, v) -> values.put(String.valueOf(k), v));
                    tool.setConfig(values);
                }
                configManager.save();
            }
            return ResponseEntity.ok(Map.of("status", "ok", "message", "updated", "tool", toolInfo(toolName, tool)));
        });
    }

    private String resolveToolName(String name) {
        return FrontendToolCompat.displayToolName(name);
    }

    private ToolsConfig toolsConfig(String agentId) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig agent = configManager.getConfig().getAgents().get(id);
        if (agent == null) {
            agent = configManager.getConfig().getAgents().get("default");
        }
        if (agent.getTools() == null) {
            agent.setTools(new ToolsConfig());
        }
        return agent.getTools();
    }

    private Map<String, Object> toolInfo(String name, BuiltinToolConfig tool) {
        String displayName = FrontendToolCompat.displayToolName(name);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", displayName);
        info.put("display_name", displayName);
        info.put("description", tool.getDescription() != null ? tool.getDescription() : "");
        info.put("enabled", tool.isEnabled());
        info.put("async_execution", tool.isAsyncExecution());
        info.put("display_to_user", tool.isDisplayToUser());
        info.put("icon", tool.getIcon() != null ? tool.getIcon() : "");
        info.put("requires_config", tool.isRequiresConfig());
        info.put("config_fields", tool.getConfigFields());
        info.put("config_values", maskedConfig(tool));
        return info;
    }

    private Map<String, Object> maskedConfig(BuiltinToolConfig tool) {
        Map<String, Object> values = new LinkedHashMap<>(tool.getConfig());
        for (Map<String, Object> field : tool.getConfigFields()) {
            if ("password".equals(field.get("type"))) {
                String name = field.get("name") == null ? "" : String.valueOf(field.get("name"));
                if (values.get(name) != null && !String.valueOf(values.get(name)).isBlank()) {
                    values.put(name, "***");
                }
            }
        }
        return values;
    }
}
