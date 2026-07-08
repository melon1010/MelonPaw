package com.melon.app.controller;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import com.melon.app.cron.CronExecutor;
import com.melon.app.service.AgentStatsService;
import com.melon.app.service.AuditLogService;
import com.melon.app.service.BuiltinSkillService;
import com.melon.app.service.SecuritySettingsService;
import com.melon.app.service.SkillService;
import com.melon.tools.agent.DelegateExternalAgentTool;
import com.melon.app.service.ApprovalService;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.HeartbeatConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * Compatibility endpoints for melonPaw frontend pages whose deep backend
 * behavior is intentionally not implemented yet.
 */
@RestController
@RequestMapping("/api")
public class FrontendCompatController {

    private final ConfigManager configManager;
    private final ApprovalService approvalService;
    private final MultiAgentManager multiAgentManager;
    private final CronExecutor cronExecutor;
    private final AgentStatsService agentStatsService;
    private final SecuritySettingsService securitySettingsService;
    private final AuditLogService auditLogService;
    private final BuiltinSkillService builtinSkillService;
    private final SkillService skillService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Map<String, Object> localModelDownload = new ConcurrentHashMap<>();

    public FrontendCompatController(ConfigManager configManager, ApprovalService approvalService,
                                    MultiAgentManager multiAgentManager,
                                    CronExecutor cronExecutor,
                                    AgentStatsService agentStatsService,
                                    SecuritySettingsService securitySettingsService,
                                    AuditLogService auditLogService,
                                    BuiltinSkillService builtinSkillService,
                                    SkillService skillService) {
        this.configManager = configManager;
        this.approvalService = approvalService;
        this.multiAgentManager = multiAgentManager;
        this.cronExecutor = cronExecutor;
        this.agentStatsService = agentStatsService;
        this.securitySettingsService = securitySettingsService;
        this.auditLogService = auditLogService;
        this.builtinSkillService = builtinSkillService;
        this.skillService = skillService;
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
    public Mono<ResponseEntity<?>> getTimezone(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig config = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        String timezone = config != null && config.getTimezone() != null && !config.getTimezone().isBlank()
                ? config.getTimezone()
                : TimeZone.getDefault().getID();
        return Mono.just(ResponseEntity.ok(Map.of("timezone", timezone)));
    }

    @PutMapping("/config/user-timezone")
    public Mono<ResponseEntity<?>> setTimezone(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @RequestBody Map<String, Object> body) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        String timezone = stringValue(body != null ? body.get("timezone") : null, TimeZone.getDefault().getID());
        if (config != null) {
            config.setTimezone(timezone);
            configManager.save();
            multiAgentManager.reload(id);
        }
        return Mono.just(ResponseEntity.ok(Map.of("timezone", timezone)));
    }

    @GetMapping("/config/heartbeat")
    public Mono<ResponseEntity<?>> getHeartbeat(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig config = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        HeartbeatConfig heartbeat = config != null && config.getHeartbeat() != null ? config.getHeartbeat() : new HeartbeatConfig();
        return Mono.just(ResponseEntity.ok(heartbeatPayload(heartbeat)));
    }

    @PutMapping("/config/heartbeat")
    public Mono<ResponseEntity<?>> setHeartbeat(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody Map<String, Object> body) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        if (config == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        HeartbeatConfig heartbeat = heartbeatFromBody(body);
        config.setHeartbeat(heartbeat);
        configManager.save();
        multiAgentManager.reload(id);
        return Mono.just(ResponseEntity.ok(heartbeatPayload(heartbeat)));
    }

