package com.melon.app.service;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.SafePathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service for file operations within agent workspaces.
 * Handles file listing, reading, uploading, and deletion.
 * All operations are scoped to the agent's workspace directory.
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    private final ConfigManager configManager;
    private final WorkspaceManager workspaceManager;
    private final FileGuardService fileGuardService;

    public FileService(ConfigManager configManager, WorkspaceManager workspaceManager,
                       FileGuardService fileGuardService) {
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
        this.fileGuardService = fileGuardService;
    }

    /**
     * Lists files in a directory within the agent's workspace.
     */
    public List<Map<String, Object>> listFiles(String agentId, String dirPath) throws IOException {
        Path workspace = workspaceDir(agentId);
        Path target = SafePathUtil.resolveSafe(workspace, dirPath);
        if (target == null) {
            throw new IllegalArgumentException("Invalid path: " + dirPath);
        }
        if (!Files.exists(target)) {
            throw new NoSuchFileException("Path not found: " + dirPath);
        }
        fileGuardService.assertAllowed(target);

        List<Map<String, Object>> result = new ArrayList<>();
        if (Files.isDirectory(target)) {
            try (Stream<Path> entries = Files.list(target)) {
                entries.forEach(p -> result.add(fileInfo(p, workspace)));
            }
        } else {
            result.add(fileInfo(target, workspace));
        }
        return result;
    }

    /**
     * Reads a file's content as bytes.
     */
    public byte[] readFile(String agentId, String filePath) throws IOException {
        Path workspace = workspaceDir(agentId);
        Path target = SafePathUtil.resolveSafe(workspace, filePath);
        if (target == null) {
            throw new IllegalArgumentException("Invalid path: " + filePath);
        }
        if (!Files.exists(target)) {
            throw new NoSuchFileException("File not found: " + filePath);
        }
        fileGuardService.assertAllowed(target);
        long size = Files.size(target);
        if (size > MAX_FILE_SIZE) {
            throw new IOException("File too large: " + size + " bytes");
        }
        return Files.readAllBytes(target);
    }

    /**
     * Uploads a file to the agent's workspace.
     */
    public Mono<Map<String, Object>> uploadFile(String agentId, String destDir, FilePart filePart) {
        Path workspace = workspaceDir(agentId);
        Path destPath = SafePathUtil.resolveSafe(workspace, destDir);
        if (destPath == null) {
            return Mono.error(new IllegalArgumentException("Invalid destination: " + destDir));
        }

        try {
            Files.createDirectories(destPath);
        } catch (IOException e) {
            return Mono.error(e);
        }

        Path targetFile = destPath.resolve(filePart.filename());
        fileGuardService.assertAllowed(targetFile);
        return filePart.transferTo(targetFile)
            .map(success -> {
                log.info("File uploaded: {} ({} bytes)", targetFile, success);
                long size = 0;
                try {
                    if (Files.exists(targetFile)) {
                        size = Files.size(targetFile);
                    }
                } catch (IOException ex) {
                    log.warn("Failed to get file size", ex);
                }
                return Map.<String, Object>of(
                    "status", "uploaded",
                    "filename", filePart.filename(),
                    "path", workspace.relativize(targetFile).toString(),
                    "size", size
                );
            });
    }

    /**
     * Deletes a file (moves to trash).
     */
    public void deleteFile(String agentId, String filePath) throws IOException {
        Path workspace = workspaceDir(agentId);
        Path target = SafePathUtil.resolveSafe(workspace, filePath);
        if (target == null) {
            throw new IllegalArgumentException("Invalid path: " + filePath);
        }
        if (!Files.exists(target)) {
            throw new NoSuchFileException("File not found: " + filePath);
        }
        fileGuardService.assertAllowed(target);

        // Move to trash directory instead of permanent deletion
        Path trashDir = workspace.resolve(".trash");
        Files.createDirectories(trashDir);
        Path trashTarget = trashDir.resolve(target.getFileName().toString());
        Files.move(target, trashTarget, StandardCopyOption.REPLACE_EXISTING);
        log.info("File moved to trash: {} -> {}", filePath, trashTarget);
    }

    private Map<String, Object> fileInfo(Path path, Path workspace) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", path.getFileName().toString());
        info.put("path", workspace.relativize(path).toString().replace('\\', '/'));
        info.put("is_directory", Files.isDirectory(path));
        try {
            info.put("size", Files.size(path));
            info.put("modified", Files.getLastModifiedTime(path).toMillis());
        } catch (IOException ignored) {
            info.put("size", -1);
            info.put("modified", -1);
        }
        return info;
    }

    private Path workspaceDir(String agentId) {
        Path workspace = configManager.resolveWorkspaceDir(safeAgentId(agentId));
        workspaceManager.initWorkspace(workspace);
        return workspace;
    }

    private String safeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }
}
