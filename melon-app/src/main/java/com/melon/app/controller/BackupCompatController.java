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
 * QwenPaw frontend-compatible backup API aliases.
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
            detail.put("files", List.of());
            detail.put("manifest", Map.of("available", false));
            return ResponseEntity.ok(detail);
        });
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> createBackupStream() {
        return Mono.fromCallable(() -> {
                    Path backup = backupService.createBackup();
                    return backupMeta(backup);
                })
                .flux()
                .flatMap(meta -> Flux.just(
                        ServerSentEvent.builder(Map.<String, Object>of("type", "progress", "message", "Creating backup", "progress", 50)).build(),
                        ServerSentEvent.builder(Map.<String, Object>of("type", "done", "meta", meta)).build()
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
            return ResponseEntity.ok(Map.of("restored", true, "id", id));
        });
    }

    @PostMapping("/delete")
    public Mono<ResponseEntity<?>> deleteBackups(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            List<String> ids = stringList(body.get("ids"));
            List<String> deleted = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String id : ids) {
                Path backup = findBackup(id);
                if (backup == null) {
                    missing.add(id);
                    continue;
                }
                if (backupService.deleteBackup(backup)) {
                    deleted.add(id);
                }
            }
            return ResponseEntity.ok(Map.of("deleted", deleted, "missing", missing));
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
        return Mono.just(ResponseEntity.status(501).body(Map.of(
                "detail", "Backup import is not implemented in Java compatibility mode",
                "enabled", false
        )));
    }

    private Map<String, Object> backupMeta(Path backup) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String name = backup.getFileName().toString();
        meta.put("id", name);
        meta.put("name", name.replaceFirst("\\.zip$", ""));
        meta.put("description", "");
        meta.put("filename", name);
        meta.put("path", backup.toString());
        try {
            meta.put("size", Files.size(backup));
            meta.put("created_at", Files.getLastModifiedTime(backup).toInstant().toString());
        } catch (Exception ignored) {
            meta.put("size", 0);
            meta.put("created_at", Instant.EPOCH.toString());
        }
        meta.put("status", "completed");
        meta.put("trusted", true);
        meta.put("scope", Map.of(
                "include_agents", true,
                "include_global_config", true,
                "include_secrets", true,
                "include_skill_pool", true
        ));
        meta.put("agent_count", 0);
        meta.put("signature", "");
        meta.put("accepted_via_trust", false);
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
}
