package com.melon.app.service;

import com.melon.core.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BackupServiceSelfCheck {

    private BackupServiceSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("melon-backup-check");
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        BackupService service = new BackupService(configManager);

        Path source = Files.createTempFile("backup-source-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(source))) {
            zos.putNextEntry(new ZipEntry("workspaces/default/AGENTS.md"));
            zos.write("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Path imported = service.importBackup(source, "my backup.zip");
        if (!Files.exists(imported) || !imported.getFileName().toString().endsWith(".zip")) {
            throw new AssertionError("backup was not imported: " + imported);
        }

        service.restoreBackup(imported);
        Path restored = home.resolve("workspaces/default/AGENTS.md");
        if (!"hello".equals(Files.readString(restored))) {
            throw new AssertionError("backup restore failed: " + restored);
        }
    }
}
