package com.melon.app.service;

import com.melon.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    private final Path backupDir;
    private final Path melonHome;

    public BackupService(ConfigManager configManager) {
        this.melonHome = configManager.resolveHomeDir();
        this.backupDir = melonHome.resolve("backups");
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            log.warn("Failed to create backup directory: {}", backupDir);
        }
    }

    /**
     * 创建备份. 将 ~/.melon 目录打包为 zip.
     * @return 备份文件路径
     */
    public Path createBackup() throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backupFile = backupDir.resolve("melon-backup-" + timestamp + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(backupFile))) {
            addToZip(melonHome, melonHome, zos, backupFile);
        }

        log.info("Backup created: {}", backupFile);
        return backupFile;
    }

    /**
     * 从备份恢复. 将 zip 内容解压到 ~/.melon.
     * @param backupFile 备份文件路径
     */
    public void restoreBackup(Path backupFile) throws IOException {
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
        // Security: ensure the file is within backup directory
        if (!backupFile.normalize().startsWith(backupDir.normalize())) {
            throw new SecurityException("Cannot delete file outside backup directory");
        }
        if (Files.exists(backupFile)) {
            Files.delete(backupFile);
            log.info("Backup deleted: {}", backupFile);
            return true;
        }
        return false;
    }

    public Path importBackup(Path source, String originalName) throws IOException {
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
        log.info("Backup imported: {}", target);
        return target;
    }

    /**
     * 递归添加文件到 zip.
     */
    private void addToZip(Path root, Path current, ZipOutputStream zos, Path zipFile) throws IOException {
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
}
