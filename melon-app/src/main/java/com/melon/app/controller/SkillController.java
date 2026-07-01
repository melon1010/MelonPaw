/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.service.SkillService;
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

import java.util.*;

/**
 * 技能管理 API. 对应 Python /api/skills.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillService skillService;
    private final SkillScanner skillScanner;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
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
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @PostMapping("/hub/install/start")
    public Mono<ResponseEntity<?>> startHubInstall(@RequestBody(required = false) Map<String, Object> body) {
        String taskId = UUID.randomUUID().toString();
        return Mono.just(ResponseEntity.ok(Map.of(
                "task_id", taskId,
                "status", "completed",
                "installed", false,
                "enabled", false,
                "detail", "Remote skill hub install is not implemented in Java compatibility mode"
        )));
    }

    @GetMapping("/hub/install/status/{taskId}")
    public Mono<ResponseEntity<?>> hubInstallStatus(@PathVariable String taskId) {
        return Mono.just(ResponseEntity.ok(Map.of("task_id", taskId, "status", "completed", "installed", false)));
    }

    @PostMapping("/hub/install/cancel/{taskId}")
    public Mono<ResponseEntity<?>> cancelHubInstall(@PathVariable String taskId) {
        return Mono.just(ResponseEntity.ok(Map.of("task_id", taskId, "status", "cancelled")));
    }

    @PostMapping("/pool/import")
    public Mono<ResponseEntity<?>> importPoolFromHub(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.status(501).body(Map.of("detail", "Remote skill import is not implemented")));
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
    public Mono<ResponseEntity<?>> batchEnable(@RequestBody List<String> names) {
        return Mono.just(ResponseEntity.ok(Map.of("results", batchResult(names, true))));
    }

    @PostMapping("/batch-disable")
    public Mono<ResponseEntity<?>> batchDisable(@RequestBody List<String> names) {
        return Mono.just(ResponseEntity.ok(Map.of("results", batchResult(names, true))));
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
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/pool/builtin-notice")
    public Mono<ResponseEntity<?>> poolBuiltinNotice() {
        return Mono.just(ResponseEntity.ok(Map.of("available", false, "updates", List.of(), "message", "")));
    }

    @PostMapping("/pool/import-builtin")
    public Mono<ResponseEntity<?>> importBuiltinPoolSkills(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "imported", List.of(),
                "updated", List.of(),
                "unchanged", List.of(),
                "conflicts", List.of()
        )));
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
            return ResponseEntity.ok(Map.of("downloaded", downloaded, "conflicts", conflicts));
        });
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadSkill(@RequestPart("file") FilePart filePart) {
        return Mono.just(ResponseEntity.status(501).body(Map.of("detail", "Skill zip upload is not implemented")));
    }

    @PostMapping(value = "/pool/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadPoolSkillZip(@RequestPart("file") FilePart filePart) {
        return Mono.just(ResponseEntity.status(501).body(Map.of("detail", "Skill pool zip upload is not implemented")));
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

                // Security scan before creating
                ScanResult scanResult = skillScanner.scan(name, content);
                if (!scanResult.isSafe()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Skill failed security scan",
                            "issues", scanResult.getIssues()
                    ));
                }

                skillService.createSkill(agentId, name, content);
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
            if (!sourceName.equals(name) && !sourceName.isBlank()) {
                try {
                    skillService.deleteSkill(agentId, sourceName);
                } catch (Exception ignored) {
                }
            }
            skillService.createSkill(agentId, name, content);
            return ResponseEntity.ok(Map.of("success", true, "mode", sourceName.equals(name) ? "edit" : "rename", "name", name));
        });
    }

    @PostMapping("/{name}/enable")
    public Mono<ResponseEntity<?>> enableSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                               @PathVariable String name) {
        return Mono.fromCallable(() -> ResponseEntity.ok(skillService.enableSkill(agentId, name)));
    }

    @PostMapping("/{name}/disable")
    public Mono<ResponseEntity<?>> disableSkill(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @PathVariable String name) {
        return Mono.fromCallable(() -> ResponseEntity.ok(skillService.disableSkill(agentId, name)));
    }

    @PutMapping("/{name}/channels")
    public Mono<ResponseEntity<?>> updateSkillChannels(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @PathVariable String name,
                                                       @RequestBody List<String> channels) {
        return Mono.fromCallable(() -> {
            List<String> values = channels != null ? channels : List.of();
            skillService.setSkillChannels(agentId, name, values);
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
            return ResponseEntity.ok(Map.of("updated", true));
        });
    }

    @DeleteMapping("/{name}/config")
    public Mono<ResponseEntity<?>> deleteSkillConfig(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                     @PathVariable String name) {
        return Mono.fromCallable(() -> {
            skillService.clearSkillConfig(agentId, name);
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
    public Mono<ResponseEntity<?>> updatePoolBuiltin(@PathVariable String name) {
        return Mono.just(ResponseEntity.ok(Map.of("updated", false, "name", name, "available", false)));
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
        spec.put("protected", false);
        spec.put("external", false);
        spec.put("external_path", "");
        spec.put("sync_status", "");
        spec.put("latest_version_text", "");
        spec.put("installed_from", "");
        return spec;
    }

    private Map<String, Object> batchResult(List<String> names, boolean success) {
        Map<String, Object> results = new LinkedHashMap<>();
        if (names != null) {
            for (String name : names) {
                results.put(name, Map.of("success", success));
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

    private List<String> configuredAgents() {
        return new ArrayList<>(skillService.agentIds());
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

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
}
