/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public WorkspaceController(ConfigManager configManager) {
        this.configManager = configManager;
        this.workspaceManager = new WorkspaceManager();
    }

    /**
     * 获取工作区信息 (路径、AGENTS.md 内容、目录列表).
     */
    @GetMapping
    public Mono<ResponseEntity<?>> getWorkspace() {
        return Mono.fromCallable(() -> {
            try {
                AgentConfig agentConfig = configManager.getConfig().getAgent("default");
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = configManager.resolveWorkspaceDir("default");
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
    public Mono<ResponseEntity<?>> updateWorkspace(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                AgentConfig agentConfig = configManager.getConfig().getAgent("default");
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = configManager.resolveWorkspaceDir("default");
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
    public Mono<ResponseEntity<?>> initWorkspace() {
        return Mono.fromCallable(() -> {
            try {
                AgentConfig agentConfig = configManager.getConfig().getAgent("default");
                if (agentConfig == null) {
                    return ResponseEntity.notFound().build();
                }

                Path workspaceDir = configManager.resolveWorkspaceDir("default");
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
    public Mono<ResponseEntity<?>> getRunningConfig() {
        return Mono.fromCallable(() -> {
            AgentConfig agentConfig = configManager.getConfig().getAgent("default");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("max_iters", agentConfig.getRunning().getMaxIters());
            result.put("auto_continue_on_text_only", true);
            result.put("shell_command_timeout", agentConfig.getRunning().getShellCommandTimeout());
            result.put("shell_command_executable", "");
            result.put("llm_retry_enabled", agentConfig.getRunning().isLlmRetryEnabled());
            result.put("llm_max_retries", agentConfig.getRunning().getLlmMaxRetries());
            result.put("llm_backoff_base", agentConfig.getRunning().getLlmBackoffBase());
            result.put("llm_backoff_cap", agentConfig.getRunning().getLlmBackoffCap());
            result.put("llm_max_concurrent", agentConfig.getRunning().getLlmMaxConcurrent());
            result.put("llm_max_qpm", 0);
            result.put("llm_rate_limit_pause", 0);
            result.put("llm_rate_limit_jitter", 0);
            result.put("llm_acquire_timeout", 0);
            result.put("history_max_length", 0);
            result.put("context_manager_backend", "native");
            result.put("memory_manager_backend", "native");
            result.put("approval_level", agentConfig.getApproval().getLevel());
            result.put("light_context_config", Map.of(
                    "strategy", "native",
                    "dialog_path", "",
                    "token_count_estimate_divisor", 4,
                    "context_compact_config", Map.of("enabled", agentConfig.getContextCompact().isEnabled(),
                            "compact_threshold_ratio", agentConfig.getContextCompact().getCompactThresholdRatio(),
                            "reserve_threshold_ratio", agentConfig.getContextCompact().getReserveThresholdRatio()),
                    "tool_result_pruning_config", Map.of("enabled", false,
                            "pruning_recent_n", 0,
                            "pruning_old_msg_max_bytes", 0,
                            "pruning_recent_msg_max_bytes", 0,
                            "offload_retention_days", 0,
                            "exempt_file_extensions", List.of(),
                            "exempt_tool_names", List.of())
            ));
            result.put("reme_light_memory_config", Map.of(
                    "summarize_when_compact", false,
                    "auto_memory_interval", 0,
                    "dream_cron", "",
                    "auto_memory_search_config", Map.of("enabled", false, "max_results", 0, "persist_to_context", false),
                    "embedding_model_config", Map.of(),
                    "rebuild_memory_index_on_start", false,
                    "enable_search_raw_log", false
            ));
            result.put("auto_title_config", Map.of("enabled", false, "timeout_seconds", 0));
            return ResponseEntity.ok(result);
        });
    }

    @PutMapping("/running-config")
    public Mono<ResponseEntity<?>> updateRunningConfig(@RequestBody Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(body));
    }

    @GetMapping("/language")
    public Mono<ResponseEntity<?>> getLanguage() {
        return Mono.just(ResponseEntity.ok(Map.of("language", "zh")));
    }

    @PutMapping("/language")
    public Mono<ResponseEntity<?>> updateLanguage(@RequestBody Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "language", body != null ? body.getOrDefault("language", "zh") : "zh",
                "copied_files", List.of()
        )));
    }

    @GetMapping("/audio-mode")
    public Mono<ResponseEntity<?>> getAudioMode() {
        return Mono.just(ResponseEntity.ok(Map.of("audio_mode", "disabled")));
    }

    @PutMapping("/audio-mode")
    public Mono<ResponseEntity<?>> updateAudioMode(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("audio_mode", body != null ? body.getOrDefault("audio_mode", "disabled") : "disabled")));
    }

    @GetMapping("/transcription-providers")
    public Mono<ResponseEntity<?>> transcriptionProviders() {
        return Mono.just(ResponseEntity.ok(Map.of("providers", List.of(), "configured_provider_id", "")));
    }

    @PutMapping("/transcription-provider")
    public Mono<ResponseEntity<?>> updateTranscriptionProvider(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("provider_id", body != null ? body.getOrDefault("provider_id", "") : "")));
    }

    @GetMapping("/transcription-provider-type")
    public Mono<ResponseEntity<?>> transcriptionProviderType() {
        return Mono.just(ResponseEntity.ok(Map.of("transcription_provider_type", "disabled")));
    }

    @PutMapping("/transcription-provider-type")
    public Mono<ResponseEntity<?>> updateTranscriptionProviderType(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of("transcription_provider_type", body != null ? body.getOrDefault("transcription_provider_type", "disabled") : "disabled")));
    }

    @GetMapping("/local-whisper-status")
    public Mono<ResponseEntity<?>> localWhisperStatus() {
        return Mono.just(ResponseEntity.ok(Map.of("available", false, "ffmpeg_installed", false, "whisper_installed", false)));
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> transcribeDisabled(@RequestPart("file") FilePart filePart) {
        return Mono.just(ResponseEntity.status(501).body(Map.of(
                "detail", Map.of("code", "TRANSCRIPTION_DISABLED", "message", "Transcription is disabled in Java compatibility mode")
        )));
    }

    @GetMapping("/files")
    public Mono<ResponseEntity<?>> listWorkspaceFiles() {
        return Mono.fromCallable(() -> ResponseEntity.ok(listFiles(resolveDefaultWorkspace(), "")));
    }

    @GetMapping("/files/{*fileName}")
    public Mono<ResponseEntity<?>> readWorkspaceFile(@PathVariable String fileName) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveDefaultWorkspace();
            String cleanName = cleanCapturedPath(fileName);
            Path file = workspace.resolve(cleanName).normalize();
            if (!file.startsWith(workspace) || !Files.exists(file) || Files.isDirectory(file)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of(
                    "name", file.getFileName().toString(),
                    "path", workspace.relativize(file).toString().replace('\\', '/'),
                    "content", Files.readString(file)
            ));
        });
    }

    @PutMapping("/files/{*fileName}")
    public Mono<ResponseEntity<?>> writeWorkspaceFile(@PathVariable String fileName, @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveDefaultWorkspace();
            Files.createDirectories(workspace);
            String cleanName = cleanCapturedPath(fileName);
            Path file = workspace.resolve(cleanName).normalize();
            if (!file.startsWith(workspace)) {
                return ResponseEntity.badRequest().body(Map.of("detail", "Invalid path"));
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.valueOf(body.getOrDefault("content", "")));
            return ResponseEntity.ok(Map.of("path", workspace.relativize(file).toString().replace('\\', '/'), "saved", true));
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
    public Mono<ResponseEntity<?>> listCodeFiles() {
        return Mono.fromCallable(() -> ResponseEntity.ok(listFiles(resolveDefaultWorkspace(), "")));
    }

    @GetMapping("/code-files/{*filePath}")
    public Mono<ResponseEntity<?>> readCodeFile(@PathVariable String filePath) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveDefaultWorkspace();
            Path file = resolveInside(workspace, cleanCapturedPath(filePath));
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
    public Mono<ResponseEntity<?>> writeCodeFile(@PathVariable String filePath, @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveDefaultWorkspace();
            Path file = resolveInside(workspace, cleanCapturedPath(filePath));
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.valueOf(body.getOrDefault("content", "")));
            return ResponseEntity.ok(Map.of(
                    "path", workspace.relativize(file).toString().replace('\\', '/'),
                    "size", Files.size(file)
            ));
        });
    }

    @GetMapping("/binary-files/{*filePath}")
    public Mono<ResponseEntity<?>> readBinaryFile(@PathVariable String filePath) {
        return Mono.fromCallable(() -> {
            Path workspace = resolveDefaultWorkspace();
            Path file = resolveInside(workspace, cleanCapturedPath(filePath));
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new ByteArrayResource(Files.readAllBytes(file)));
        });
    }

    @GetMapping("/download")
    public Mono<ResponseEntity<?>> downloadWorkspace() {
        return Mono.fromCallable(() -> {
            Path workspace = resolveDefaultWorkspace();
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
    public Mono<ResponseEntity<?>> uploadWorkspaceFile(@RequestPart("file") FilePart filePart) {
        Path workspace = resolveDefaultWorkspace();
        Path target = resolveInside(workspace, filePart.filename());
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            return Mono.just(ResponseEntity.internalServerError().body(Map.of("detail", e.getMessage())));
        }
        return filePart.transferTo(target)
                .thenReturn(ResponseEntity.ok(Map.of("success", true, "message", "uploaded", "path", filePart.filename())));
    }

    @GetMapping("/system-prompt-files")
    public Mono<ResponseEntity<?>> systemPromptFiles() {
        AgentConfig agentConfig = configManager.getConfig().getAgent("default");
        return Mono.just(ResponseEntity.ok(agentConfig.getSystemPromptFiles()));
    }

    @PutMapping("/system-prompt-files")
    public Mono<ResponseEntity<?>> updateSystemPromptFiles(@RequestBody List<String> files) {
        return Mono.just(ResponseEntity.ok(files != null ? files : List.of()));
    }

    @GetMapping("/watch")
    public Mono<ResponseEntity<?>> watch() {
        return Mono.just(ResponseEntity.ok(Map.of("enabled", false, "events", List.of())));
    }

    private Path resolveDefaultWorkspace() {
        return configManager.resolveWorkspaceDir("default");
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
}
