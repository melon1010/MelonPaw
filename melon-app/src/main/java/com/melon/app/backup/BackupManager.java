/**
 * @author melon
 */
package com.melon.app.backup;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup manager. Creates and restores zip backups of the ~/.melon directory.
 * Corresponds to Python backup_manager.py.
 *
 * <p>Each backup consists of:
 * <ul>
 *   <li>A zip file containing the ~/.melon directory contents</li>
 *   <li>A companion .meta.json file with backup metadata</li>
 * </ul>
 */
@Component
public class BackupManager {

    private static final Logger log = LoggerFactory.getLogger(BackupManager.class);

    private static final String VERSION = "1.0.0";
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path melonHome;
    private final Path defaultBackupDir;

    public BackupManager(ConfigManager configManager) {
        this.melonHome = configManager.resolveHomeDir();
        this.defaultBackupDir = melonHome.resolve("backups");
        try {
            Files.createDirectories(defaultBackupDir);
        } catch (IOException e) {
            log.warn("Failed to create backup directory: {}", defaultBackupDir);
        }
    }

    /**
     * Creates a zip backup of the ~/.melon directory.
     *
     * @param targetDir the directory where the backup zip will be stored;
     *                  if null or blank, defaults to ~/.melon/backups
     * @return the path to the created backup zip file
     * @throws IOException if backup creation fails
     */
    public Path createBackup(String targetDir) throws IOException {
        Path backupDir = resolveTargetDir(targetDir);
        Files.createDirectories(backupDir);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        Path backupFile = backupDir.resolve("melon-backup-" + timestamp + ".zip");

        log.info("Creating backup of {} to {}", melonHome, backupFile);

        int[] counts = new int[2]; // [agentCount, sessionCount]
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(backupFile))) {
            addToZip(melonHome, melonHome, zos, backupFile, counts);
        }

        long fileSize = Files.size(backupFile);
        BackupMeta meta = new BackupMeta(
                System.currentTimeMillis(),
                VERSION,
                fileSize,
                counts[0],
                counts[1]
        );

        // Save companion metadata file
        Path metaFile = getMetaFile(backupFile);
        JsonUtils.save(metaFile, meta.toMap());

        log.info("Backup created: {} (size={}, agents={}, sessions={})",
                backupFile, fileSize, counts[0], counts[1]);
        return backupFile;
    }

    /**
     * Restores the ~/.melon directory from a backup zip file.
     *
     * @param backupFile the path to the backup zip file
     * @throws IOException if restore fails
     */
    public void restoreBackup(String backupFile) throws IOException {
        if (backupFile == null || backupFile.isBlank()) {
            throw new IllegalArgumentException("Backup file path must not be null or blank");
        }

        Path zipPath = Path.of(backupFile);
        if (!Files.exists(zipPath)) {
            throw new NoSuchFileException("Backup file not found: " + backupFile);
        }

        log.info("Restoring backup from {} to {}", zipPath, melonHome);
        Files.createDirectories(melonHome);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = melonHome.resolve(entry.getName()).normalize();

                // Security: prevent path traversal outside ~/.melon
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

        log.info("Backup restored successfully from: {}", zipPath);
    }

    /**
     * Lists all backup files along with their metadata.
     *
     * @return a list of BackupEntry objects containing the file path and metadata
     */
    public List<BackupEntry> listBackups() {
        List<BackupEntry> backups = new ArrayList<>();
        if (!Files.exists(defaultBackupDir)) {
            return backups;
        }

        try (Stream<Path> entries = Files.list(defaultBackupDir)) {
            entries.filter(p -> p.toString().endsWith(".zip"))
                   .sorted(Comparator.reverseOrder())
                   .forEach(zipPath -> {
                       BackupMeta meta = loadMeta(zipPath);
                       if (meta == null) {
                           // Create minimal metadata from file if .meta.json is missing
                           try {
                               meta = new BackupMeta(
                                       Files.getLastModifiedTime(zipPath).toMillis(),
                                       "unknown",
                                       Files.size(zipPath),
                                       0,
                                       0
                               );
                           } catch (IOException e) {
                               meta = new BackupMeta(0, "unknown", 0, 0, 0);
                           }
                       }
                       backups.add(new BackupEntry(zipPath, meta));
                   });
        } catch (IOException e) {
            log.error("Failed to list backups", e);
        }
        return backups;
    }

    /**
     * Deletes a backup file and its companion metadata.
     *
     * @param backupFile the path to the backup zip file
     * @return true if the backup was deleted, false if it did not exist
     * @throws IOException if deletion fails
     */
    public boolean deleteBackup(String backupFile) throws IOException {
        if (backupFile == null || backupFile.isBlank()) {
            throw new IllegalArgumentException("Backup file path must not be null or blank");
        }
        Path zipPath = Path.of(backupFile).normalize();

        // Security: ensure the file is within the backup directory
        if (!zipPath.startsWith(defaultBackupDir.normalize())) {
            throw new SecurityException("Cannot delete file outside backup directory");
        }

        boolean deleted = false;
        if (Files.exists(zipPath)) {
            Files.delete(zipPath);
            deleted = true;
        }

        // Also delete companion metadata file
        Path metaFile = getMetaFile(zipPath);
        if (Files.exists(metaFile)) {
            Files.delete(metaFile);
        }

        if (deleted) {
            log.info("Backup deleted: {}", zipPath);
        }
        return deleted;
    }

    // --- Internal helpers ---

    private Path resolveTargetDir(String targetDir) {
        if (targetDir == null || targetDir.isBlank()) {
            return defaultBackupDir;
        }
        Path path = Path.of(targetDir);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.home"), targetDir);
        }
        return path;
    }

    private Path getMetaFile(Path zipFile) {
        String fileName = zipFile.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - 4); // strip .zip
        return zipFile.resolveSibling(baseName + ".meta.json");
    }

    @SuppressWarnings("unchecked")
    private BackupMeta loadMeta(Path zipFile) {
        Path metaFile = getMetaFile(zipFile);
        if (!Files.exists(metaFile)) {
            return null;
        }
        Map<String, Object> map = JsonUtils.loadAsMap(metaFile);
        if (map.isEmpty()) {
            return null;
        }
        return BackupMeta.fromMap(map);
    }

    /**
     * Recursively adds files to the zip archive.
     *
     * @param root      the root directory being backed up
     * @param current   the current directory being traversed
     * @param zos       the zip output stream
     * @param zipFile   the zip file being created (to skip self)
     * @param counts    int[2] accumulator: [agentCount, sessionCount]
     */
    private void addToZip(Path root, Path current, ZipOutputStream zos, Path zipFile, int[] counts)
            throws IOException {
        try (Stream<Path> stream = Files.list(current)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                // Skip the backups directory and the zip being created
                if (path.equals(defaultBackupDir) || path.equals(zipFile)) {
                    continue;
                }

                String entryName = root.relativize(path).toString().replace('\\', '/');

                if (Files.isDirectory(path)) {
                    zos.putNextEntry(new ZipEntry(entryName + "/"));
                    zos.closeEntry();
                    addToZip(root, path, zos, zipFile, counts);
                } else {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zos);
                    zos.closeEntry();

                    // Count agents and sessions based on path
                    if (entryName.startsWith("agents/") && entryName.endsWith(".json")) {
                        counts[0]++;
                    } else if (entryName.startsWith("sessions/") && entryName.endsWith(".json")) {
                        counts[1]++;
                    }
                }
            }
        }
    }

    /**
     * Represents a single backup entry with its file path and metadata.
     */
    public static class BackupEntry {
        private final Path file;
        private final BackupMeta meta;

        public BackupEntry(Path file, BackupMeta meta) {
            this.file = file;
            this.meta = meta;
        }

        public Path getFile() {
            return file;
        }

        public BackupMeta getMeta() {
            return meta;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", file.getFileName().toString());
            map.put("path", file.toString());
            map.put("timestamp", meta.getTimestamp());
            map.put("version", meta.getVersion());
            map.put("size", meta.getSize());
            map.put("agentCount", meta.getAgentCount());
            map.put("sessionCount", meta.getSessionCount());
            return map;
        }
    }
}
