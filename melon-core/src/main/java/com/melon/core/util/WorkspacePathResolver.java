package com.melon.core.util;

import java.nio.file.Path;

public final class WorkspacePathResolver {

    private final Path workspaceDir;

    public WorkspacePathResolver(String workspaceDir) {
        this.workspaceDir = workspaceDir == null || workspaceDir.isBlank()
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : Path.of(workspaceDir).toAbsolutePath().normalize();
    }

    public Path resolve(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("file_path is required");
        }
        Path requested = Path.of(filePath);
        if (requested.isAbsolute()) {
            Path normalized = requested.normalize();
            if (!SafePathUtil.isWithinDir(normalized, workspaceDir)) {
                throw new SecurityException("Path outside workspace: " + filePath);
            }
            return normalized;
        }
        return SafePathUtil.resolveSafe(workspaceDir, filePath).toAbsolutePath().normalize();
    }

    public Path resolveOptional(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return workspaceDir;
        }
        return resolve(filePath);
    }
}
