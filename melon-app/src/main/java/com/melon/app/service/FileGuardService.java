package com.melon.app.service;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FileGuardService {

    private final SecuritySettingsService securitySettingsService;

    public FileGuardService(SecuritySettingsService securitySettingsService) {
        this.securitySettingsService = securitySettingsService;
    }

    public void assertAllowed(Path path) {
        if (isSensitive(path)) {
            throw new SecurityException("SENSITIVE_FILE_BLOCKED");
        }
    }

    public boolean isSensitive(Path path) {
        Map<String, Object> guard = securitySettingsService.fileGuard();
        if (!Boolean.TRUE.equals(guard.get("enabled"))) {
            return false;
        }
        Path target = normalize(path);
        for (GuardPath guardPath : guardedPaths(guard.get("paths"))) {
            if (guardPath.matches(target)) {
                return true;
            }
        }
        return false;
    }

    public boolean allowPreviewOutsideWorkspace() {
        return Boolean.TRUE.equals(securitySettingsService.fileGuard().get("allow_preview_outside_workspace"));
    }

    private List<GuardPath> guardedPaths(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<GuardPath> result = new ArrayList<>();
        for (Object item : list) {
            String value = String.valueOf(item).trim();
            if (!value.isBlank()) {
                result.add(GuardPath.of(value));
            }
        }
        return result;
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private record GuardPath(Path path, boolean directory) {
        static GuardPath of(String raw) {
            boolean directory = raw.endsWith("/") || raw.endsWith("\\");
            Path path = expandHome(raw).toAbsolutePath().normalize();
            if (!directory && java.nio.file.Files.isDirectory(path)) {
                directory = true;
            }
            return new GuardPath(path, directory);
        }

        private static Path expandHome(String raw) {
            if (raw.equals("~")) {
                return Path.of(System.getProperty("user.home"));
            }
            if (raw.startsWith("~/") || raw.startsWith("~\\")) {
                return Path.of(System.getProperty("user.home")).resolve(raw.substring(2));
            }
            return Path.of(raw);
        }

        boolean matches(Path target) {
            return directory ? target.startsWith(path) : target.equals(path);
        }
    }
}