    @PostMapping("/config/heartbeat/run")
    public Mono<ResponseEntity<?>> runHeartbeat(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String id = AgentRequestSupport.agentId(agentId);
        return Mono.fromCallable(() -> {
            AgentConfig config = configManager.getConfig().getAgent(id);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            String message = heartbeatInstruction(id);
            cronExecutor.injectMessage(id, message, "heartbeat-manual-" + System.currentTimeMillis(),
                    "main", "main", "console", Map.of("source", "heartbeat", "channel", "console"),
                    reply -> {}, err -> {});
            return ResponseEntity.ok(Map.of("started", true));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @SuppressWarnings("unchecked")
    private HeartbeatConfig heartbeatFromBody(Map<String, Object> body) {
        HeartbeatConfig heartbeat = new HeartbeatConfig();
        if (body == null) return heartbeat;
        heartbeat.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        heartbeat.setEvery(stringValue(body.get("every"), "6h"));
        heartbeat.setTarget(stringValue(body.get("target"), "main"));
        heartbeat.setTimeoutSeconds((int) numberValue(body.get("timeoutSeconds"), 300));
        Object activeHours = body.get("activeHours");
        if (activeHours instanceof Map<?, ?> raw) {
            Map<String, String> value = new LinkedHashMap<>();
            raw.forEach((key, val) -> value.put(String.valueOf(key), String.valueOf(val)));
            heartbeat.setActiveHours(value);
        }
        return heartbeat;
    }

    private Map<String, Object> heartbeatPayload(HeartbeatConfig heartbeat) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", heartbeat.isEnabled());
        result.put("every", heartbeat.getEvery() != null && !heartbeat.getEvery().isBlank() ? heartbeat.getEvery() : "6h");
        result.put("target", heartbeat.getTarget() != null && !heartbeat.getTarget().isBlank() ? heartbeat.getTarget() : "main");
        result.put("timeoutSeconds", heartbeat.getTimeoutSeconds() > 0 ? heartbeat.getTimeoutSeconds() : 300);
        result.put("activeHours", heartbeat.getActiveHours());
        return result;
    }

    private String heartbeatInstruction(String agentId) {
        Path heartbeatFile = configManager.resolveWorkspaceDir(agentId).resolve("HEARTBEAT.md");
        if (Files.isRegularFile(heartbeatFile)) {
            try {
                String text = Files.readString(heartbeatFile).trim();
                if (!text.isBlank()) return text;
            } catch (Exception ignored) {
                // Use default instruction below.
            }
        }
        return "Heartbeat check: please check pending tasks and continue if needed.";
    }

    private double numberValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @GetMapping("/config/security/tool-guard")
    public Mono<ResponseEntity<?>> toolGuard(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig config = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        String level = config != null && config.getApproval() != null ? config.getApproval().getLevel() : "AUTO";
        Map<String, Object> result = new LinkedHashMap<>(storedToolGuard(config));
        result.put("enabled", !"OFF".equalsIgnoreCase(level));
        result.put("approval_level", level);
        result.putIfAbsent("guarded_tools", null);
        result.putIfAbsent("denied_tools", List.of());
        result.putIfAbsent("custom_rules", List.of());
        result.putIfAbsent("disabled_rules", List.of());
        result.putIfAbsent("auto_denied_rules", List.of());
        result.putIfAbsent("shell_evasion_checks", Map.of());
        return Mono.just(ResponseEntity.ok(result));
    }

    @PutMapping("/config/security/tool-guard")
    public Mono<ResponseEntity<?>> updateToolGuard(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        if (config == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        Map<String, Object> frontend = new LinkedHashMap<>(config.getFrontendRunningConfig() != null
                ? config.getFrontendRunningConfig()
                : Map.of());
        frontend.put("tool_guard_config", body != null ? new LinkedHashMap<>(body) : Map.of());
        config.setFrontendRunningConfig(frontend);
        String level = body != null && body.get("approval_level") != null
                ? String.valueOf(body.get("approval_level"))
                : (Boolean.FALSE.equals(body != null ? body.get("enabled") : null)
                    ? "OFF"
                    : ("OFF".equalsIgnoreCase(config.getApproval().getLevel()) ? "AUTO" : config.getApproval().getLevel()));
        config.getApproval().setLevel(normalizeApprovalLevel(level));
        configManager.save();
        multiAgentManager.reload(id);
        return toolGuard(agentId);
    }

    @GetMapping("/config/security/tool-guard/builtin-rules")
    public Mono<ResponseEntity<?>> builtinToolGuardRules() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/config/security/audit-events")
    public Mono<ResponseEntity<?>> auditEvents(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @RequestParam(value = "session_id", required = false) String sessionId,
                                               @RequestParam(value = "tool_name", required = false) String toolName,
                                               @RequestParam(value = "decision", required = false) String decision,
                                               @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return Mono.just(ResponseEntity.ok(auditLogService.query(
                AgentRequestSupport.agentId(agentId), sessionId, toolName, decision, limit)));
    }

    @GetMapping("/config/security/file-guard")
    public Mono<ResponseEntity<?>> fileGuard() {
        return Mono.just(ResponseEntity.ok(securitySettingsService.fileGuard()));
    }

    @PutMapping("/config/security/file-guard")
    public Mono<ResponseEntity<?>> updateFileGuard(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(securitySettingsService.saveFileGuard(body)));
    }

    @GetMapping("/config/security/skill-scanner")
    public Mono<ResponseEntity<?>> skillScanner() {
        return Mono.just(ResponseEntity.ok(securitySettingsService.skillScanner()));
    }

    @PutMapping("/config/security/skill-scanner")
    public Mono<ResponseEntity<?>> updateSkillScanner(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(securitySettingsService.saveSkillScanner(body)));
    }

    @GetMapping("/config/security/skill-scanner/blocked-history")
    public Mono<ResponseEntity<?>> blockedSkillHistory() {
        return Mono.just(ResponseEntity.ok(securitySettingsService.blockedHistory()));
    }

    @DeleteMapping("/config/security/skill-scanner/blocked-history")
    public Mono<ResponseEntity<?>> clearBlockedSkillHistory() {
        securitySettingsService.clearBlockedHistory();
        return Mono.just(ResponseEntity.ok(Map.of("cleared", true)));
    }

    @DeleteMapping("/config/security/skill-scanner/blocked-history/{index}")
    public Mono<ResponseEntity<?>> removeBlockedSkillEntry(@PathVariable int index) {
        return Mono.just(ResponseEntity.ok(Map.of("removed", securitySettingsService.removeBlockedEntry(index))));
    }

    @PostMapping("/config/security/skill-scanner/whitelist")
    public Mono<ResponseEntity<?>> addSkillScannerWhitelist(@RequestBody(required = false) Map<String, Object> body) {
        String skillName = body != null ? stringValue(body.get("skill_name"), "") : "";
        String contentHash = body != null ? stringValue(body.get("content_hash"), "") : "";
        Map<String, Object> entry = securitySettingsService.addWhitelist(skillName, contentHash);
        return Mono.just(ResponseEntity.ok(Map.of(
                "whitelisted", true,
                "skill_name", entry.get("skill_name")
        )));
    }

    @DeleteMapping("/config/security/skill-scanner/whitelist/{skillName}")
    public Mono<ResponseEntity<?>> removeSkillScannerWhitelist(@PathVariable String skillName) {
        return Mono.just(ResponseEntity.ok(Map.of("removed", securitySettingsService.removeWhitelist(skillName), "skill_name", skillName)));
    }

    @GetMapping("/config/security/allow-no-auth-hosts")
    public Mono<ResponseEntity<?>> allowNoAuthHosts() {
        return Mono.just(ResponseEntity.ok(securitySettingsService.allowNoAuthHosts()));
    }

    @PutMapping("/config/security/allow-no-auth-hosts")
    public Mono<ResponseEntity<?>> updateAllowNoAuthHosts(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(securitySettingsService.saveAllowNoAuthHosts(body)));
    }

