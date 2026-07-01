/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import com.melon.tools.agent.DelegateExternalAgentTool;
import com.melon.app.runner.AgentRunner;
import com.melon.app.service.ApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Compatibility endpoints for QwenPaw frontend pages whose deep backend
 * behavior is intentionally not implemented yet.
 */
@RestController
@RequestMapping("/api")
public class FrontendCompatController {

    private final ConfigManager configManager;
    private final ApprovalService approvalService;
    private final AgentRunner agentRunner;

    public FrontendCompatController(ConfigManager configManager, ApprovalService approvalService, AgentRunner agentRunner) {
        this.configManager = configManager;
        this.approvalService = approvalService;
        this.agentRunner = agentRunner;
    }

    @GetMapping("/version")
    public Mono<ResponseEntity<?>> version() {
        return Mono.just(ResponseEntity.ok(Map.of("version", "java-1.0.0-SNAPSHOT")));
    }

    @GetMapping("/settings/language")
    public Mono<ResponseEntity<?>> getLanguage() {
        return Mono.just(ResponseEntity.ok(Map.of("language", "zh")));
    }

    @PutMapping("/settings/language")
    public Mono<ResponseEntity<?>> setLanguage(@RequestBody Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("language", stringValue(body.get("language"), "zh"))));
    }

    @GetMapping("/settings/upload-limit")
    public Mono<ResponseEntity<?>> uploadLimit() {
        return Mono.just(ResponseEntity.ok(Map.of("upload_max_size_mb", 100)));
    }

    @GetMapping("/config/user-timezone")
    public Mono<ResponseEntity<?>> getTimezone() {
        return Mono.just(ResponseEntity.ok(Map.of("timezone", TimeZone.getDefault().getID())));
    }

    @PutMapping("/config/user-timezone")
    public Mono<ResponseEntity<?>> setTimezone(@RequestBody Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("timezone", stringValue(body.get("timezone"), TimeZone.getDefault().getID()))));
    }

    @GetMapping("/config/heartbeat")
    public Mono<ResponseEntity<?>> getHeartbeat() {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "interval_minutes", 0, "query", "")));
    }

    @PutMapping("/config/heartbeat")
    public Mono<ResponseEntity<?>> setHeartbeat(@RequestBody Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(body != null ? body : Map.of("enabled", false)));
    }

    @PostMapping("/config/heartbeat/run")
    public Mono<ResponseEntity<?>> runHeartbeat() {
        return Mono.just(ResponseEntity.ok(Map.of("started", false, "detail", "Heartbeat is disabled in Java compatibility mode")));
    }

    @GetMapping("/config/channels/types")
    public Mono<ResponseEntity<?>> channelTypes() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/config/channels")
    public Mono<ResponseEntity<?>> channels() {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "channels", Map.of())));
    }

    @PutMapping("/config/channels")
    public Mono<ResponseEntity<?>> updateChannels(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "channels", Map.of())));
    }

    @GetMapping("/config/channels/{channel}")
    public Mono<ResponseEntity<?>> channel(@PathVariable String channel) {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "type", channel)));
    }

    @PutMapping("/config/channels/{channel}")
    public Mono<ResponseEntity<?>> updateChannel(@PathVariable String channel) {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "type", channel)));
    }

    @GetMapping("/config/channels/{channel}/qrcode")
    public Mono<ResponseEntity<?>> channelQrcode(@PathVariable String channel) {
        return Mono.just(ResponseEntity.ok(Map.of("qrcode_img", "", "poll_token", "", "enabled", false)));
    }

    @GetMapping("/config/channels/{channel}/qrcode/status")
    public Mono<ResponseEntity<?>> channelQrcodeStatus(@PathVariable String channel,
                                                       @RequestParam(required = false) String token) {
        return Mono.just(ResponseEntity.ok(Map.of("status", "disabled", "credentials", Map.of())));
    }

    @GetMapping("/access-control")
    public Mono<ResponseEntity<?>> accessControlAll() {
        return Mono.just(ResponseEntity.ok(Map.of()));
    }

    @GetMapping("/access-control/pending/all")
    public Mono<ResponseEntity<?>> accessControlPendingAll() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/access-control/{channel}")
    public Mono<ResponseEntity<?>> accessControl(@PathVariable String channel) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "channel", channel,
                "enabled", false,
                "whitelist", List.of(),
                "blacklist", List.of(),
                "pending", List.of()
        )));
    }

    @PostMapping("/access-control/{action}")
    public Mono<ResponseEntity<?>> accessControlAction(@PathVariable String action) {
        return Mono.just(ResponseEntity.ok(Map.of("success", true, "action", action, "enabled", false)));
    }

    @PostMapping("/access-control/{group}/{action}")
    public Mono<ResponseEntity<?>> accessControlNestedAction(@PathVariable String group, @PathVariable String action) {
        return Mono.just(ResponseEntity.ok(Map.of("success", true, "group", group, "action", action, "enabled", false)));
    }

    @PostMapping("/access-control/{group}/{action}/{target}")
    public Mono<ResponseEntity<?>> accessControlTripleAction(@PathVariable String group, @PathVariable String action, @PathVariable String target) {
        return Mono.just(ResponseEntity.ok(Map.of("success", true, "group", group, "action", action, "target", target, "enabled", false)));
    }

    @GetMapping("/config/security/tool-guard")
    public Mono<ResponseEntity<?>> toolGuard() {
        String level = configManager.getConfig().getAgent("default").getApproval().getLevel();
        return Mono.just(ResponseEntity.ok(Map.of("enabled", true, "approval_level", level, "rules", List.of())));
    }

    @PutMapping("/config/security/tool-guard")
    public Mono<ResponseEntity<?>> updateToolGuard(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(body != null ? body : Map.of("enabled", true)));
    }

    @GetMapping("/config/security/tool-guard/builtin-rules")
    public Mono<ResponseEntity<?>> builtinToolGuardRules() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/config/security/file-guard")
    public Mono<ResponseEntity<?>> fileGuard() {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "rules", List.of())));
    }

    @PutMapping("/config/security/file-guard")
    public Mono<ResponseEntity<?>> updateFileGuard(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(body != null ? body : Map.of("enabled", false)));
    }

    @GetMapping("/config/security/skill-scanner")
    public Mono<ResponseEntity<?>> skillScanner() {
        return Mono.just(ResponseEntity.ok(Map.of("mode", "warn", "timeout", 10, "whitelist", List.of())));
    }

    @PutMapping("/config/security/skill-scanner")
    public Mono<ResponseEntity<?>> updateSkillScanner(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(body != null ? body : Map.of("mode", "warn", "timeout", 10, "whitelist", List.of())));
    }

    @GetMapping("/config/security/skill-scanner/blocked-history")
    public Mono<ResponseEntity<?>> blockedSkillHistory() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @DeleteMapping("/config/security/skill-scanner/blocked-history")
    public Mono<ResponseEntity<?>> clearBlockedSkillHistory() {
        return Mono.just(ResponseEntity.ok(Map.of("cleared", true)));
    }

    @DeleteMapping("/config/security/skill-scanner/blocked-history/{index}")
    public Mono<ResponseEntity<?>> removeBlockedSkillEntry(@PathVariable int index) {
        return Mono.just(ResponseEntity.ok(Map.of("removed", false)));
    }

    @PostMapping("/config/security/skill-scanner/whitelist")
    public Mono<ResponseEntity<?>> addSkillScannerWhitelist(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "whitelisted", true,
                "skill_name", body != null ? stringValue(body.get("skill_name"), "") : ""
        )));
    }

    @DeleteMapping("/config/security/skill-scanner/whitelist/{skillName}")
    public Mono<ResponseEntity<?>> removeSkillScannerWhitelist(@PathVariable String skillName) {
        return Mono.just(ResponseEntity.ok(Map.of("removed", true, "skill_name", skillName)));
    }

    @GetMapping("/config/security/allow-no-auth-hosts")
    public Mono<ResponseEntity<?>> allowNoAuthHosts() {
        return Mono.just(ResponseEntity.ok(Map.of("hosts", List.of())));
    }

    @PutMapping("/config/security/allow-no-auth-hosts")
    public Mono<ResponseEntity<?>> updateAllowNoAuthHosts(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(body != null ? body : Map.of("hosts", List.of())));
    }

    @GetMapping("/local-models/config")
    public Mono<ResponseEntity<?>> localModelConfig() {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "port", 0, "model_name", "")));
    }

    @PutMapping("/local-models/config")
    public Mono<ResponseEntity<?>> updateLocalModelConfig(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(body != null ? body : Map.of("enabled", false)));
    }

    @GetMapping("/local-models/server")
    public Mono<ResponseEntity<?>> localModelServer() {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "available", false, "running", false, "port", 0, "model_name", "")));
    }

    @PostMapping("/local-models/server")
    public Mono<ResponseEntity<?>> startLocalModelServer(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "available", false, "started", false)));
    }

    @DeleteMapping("/local-models/server")
    public Mono<ResponseEntity<?>> stopLocalModelServer() {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "stopped", false)));
    }

    @GetMapping("/local-models/server/update")
    public Mono<ResponseEntity<?>> localModelServerUpdateStatus() {
        return Mono.just(ResponseEntity.ok(Map.of("available", false, "checking", false)));
    }

    @GetMapping("/local-models/server/download")
    public Mono<ResponseEntity<?>> localModelServerDownloadStatus() {
        return Mono.just(ResponseEntity.ok(Map.of("running", false, "progress", 0, "enabled", false)));
    }

    @PostMapping("/local-models/server/download")
    public Mono<ResponseEntity<?>> localModelServerDownload(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("started", false, "enabled", false)));
    }

    @DeleteMapping("/local-models/server/download")
    public Mono<ResponseEntity<?>> cancelLocalModelServerDownload() {
        return Mono.just(ResponseEntity.ok(Map.of("cancelled", false, "enabled", false)));
    }

    @GetMapping("/local-models/models")
    public Mono<ResponseEntity<?>> localModels() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @PostMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> localModelDownload(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("started", false, "enabled", false)));
    }

    @GetMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> localModelDownloadStatus() {
        return Mono.just(ResponseEntity.ok(Map.of("running", false, "progress", 0, "enabled", false)));
    }

    @DeleteMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> cancelLocalModelDownload() {
        return Mono.just(ResponseEntity.ok(Map.of("cancelled", false, "enabled", false)));
    }

    @DeleteMapping("/local-models/models/{modelId}")
    public Mono<ResponseEntity<?>> deleteLocalModel(@PathVariable String modelId) {
        return Mono.just(ResponseEntity.ok(Map.of("deleted", false, "model_id", modelId, "enabled", false)));
    }

    @GetMapping("/market/providers")
    public Mono<ResponseEntity<?>> marketProviders() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/market/categories")
    public Mono<ResponseEntity<?>> marketCategories() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @PostMapping("/market/search")
    public Mono<ResponseEntity<?>> marketSearch() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", List.of());
        result.put("results", List.of());
        result.put("next_page", null);
        return Mono.just(ResponseEntity.ok(result));
    }

    @GetMapping("/debug/logs")
    public Mono<ResponseEntity<?>> debugLogs() {
        return Mono.just(ResponseEntity.ok(Map.of("logs", List.of(), "enabled", false)));
    }

    @GetMapping("/agent-stats")
    public Mono<ResponseEntity<?>> agentStats() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "summary", Map.of(),
                "agents", List.of(),
                "total_messages", 0,
                "total_tool_calls", 0,
                "total_tokens", 0
        )));
    }

    @GetMapping("/")
    public Mono<ResponseEntity<?>> root() {
        return Mono.just(ResponseEntity.ok(Map.of("ok", true, "service", "qwenpaw-java")));
    }

    @PostMapping("/commands/check")
    public Mono<ResponseEntity<?>> checkCommand(@RequestBody(required = false) Map<String, Object> body) {
        String text = body != null ? stringValue(body.get("text"), "") : "";
        boolean control = text.startsWith("/") && (text.startsWith("/approve") || text.startsWith("/deny"));
        return Mono.just(ResponseEntity.ok(Map.of("is_control_command", control, "command_token", control ? text.split("\\s+")[0] : "")));
    }

    @PostMapping("/approval/approve")
    public Mono<ResponseEntity<?>> approveCommand(@RequestBody(required = false) Map<String, Object> body,
                                                  @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                                  @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        String sessionId = body != null ? stringValue(body.get("session_id"), "default") : "default";
        return agentRunner.confirm(agentId, userId, sessionId, true)
                .thenReturn(ResponseEntity.ok(Map.of("success", true, "message", "approved")));
    }

    @PostMapping("/approval/deny")
    public Mono<ResponseEntity<?>> denyCommand(@RequestBody(required = false) Map<String, Object> body,
                                               @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                               @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        String sessionId = body != null ? stringValue(body.get("session_id"), "default") : "default";
        return agentRunner.confirm(agentId, userId, sessionId, false)
                .thenReturn(ResponseEntity.ok(Map.of("success", true, "message", "denied")));
    }

    @GetMapping("/agent/")
    public Mono<ResponseEntity<?>> agentRoot() {
        return Mono.just(ResponseEntity.ok(Map.of("ok", true)));
    }

    @GetMapping("/agent/health")
    public Mono<ResponseEntity<?>> agentHealth() {
        return Mono.just(ResponseEntity.ok(Map.of("status", "ok")));
    }

    @GetMapping("/agent/admin/status")
    public Mono<ResponseEntity<?>> agentAdminStatus() {
        return Mono.just(ResponseEntity.ok(Map.of("running", true, "status", "ok")));
    }

    @PostMapping({"/agent/shutdown", "/agent/admin/shutdown"})
    public Mono<ResponseEntity<?>> shutdownDisabled() {
        return Mono.just(ResponseEntity.ok(Map.of("accepted", false, "detail", "Shutdown is disabled in Java compatibility mode")));
    }

    @GetMapping("/config/acp")
    public Mono<ResponseEntity<?>> getAcpConfig() {
        return Mono.fromCallable(() -> ResponseEntity.ok(acpConfig()));
    }

    @PutMapping("/config/acp")
    public Mono<ResponseEntity<?>> updateAcpConfig(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> config = acpConfig();
            if (body != null) config.putAll(body);
            JsonUtils.save(acpConfigFile(), config);
            DelegateExternalAgentTool.setConfig(acpAgentConfigMap(config));
            return ResponseEntity.ok(config);
        });
    }

    @GetMapping("/config/acp/{agentName}")
    public Mono<ResponseEntity<?>> getAcpAgent(@PathVariable String agentName) {
        return Mono.fromCallable(() -> {
            Map<String, Object> agents = acpAgents(acpConfig());
            Object config = agents.get(agentName);
            return ResponseEntity.ok(config instanceof Map<?, ?> ? config : defaultAcpAgent(agentName));
        });
    }

    @PutMapping("/config/acp/{agentName}")
    public Mono<ResponseEntity<?>> updateAcpAgent(@PathVariable String agentName, @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> config = acpConfig();
            Map<String, Object> agents = acpAgents(config);
            Map<String, Object> result = new LinkedHashMap<>(defaultAcpAgent(agentName));
            if (body != null) result.putAll(body);
            agents.put(agentName, result);
            config.put("agents", agents);
            JsonUtils.save(acpConfigFile(), config);
            DelegateExternalAgentTool.setConfig(acpAgentConfigMap(config));
            return ResponseEntity.ok(result);
        });
    }

    @GetMapping("/coding-mode")
    public Mono<ResponseEntity<?>> getCodingMode() {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", true)));
    }

    @PutMapping("/coding-mode")
    public Mono<ResponseEntity<?>> updateCodingMode(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", body == null || Boolean.TRUE.equals(body.getOrDefault("enabled", true)))));
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> acpConfig() {
        Map<String, Object> config = new LinkedHashMap<>(JsonUtils.loadAsMap(acpConfigFile()));
        Map<String, Object> agents = acpAgents(config);
        for (String key : List.of("opencode", "qwen_code", "claude_code", "codex")) {
            agents.putIfAbsent(key, defaultAcpAgent(key));
        }
        config.put("agents", agents);
        DelegateExternalAgentTool.setConfig(acpAgentConfigMap(config));
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> acpAgents(Map<String, Object> config) {
        Object raw = config.get("agents");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> defaultAcpAgent(String name) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        if ("opencode".equals(name)) {
            config.put("command", "opencode");
            config.put("args", List.of("acp"));
            config.put("tool_parse_mode", "update_detail");
        } else if ("qwen_code".equals(name)) {
            config.put("command", "qwen");
            config.put("args", List.of("--acp"));
            config.put("tool_parse_mode", "call_detail");
        } else if ("claude_code".equals(name)) {
            config.put("command", "npx");
            config.put("args", List.of("-y", "@zed-industries/claude-agent-acp"));
            config.put("tool_parse_mode", "update_detail");
        } else if ("codex".equals(name)) {
            config.put("command", "npx");
            config.put("args", List.of("-y", "@zed-industries/codex-acp"));
            config.put("tool_parse_mode", "call_detail");
        } else {
            config.put("command", "");
            config.put("args", List.of());
            config.put("tool_parse_mode", "call_title");
        }
        config.put("env", Map.of());
        config.put("trusted", true);
        config.put("stdio_buffer_limit_bytes", 50 * 1024 * 1024);
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> acpAgentConfigMap(Map<String, Object> config) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var entry : acpAgents(config).entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> map) {
                Map<String, Object> values = new LinkedHashMap<>();
                map.forEach((k, v) -> values.put(String.valueOf(k), v));
                result.put(entry.getKey(), values);
            }
        }
        return result;
    }

    private java.nio.file.Path acpConfigFile() {
        return configManager.resolveStateDir().resolve("acp.json");
    }
}
