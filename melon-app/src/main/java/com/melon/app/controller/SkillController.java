package com.melon.app.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.app.service.BuiltinSkillService;
import com.melon.app.service.SecuritySettingsService;
import com.melon.app.service.SkillService;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.util.JsonUtils;
import com.melon.core.security.ScanResult;
import com.melon.core.security.SkillScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * 技能管理 API. 对应 Python /api/skills.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);
    private static final long MAX_REMOTE_SKILL_ZIP_BYTES = 100L * 1024L * 1024L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final SkillService skillService;
    private final BuiltinSkillService builtinSkillService;
    private final SecuritySettingsService securitySettingsService;
    private final SkillScanner skillScanner;
    private final MultiAgentManager multiAgentManager;
    private final Map<String, Map<String, Object>> hubInstallTasks = new ConcurrentHashMap<>();

    public SkillController(SkillService skillService, BuiltinSkillService builtinSkillService,
                           SecuritySettingsService securitySettingsService,
                           MultiAgentManager multiAgentManager) {
        this.skillService = skillService;
        this.builtinSkillService = builtinSkillService;
        this.securitySettingsService = securitySettingsService;
        this.multiAgentManager = multiAgentManager;
        this.skillScanner = new SkillScanner();
    }

    /**
     * 列出所有技能.
     */
    @GetMapping
    public Mono<ResponseEntity<?>> listSkills(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> {
            try {
                var skills = skillService.listSkills(agentId).stream().map(this::skillSpec).toList();
                return ResponseEntity.ok(skills);
            } catch (Exception e) {
                log.error("Failed to list skills", e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<?>> refreshSkills(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return listSkills(agentId);
    }

    @GetMapping("/workspaces")
    public Mono<ResponseEntity<?>> listSkillWorkspaces(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(configuredAgents().stream()
                .map(id -> Map.of(
                        "agent_id", id,
                        "agent_name", agentName(id),
                        "workspace_dir", skillService.workspaceDir(id).toString(),
                        "skills", skillService.listSkills(id).stream().map(this::skillSpec).toList()
                ))
                .toList()));
    }

    @GetMapping("/pool")
    public Mono<ResponseEntity<?>> listSkillPool(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return Mono.fromCallable(() -> ResponseEntity.ok(
                skillService.listPoolSkills().stream().map(this::poolSkillSpec).toList()
        ));
    }

    @PostMapping("/pool/refresh")
    public Mono<ResponseEntity<?>> refreshSkillPool(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return listSkillPool(agentId);
    }

    @GetMapping("/hub/search")
    public Mono<ResponseEntity<?>> searchHubSkills(@RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return Mono.fromCallable(() -> {
            String needle = stringValue(q, "").toLowerCase(Locale.ROOT);
            int max = Math.max(1, Math.min(limit, 100));
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> source : builtinSkillService.listBuiltinSources()) {
                Map<String, Object> item = hubCandidate(source, "builtin");
                if (matchesHubQuery(item, needle)) {
                    results.add(item);
                }
                if (results.size() >= max) {
                    return ResponseEntity.ok(results);
                }
            }
            for (Map<String, Object> source : skillService.listPoolSkills()) {
                Map<String, Object> item = hubCandidate(source, "pool");
                if (matchesHubQuery(item, needle)) {
                    results.add(item);
                }
                if (results.size() >= max) {
                    break;
                }
            }
            return ResponseEntity.ok(results);
        });
    }

    @PostMapping("/hub/install/start")
    public Mono<ResponseEntity<?>> startHubInstall(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String taskId = UUID.randomUUID().toString();
            Map<String, Object> task = installTask(taskId, body, "importing", null, null);
            hubInstallTasks.put(taskId, task);
            try {
                Map<String, Object> result = installLocalHubSkill(safeAgentId(agentId), body, true);
                task = installTask(taskId, body, "completed", null, result);
            } catch (Exception e) {
                task = installTask(taskId, body, "failed", e.getMessage(), null);
            }
            hubInstallTasks.put(taskId, task);
            return ResponseEntity.ok(task);
        });
    }

    @GetMapping("/hub/install/status/{taskId}")
    public Mono<ResponseEntity<?>> hubInstallStatus(@PathVariable String taskId) {
        return Mono.just(ResponseEntity.ok(hubInstallTasks.getOrDefault(taskId,
                installTask(taskId, null, "failed", "Unknown install task", null))));
    }

    @PostMapping("/hub/install/cancel/{taskId}")
    public Mono<ResponseEntity<?>> cancelHubInstall(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> task = hubInstallTasks.get(taskId);
            if (task == null || "completed".equals(task.get("status")) || "failed".equals(task.get("status"))) {
                return ResponseEntity.ok(Map.of("task_id", taskId, "status", task == null ? "cancelled" : task.get("status")));
            }
            Map<String, Object> cancelled = installTask(taskId, task, "cancelled", null, null);
            hubInstallTasks.put(taskId, cancelled);
            return ResponseEntity.ok(cancelled);
        });
    }

    @PostMapping("/pool/import")
    public Mono<ResponseEntity<?>> importPoolFromHub(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> responseForLocalImport(importLocalHubSkillToPool(body)))
                .onErrorResume(e -> Mono.just(badRequest(e)));
    }

    @PostMapping("/pool/create")
    public Mono<ResponseEntity<?>> createSkillPoolSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                        @RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String name = body.get("name");
            String content = body.getOrDefault("content", "");
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("detail", "Skill name is required"));
            }
            skillService.createPoolSkill(name, content);
            return ResponseEntity.ok(Map.of("created", true, "status", "created", "name", name));
        });
    }

    @PutMapping("/pool/save")
    public Mono<ResponseEntity<?>> saveSkillPoolSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                      @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String name = stringValue(body.get("name"), "");
            String sourceName = stringValue(body.get("source_name"), name);
            String content = stringValue(body.get("content"), "");
            if (name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("detail", "name is required"));
            }
            if (!sourceName.equals(name) && !sourceName.isBlank()) {
                skillService.deletePoolSkill(sourceName);
            }
            skillService.createPoolSkill(name, content);
            return ResponseEntity.ok(Map.of("success", true, "mode", sourceName.equals(name) ? "edit" : "rename", "name", name));
        });
    }

    @PostMapping("/batch-enable")
    public Mono<ResponseEntity<?>> batchEnable(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @RequestBody List<String> names) {
        return Mono.fromCallable(() -> {
            Map<String, Object> results = batchSetEnabled(agentId, names, true);
            reload(agentId);
            return ResponseEntity.ok(Map.of("results", results));
        });
    }

    @PostMapping("/batch-disable")
    public Mono<ResponseEntity<?>> batchDisable(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody List<String> names) {
        return Mono.fromCallable(() -> {
            Map<String, Object> results = batchSetEnabled(agentId, names, false);
            reload(agentId);
            return ResponseEntity.ok(Map.of("results", results));
        });
    }

    @PostMapping("/batch-delete")
    public Mono<ResponseEntity<?>> batchDelete(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @RequestBody List<String> names) {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of("results", deleteMany(agentId, names))));
    }

    @PostMapping("/pool/batch-delete")
    public Mono<ResponseEntity<?>> batchDeletePool(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody List<String> names) {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of("results", deleteManyPool(names))));
    }

    @GetMapping("/pool/builtin-sources")
    public Mono<ResponseEntity<?>> poolBuiltinSources() {
        return Mono.fromCallable(() -> ResponseEntity.ok(builtinSkillService.listBuiltinSources()));
    }

    @GetMapping("/pool/builtin-notice")
    public Mono<ResponseEntity<?>> poolBuiltinNotice() {
        return Mono.fromCallable(() -> ResponseEntity.ok(builtinSkillService.builtinNotice()));
    }

    @PostMapping("/pool/import-builtin")
    public Mono<ResponseEntity<?>> importBuiltinPoolSkills(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> responseForImport(builtinSkillService.importBuiltins(
                builtinImports(body),
                Boolean.TRUE.equals(body != null ? body.get("overwrite_conflicts") : null)
        ))).onErrorResume(e -> Mono.just(badRequest(e)));
    }

    @PostMapping("/pool/upload")
    public Mono<ResponseEntity<?>> uploadWorkspaceSkillToPool(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String workspaceId = stringValue(body != null ? body.get("workspace_id") : null, "default");
            String skillName = stringValue(body != null ? body.get("skill_name") : null, "");
            boolean overwrite = Boolean.TRUE.equals(body != null ? body.get("overwrite") : null);
            if (skillName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("detail", "skill_name is required"));
            }
            return ResponseEntity.ok(skillService.uploadWorkspaceSkillToPool(workspaceId, skillName, overwrite));
        });
    }

    @PostMapping("/pool/download")
    public Mono<ResponseEntity<?>> downloadSkillPoolSkill(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String skillName = stringValue(body != null ? body.get("skill_name") : null, "");
            if (skillName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("detail", "skill_name is required"));
            }
            boolean overwrite = Boolean.TRUE.equals(body != null ? body.get("overwrite") : null);
            List<String> targets = targetWorkspaceIds(body);
            List<Map<String, Object>> downloaded = new ArrayList<>();
            List<Map<String, Object>> conflicts = new ArrayList<>();
            for (String target : targets) {
                Map<String, Object> result = skillService.downloadPoolSkillToWorkspace(target, skillName, overwrite);
                if (Boolean.TRUE.equals(result.get("conflict"))) {
                    conflicts.add(Map.of("skill_name", skillName, "workspace_id", target, "reason", "exists"));
                } else {
                    downloaded.add(result);
                }
            }
            targets.forEach(multiAgentManager::reload);
            return ResponseEntity.ok(Map.of("downloaded", downloaded, "conflicts", conflicts));
        });
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadSkill(@RequestPart("file") FilePart filePart,
                                               @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @RequestParam(defaultValue = "true") boolean enable,
                                               @RequestParam(defaultValue = "") String targetName,
                                               @RequestParam(defaultValue = "") String renameMap) {
        return uploadZip(filePart, path -> {
            Map<String, Object> result = skillService.importWorkspaceSkillZip(
                    safeAgentId(agentId), path, enable, targetName, parseRenameMap(renameMap));
            reload(agentId);
            return result;
        });
    }

    @PostMapping(value = "/pool/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadPoolSkillZip(@RequestPart("file") FilePart filePart,
                                                      @RequestParam(defaultValue = "") String targetName,
                                                      @RequestParam(defaultValue = "") String renameMap) {
        return uploadZip(filePart, path -> skillService.importPoolSkillZip(
                path, targetName, parseRenameMap(renameMap)));
    }

    @PostMapping(value = "/ai/optimize/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> optimizeSkillStream(@RequestBody(required = false) Map<String, Object> body) {
        return Flux.just(ServerSentEvent.builder(Map.<String, Object>of("done", true)).build());
    }

    /**
     * 获取单个技能内容.
     */
    @GetMapping("/{name}")
    public Mono<ResponseEntity<?>> getSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                            @PathVariable String name) {
        return Mono.fromCallable(() -> {
            try {
                String content = skillService.getSkillContent(agentId, name);
                return ResponseEntity.ok(Map.of("name", name, "content", content));
            } catch (java.nio.file.NoSuchFileException e) {
                return ResponseEntity.notFound().build();
            } catch (Exception e) {
                log.error("Failed to get skill: {}", name, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 创建新技能. 先进行安全扫描.
     */
    @PostMapping
    public Mono<ResponseEntity<?>> createSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                String name = body.get("name");
                String content = body.getOrDefault("content", "");

                if (name == null || name.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Skill name is required"));
                }

                ScanResult scanResult = scanSkill(name, content);
                if (securitySettingsService.shouldBlockSkill(name, scanResult)) {
                    securitySettingsService.recordBlockedSkill(name, scanResult, "blocked");
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Skill failed security scan",
                            "issues", scanResult.getIssues()
                    ));
                }
                recordWarnedSkill(name, scanResult);

                skillService.createSkill(agentId, name, content);
                reload(agentId);
                return ResponseEntity.ok(Map.of("created", true, "status", "created", "name", name));
            } catch (Exception e) {
                log.error("Failed to create skill", e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    @PutMapping("/save")
    public Mono<ResponseEntity<?>> saveSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                             @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String name = stringValue(body.get("name"), "");
            String sourceName = stringValue(body.get("source_name"), name);
            String content = stringValue(body.get("content"), "");
            if (name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("detail", "name is required"));
            }
            ScanResult scanResult = scanSkill(name, content);
            if (securitySettingsService.shouldBlockSkill(name, scanResult)) {
                securitySettingsService.recordBlockedSkill(name, scanResult, "blocked");
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Skill failed security scan",
                        "issues", scanResult.getIssues()
                ));
            }
            recordWarnedSkill(name, scanResult);
            if (!sourceName.equals(name) && !sourceName.isBlank()) {
                try {
                    skillService.deleteSkill(agentId, sourceName);
                } catch (Exception ignored) {
                }
            }
            skillService.createSkill(agentId, name, content);
            reload(agentId);
            return ResponseEntity.ok(Map.of("success", true, "mode", sourceName.equals(name) ? "edit" : "rename", "name", name));
        });
    }

    @PostMapping("/{name}/enable")
    public Mono<ResponseEntity<?>> enableSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @PathVariable String name) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = skillService.enableSkill(agentId, name);
            reload(agentId);
            return ResponseEntity.ok(result);
        });
    }

    @PostMapping("/{name}/disable")
    public Mono<ResponseEntity<?>> disableSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @PathVariable String name) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = skillService.disableSkill(agentId, name);
            reload(agentId);
            return ResponseEntity.ok(result);
        });
    }

    @PutMapping("/{name}/channels")
    public Mono<ResponseEntity<?>> updateSkillChannels(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @PathVariable String name,
                                                       @RequestBody List<String> channels) {
        return Mono.fromCallable(() -> {
            List<String> values = channels != null ? channels : List.of();
            skillService.setSkillChannels(agentId, name, values);
            reload(agentId);
            return ResponseEntity.ok(Map.of("updated", true, "channels", values));
        });
    }

    @PutMapping("/{name}/tags")
    public Mono<ResponseEntity<?>> updateSkillTags(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @PathVariable String name,
                                                   @RequestBody List<String> tags) {
        return Mono.fromCallable(() -> {
            List<String> values = tags != null ? tags : List.of();
            skillService.setSkillTags(agentId, name, values);
            reload(agentId);
            return ResponseEntity.ok(Map.of("updated", true, "tags", values));
        });
    }

    @GetMapping("/{name}/config")
    public Mono<ResponseEntity<?>> getSkillConfig(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                  @PathVariable String name) {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of("config", skillService.getSkillConfig(agentId, name))));
    }

    @PutMapping("/{name}/config")
    public Mono<ResponseEntity<?>> updateSkillConfig(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                     @PathVariable String name,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            skillService.setSkillConfig(agentId, name, configPayload(body));
            reload(agentId);
            return ResponseEntity.ok(Map.of("updated", true));
        });
    }

    @DeleteMapping("/{name}/config")
    public Mono<ResponseEntity<?>> deleteSkillConfig(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                     @PathVariable String name) {
        return Mono.fromCallable(() -> {
            skillService.clearSkillConfig(agentId, name);
            reload(agentId);
            return ResponseEntity.ok(Map.of("cleared", true));
        });
    }

    @GetMapping("/pool/{name}/config")
    public Mono<ResponseEntity<?>> getPoolSkillConfig(@PathVariable String name) {
        return Mono.fromCallable(() -> ResponseEntity.ok(Map.of("config", skillService.getPoolSkillConfig(name))));
    }

    @PutMapping("/pool/{name}/config")
    public Mono<ResponseEntity<?>> updatePoolSkillConfig(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            skillService.setPoolSkillConfig(name, configPayload(body));
            return ResponseEntity.ok(Map.of("updated", true));
        });
    }

    @DeleteMapping("/pool/{name}/config")
    public Mono<ResponseEntity<?>> deletePoolSkillConfig(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            skillService.clearPoolSkillConfig(name);
            return ResponseEntity.ok(Map.of("cleared", true));
        });
    }

    @PutMapping("/pool/{name}/tags")
    public Mono<ResponseEntity<?>> updatePoolSkillTags(@PathVariable String name, @RequestBody List<String> tags) {
        return Mono.fromCallable(() -> {
            List<String> values = tags != null ? tags : List.of();
            skillService.setPoolSkillTags(name, values);
            return ResponseEntity.ok(Map.of("updated", true, "tags", values));
        });
    }

    @PostMapping("/pool/{name}/update-builtin")
    public Mono<ResponseEntity<?>> updatePoolBuiltin(@PathVariable String name,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return Mono.<ResponseEntity<?>>fromCallable(() -> ResponseEntity.ok(
                builtinSkillService.updateBuiltin(name, stringValue(body != null ? body.get("language") : null, ""))
        )).onErrorResume(e -> Mono.just(badRequest(e)));
    }

    @DeleteMapping("/pool/{name}")
    public Mono<ResponseEntity<?>> deleteSkillPoolSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                        @PathVariable String name) {
        return Mono.fromCallable(() -> {
            skillService.deletePoolSkill(name);
            return ResponseEntity.ok(Map.of("deleted", true, "status", "deleted", "name", name));
        });
    }

    /**
     * 删除技能.
     */
    @DeleteMapping("/{name}")
    public Mono<ResponseEntity<?>> deleteSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @PathVariable String name) {
        return Mono.fromCallable(() -> {
            try {
                skillService.deleteSkill(agentId, name);
                reload(agentId);
                return ResponseEntity.ok(Map.of("deleted", true, "status", "deleted", "name", name));
            } catch (Exception e) {
                log.error("Failed to delete skill: {}", name, e);
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        });
    }

    private Map<String, Object> skillSpec(Map<String, Object> raw) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", stringValue(raw.get("name"), ""));
        spec.put("description", stringValue(raw.get("description"), ""));
        spec.put("path", stringValue(raw.get("path"), ""));
        spec.put("content", stringValue(raw.get("content"), ""));
        spec.put("enabled", Boolean.TRUE.equals(raw.get("enabled")));
        spec.put("source", stringValue(raw.get("source"), "local"));
        spec.put("tags", raw.getOrDefault("tags", List.of()));
        spec.put("channels", raw.getOrDefault("channels", List.of()));
        spec.put("config", raw.getOrDefault("config", Map.of()));
        spec.put("last_updated", stringValue(raw.get("last_updated"), ""));
        return spec;
    }

    private Map<String, Object> poolSkillSpec(Map<String, Object> raw) {
        Map<String, Object> spec = skillSpec(raw);
        spec.put("protected", Boolean.TRUE.equals(raw.get("protected")));
        spec.put("external", Boolean.TRUE.equals(raw.get("external")));
        spec.put("external_path", stringValue(raw.get("external_path"), ""));
        spec.put("version_text", stringValue(raw.get("version_text"), ""));
        spec.put("commit_text", stringValue(raw.get("commit_text"), ""));
        spec.put("builtin_language", stringValue(raw.get("builtin_language"), ""));
        spec.put("available_builtin_languages", raw.getOrDefault("available_builtin_languages", List.of()));
        Map<String, Object> sync = builtinSkillService.syncInfo(stringValue(raw.get("name"), ""), raw);
        spec.put("sync_status", stringValue(sync.get("sync_status"), ""));
        spec.put("latest_version_text", stringValue(sync.get("latest_version_text"), ""));
        if (sync.get("available_languages") instanceof List<?> languages && !languages.isEmpty()) {
            spec.put("available_builtin_languages", languages);
        }
        spec.put("installed_from", stringValue(raw.get("installed_from"), ""));
        return spec;
    }

    private Map<String, Object> batchSetEnabled(String agentId, List<String> names, boolean enabled) {
        Map<String, Object> results = new LinkedHashMap<>();
        if (names != null) {
            for (String name : names) {
                try {
                    results.put(name, enabled
                            ? skillService.enableSkill(agentId, name)
                            : skillService.disableSkill(agentId, name));
                } catch (Exception e) {
                    results.put(name, Map.of("success", false, "reason", e.getMessage()));
                }
            }
        }
        return results;
    }

    private Map<String, Object> deleteMany(String agentId, List<String> names) {
        Map<String, Object> results = new LinkedHashMap<>();
        if (names != null) {
            for (String name : names) {
                try {
                    skillService.deleteSkill(agentId, name);
                    results.put(name, Map.of("success", true));
                } catch (Exception e) {
                    results.put(name, Map.of("success", false, "reason", e.getMessage()));
                }
            }
        }
        reload(agentId);
        return results;
    }

    private Map<String, Object> deleteManyPool(List<String> names) {
        Map<String, Object> results = new LinkedHashMap<>();
        if (names != null) {
            for (String name : names) {
                try {
                    skillService.deletePoolSkill(name);
                    results.put(name, Map.of("success", true));
                } catch (Exception e) {
                    results.put(name, Map.of("success", false, "reason", e.getMessage()));
                }
            }
        }
        return results;
    }

    private Map<String, Object> hubCandidate(Map<String, Object> raw, String sourceType) {
        String name = stringValue(raw.get("name"), "");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("slug", sourceType + "/" + name);
        item.put("name", name);
        item.put("description", stringValue(raw.get("description"), ""));
        item.put("version", stringValue(raw.get("version_text"), ""));
        item.put("source_url", sourceType + "://" + name);
        item.put("source", sourceType);
        item.put("status", stringValue(raw.get("status"), ""));
        return item;
    }

    private boolean matchesHubQuery(Map<String, Object> item, String query) {
        if (query.isBlank()) {
            return true;
        }
        return stringValue(item.get("name"), "").toLowerCase(Locale.ROOT).contains(query)
                || stringValue(item.get("description"), "").toLowerCase(Locale.ROOT).contains(query);
    }

    private Map<String, Object> installLocalHubSkill(String agentId, Map<String, Object> body, boolean defaultEnable) throws Exception {
        Map<String, Object> poolResult = importLocalHubSkillToPool(body);
        if (hasConflicts(poolResult)) {
            return poolResult;
        }
        String name = importedSkillName(poolResult);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Workspace install requires a single-skill zip or target_name");
        }
        boolean enable = body == null ? defaultEnable : !Boolean.FALSE.equals(body.get("enable"));
        boolean overwrite = Boolean.TRUE.equals(body != null ? body.get("overwrite") : null);
        Map<String, Object> download = skillService.downloadPoolSkillToWorkspace(agentId, name, overwrite);
        if (Boolean.TRUE.equals(download.get("conflict"))) {
            return Map.of("installed", false, "conflicts", List.of(download), "name", name);
        }
        if (enable) {
            skillService.enableSkill(agentId, name);
        } else {
            skillService.disableSkill(agentId, name);
        }
        reload(agentId);
        Map<String, Object> result = new LinkedHashMap<>(poolResult);
        result.put("installed", true);
        result.put("enabled", enable);
        result.put("workspace_id", agentId);
        result.put("installed_from", poolResult.get("source_url"));
        return result;
    }

    private Map<String, Object> importLocalHubSkillToPool(Map<String, Object> body) throws Exception {
        String remoteUrl = firstNonBlank(body, "source_url", "bundle_url");
        if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
            return importRemoteHubSkillToPool(body, remoteUrl);
        }
        HubSource source = localHubSource(body);
        String targetName = stringValue(body != null ? body.get("target_name") : null, "");
        if (!targetName.isBlank() && !targetName.equals(source.name())) {
            throw new IllegalArgumentException("target_name rename is not supported for local skill sources");
        }
        if ("pool".equals(source.type())) {
            if (poolSkill(source.name()).isEmpty()) {
                throw new java.nio.file.NoSuchFileException("Pool skill not found: " + source.name());
            }
            return localImportResult(source.name(), source.url(), true);
        }

        boolean overwrite = Boolean.TRUE.equals(body != null ? body.get("overwrite_conflicts") : null)
                || Boolean.TRUE.equals(body != null ? body.get("overwrite") : null);
        Map<String, Object> imported = builtinSkillService.importBuiltins(
                List.of(Map.of("skill_name", source.name())),
                overwrite
        );
        if (hasConflicts(imported)) {
            Map<String, Object> result = new LinkedHashMap<>(imported);
            result.put("installed", false);
            result.put("name", source.name());
            result.put("source_url", source.url());
            return result;
        }
        return localImportResult(source.name(), source.url(), false);
    }

    private Map<String, Object> importRemoteHubSkillToPool(Map<String, Object> body, String sourceUrl) throws Exception {
        String targetName = stringValue(body != null ? body.get("target_name") : null, "");
        Path zip = downloadRemoteZip(sourceUrl);
        try {
            Map<String, Object> imported = new LinkedHashMap<>(skillService.importPoolSkillZip(zip, targetName, Map.of()));
            imported.put("source_url", sourceUrl);
            imported.put("installed_from", sourceUrl);
            String name = importedSkillName(imported);
            if (!name.isBlank()) {
                imported.put("name", name);
                imported.put("enabled", true);
                imported.put("installed", true);
            }
            if (hasConflicts(imported)) {
                imported.put("installed", false);
            }
            return imported;
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private HubSource localHubSource(Map<String, Object> body) throws Exception {
        String raw = firstNonBlank(body, "source_url", "bundle_url", "skill_name", "source_name", "name");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("bundle_url or skill_name is required");
        }
        if (raw.startsWith("builtin://")) {
            return requireKnownSource("builtin", raw.substring("builtin://".length()), raw);
        }
        if (raw.startsWith("builtin:")) {
            return requireKnownSource("builtin", raw.substring("builtin:".length()), raw);
        }
        if (raw.startsWith("pool://")) {
            return requireKnownSource("pool", raw.substring("pool://".length()), raw);
        }
        if (raw.startsWith("pool:")) {
            return requireKnownSource("pool", raw.substring("pool:".length()), raw);
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            throw new IllegalArgumentException("Remote skill hub import is not implemented");
        }
        if (builtinSource(raw).isPresent()) {
            return new HubSource("builtin", raw, "builtin://" + raw);
        }
        if (poolSkill(raw).isPresent()) {
            return new HubSource("pool", raw, "pool://" + raw);
        }
        throw new java.nio.file.NoSuchFileException("Local skill source not found: " + raw);
    }

    private HubSource requireKnownSource(String type, String name, String url) throws Exception {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Skill name is required");
        }
        if ("builtin".equals(type) && builtinSource(normalized).isPresent()) {
            return new HubSource(type, normalized, url);
        }
        if ("pool".equals(type) && poolSkill(normalized).isPresent()) {
            return new HubSource(type, normalized, url);
        }
        throw new java.nio.file.NoSuchFileException("Local skill source not found: " + normalized);
    }

    private Optional<Map<String, Object>> builtinSource(String name) throws Exception {
        for (Map<String, Object> source : builtinSkillService.listBuiltinSources()) {
            if (name.equals(stringValue(source.get("name"), ""))) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> poolSkill(String name) {
        for (Map<String, Object> source : skillService.listPoolSkills()) {
            if (name.equals(stringValue(source.get("name"), ""))) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    private String firstNonBlank(Map<String, Object> body, String... keys) {
        if (body == null) {
            return "";
        }
        for (String key : keys) {
            String value = stringValue(body.get(key), "").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Map<String, Object> localImportResult(String name, String sourceUrl, boolean alreadyInPool) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("installed", true);
        result.put("name", name);
        result.put("enabled", true);
        result.put("source_url", sourceUrl);
        result.put("installed_from", sourceUrl);
        result.put("already_in_pool", alreadyInPool);
        return result;
    }

    private String importedSkillName(Map<String, Object> result) {
        String name = stringValue(result != null ? result.get("name") : null, "");
        if (!name.isBlank()) {
            return name;
        }
        Object imported = result != null ? result.get("imported") : null;
        if (imported instanceof List<?> list && list.size() == 1) {
            return stringValue(list.get(0), "");
        }
        return "";
    }

    private Path downloadRemoteZip(String sourceUrl) throws Exception {
        URI uri = URI.create(sourceUrl);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("Skill download failed: HTTP " + response.statusCode());
        }
        long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (declared > MAX_REMOTE_SKILL_ZIP_BYTES) {
            throw new IllegalArgumentException("Skill archive is too large");
        }
        Path zip = Files.createTempFile("melon-skill-remote-", ".zip");
        boolean ok = false;
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(zip)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REMOTE_SKILL_ZIP_BYTES) {
                    throw new IllegalArgumentException("Skill archive is too large");
                }
                output.write(buffer, 0, read);
            }
            ok = true;
            return zip;
        } finally {
            if (!ok) {
                Files.deleteIfExists(zip);
            }
        }
    }

    private ResponseEntity<?> responseForLocalImport(Map<String, Object> result) {
        if (hasConflicts(result)) {
            return ResponseEntity.status(409).body(result);
        }
        return ResponseEntity.ok(result);
    }

    private boolean hasConflicts(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object conflicts = result.get("conflicts");
        return conflicts instanceof List<?> list && !list.isEmpty();
    }

    private Map<String, Object> installTask(String taskId, Map<String, Object> body, String status,
                                            String error, Map<String, Object> result) {
        long now = System.currentTimeMillis() / 1000L;
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("task_id", taskId);
        task.put("bundle_url", firstNonBlank(body, "bundle_url", "source_url"));
        task.put("version", stringValue(body != null ? body.get("version") : null, ""));
        task.put("enable", body == null || !Boolean.FALSE.equals(body.get("enable")));
        task.put("status", hasConflicts(result) ? "failed" : status);
        task.put("error", hasConflicts(result) ? "Skill import conflict" : error);
        task.put("result", result);
        task.put("created_at", numberValue(body != null ? body.get("created_at") : null, now));
        task.put("updated_at", now);
        return task;
    }

    private long numberValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private List<String> targetWorkspaceIds(Map<String, Object> body) {
        if (body != null && Boolean.TRUE.equals(body.get("all_workspaces"))) {
            return List.of("default");
        }
        Object targets = body != null ? body.get("targets") : null;
        if (targets instanceof List<?> list && !list.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    ids.add(stringValue(map.get("workspace_id"), "default"));
                }
            }
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        return List.of("default");
    }

    private String safeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    private void reload(String agentId) {
        multiAgentManager.reload(safeAgentId(agentId));
    }

    private List<String> configuredAgents() {
        return new ArrayList<>(skillService.agentIds());
    }

    private ScanResult scanSkill(String name, String content) {
        Map<String, Object> config = securitySettingsService.skillScanner();
        if ("off".equals(config.get("mode"))) {
            ScanResult result = new ScanResult(name);
            result.setSafe(true);
            return result;
        }
        return skillScanner.scan(name, content);
    }

    private void recordWarnedSkill(String name, ScanResult scanResult) {
        if (scanResult != null && (!scanResult.isSafe() || !scanResult.getWarnings().isEmpty())) {
            securitySettingsService.recordBlockedSkill(name, scanResult, "warned");
        }
    }

    private String agentName(String id) {
        var config = skillService.agentConfig(id);
        return config != null && config.getName() != null ? config.getName() : id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configPayload(Map<String, Object> body) {
        Object raw = body != null ? body.get("config") : null;
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> builtinImports(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object imports = body.get("imports");
        if (imports instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    result.add(copy);
                }
            }
            return result;
        }
        Object names = body.get("skill_names");
        if (names instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object name : list) {
                result.add(Map.of("skill_name", String.valueOf(name)));
            }
            return result;
        }
        return null;
    }

    private Mono<ResponseEntity<?>> uploadZip(FilePart filePart, ZipImporter importer) {
        return Mono.<ResponseEntity<?>>defer(() -> {
            Path path;
            try {
                path = Files.createTempFile("melon-skill-upload-", ".zip");
            } catch (Exception e) {
                return Mono.error(e);
            }
            return filePart.transferTo(path)
                    .then(Mono.fromCallable(() -> (ResponseEntity<?>) responseForImport(importer.importZip(path))))
                    .doFinally(signal -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }).onErrorResume(e -> {
            log.warn("Skill zip upload failed: {}", e.getMessage());
            ResponseEntity<?> response = ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
            return Mono.just(response);
        });
    }

    private ResponseEntity<?> responseForImport(Map<String, Object> result) {
        Object conflicts = result.get("conflicts");
        if (conflicts instanceof List<?> list && !list.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("detail", result));
        }
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<?> badRequest(Throwable e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage() == null ? "Bad request" : e.getMessage()));
    }

    private Map<String, String> parseRenameMap(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, Object> parsed = JsonUtils.getMapper().readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        Map<String, String> result = new LinkedHashMap<>();
        parsed.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    @FunctionalInterface
    private interface ZipImporter {
        Map<String, Object> importZip(Path path) throws Exception;
    }

    private record HubSource(String type, String name, String url) {}

}