    @GetMapping("/local-models/config")
    public Mono<ResponseEntity<?>> localModelConfig() {
        return Mono.just(ResponseEntity.ok(localModelConfigPayload()));
    }

    @PutMapping("/local-models/config")
    public Mono<ResponseEntity<?>> updateLocalModelConfig(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> config = ollamaConfig();
            if (body != null) {
                if (body.containsKey("port")) config.put("port", body.get("port"));
                if (body.containsKey("max_context_length")) config.put("max_context_length", body.get("max_context_length"));
                if (body.containsKey("generate_kwargs")) config.put("generate_kwargs", body.get("generate_kwargs"));
            }
            saveOllamaConfig(config);
            return ResponseEntity.ok(localModelConfigPayload());
        });
    }

    @GetMapping("/local-models/server")
    public Mono<ResponseEntity<?>> localModelServer() {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(localServerStatus())).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/local-models/server")
    public Mono<ResponseEntity<?>> startLocalModelServer(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            Map<String, Object> status = localServerStatus();
            if (!Boolean.TRUE.equals(status.get("available"))) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", status.get("message")));
            }
            String modelId = body != null ? stringValue(body.get("model_id"), "") : "";
            Map<String, Object> config = ollamaConfig();
            if (!modelId.isBlank()) {
                config.put("model_name", modelId);
                saveOllamaConfig(config);
            }
            return ResponseEntity.ok(Map.of(
                    "port", integerValue(config.get("port"), 11434),
                    "model_name", modelId
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/local-models/server")
    public Mono<ResponseEntity<?>> stopLocalModelServer() {
        return Mono.fromCallable(() -> {
            Map<String, Object> config = ollamaConfig();
            config.remove("model_name");
            saveOllamaConfig(config);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "Local model selection cleared"));
        });
    }

    @GetMapping("/local-models/server/update")
    public Mono<ResponseEntity<?>> localModelServerUpdateStatus() {
        return Mono.just(ResponseEntity.ok(Map.of("has_update", false)));
    }

    @GetMapping("/local-models/server/download")
    public Mono<ResponseEntity<?>> localModelServerDownloadStatus() {
        return Mono.just(ResponseEntity.ok(idleDownload(null)));
    }

    @PostMapping("/local-models/server/download")
    public Mono<ResponseEntity<?>> localModelServerDownload(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("status", "skipped", "message", "Ollama is managed outside Java")));
    }

    @DeleteMapping("/local-models/server/download")
    public Mono<ResponseEntity<?>> cancelLocalModelServerDownload() {
        return Mono.just(ResponseEntity.ok(Map.of("status", "cancelled", "message", "No server download is running")));
    }

    @GetMapping("/local-models/models")
    public Mono<ResponseEntity<?>> localModels() {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(localModelList())).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> localModelDownload(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String modelName = body != null ? stringValue(body.get("model_name"), "") : "";
            String source = body != null ? stringValue(body.get("source"), "auto") : "auto";
            if (modelName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "model_name is required"));
            }
            if (isDownloadActive(localModelDownload)) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Another model download is already running"));
            }
            localModelDownload.clear();
            localModelDownload.putAll(downloadStatus("pending", modelName, 0L, null, source, null));
            CompletableFuture.runAsync(() -> pullOllamaModel(modelName, source));
            return ResponseEntity.ok(Map.of("status", "started", "message", "Model download started"));
        });
    }

    @GetMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> localModelDownloadStatus() {
        return Mono.just(ResponseEntity.ok(localModelDownload.isEmpty() ? idleDownload(null) : new LinkedHashMap<>(localModelDownload)));
    }

    @DeleteMapping("/local-models/models/download")
    public Mono<ResponseEntity<?>> cancelLocalModelDownload() {
        localModelDownload.clear();
        localModelDownload.putAll(downloadStatus("cancelled", null, 0L, null, null, null));
        return Mono.just(ResponseEntity.ok(Map.of("status", "cancelled", "message", "Download cancellation requested")));
    }

    @DeleteMapping("/local-models/models/{modelId}")
    public Mono<ResponseEntity<?>> deleteLocalModel(@PathVariable String modelId) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/delete"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(Map.of("model", modelId))))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return ResponseEntity.ok(Map.of("status", "deleted", "message", "Model deleted"));
            }
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", response.body()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> localModelConfigPayload() {
        Map<String, Object> config = ollamaConfig();
        return Map.of(
                "max_context_length", integerValue(config.get("max_context_length"), 8192),
                "port", integerValue(config.get("port"), 11434)
        );
    }

    private Map<String, Object> localServerStatus() {
        boolean available = ollamaAvailable();
        Map<String, Object> config = ollamaConfig();
        return Map.of(
                "available", available,
                "installable", false,
                "installed", available,
                "port", integerValue(config.get("port"), 11434),
                "model_name", stringOrNull(config.get("model_name")),
                "message", available ? "Ollama is reachable" : "Ollama is not reachable at " + ollamaBaseUrl()
        );
    }

    private List<Map<String, Object>> localModelList() {
        Set<String> downloaded = ollamaTags();
        LinkedHashMap<String, Map<String, Object>> models = new LinkedHashMap<>();
        for (String id : List.of("llama3.1", "qwen2.5", "deepseek-r1", "mistral", "phi3")) {
            models.put(id, localModelInfo(id, id, 0L, downloaded.contains(id)));
        }
        for (String id : downloaded) {
            models.put(id, localModelInfo(id, id, 0L, true));
        }
        return new ArrayList<>(models.values());
    }

    private Map<String, Object> localModelInfo(String id, String name, long sizeBytes, boolean downloaded) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id);
        model.put("name", name);
        model.put("size_bytes", sizeBytes);
        model.put("downloaded", downloaded);
        model.put("source", "auto");
        return model;
    }

    private Set<String> ollamaTags() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Set.of();
            }
            Object parsed = JsonUtils.getMapper().readValue(response.body(), Object.class);
            Object rawModels = parsed instanceof Map<?, ?> map ? map.get("models") : null;
            if (!(rawModels instanceof List<?> list)) {
                return Set.of();
            }
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> model) {
                    String name = stringValue(model.get("name"), "");
                    if (!name.isBlank()) result.add(name);
                }
            }
            return result;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private boolean ollamaAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void pullOllamaModel(String modelName, String source) {
        try {
            localModelDownload.clear();
            localModelDownload.putAll(downloadStatus("downloading", modelName, 0L, null, source, null));
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/pull"))
                    .timeout(Duration.ofHours(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(Map.of("model", modelName, "stream", false))))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                localModelDownload.clear();
                localModelDownload.putAll(downloadStatus("completed", modelName, 0L, null, source, null));
            } else {
                localModelDownload.clear();
                localModelDownload.putAll(downloadStatus("failed", modelName, 0L, null, source, response.body()));
            }
        } catch (Exception e) {
            localModelDownload.clear();
            localModelDownload.putAll(downloadStatus("failed", modelName, 0L, null, source, e.getMessage()));
        }
    }

    private Map<String, Object> idleDownload(String modelName) {
        return downloadStatus("idle", modelName, 0L, null, null, null);
    }

    private Map<String, Object> downloadStatus(String status, String modelName, long downloadedBytes,
                                               Long totalBytes, String source, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("model_name", modelName);
        result.put("downloaded_bytes", downloadedBytes);
        result.put("total_bytes", totalBytes);
        result.put("speed_bytes_per_sec", 0L);
        result.put("source", source);
        result.put("error", error);
        result.put("local_path", null);
        return result;
    }

    private boolean isDownloadActive(Map<String, Object> state) {
        String status = stringValue(state.get("status"), "");
        return "pending".equals(status) || "downloading".equals(status) || "canceling".equals(status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ollamaConfig() {
        Object raw = providerConfigs().get("ollama");
        Map<String, Object> config = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        String baseUrl = stringValue(config.get("base_url"), "");
        if (baseUrl.isBlank()) {
            config.put("base_url", "http://127.0.0.1:" + integerValue(config.get("port"), 11434));
        }
        config.putIfAbsent("port", portFromBaseUrl(stringValue(config.get("base_url"), "")));
        config.putIfAbsent("max_context_length", 8192);
        return config;
    }

    private void saveOllamaConfig(Map<String, Object> config) {
        int port = integerValue(config.get("port"), 11434);
        config.put("port", port);
        config.put("base_url", "http://127.0.0.1:" + port);
        config.put("enabled", true);
        Map<String, Object> providers = providerConfigs();
        providers.put("ollama", config);
        JsonUtils.save(providerConfigFile(), providers);
    }

    private Map<String, Object> providerConfigs() {
        return new LinkedHashMap<>(JsonUtils.loadAsMap(providerConfigFile()));
    }

    private Path providerConfigFile() {
        return configManager.resolveHomeDir().resolve("providers.json");
    }

    private String ollamaBaseUrl() {
        return stringValue(ollamaConfig().get("base_url"), "http://127.0.0.1:11434").replaceAll("/+$", "");
    }

    private int portFromBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getPort() > 0 ? uri.getPort() : 11434;
        } catch (Exception ignored) {
            return 11434;
        }
    }

    private int integerValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null || String.valueOf(value).isBlank() ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String stringOrNull(Object value) {
        String text = stringValue(value, "");
        return text.isBlank() ? null : text;
    }

    @GetMapping("/market/providers")
    public Mono<ResponseEntity<?>> marketProviders() {
        return Mono.just(ResponseEntity.ok(List.of(Map.of(
                "key", "qwenpaw",
                "label", "melonPaw Local",
                "available", true,
                "reason", null,
                "supports_browse", true
        ))));
    }

    @GetMapping("/market/categories")
    public Mono<ResponseEntity<?>> marketCategories() {
        return Mono.just(ResponseEntity.ok(List.of(
                Map.of("id", "builtin", "label", "Built-in"),
                Map.of("id", "pool", "label", "Skill Pool")
        )));
    }

    @PostMapping("/market/search")
    public Mono<ResponseEntity<?>> marketSearch(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
            String query = stringValue(body != null ? body.get("query") : null, "").toLowerCase(Locale.ROOT);
            String category = stringValue(body != null ? body.get("category") : null, "");
            int limit = Math.max(1, Math.min(integerValue(body != null ? body.get("limit") : null, 10), 50));
            int page = marketPage(body);
            List<Map<String, Object>> all = localMarketResults(query, category);
            int from = Math.min((page - 1) * limit, all.size());
            int to = Math.min(from + limit, all.size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("results", all.subList(from, to));
            result.put("errors", List.of());
            result.put("by_provider", Map.of("qwenpaw", Map.of(
                    "has_more", to < all.size(),
                    "total", all.size()
            )));
            return ResponseEntity.ok(result);
        });
    }

    private List<Map<String, Object>> localMarketResults(String query, String category) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        if (category.isBlank() || "builtin".equals(category)) {
            for (Map<String, Object> skill : builtinSkillService.listBuiltinSources()) {
                Map<String, Object> item = marketSkill(skill, "builtin", "builtin://" + stringValue(skill.get("name"), ""));
                if (matchesMarketQuery(item, query)) results.add(item);
            }
        }
        if (category.isBlank() || "pool".equals(category)) {
            for (Map<String, Object> skill : skillService.listPoolSkills()) {
                Map<String, Object> item = marketSkill(skill, "pool", "pool://" + stringValue(skill.get("name"), ""));
                if (matchesMarketQuery(item, query)) results.add(item);
            }
        }
        return results;
    }

    private Map<String, Object> marketSkill(Map<String, Object> skill, String kind, String sourceUrl) {
        String name = stringValue(skill.get("name"), "");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", "qwenpaw");
        item.put("slug", kind + "/" + name);
        item.put("name", name);
        item.put("description", stringValue(skill.get("description"), null));
        item.put("source_url", sourceUrl);
        item.put("version", stringValue(skill.get("version_text"), null));
        item.put("author", null);
        item.put("icon_url", null);
        item.put("stats", Map.of("kind", kind));
        return item;
    }

    private boolean matchesMarketQuery(Map<String, Object> item, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return stringValue(item.get("name"), "").toLowerCase(Locale.ROOT).contains(query)
                || stringValue(item.get("description"), "").toLowerCase(Locale.ROOT).contains(query);
    }

    private int marketPage(Map<String, Object> body) {
        Object raw = body != null ? body.get("provider_pages") : null;
        if (raw instanceof Map<?, ?> pages) {
            return Math.max(1, integerValue(pages.get("qwenpaw"), 1));
        }
        return 1;
    }

    @GetMapping("/debug/logs")
    public Mono<ResponseEntity<?>> debugLogs() {
        return Mono.just(ResponseEntity.ok(Map.of("logs", List.of(), "enabled", false)));
    }

    @GetMapping("/agent-stats")
    public Mono<ResponseEntity<Object>> agentStats(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestParam(value = "start_date", required = false) String startDate,
                                                   @RequestParam(value = "end_date", required = false) String endDate) {
        String id = AgentRequestSupport.agentId(agentId);
        return Mono.fromCallable(() -> ResponseEntity.ok((Object) agentStatsService.getSummary(id, startDate, endDate)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/")
    public Mono<ResponseEntity<?>> root() {
        return Mono.just(ResponseEntity.ok(Map.of("ok", true, "service", "melonpaw-java")));
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
        String requestId = body != null ? stringValue(body.get("request_id"), "") : "";
        boolean accepted = approvalService.decidePendingApproval(sessionId, requestId, true, agentId);
        return Mono.just(ResponseEntity.ok(Map.of("success", accepted, "message", accepted ? "approved" : "approval not found")));
    }

    @PostMapping("/approval/deny")
    public Mono<ResponseEntity<?>> denyCommand(@RequestBody(required = false) Map<String, Object> body,
                                               @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                               @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        String sessionId = body != null ? stringValue(body.get("session_id"), "default") : "default";
        String requestId = body != null ? stringValue(body.get("request_id"), "") : "";
        boolean accepted = approvalService.decidePendingApproval(sessionId, requestId, false, agentId);
        return Mono.just(ResponseEntity.ok(Map.of("success", accepted, "message", accepted ? "denied" : "approval not found")));
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
    public Mono<ResponseEntity<?>> getCodingMode(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.just(codingModeState(agentId));
    }

    @PostMapping("/coding-mode")
    public Mono<ResponseEntity<?>> postCodingMode(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return updateCodingModeState(agentId, body);
    }

    @PutMapping("/coding-mode")
    public Mono<ResponseEntity<?>> putCodingMode(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return updateCodingModeState(agentId, body);
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

    private String normalizeApprovalLevel(String value) {
        String normalized = value == null ? "AUTO" : value.trim().toUpperCase();
        return switch (normalized) {
            case "OFF", "AUTO", "SMART", "STRICT" -> normalized;
            default -> "AUTO";
        };
    }

    private Mono<ResponseEntity<?>> updateCodingModeState(String agentId, Map<String, Object> body) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        if (config == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        config.getCodingMode().setEnabled(enabled);
        configManager.save();
        multiAgentManager.reload(id);
        return Mono.just(ResponseEntity.ok(codingModePayload(id, config)));
    }

    private ResponseEntity<?> codingModeState(String agentId) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(codingModePayload(id, config));
    }

    private Map<String, Object> codingModePayload(String agentId, AgentConfig config) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", config.getCodingMode() != null && config.getCodingMode().isEnabled());
        payload.put("project_dir", null);
        payload.put("agent_id", agentId);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> storedToolGuard(AgentConfig config) {
        if (config == null || config.getFrontendRunningConfig() == null) return Map.of();
        Object raw = config.getFrontendRunningConfig().get("tool_guard_config");
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
