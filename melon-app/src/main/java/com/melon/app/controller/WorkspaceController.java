package com.melon.app.controller;

import com.melon.core.agent.MultiAgentManager;
import com.melon.core.agent.WorkspaceManager;
import com.melon.app.service.FileGuardService;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.config.LightContextConfig;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 工作区 API. 对应 Python /api/workspace.
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private final MultiAgentManager multiAgentManager;
    private final FileGuardService fileGuardService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WorkspaceController(ConfigManager configManager, WorkspaceManager workspaceManager,
                               MultiAgentManager multiAgentManager, FileGuardService fileGuardService) {
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
        this.multiAgentManager = multiAgentManager;
        this.fileGuardService = fileGuardService;
    }

    /**
     * 获取工作区信息 (路径、AGENTS.md 内容、目录列表).
     */
    @GetMapping
    public Mono<ResponseEntity<?>> getWorkspace(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            try {
                String id = AgentRequestSupport.agentId(agentId);
                AgentConfig agentConfig = configManager.getConfig().getAgent(id);
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = configManager.resolveWorkspaceDir(id);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("path", workspaceDir.toString());

                // Read AGENTS.md
                Path agentsMd = workspaceDir.resolve("AGENTS.md");
                if (Files.exists(agentsMd)) {
                    result.put("agents_md", Files.readString(agentsMd));
                } else {
                    result.put("agents_md", "");
                }

                // List top-level entries
                if (Files.exists(workspaceDir)) {
                    try (var stream = Files.list(workspaceDir)) {
                        result.put("entries", stream
                                .map(p -> p.getFileName().toString())
                                .sorted()
                                .toList());
                    }
                } else {
                    result.put("entries", java.util.List.of());
                }

                return ResponseEntity.ok(result);
            } catch (Exception e) {
                log.error("Failed to get workspace info", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 更新工作区 (主要更新 AGENTS.md 内容).
     */
    @PutMapping
    public Mono<ResponseEntity<?>> updateWorkspace(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                String id = AgentRequestSupport.agentId(agentId);
                AgentConfig agentConfig = configManager.getConfig().getAgent(id);
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = configManager.resolveWorkspaceDir(id);
                Files.createDirectories(workspaceDir);

                // Update AGENTS.md if provided
                String agentsMd = body.get("agents_md");
                if (agentsMd != null) {
                    Path agentsMdFile = workspaceDir.resolve("AGENTS.md");
                    Files.writeString(agentsMdFile, agentsMd);
                    log.info("AGENTS.md updated at {}", agentsMdFile);
                }

                // Update workspace dir if provided
                String newDir = body.get("path");
                if (newDir != null && !newDir.isBlank()) {
                    agentConfig.setWorkspaceDir(newDir);
                    configManager.save();
                    log.info("Workspace dir updated to: {}", newDir);
                }

                return ResponseEntity.ok(Map.of("status", "updated"));
            } catch (IOException e) {
                log.error("Failed to update workspace", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 初始化工作区 (创建目录结构和默认文件).
     */
    @PostMapping("/init")
    public Mono<ResponseEntity<?>> initWorkspace(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            try {
                String id = AgentRequestSupport.agentId(agentId);
                AgentConfig agentConfig = configManager.getConfig().getAgent(id);
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = configManager.resolveWorkspaceDir(id);
                workspaceManager.initWorkspace(workspaceDir);

                return ResponseEntity.ok(Map.of("status", "initialized", "path", workspaceDir.toString()));
            } catch (Exception e) {
                log.error("Failed to init workspace", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    @GetMapping("/running-config")
    public Mono<ResponseEntity<?>> getRunningConfig(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            AgentConfig agentConfig = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
            if (agentConfig == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            if (agentConfig.getFrontendRunningConfig() != null) {
                result.putAll(agentConfig.getFrontendRunningConfig());
            }
            result.put("max_iters", agentConfig.getRunning().getMaxIters());
            result.put("auto_continue_on_text_only", agentConfig.getRunning().isAutoContinueOnTextOnly());
            result.put("shell_command_timeout", agentConfig.getRunning().getShellCommandTimeout());
            result.put("shell_command_executable", agentConfig.getRunning().getShellCommandExecutable() != null
                    ? agentConfig.getRunning().getShellCommandExecutable()
                    : "");
            result.put("llm_retry_enabled", agentConfig.getRunning().isLlmRetryEnabled());
            result.put("llm_max_retries", agentConfig.getRunning().getLlmMaxRetries());
            result.put("llm_backoff_base", agentConfig.getRunning().getLlmBackoffBase());
            result.put("llm_backoff_cap", agentConfig.getRunning().getLlmBackoffCap());
            result.put("llm_max_concurrent", agentConfig.getRunning().getLlmMaxConcurrent());
            result.put("llm_max_qpm", agentConfig.getRunning().getLlmMaxQpm());
            result.put("llm_rate_limit_pause", agentConfig.getRunning().getLlmRateLimitPause());
            result.put("llm_rate_limit_jitter", agentConfig.getRunning().getLlmRateLimitJitter());
            result.put("llm_acquire_timeout", agentConfig.getRunning().getLlmAcquireTimeout());
            result.put("history_max_length", agentConfig.getRunning().getHistoryMaxLength());
            result.putIfAbsent("context_manager_backend", "light");
            result.put("memory_manager_backend", agentConfig.getMemoryManagerBackend());
            result.put("approval_level", agentConfig.getApproval().getLevel());
            result.put("light_context_config", lightContextPayload(agentConfig));
            result.putIfAbsent("reme_light_memory_config", defaultReMeLightMemoryConfig());
            result.putIfAbsent("auto_title_config", Map.of("enabled", true, "timeout_seconds", 30));
            return ResponseEntity.ok(result);
        });
    }

    @PutMapping("/running-config")
    public Mono<ResponseEntity<?>> updateRunningConfig(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String id = AgentRequestSupport.agentId(agentId);
            AgentConfig agentConfig = configManager.getConfig().getAgent(id);
            if (agentConfig == null) {
                return ResponseEntity.notFound().build();
            }
            if (body != null) {
                agentConfig.setFrontendRunningConfig(new LinkedHashMap<>(body));
                applyRunningConfig(agentConfig, body);
            }
            String approvalLevel = body != null && body.get("approval_level") != null
                    ? String.valueOf(body.get("approval_level"))
                    : "";
            if (!approvalLevel.isBlank()) {
                agentConfig.getApproval().setLevel(normalizeApprovalLevel(approvalLevel));
            }
            configManager.save();
            multiAgentManager.reload(id);
            return ResponseEntity.ok(body != null ? body : Map.of());
        });
    }

    private String normalizeApprovalLevel(String value) {
        String normalized = value == null ? "AUTO" : value.trim().toUpperCase();
        return switch (normalized) {
            case "OFF", "AUTO", "SMART", "STRICT" -> normalized;
            default -> "AUTO";
        };
    }

    private void applyRunningConfig(AgentConfig agentConfig, Map<String, Object> body) {
        if (body.get("max_iters") != null) {
            agentConfig.getRunning().setMaxIters((int) numberValue(body.get("max_iters"), agentConfig.getRunning().getMaxIters()));
        }
        if (body.get("auto_continue_on_text_only") != null) {
            agentConfig.getRunning().setAutoContinueOnTextOnly(booleanValue(body.get("auto_continue_on_text_only"), agentConfig.getRunning().isAutoContinueOnTextOnly()));
        }
        if (body.get("shell_command_timeout") != null) {
            agentConfig.getRunning().setShellCommandTimeout(numberValue(body.get("shell_command_timeout"), agentConfig.getRunning().getShellCommandTimeout()));
        }
        if (body.get("shell_command_executable") != null) {
            String executable = String.valueOf(body.get("shell_command_executable")).trim();
            agentConfig.getRunning().setShellCommandExecutable(executable.isBlank() ? null : executable);
        }
        if (body.get("llm_retry_enabled") != null) {
            agentConfig.getRunning().setLlmRetryEnabled(booleanValue(body.get("llm_retry_enabled"), agentConfig.getRunning().isLlmRetryEnabled()));
        }
        if (body.get("llm_max_retries") != null) {
            agentConfig.getRunning().setLlmMaxRetries((int) numberValue(body.get("llm_max_retries"), agentConfig.getRunning().getLlmMaxRetries()));
        }
        if (body.get("llm_backoff_base") != null) {
            agentConfig.getRunning().setLlmBackoffBase(numberValue(body.get("llm_backoff_base"), agentConfig.getRunning().getLlmBackoffBase()));
        }
        if (body.get("llm_backoff_cap") != null) {
            agentConfig.getRunning().setLlmBackoffCap(numberValue(body.get("llm_backoff_cap"), agentConfig.getRunning().getLlmBackoffCap()));
        }
        if (body.get("llm_max_concurrent") != null) {
            agentConfig.getRunning().setLlmMaxConcurrent((int) numberValue(body.get("llm_max_concurrent"), agentConfig.getRunning().getLlmMaxConcurrent()));
        }
        if (body.get("llm_max_qpm") != null) {
            agentConfig.getRunning().setLlmMaxQpm((int) numberValue(body.get("llm_max_qpm"), agentConfig.getRunning().getLlmMaxQpm()));
        }
        if (body.get("llm_rate_limit_pause") != null) {
            agentConfig.getRunning().setLlmRateLimitPause(numberValue(body.get("llm_rate_limit_pause"), agentConfig.getRunning().getLlmRateLimitPause()));
        }
        if (body.get("llm_rate_limit_jitter") != null) {
            agentConfig.getRunning().setLlmRateLimitJitter(numberValue(body.get("llm_rate_limit_jitter"), agentConfig.getRunning().getLlmRateLimitJitter()));
        }
        if (body.get("llm_acquire_timeout") != null) {
            agentConfig.getRunning().setLlmAcquireTimeout(numberValue(body.get("llm_acquire_timeout"), agentConfig.getRunning().getLlmAcquireTimeout()));
        }
        if (body.get("history_max_length") != null) {
            agentConfig.getRunning().setHistoryMaxLength((int) numberValue(body.get("history_max_length"), agentConfig.getRunning().getHistoryMaxLength()));
        }
        if (body.get("memory_manager_backend") != null) {
            String backend = String.valueOf(body.get("memory_manager_backend")).trim();
            agentConfig.setMemoryManagerBackend(backend.isBlank() ? "remelight" : backend);
        }
        if (body.get("light_context_config") instanceof Map<?, ?> lightRaw) {
            Map<String, Object> light = toStringMap(lightRaw);
            LightContextConfig lightConfig = JsonUtils.getMapper().convertValue(light, LightContextConfig.class);
            if (!light.containsKey("context_compact_config")) {
                lightConfig.setContextCompactConfig(agentConfig.getContextCompact());
            }
            normalizeLightContext(agentConfig, lightConfig);
            agentConfig.setLightContextConfig(lightConfig);
            agentConfig.setContextCompact(lightConfig.getContextCompactConfig());
            if (light.get("context_compact_config") instanceof Map<?, ?> compactRaw) {
                Map<String, Object> compact = toStringMap(compactRaw);
                if (compact.get("enabled") != null) {
                    agentConfig.getContextCompact().setEnabled(booleanValue(compact.get("enabled"), agentConfig.getContextCompact().isEnabled()));
                }
                if (compact.get("compact_threshold_ratio") != null) {
                    agentConfig.getContextCompact().setCompactThresholdRatio(numberValue(compact.get("compact_threshold_ratio"), agentConfig.getContextCompact().getCompactThresholdRatio()));
                }
                if (compact.get("reserve_threshold_ratio") != null) {
                    agentConfig.getContextCompact().setReserveThresholdRatio(numberValue(compact.get("reserve_threshold_ratio"), agentConfig.getContextCompact().getReserveThresholdRatio()));
                }
            }
        }
    }

    private Map<String, Object> lightContextPayload(AgentConfig agentConfig) {
        LightContextConfig light = agentConfig.getLightContextConfig() != null
                ? agentConfig.getLightContextConfig()
                : new LightContextConfig();
        normalizeLightContext(agentConfig, light);
        return JsonUtils.getMapper().convertValue(light, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private void normalizeLightContext(AgentConfig agentConfig, LightContextConfig light) {
        if (light.getStrategy() == null || light.getStrategy().isBlank()) light.setStrategy("scroll");
        if (!"native".equals(light.getStrategy()) && !"scroll".equals(light.getStrategy())) light.setStrategy("scroll");
        if (light.getDialogPath() == null || light.getDialogPath().isBlank()) light.setDialogPath("dialog");
        if (light.getTokenCountEstimateDivisor() <= 0) light.setTokenCountEstimateDivisor(4.0);
        if (light.getContextCompactConfig() == null) light.setContextCompactConfig(agentConfig.getContextCompact());
        if (light.getToolResultPruningConfig() == null) light.setToolResultPruningConfig(new com.melon.core.config.ToolResultPruningConfig());
        if (light.getScrollConfig() == null) light.setScrollConfig(new com.melon.core.config.ScrollContextConfig());
        if (light.getScrollConfig().getDbFilename() == null || light.getScrollConfig().getDbFilename().isBlank()) {
            light.getScrollConfig().setDbFilename("history.db");
        }
    }

    private Map<String, Object> defaultReMeLightMemoryConfig() {
        return Map.of(
                "summarize_when_compact", false,
                "auto_memory_interval", 0,
                "dream_cron", "",
                "auto_memory_search_config", Map.of("enabled", false, "max_results", 5, "persist_to_context", false),
                "embedding_model_config", Map.of(
                        "backend", "",
                        "api_key", "",
                        "base_url", "",
                        "model_name", "",
                        "dimensions", 1536,
                        "enable_cache", true,
                        "use_dimensions", false,
                        "max_cache_size", 1000,
                        "max_input_length", 8192,
                        "max_batch_size", 32
                ),
                "rebuild_memory_index_on_start", false,
                "enable_search_raw_log", false
        );
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    @GetMapping("/language")
    public Mono<ResponseEntity<?>> getLanguage(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig config = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        return Mono.just(ResponseEntity.ok(Map.of("language", config != null ? config.getLanguage() : "zh")));
    }

    @PutMapping("/language")
    public Mono<ResponseEntity<?>> updateLanguage(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                  @RequestBody Map<String, Object> body) {
        String id = AgentRequestSupport.agentId(agentId);
        AgentConfig config = configManager.getConfig().getAgent(id);
        String language = body != null ? String.valueOf(body.getOrDefault("language", "zh")) : "zh";
        if (config != null) {
            config.setLanguage(language);
            configManager.save();
            multiAgentManager.reload(id);
        }
        return Mono.just(ResponseEntity.ok(Map.of(
                "language", language,
                "copied_files", List.of()
        )));
    }

    @GetMapping("/audio-mode")
    public Mono<ResponseEntity<?>> getAudioMode() {
        return Mono.just(ResponseEntity.ok(Map.of("audio_mode", stringValue(transcriptionConfig().get("audio_mode"), "auto"))));
    }

    @PutMapping("/audio-mode")
    public Mono<ResponseEntity<?>> updateAudioMode(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = transcriptionConfig();
        String audioMode = stringValue(body != null ? body.get("audio_mode") : null, "auto");
        config.put("audio_mode", audioMode);
        saveTranscriptionConfig(config);
        return Mono.just(ResponseEntity.ok(Map.of("audio_mode", audioMode)));
    }

    @GetMapping("/transcription-providers")
    public Mono<ResponseEntity<?>> transcriptionProviders() {
        Map<String, Object> config = transcriptionConfig();
        return Mono.just(ResponseEntity.ok(Map.of(
                "providers", transcriptionProvidersPayload(config),
                "configured_provider_id", stringValue(config.get("provider_id"), "")
        )));
    }

    @PutMapping("/transcription-provider")
    public Mono<ResponseEntity<?>> updateTranscriptionProvider(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = transcriptionConfig();
        if (body != null) {
            for (String key : List.of("provider_id", "base_url", "api_key", "model", "name")) {
                if (body.containsKey(key)) config.put(key, body.get(key));
            }
        }
        String providerId = stringValue(config.get("provider_id"), "");
        saveTranscriptionConfig(config);
        return Mono.just(ResponseEntity.ok(Map.of("provider_id", providerId)));
    }

    @GetMapping("/transcription-provider-type")
    public Mono<ResponseEntity<?>> transcriptionProviderType() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "transcription_provider_type", stringValue(transcriptionConfig().get("transcription_provider_type"), "disabled")
        )));
    }

    @PutMapping("/transcription-provider-type")
    public Mono<ResponseEntity<?>> updateTranscriptionProviderType(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = transcriptionConfig();
        String type = stringValue(body != null ? body.get("transcription_provider_type") : null, "disabled");
        config.put("transcription_provider_type", type);
        saveTranscriptionConfig(config);
        return Mono.just(ResponseEntity.ok(Map.of("transcription_provider_type", type)));
    }

    @GetMapping("/local-whisper-status")
    public Mono<ResponseEntity<?>> localWhisperStatus() {
        return Mono.just(ResponseEntity.ok(Map.of("available", false, "ffmpeg_installed", false, "whisper_installed", false)));
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> transcribeDisabled(@RequestPart("file") FilePart filePart) {
        Map<String, Object> config = transcriptionConfig();
        if (!"whisper_api".equals(stringValue(config.get("transcription_provider_type"), "disabled"))) {
            return Mono.just(transcriptionDisabledResponse());
        }
        Path path;
        try {
            path = Files.createTempFile("melon-transcribe-", ".audio");
        } catch (IOException e) {
            return Mono.error(e);
        }
        return filePart.transferTo(path)
                .then(Mono.<ResponseEntity<?>>fromCallable(() -> forwardExternalTranscription(path, filePart.filename(), config)))
                .doFinally(signal -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
    }

    private ResponseEntity<?> forwardExternalTranscription(Path file, String filename, Map<String, Object> transcriptionConfig) throws Exception {
        Map<String, Object> provider = transcriptionProviderConfig(transcriptionConfig);
        String baseUrl = stringValue(provider.get("base_url"), "");
        String apiKey = stringValue(provider.get("api_key"), "");
        String model = stringValue(provider.get("model"), "whisper-1");
        if (baseUrl.isBlank()) {
            return transcriptionDisabledResponse("External transcription base_url is not configured");
        }
        String boundary = "melon-" + java.util.UUID.randomUUID();
        byte[] body = transcriptionMultipartBody(file, filename, model, boundary);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(transcriptionEndpoint(baseUrl)))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ResponseEntity.status(response.statusCode()).body(Map.of(
                    "detail", Map.of("code", "TRANSCRIPTION_DISABLED", "message", response.body())
            ));
        }
        Map<String, Object> parsed = JsonUtils.fromJson(response.body(), Map.class);
        String text = parsed != null ? stringValue(parsed.get("text"), "") : "";
        return ResponseEntity.ok(Map.of("text", text));
    }

    private byte[] transcriptionMultipartBody(Path file, String filename, String model, String boundary) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeMultipartField(out, boundary, "model", model);
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFilename(filename) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(file));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void writeMultipartField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<?> transcriptionDisabledResponse() {
        return transcriptionDisabledResponse("Transcription is disabled. Configure an external Whisper-compatible provider.");
    }

    private ResponseEntity<?> transcriptionDisabledResponse(String message) {
        return ResponseEntity.status(503).body(Map.of(
                "detail", Map.of("code", "TRANSCRIPTION_DISABLED", "message", message)
        ));
    }

    private String transcriptionEndpoint(String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        if (base.endsWith("/audio/transcriptions")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/audio/transcriptions";
        }
        return base + "/v1/audio/transcriptions";
    }

    private List<Map<String, Object>> transcriptionProvidersPayload(Map<String, Object> transcriptionConfig) {
        List<Map<String, Object>> providers = new ArrayList<>();
        Map<String, Object> external = transcriptionProviderConfig(transcriptionConfig);
        if (!stringValue(external.get("base_url"), "").isBlank()) {
            providers.add(Map.of(
                    "id", "external",
                    "name", stringValue(external.get("name"), "External Whisper API"),
                    "available", true
            ));
        }
        Map<String, Object> openai = providerConfig("openai");
        if (!stringValue(openai.get("base_url"), "").isBlank() || !providerApiKey("openai", openai).isBlank()) {
            providers.add(Map.of(
                    "id", "openai",
                    "name", "OpenAI",
                    "available", !providerApiKey("openai", openai).isBlank()
            ));
        }
        return providers;
    }

    private Map<String, Object> transcriptionProviderConfig(Map<String, Object> transcriptionConfig) {
        String providerId = stringValue(transcriptionConfig.get("provider_id"), "external");
        Map<String, Object> result = new LinkedHashMap<>();
        if ("external".equals(providerId)) {
            result.put("base_url", transcriptionConfig.get("base_url"));
            result.put("api_key", transcriptionConfig.get("api_key"));
            result.put("model", stringValue(transcriptionConfig.get("model"), "whisper-1"));
            result.put("name", stringValue(transcriptionConfig.get("name"), "External Whisper API"));
            return result;
        }
        Map<String, Object> provider = providerConfig(providerId);
        result.put("base_url", stringValue(provider.get("base_url"), "https://api.openai.com/v1"));
        result.put("api_key", providerApiKey(providerId, provider));
        result.put("model", stringValue(transcriptionConfig.get("model"), "whisper-1"));
        result.put("name", providerId);
        return result;
    }

    private Map<String, Object> transcriptionConfig() {
        Map<String, Object> config = new LinkedHashMap<>(JsonUtils.loadAsMap(transcriptionConfigFile()));
        config.putIfAbsent("audio_mode", "auto");
        config.putIfAbsent("transcription_provider_type", "disabled");
        config.putIfAbsent("provider_id", "");
        config.putIfAbsent("model", "whisper-1");
        return config;
    }

    private void saveTranscriptionConfig(Map<String, Object> config) {
        JsonUtils.save(transcriptionConfigFile(), config);
    }

    private Path transcriptionConfigFile() {
        return configManager.resolveHomeDir().resolve("transcription.json");
    }

    private Map<String, Object> providerConfig(String providerId) {
        Object raw = JsonUtils.loadAsMap(providerConfigFile()).get(providerId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }

    private Path providerConfigFile() {
        return configManager.resolveHomeDir().resolve("providers.json");
    }

    private String providerApiKey(String providerId, Map<String, Object> provider) {
        String apiKey = stringValue(provider.get("api_key"), "");
        if (!apiKey.isBlank()) {
            return apiKey;
        }
        String env = switch (providerId) {
            case "openai" -> "OPENAI_API_KEY";
            default -> "";
        };
        return env.isBlank() ? "" : stringValue(System.getenv(env), "");
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "audio.webm" : filename;
        return value.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    @GetMapping("/files")
    public Mono<ResponseEntity<?>> listWorkspaceFiles(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(listWorkingMarkdownFiles(resolveWorkspace(agentId))));
    }

    @GetMapping("/files/{*fileName}")
    public Mono<ResponseEntity<?>> readWorkspaceFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                     @PathVariable String fileName) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveWorkspace(agentId);
            String cleanName = cleanCapturedPath(fileName);
            Path file = resolveWorkingMarkdownFile(workspace, cleanName);
            fileGuardService.assertAllowed(file);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("content", Files.readString(file).strip()));
        });
    }

    @PutMapping("/files/{*fileName}")
    public Mono<ResponseEntity<?>> writeWorkspaceFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                      @PathVariable String fileName,
                                                      @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveWorkspace(agentId);
            Files.createDirectories(workspace);
            String cleanName = cleanCapturedPath(fileName);
            Path file = resolveWorkingMarkdownFile(workspace, cleanName);
            fileGuardService.assertAllowed(file);
            Files.writeString(file, String.valueOf(body.getOrDefault("content", "")));
            return ResponseEntity.ok(Map.of("written", true));
        });
    }

    @GetMapping("/memory")
    public Mono<ResponseEntity<?>> listMemoryFiles() {
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/memory/{*memoryPath}")
    public Mono<ResponseEntity<?>> readMemoryFile(@PathVariable String memoryPath) {
        return Mono.just(ResponseEntity.ok(Map.of("name", cleanCapturedPath(memoryPath), "path", cleanCapturedPath(memoryPath), "content", "")));
    }

    @PutMapping("/memory/{*memoryPath}")
    public Mono<ResponseEntity<?>> writeMemoryFile(@PathVariable String memoryPath, @RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("path", cleanCapturedPath(memoryPath), "saved", false, "enabled", false)));
    }

    @GetMapping("/code-files")
    public Mono<ResponseEntity<?>> listCodeFiles(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(listFiles(resolveWorkspace(agentId), "")));
    }

    @GetMapping("/code-files/{*filePath}")
    public Mono<ResponseEntity<?>> readCodeFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @PathVariable String filePath) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveWorkspace(agentId);
            Path file = resolveInside(workspace, cleanCapturedPath(filePath));
            fileGuardService.assertAllowed(file);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of(
                    "path", workspace.relativize(file).toString().replace('\\', '/'),
                    "content", Files.readString(file)
            ));
        });
    }

    @PutMapping("/code-files/{*filePath}")
    public Mono<ResponseEntity<?>> writeCodeFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String filePath,
                                                 @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveWorkspace(agentId);
            Path file = resolveInside(workspace, cleanCapturedPath(filePath));
            fileGuardService.assertAllowed(file);
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.valueOf(body.getOrDefault("content", "")));
            return ResponseEntity.ok(Map.of(
                    "path", workspace.relativize(file).toString().replace('\\', '/'),
                    "size", Files.size(file)
            ));
        });
    }

    @GetMapping("/binary-files/{*filePath}")
    public Mono<ResponseEntity<?>> readBinaryFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                  @PathVariable String filePath) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveWorkspace(agentId);
            Path file = resolveInside(workspace, cleanCapturedPath(filePath));
            fileGuardService.assertAllowed(file);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new ByteArrayResource(Files.readAllBytes(file)));
        });
    }

    @GetMapping("/download")
    public Mono<ResponseEntity<?>> downloadWorkspace(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveWorkspace(agentId);
            Path zip = Files.createTempFile("qwenpaw-workspace-", ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
                if (Files.exists(workspace)) {
                    try (var stream = Files.walk(workspace)) {
                        for (Path path : stream.filter(p -> !Files.isDirectory(p)).toList()) {
                            String entryName = workspace.relativize(path).toString().replace('\\', '/');
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        }
                    }
                }
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"qwenpaw-workspace.zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new ByteArrayResource(Files.readAllBytes(zip)));
        });
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadWorkspaceFile(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @RequestPart("file") FilePart filePart) {
        Path workspace = resolveWorkspace(agentId);
        Path target = resolveInside(workspace, filePart.filename());
        fileGuardService.assertAllowed(target);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            return Mono.just(ResponseEntity.internalServerError().body(Map.of("detail", e.getMessage())));
        }
        return filePart.transferTo(target)
                .thenReturn(ResponseEntity.ok(Map.of("success", true, "message", "uploaded", "path", filePart.filename())));
    }

    @GetMapping("/system-prompt-files")
    public Mono<ResponseEntity<?>> systemPromptFiles(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        AgentConfig agentConfig = configManager.getConfig().getAgent(AgentRequestSupport.agentId(agentId));
        return Mono.just(ResponseEntity.ok(agentConfig.getSystemPromptFiles()));
    }

    @PutMapping("/system-prompt-files")
    public Mono<ResponseEntity<?>> updateSystemPromptFiles(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                           @RequestBody List<String> files) {
        return Mono.fromCallable(() -> {
            String id = AgentRequestSupport.agentId(agentId);
            AgentConfig agentConfig = configManager.getConfig().getAgent(id);
            if (agentConfig == null) {
                return ResponseEntity.notFound().build();
            }
            List<String> cleanFiles = files == null ? List.of() : files.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> Path.of(value).getFileName().toString())
                    .filter(value -> value.endsWith(".md"))
                    .distinct()
                    .toList();
            agentConfig.setSystemPromptFiles(cleanFiles);
            configManager.save();
            multiAgentManager.reload(id);
            return ResponseEntity.ok(cleanFiles);
        });
    }

    @GetMapping(value = "/watch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> watch(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        Flux<ServerSentEvent<Map<String, Object>>> stream = Flux.create(sink -> {
            Path root = resolveWorkspace(agentId);
            try {
                Files.createDirectories(root);
                WatchService watchService = FileSystems.getDefault().newWatchService();
                registerAll(root, watchService);
                Thread thread = new Thread(() -> {
                    try {
                        sink.next(ServerSentEvent.builder(Map.<String, Object>of("type", "ready")).event("ready").build());
                        while (!sink.isCancelled()) {
                            WatchKey key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                            if (key == null) continue;
                            Path dir = (Path) key.watchable();
                            List<Map<String, Object>> changes = new ArrayList<>();
                            for (WatchEvent<?> event : key.pollEvents()) {
                                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                                Path changed = dir.resolve((Path) event.context()).normalize();
                                if (Files.isDirectory(changed) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                                    try {
                                        registerAll(changed, watchService);
                                    } catch (IOException ignored) {
                                    }
                                }
                                if (!changed.startsWith(root)) continue;
                                changes.add(Map.of(
                                        "change", changeName(event.kind()),
                                        "path", root.relativize(changed).toString().replace('\\', '/')
                                ));
                            }
                            key.reset();
                            if (!changes.isEmpty()) {
                                sink.next(ServerSentEvent.builder(Map.<String, Object>of("type", "file_change", "events", changes)).event("file_change").build());
                            }
                        }
                    } catch (Exception e) {
                        if (!sink.isCancelled()) sink.error(e);
                    } finally {
                        try {
                            watchService.close();
                        } catch (IOException ignored) {
                        }
                    }
                }, "workspace-watch-" + AgentRequestSupport.agentId(agentId));
                thread.setDaemon(true);
                thread.start();
                sink.onCancel(() -> {
                    try {
                        watchService.close();
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException e) {
                sink.error(e);
            }
        });
        Flux<ServerSentEvent<Map<String, Object>>> pings = Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.builder(Map.<String, Object>of("type", "ping")).event("ping").build());
        return stream.mergeWith(pings);
    }

    private Path resolveWorkspace(String agentId) {
        return configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId));
    }

    private Path resolveInside(Path root, String path) {
        Path normalized = root.resolve(path).normalize();
        if (!normalized.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Invalid path");
        }
        return normalized;
    }

    private String cleanCapturedPath(String path) {
        if (path == null) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private List<Map<String, Object>> listFiles(Path root, String subdir) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.exists(root)) {
            return result;
        }
        try (var stream = Files.list(root.resolve(subdir).normalize())) {
            stream.forEach(path -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", path.getFileName().toString());
                info.put("filename", root.relativize(path).toString().replace('\\', '/'));
                info.put("path", root.relativize(path).toString().replace('\\', '/'));
                info.put("is_directory", Files.isDirectory(path));
                try {
                    info.put("size", Files.size(path));
                    info.put("modified", Files.getLastModifiedTime(path).toMillis());
                    info.put("modified_time", Files.getLastModifiedTime(path).toInstant().toString());
                } catch (IOException ignored) {
                    info.put("size", 0);
                    info.put("modified", 0);
                    info.put("modified_time", "1970-01-01T00:00:00Z");
                }
                result.add(info);
            });
        }
        return result;
    }

    private List<Map<String, Object>> listWorkingMarkdownFiles(Path root) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.exists(root)) return result;
        try (var stream = Files.list(root)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(this::lastModifiedMillis).reversed())
                    .toList()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("filename", path.getFileName().toString());
                info.put("path", path.toString());
                info.put("size", Files.size(path));
                info.put("created_time", Files.getLastModifiedTime(path).toInstant().toString());
                info.put("modified_time", Files.getLastModifiedTime(path).toInstant().toString());
                result.add(info);
            }
        }
        return result;
    }

    private Path resolveWorkingMarkdownFile(Path workspace, String name) {
        String fileName = Path.of(name == null || name.isBlank() ? "AGENTS.md" : name).getFileName().toString();
        if (!fileName.endsWith(".md")) fileName += ".md";
        Path file = workspace.resolve(fileName).normalize();
        if (!file.startsWith(workspace.normalize())) {
            throw new IllegalArgumentException("Invalid path");
        }
        return file;
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void registerAll(Path root, WatchService watchService) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
            }
        }
    }

    private String changeName(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) return "added";
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) return "deleted";
        return "modified";
    }
}
