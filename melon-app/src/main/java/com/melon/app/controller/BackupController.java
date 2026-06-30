/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 备份 API. 对应 Python /api/backup.
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private static final Logger log = LoggerFactory.getLogger(BackupController.class);

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    /**
     * 创建备份.
     */
    @PostMapping("/create")
    public Mono<ResponseEntity<?>> createBackup() {
        return Mono.fromCallable(() -> {
            try {
                Path backupFile = backupService.createBackup();
                return ResponseEntity.ok(Map.of(
                        "status", "created",
                        "path", backupFile.toString(),
                        "size", backupFile.toFile().length()
                ));
            } catch (Exception e) {
                log.error("Failed to create backup", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 从备份恢复.
     */
    @PostMapping("/restore")
    public Mono<ResponseEntity<?>> restoreBackup(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                String pathStr = body.get("path");
                if (pathStr == null || pathStr.isBlank()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "'path' field is required"));
                }
                Path backupFile = Path.of(pathStr);
                backupService.restoreBackup(backupFile);
                return ResponseEntity.ok(Map.of("status", "restored", "path", pathStr));
            } catch (Exception e) {
                log.error("Failed to restore backup", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * 列出所有备份.
     */
    @GetMapping
    public Mono<ResponseEntity<?>> listBackups() {
        return Mono.fromCallable(() -> {
            var backups = backupService.listBackups();
            var result = backups.stream()
                    .map(p -> Map.of(
                            "name", p.getFileName().toString(),
                            "path", p.toString(),
                            "size", p.toFile().length()
                    ))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(result);
        });
    }

    /**
     * 删除备份.
     */
    @DeleteMapping
    public Mono<ResponseEntity<?>> deleteBackup(@RequestParam String path) {
        return Mono.fromCallable(() -> {
            try {
                boolean deleted = backupService.deleteBackup(Path.of(path));
                if (!deleted) {
                    return ResponseEntity.notFound().build();
                }
                return ResponseEntity.ok(Map.of("status", "deleted", "path", path));
            } catch (Exception e) {
                log.error("Failed to delete backup", e);
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        });
    }
}
