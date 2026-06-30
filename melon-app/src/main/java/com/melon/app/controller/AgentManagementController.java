/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 管理 CRUD. 对应 Python /api/agents.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentManagementController {

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private WorkspaceManager workspaceManager;

    @Autowired
    private MultiAgentManager multiAgentManager;

    @GetMapping
    public Mono<ResponseEntity<?>> list() {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of(
                "agents", configManager.getConfig().getAgents().entrySet().stream()
                        .map(entry -> agentPayload(entry.getKey(), entry.getValue()))
                        .toList()
        )));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<?>> get(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            AgentConfig config = configManager.getConfig().getAgent(id);
            return config == null
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok(agentPayload(id, config));
        });
    }

    @PostMapping
    public Mono<ResponseEntity<?>> create(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String id = sanitizeId(stringValue(value(body, "id", "agent_id", "name"), "agent"));
            Map<String, AgentConfig> agents = mutableAgents();
            if (agents.containsKey(id)) {
                return ResponseEntity.status(409).body(Map.of("detail", "Agent already exists: " + id));
            }
            AgentConfig agent = new AgentConfig();
            agent.setName(stringValue(body != null ? body.get("name") : null, id));
            String workspaceDirValue = stringValue(body != null ? body.get("workspace_dir") : null, "");
            if (!workspaceDirValue.isBlank()) {
                agent.setWorkspaceDir(workspaceDirValue);
            }
            applyActiveModel(agent, body != null ? body.get("active_model") : null);
            if (body != null && body.get("system_prompt_files") instanceof List<?> files) {
                agent.setSystemPromptFiles(files.stream().map(String::valueOf).toList());
            }
            agents.put(id, agent);
            configManager.getConfig().setAgents(agents);
            Path workspaceDir = configManager.resolveWorkspaceDir(id);
            workspaceManager.initWorkspace(workspaceDir);
            workspaceManager.writeAgentJson(workspaceDir, id, agent);
            configManager.save();
            return ResponseEntity.ok(agentPayload(id, agent));
        });
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<?>> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            AgentConfig agent = configManager.getConfig().getAgent(id);
            if (agent == null) {
                return ResponseEntity.notFound().build();
            }
            if (body != null) {
                if (body.get("name") != null) agent.setName(String.valueOf(body.get("name")));
                if (body.get("active_model") != null) applyActiveModel(agent, body.get("active_model"));
                if (body.get("system_prompt_files") instanceof List<?> files) {
                    agent.setSystemPromptFiles(files.stream().map(String::valueOf).toList());
                }
            }
            configManager.save();
            workspaceManager.writeAgentJson(configManager.resolveWorkspaceDir(id), id, agent);
            multiAgentManager.reload(id);
            return ResponseEntity.ok(agentPayload(id, agent));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<?>> delete(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            if ("default".equals(id)) {
                return ResponseEntity.status(409).body(Map.of("detail", "Default agent cannot be deleted"));
            }
            Map<String, AgentConfig> agents = mutableAgents();
            boolean removed = agents.remove(id) != null;
            configManager.getConfig().setAgents(agents);
            configManager.save();
            multiAgentManager.stop(id);
            return ResponseEntity.ok(Map.of("success", removed, "agent_id", id));
        });
    }

    @PutMapping("/order")
    public Mono<ResponseEntity<?>> reorder(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("success", true, "agent_ids", body != null ? body.getOrDefault("agent_ids", java.util.List.of()) : java.util.List.of())));
    }

    @PatchMapping("/{id}/toggle")
    public Mono<ResponseEntity<?>> toggle(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean enabled = body == null || Boolean.TRUE.equals(body.getOrDefault("enabled", true));
        return Mono.just(ResponseEntity.ok(Map.of("success", true, "agent_id", id, "enabled", enabled)));
    }

    private Map<String, AgentConfig> mutableAgents() {
        return new LinkedHashMap<>(configManager.getConfig().getAgents());
    }

    private Map<String, Object> agentPayload(String id, AgentConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("agent_id", id);
        result.put("name", config.getName() != null ? config.getName() : id);
        result.put("description", "");
        result.put("workspace_dir", configManager.resolveWorkspaceDir(id).toString());
        result.put("enabled", true);
        result.put("active_model", activeModel(config.getActiveModel()));
        result.put("approval_level", config.getApproval() != null ? config.getApproval().getLevel() : "AUTO");
        result.put("system_prompt_files", config.getSystemPromptFiles());
        result.put("tools", config.getTools());
        result.put("running", config.getRunning());
        result.put("channels", Map.of());
        result.put("mcp", Map.of());
        result.put("heartbeat", Map.of("enabled", false));
        return result;
    }

    private Map<String, Object> activeModel(String active) {
        if (active == null || active.isBlank()) {
            return Map.of();
        }
        int idx = active.indexOf(':');
        return idx > 0 && idx < active.length() - 1
                ? Map.of("provider_id", active.substring(0, idx), "model", active.substring(idx + 1))
                : Map.of("provider_id", "dashscope", "model", active);
    }

    @SuppressWarnings("unchecked")
    private void applyActiveModel(AgentConfig agent, Object value) {
        if (value instanceof Map<?, ?> map) {
            String provider = stringValue(map.get("provider_id"), "");
            String model = stringValue(map.get("model"), "");
            if (!provider.isBlank() && !model.isBlank()) {
                agent.setActiveModel(provider + ":" + model);
            }
            return;
        }
        String text = stringValue(value, "");
        if (!text.isBlank()) {
            agent.setActiveModel(text);
        }
    }

    private Object value(Map<String, Object> body, String... keys) {
        if (body == null) return null;
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private String sanitizeId(String raw) {
        String id = raw.replaceAll("[^A-Za-z0-9_.-]", "_");
        return id.isBlank() ? "agent" : id;
    }
}
