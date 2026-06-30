/**
 * @author melon
 */
package com.melon.app.controller;

import com.melon.app.service.FileService;
import com.melon.core.util.SafePathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/**
 * REST controller for file operations.
 * Handles file upload, download, and listing.
 * Corresponds to Python file API endpoints.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final long MAX_UPLOAD_SIZE = 100 * 1024 * 1024; // 100MB

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * Lists files in a directory.
     */
    @GetMapping("/list")
    public Mono<ResponseEntity<?>> listFiles(
            @RequestParam(defaultValue = ".") String path,
            @RequestHeader("X-Agent-Id") String agentId) {
        return Mono.fromCallable(() -> {
            try {
                var files = fileService.listFiles(agentId, path);
                return ResponseEntity.ok(files);
            } catch (Exception e) {
                log.error("Failed to list files: {}", path, e);
                return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * Downloads a file.
     */
    @GetMapping("/download")
    public Mono<ResponseEntity<?>> downloadFile(
            @RequestParam String path,
            @RequestHeader("X-Agent-Id") String agentId) {
        return Mono.fromCallable(() -> {
            try {
                byte[] data = fileService.readFile(agentId, path);
                String fileName = Path.of(path).getFileName().toString();
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new ByteArrayResource(data));
            } catch (Exception e) {
                log.error("Failed to download file: {}", path, e);
                return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
            }
        });
    }

    /**
     * Uploads a file.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadFile(
            @RequestPart("file") FilePart filePart,
            @RequestParam(defaultValue = ".") String destDir,
            @RequestHeader("X-Agent-Id") String agentId) {
        return fileService.uploadFile(agentId, destDir, filePart)
            .<ResponseEntity<?>>map(result -> ResponseEntity.ok(result))
            .onErrorResume(e -> {
                log.error("Failed to upload file", e);
                return Mono.just(ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage())));
            });
    }

    /**
     * Deletes a file (moves to trash).
     */
    @DeleteMapping
    public Mono<ResponseEntity<?>> deleteFile(
            @RequestParam String path,
            @RequestHeader("X-Agent-Id") String agentId) {
        return Mono.fromCallable(() -> {
            try {
                fileService.deleteFile(agentId, path);
                return ResponseEntity.ok(java.util.Map.of("status", "deleted"));
            } catch (Exception e) {
                log.error("Failed to delete file: {}", path, e);
                return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
            }
        });
    }
}
