package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 备份服务. 创建 zip 备份、恢复备份.
 * Corresponds to Python backup_service.py.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final ConfigManager configManager;

    public BackupService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 创建备份. 将 ~/.melon 目录打包为 zip.
     * @return 备份文件路径
     */
    public Path createBackup() throws IOException {
        return createBackup("", "", Map.of(), List.of());
    }

    public Path createBackup(String name, String description, Map<String, Object> scope, List<String> agents) throws IOException {
        Path backupDir = backupDir();
        Path melonHome = melonHome();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String prefix = sanitizeNamePrefix(name);
        Path backupFile = backupDir.resolve(prefix + "-" + timestamp + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(backupFile))) {
            addToZip(melonHome, melonHome, zos, backupFile);
        }
        JsonUtils.save(metaFile(backupFile), metadata(backupFile, name, description, scope, agents));

        log.info("Backup created: {}", backupFile);
        return backupFile;
    }

    /**
     * 从备份恢复. 将 zip 内容解压到 ~/.melon.
     * @param backupFile 备份文件路径
     */
    public void restoreBackup(Path backupFile) throws IOException {
        Path melonHome = melonHome();
        if (!Files.exists(backupFile)) {
            throw new NoSuchFileException("Backup file not found: " + backupFile);
        }

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(backupFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = melonHome.resolve(entry.getName()).normalize();

                // Security: prevent path traversal
                if (!target.startsWith(melonHome.normalize())) {
                    log.warn("Skipping suspicious path in backup: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }

        log.info("Backup restored from: {}", backupFile);
    }

    /**
     * 列出所有备份文件.
     */
    public java.util.List<Path> listBackups() {
        Path backupDir = backupDir();
        java.util.List<Path> backups = new java.util.ArrayList<>();
        if (!Files.exists(backupDir)) {
            return backups;
        }
        try (Stream<Path> entries = Files.list(backupDir)) {
            entries.filter(p -> p.toString().endsWith(".zip"))
                   .sorted(Comparator.reverseOrder())
                   .forEach(backups::add);
        } catch (IOException e) {
            log.error("Failed to list backups", e);
        }
        return backups;
    }

    /**
     * 删除备份文件.
     */
    public boolean deleteBackup(Path backupFile) throws IOException {
        Path backupDir = backupDir();
        // Security: ensure the file is within backup directory
        if (!backupFile.normalize().startsWith(backupDir.normalize())) {
            throw new SecurityException("Cannot delete file outside backup directory");
        }
        if (Files.exists(backupFile)) {
            Files.delete(backupFile);
            Files.deleteIfExists(metaFile(backupFile));
            log.info("Backup deleted: {}", backupFile);
            return true;
        }
        return false;
    }

    public Path importBackup(Path source, String originalName) throws IOException {
        Path backupDir = backupDir();
        if (source == null || !Files.isRegularFile(source)) {
            throw new NoSuchFileException("Backup file not found: " + source);
        }
        String filename = sanitizeBackupName(originalName);
        validateZip(source);
        Path target = backupDir.resolve(filename).normalize();
        if (!target.startsWith(backupDir.normalize())) {
            throw new SecurityException("Cannot import backup outside backup directory");
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        JsonUtils.save(metaFile(target), metadata(target, "", "", Map.of(), List.of()));
        log.info("Backup imported: {}", target);
        return target;
    }

    public Map<String, Object> readMeta(Path backupFile) {
        Map<String, Object> meta = JsonUtils.loadAsMap(metaFile(backupFile));
        if (!meta.isEmpty()) {
            return meta;
        }
        return metadata(backupFile, "", "", Map.of(), List.of());
    }

    /**
     * 递归添加文件到 zip.
     */
    private void addToZip(Path root, Path current, ZipOutputStream zos, Path zipFile) throws IOException {
        Path backupDir = backupDir();
        try (Stream<Path> stream = Files.list(current)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                // Skip the backup directory itself and the zip being created
                if (path.equals(backupDir) || path.equals(zipFile)) {
                    continue;
                }

                String entryName = root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    zos.putNextEntry(new ZipEntry(entryName + "/"));
                    zos.closeEntry();
                    addToZip(root, path, zos, zipFile);
                } else {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    private void validateZip(Path zipFile) throws IOException {
        Path melonHome = melonHome();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = melonHome.resolve(entry.getName()).normalize();
                if (!target.startsWith(melonHome.normalize())) {
                    throw new SecurityException("Suspicious path in backup: " + entry.getName());
                }
                zis.closeEntry();
            }
        }
    }

    private String sanitizeBackupName(String name) {
        String value = name == null || name.isBlank()
                ? "melon-backup-imported.zip"
                : Path.of(name).getFileName().toString();
        value = value.replaceAll("[^A-Za-z0-9._-]", "-");
        if (!value.endsWith(".zip")) value += ".zip";
        return value.isBlank() ? "melon-backup-imported.zip" : value;
    }

    private Path melonHome() {
        return configManager.resolveHomeDir();
    }

    private Path backupDir() {
        Path dir = melonHome().resolve("backups");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Failed to create backup directory: {}", dir);
        }
        return dir;
    }

    private Path metaFile(Path backupFile) {
        return backupFile.resolveSibling(backupFile.getFileName() + ".meta.json");
    }

    private Map<String, Object> metadata(Path backupFile, String name, String description,
                                         Map<String, Object> scope, List<String> agents) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String filename = backupFile.getFileName().toString();
        meta.put("id", filename);
        meta.put("name", name == null || name.isBlank() ? filename.replaceFirst("\\.zip$", "") : name);
        meta.put("description", description == null ? "" : description);
        try {
            meta.put("created_at", Files.getLastModifiedTime(backupFile).toInstant().toString());
            meta.put("size", Files.size(backupFile));
        } catch (Exception e) {
            meta.put("created_at", Instant.EPOCH.toString());
            meta.put("size", 0);
        }
        meta.put("filename", filename);
        meta.put("path", backupFile.toString());
        meta.put("status", "completed");
        meta.put("trusted", true);
        meta.put("scope", normalizeScope(scope));
        meta.put("agent_count", agents == null || agents.isEmpty() ? 0 : agents.size());
        meta.put("signature", "");
        meta.put("accepted_via_trust", false);
        return meta;
    }

    private Map<String, Object> normalizeScope(Map<String, Object> scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("include_agents", bool(scope.get("include_agents"), true));
        result.put("include_global_config", bool(scope.get("include_global_config"), true));
        result.put("include_secrets", bool(scope.get("include_secrets"), true));
        result.put("include_skill_pool", bool(scope.get("include_skill_pool"), true));
        return result;
    }

    private boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private String sanitizeNamePrefix(String name) {
        String value = name == null || name.isBlank() ? "melon-backup" : name.trim();
        value = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return value.isBlank() ? "melon-backup" : value;
    }
}
