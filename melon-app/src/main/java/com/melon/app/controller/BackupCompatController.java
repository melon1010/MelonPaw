package com.melon.app.controller;

import com.melon.app.service.BackupService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * melonPaw frontend-compatible backup API aliases.
 */
@RestController
@RequestMapping("/api/backups")
public class BackupCompatController {

    private final BackupService backupService;

    public BackupCompatController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> listBackups() {
        return Mono.fromCallable(() -> ResponseEntity.ok(backupService.listBackups().stream()
                .map(this::backupMeta)
                .toList()));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<?>> getBackup(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Path backup = findBackup(id);
            if (backup == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> detail = new LinkedHashMap<>(backupMeta(backup));
            detail.put("workspace_stats", Map.of());
            return ResponseEntity.ok(detail);
        });
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> createBackupStream(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
                    Map<String, Object> scope = mapValue(body != null ? body.get("scope") : null);
                    Path backup = backupService.createBackup(
                            stringValue(body != null ? body.get("name") : null, ""),
                            stringValue(body != null ? body.get("description") : null, ""),
                            scope,
                            stringList(body != null ? body.get("agents") : null)
                    );
                    return backupMeta(backup);
                })
                .flux()
                .flatMap(meta -> Flux.just(
                        ServerSentEvent.builder(Map.<String, Object>of("type", "start", "total_agents", meta.get("agent_count"), "percent", 0)).build(),
                        ServerSentEvent.builder(Map.<String, Object>of("type", "saving", "percent", 80)).build(),
                        ServerSentEvent.builder(Map.<String, Object>of("type", "done", "meta", meta, "percent", 100)).build()
                ))
                .onErrorResume(error -> Flux.just(ServerSentEvent.builder(Map.<String, Object>of(
                        "type", "error",
                        "message", error.getMessage() != null ? error.getMessage() : "Backup failed"
                )).build()));
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<?>> restoreBackup(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Path backup = findBackup(id);
            if (backup == null) {
                return ResponseEntity.notFound().build();
            }
            backupService.restoreBackup(backup);
            return ResponseEntity.ok(Map.of("ok", true, "preserved_local_keys", List.of()));
        });
    }

    @PostMapping("/delete")
    public Mono<ResponseEntity<?>> deleteBackups(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            List<String> ids = stringList(body.get("ids"));
            List<String> deleted = new ArrayList<>();
            List<Map<String, Object>> failed = new ArrayList<>();
            for (String id : ids) {
                Path backup = findBackup(id);
                if (backup == null) {
                    failed.add(Map.of("id", id, "reason", "not_found"));
                    continue;
                }
                if (backupService.deleteBackup(backup)) {
                    deleted.add(id);
                } else {
                    failed.add(Map.of("id", id, "reason", "not_deleted"));
                }
            }
            return ResponseEntity.ok(Map.of("deleted", deleted, "failed", failed));
        });
    }

    @GetMapping("/{id}/export")
    public Mono<ResponseEntity<?>> exportBackup(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Path backup = findBackup(id);
            if (backup == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + backup.getFileName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new ByteArrayResource(Files.readAllBytes(backup)));
        });
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> importBackup(@RequestPart(value = "file", required = false) FilePart filePart,
                                                @RequestPart(value = "pending_token", required = false) String pendingToken) {
        if (filePart == null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("detail", "file is required")));
        }
        return Mono.<ResponseEntity<?>>defer(() -> {
            Path temp;
            try {
                temp = Files.createTempFile("melon-backup-import-", ".zip");
            } catch (Exception e) {
                return Mono.error(e);
            }
            return filePart.transferTo(temp)
                    .then(Mono.<ResponseEntity<?>>fromCallable(() -> {
                        Path imported = backupService.importBackup(temp, filePart.filename());
                        return ResponseEntity.ok(backupMeta(imported));
                    }))
                    .doFinally(signal -> {
                        try {
                            Files.deleteIfExists(temp);
                        } catch (Exception ignored) {
                        }
                    });
        }).onErrorResume(e -> Mono.just(ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()))));
    }

    private Map<String, Object> backupMeta(Path backup) {
        Map<String, Object> meta = new LinkedHashMap<>(backupService.readMeta(backup));
        String name = backup.getFileName().toString();
        meta.putIfAbsent("id", name);
        meta.putIfAbsent("name", name.replaceFirst("\\.zip$", ""));
        meta.putIfAbsent("description", "");
        meta.put("filename", name);
        meta.put("path", backup.toString());
        try {
            meta.put("size", Files.size(backup));
            meta.put("created_at", Files.getLastModifiedTime(backup).toInstant().toString());
        } catch (Exception ignored) {
            meta.put("size", 0);
            meta.put("created_at", Instant.EPOCH.toString());
        }
        meta.putIfAbsent("status", "completed");
        meta.putIfAbsent("trusted", true);
        meta.putIfAbsent("scope", Map.of(
                "include_agents", true,
                "include_global_config", true,
                "include_secrets", true,
                "include_skill_pool", true
        ));
        meta.putIfAbsent("agent_count", 0);
        meta.putIfAbsent("signature", "");
        meta.putIfAbsent("accepted_via_trust", false);
        return meta;
    }

    private Path findBackup(String id) {
        return backupService.listBackups().stream()
                .filter(path -> id.equals(path.getFileName().toString()) || id.equals(path.getFileName().toString().replaceFirst("\\.zip$", "")))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }
}
